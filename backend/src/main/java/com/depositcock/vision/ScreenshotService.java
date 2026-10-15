package com.depositcock.vision;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import com.depositcock.comment.CommentService;
import com.depositcock.common.ApiException;
import com.depositcock.common.ExternalApiException;
import com.depositcock.deposit.BankStatementReader;
import com.depositcock.deposit.DepositLine;
import com.depositcock.deposit.DepositService;
import com.depositcock.deposit.DepositSource;
import com.depositcock.groupbuy.GroupBuy;
import com.depositcock.groupbuy.GroupBuyService;
import com.depositcock.vision.OverlapDeduplicator.Item;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 스크린샷 → 비전 추출 → 겹침 제거 → (검수는 화면에서) → 저장.
 * 원본 이미지는 이 메서드 안에서만 쓰고 저장하지 않는다 (추출 직후 버림).
 */
@Service
public class ScreenshotService {

    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");
    private static final int MAX_IMAGES = 20;

    private final VisionExtractor extractor;
    private final GroupBuyService groupBuyService;
    private final DepositService depositService;
    private final CaptureUsageService usageService;

    public ScreenshotService(VisionExtractor extractor, GroupBuyService groupBuyService, DepositService depositService,
                             CaptureUsageService usageService) {
        this.extractor = extractor;
        this.groupBuyService = groupBuyService;
        this.depositService = depositService;
        this.usageService = usageService;
    }

    public record DepositDraft(String name, long amount, String datetime, String type, boolean duplicateSuspected,
                               int imageIndex) {
    }

    public record DepositExtractResponse(List<DepositDraft> deposits, int skippedWithdrawals, String provider,
                                         String notice, boolean limitExceeded, Integer remainingToday) {
    }

    public record CommentDraft(String author, String content, boolean duplicateSuspected, int imageIndex) {
    }

    public record CommentExtractResponse(List<CommentDraft> comments, String provider, String notice,
                                         boolean limitExceeded, Integer remainingToday) {
    }

    public record SaveRow(String name, int amount, String datetimeText) {
    }

    public DepositExtractResponse extractDeposits(Long sellerId, Long groupBuyId, List<MultipartFile> files) {
        groupBuyService.getOwned(sellerId, groupBuyId);
        if (!extractor.available()) {
            return new DepositExtractResponse(List.of(), 0, extractor.provider(), notConfigured(), false, null);
        }
        checked(files);
        rejectUnusable(files);
        CaptureUsageService.Reservation reservation = usageService.reserve(sellerId, files.size());
        if (!reservation.allowed()) {
            return new DepositExtractResponse(List.of(), 0, extractor.provider(),
                    limitNotice(reservation, files.size()), true, reservation.remainingBefore());
        }
        int remainingAfter = reservation.remainingBefore() - files.size();
        List<Integer> failed = new ArrayList<>();
        List<List<Item<ExtractedDeposit>>> perImage = readAll(files, failed, extractor::extractDeposits,
                extractor::extractDepositsFromPiece, ExtractedDeposit::order, ScreenshotService::depositKey);
        List<Item<ExtractedDeposit>> items = OverlapDeduplicator.dedupeItems(perImage, ScreenshotService::depositKey);
        List<DepositDraft> drafts = new ArrayList<>();
        int skipped = 0;
        for (Item<ExtractedDeposit> item : items) {
            ExtractedDeposit d = item.value();
            String type = d.type() == null ? "" : d.type();
            if (type.contains("출금") || type.contains("이자") || d.amount() <= 0) {
                skipped++;
                continue;
            }
            drafts.add(new DepositDraft(d.name() == null ? "" : d.name().trim(), d.amount(), blankToNull(d.datetime()),
                    type, item.duplicateSuspected(), item.imageIndex()));
        }
        return new DepositExtractResponse(drafts, skipped, extractor.provider(),
                "AI가 읽은 결과입니다. 화면과 비교해 이름·금액을 꼭 확인하세요." + failedNote(failed)
                        + " (오늘 남은 캡처 " + remainingAfter + "장)",
                false, remainingAfter);
    }

    public DepositService.SaveResult saveDeposits(Long sellerId, Long groupBuyId, List<SaveRow> rows) {
        GroupBuy groupBuy = groupBuyService.getOwned(sellerId, groupBuyId);
        List<DepositLine> lines = new ArrayList<>();
        int no = 0;
        for (SaveRow r : rows) {
            no++;
            if (r.name() == null || r.name().isBlank() || r.amount() <= 0) {
                continue;
            }
            if (r.name().contains("[?]") || (r.datetimeText() != null && r.datetimeText().contains("[?]"))) {
                throw ApiException.badRequest("읽지 못한 글자([?])가 남아 있습니다: " + no + "번째 줄");
            }
            // DB 열 길이(deposit_row.depositor_name 200, datetime_text 100)를 넘으면 409 "이미 같은 데이터" 로 잘못 안내되므로 먼저 막는다
            if (r.name().trim().length() > 200) {
                throw ApiException.badRequest("입금자명이 너무 깁니다 (200자까지): " + no + "번째 줄");
            }
            if (r.datetimeText() != null && r.datetimeText().trim().length() > 100) {
                throw ApiException.badRequest("일시가 너무 깁니다 (100자까지): " + no + "번째 줄");
            }
            lines.add(new DepositLine(no, r.name().trim(), r.amount(), blankToNull(r.datetimeText()),
                    BankStatementReader.parseDateTimeText(r.datetimeText())));
        }
        if (lines.isEmpty()) {
            throw ApiException.badRequest("저장할 입금이 없습니다.");
        }
        return depositService.saveDeposits(groupBuy.getId(), DepositSource.SCREENSHOT, "스크린샷", lines);
    }

    public CommentExtractResponse extractComments(Long sellerId, Long groupBuyId, List<MultipartFile> files) {
        // 캡처 장수를 쓰기 전에 막는다
        CommentService.requireCommentOrders(groupBuyService.getOwned(sellerId, groupBuyId));
        if (!extractor.available()) {
            return new CommentExtractResponse(List.of(), extractor.provider(), notConfigured(), false, null);
        }
        checked(files);
        rejectUnusable(files);
        CaptureUsageService.Reservation reservation = usageService.reserve(sellerId, files.size());
        if (!reservation.allowed()) {
            return new CommentExtractResponse(List.of(), extractor.provider(), limitNotice(reservation, files.size()),
                    true, reservation.remainingBefore());
        }
        int remainingAfter = reservation.remainingBefore() - files.size();
        List<Integer> failed = new ArrayList<>();
        List<List<Item<ExtractedComment>>> perImage = readAll(files, failed, extractor::extractComments,
                extractor::extractCommentsFromPiece, ExtractedComment::order, ScreenshotService::commentKey);
        List<CommentDraft> drafts = OverlapDeduplicator.dedupeItems(perImage, ScreenshotService::commentKey).stream()
                .map(i -> new CommentDraft(nullToEmpty(i.value().author()).trim(), nullToEmpty(i.value().content()).trim(),
                        i.duplicateSuspected(), i.imageIndex()))
                .toList();
        return new CommentExtractResponse(drafts, extractor.provider(),
                "AI가 읽은 결과입니다. 작성자·내용을 확인하세요." + failedNote(failed)
                        + " (오늘 남은 캡처 " + remainingAfter + "장)", false, remainingAfter);
    }

    /**
     * 올린 이미지를 한 장씩 읽는다.
     * - 긴 캡처는 ImageSlicer 로 잘라서 조각마다 읽고, 조각 사이 겹침을 합쳐 한 장의 결과로 만든다.
     *   조각 경계에서 확인이 필요한 줄에 붙은 "중복 의심" 표시는 이미지 사이 겹침 제거까지 그대로 넘긴다.
     * - API 제한을 넘는 이미지·조각은 ImageSlicer 가 줄여서 보낸다.
     * - 한 장이 실패해도 나머지는 계속 읽고, 실패한 장 번호를 failed 에 담는다. 전부 실패하면 첫 오류를 던진다.
     */
    private <T> List<List<Item<T>>> readAll(List<MultipartFile> files, List<Integer> failed,
                                            BiFunction<byte[], String, List<T>> readWhole,
                                            BiFunction<byte[], String, List<T>> readPiece,
                                            ToIntFunction<T> order, Function<T, String> key) {
        List<List<Item<T>>> perImage = new ArrayList<>();
        RuntimeException firstError = null;
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            try {
                List<ImageSlicer.Piece> pieces = ImageSlicer.prepare(bytes(file), mediaType(file));
                List<List<T>> perPiece = new ArrayList<>();
                for (ImageSlicer.Piece piece : pieces) {
                    List<T> list = new ArrayList<>(pieces.size() == 1
                            ? readWhole.apply(piece.data(), piece.mediaType())
                            : readPiece.apply(piece.data(), piece.mediaType()));
                    list.sort(Comparator.comparingInt(order));
                    perPiece.add(list);
                }
                perImage.add(OverlapDeduplicator.mergePieces(perPiece, key));
            } catch (ExternalApiException | ImageSlicer.UnusableImageException e) {
                failed.add(i + 1);
                if (firstError == null) {
                    firstError = e;
                }
                perImage.add(List.of());
            }
        }
        if (firstError != null && failed.size() == files.size()) {
            if (firstError instanceof ImageSlicer.UnusableImageException) {
                throw ApiException.badRequest(firstError.getMessage());
            }
            throw firstError;
        }
        return perImage;
    }

    /** 보내지도 못할 이미지는 사용량을 잡기 전에 요청 전체를 막는다 (헤더만 읽음) */
    private static void rejectUnusable(List<MultipartFile> files) {
        for (int i = 0; i < files.size(); i++) {
            String reason = ImageSlicer.rejectReason(bytes(files.get(i)));
            if (reason != null) {
                throw ApiException.badRequest((i + 1) + "번째 이미지: " + reason);
            }
        }
    }

    private static String failedNote(List<Integer> failed) {
        if (failed.isEmpty()) {
            return "";
        }
        return " " + failed.stream().map(String::valueOf).collect(Collectors.joining(", "))
                + "번째 캡처는 읽지 못했습니다. 그 부분은 직접 입력해 주세요.";
    }

    static String depositKey(ExtractedDeposit d) {
        return d.name() + "|" + d.amount() + "|" + d.datetime() + "|" + d.type();
    }

    static String commentKey(ExtractedComment c) {
        return c.author() + "|" + c.content();
    }

    private static String limitNotice(CaptureUsageService.Reservation r, int requested) {
        if (r.remainingBefore() <= 0) {
            return "오늘 캡처 한도(" + r.dailyLimit() + "장)를 초과했습니다. 아래 표에 직접 입력해 주세요. (내일 다시 쓸 수 있습니다)";
        }
        return "오늘 캡처 한도 초과: 남은 장수는 " + r.remainingBefore() + "장인데 " + requested
                + "장을 올렸습니다. 장수를 줄여서 다시 올리거나 아래 표에 직접 입력해 주세요.";
    }

    private static String notConfigured() {
        return "스크린샷 글자 읽기(비전 AI)가 아직 설정되지 않았습니다. 아래 표에 직접 입력해 주세요. "
                + "(관리자: 환경변수 APP_VISION_PROVIDER=anthropic, ANTHROPIC_API_KEY 설정)";
    }

    private static List<MultipartFile> checked(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw ApiException.badRequest("이미지를 선택해 주세요.");
        }
        if (files.size() > MAX_IMAGES) {
            throw ApiException.badRequest("한 번에 " + MAX_IMAGES + "장까지 올릴 수 있습니다.");
        }
        for (MultipartFile f : files) {
            if (!IMAGE_TYPES.contains(mediaType(f))) {
                throw ApiException.badRequest("이미지(png, jpg, gif, webp)만 올릴 수 있습니다: " + f.getOriginalFilename());
            }
        }
        return files;
    }

    private static String mediaType(MultipartFile file) {
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        return type.equals("image/jpg") ? "image/jpeg" : type;
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("이미지를 읽을 수 없습니다.");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
