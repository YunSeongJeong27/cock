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

import com.depositcock.commentorder.CommentService;
import com.depositcock.common.ApiException;
import com.depositcock.common.ErrorCode;
import com.depositcock.common.ExternalApiException;
import com.depositcock.common.Notice;
import com.depositcock.common.Texts;
import com.depositcock.deposit.BankStatementReader;
import com.depositcock.deposit.DepositLine;
import com.depositcock.deposit.DepositRow;
import com.depositcock.deposit.DepositService;
import com.depositcock.deposit.DepositSource;
import com.depositcock.groupbuy.GroupBuy;
import com.depositcock.groupbuy.GroupBuyService;
import com.depositcock.vision.OverlapDeduplicator.Item;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 스크린샷 → 비전 추출 → 겹침 제거 → (검수는 화면에서) → 저장.
 * 원본 이미지는 이 메서드 안에서만 쓰고 저장하지 않는다 (추출 직후 버림).
 */
@Service
@RequiredArgsConstructor
public class ScreenshotService {

    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");
    private static final int MAX_IMAGES = 20;

    private final VisionExtractor extractor;
    private final GroupBuyService groupBuyService;
    private final DepositService depositService;
    private final CaptureUsageService usageService;
    private final CommentService commentService;

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
            drafts.add(new DepositDraft(d.name() == null ? "" : d.name().trim(), d.amount(), Texts.blankToNull(d.datetime()),
                    type, item.duplicateSuspected(), item.imageIndex()));
        }
        return new DepositExtractResponse(drafts, skipped, extractor.provider(),
                Notice.SCREENSHOT_DEPOSITS_READ.text(failedNote(failed), remainingAfter), false, remainingAfter);
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
                throw new ApiException(ErrorCode.SCREENSHOT_UNREADABLE_CHARS, no);
            }
            // DB 열 길이(deposit_row.depositor_name 200, datetime_text 100)를 넘으면 409 "이미 같은 데이터" 로 잘못 안내되므로 먼저 막는다
            if (r.name().trim().length() > DepositRow.MAX_DEPOSITOR_NAME) {
                throw new ApiException(ErrorCode.SCREENSHOT_NAME_TOO_LONG, no);
            }
            if (r.datetimeText() != null && r.datetimeText().trim().length() > DepositRow.MAX_DATETIME_TEXT) {
                throw new ApiException(ErrorCode.SCREENSHOT_DATETIME_TOO_LONG, no);
            }
            lines.add(new DepositLine(no, r.name().trim(), r.amount(), Texts.blankToNull(r.datetimeText()),
                    BankStatementReader.parseDateTime(r.datetimeText())));
        }
        if (lines.isEmpty()) {
            throw new ApiException(ErrorCode.SCREENSHOT_NOTHING_TO_SAVE);
        }
        return depositService.saveDeposits(groupBuy, DepositSource.SCREENSHOT, "스크린샷", lines);
    }

    public CommentExtractResponse extractComments(Long sellerId, Long groupBuyId, List<MultipartFile> files) {
        // 캡처 장수를 쓰기 전에 막는다
        commentService.checkCommentUpload(sellerId, groupBuyId);
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
                .map(i -> new CommentDraft(Texts.nullToEmpty(i.value().author()).trim(), Texts.nullToEmpty(i.value().content()).trim(),
                        i.duplicateSuspected(), i.imageIndex()))
                .toList();
        return new CommentExtractResponse(drafts, extractor.provider(),
                Notice.SCREENSHOT_COMMENTS_READ.text(failedNote(failed), remainingAfter), false, remainingAfter);
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
            if (firstError instanceof ImageSlicer.UnusableImageException unusable) {
                throw ApiException.withMessage(unusable.getCode(), unusable.getMessage());
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
                throw new ApiException(ErrorCode.IMAGE_REJECTED, i + 1, reason);
            }
        }
    }

    private static String failedNote(List<Integer> failed) {
        if (failed.isEmpty()) {
            return "";
        }
        return Notice.SCREENSHOT_PAGES_FAILED.text(failed.stream().map(String::valueOf).collect(Collectors.joining(", ")));
    }

    static String depositKey(ExtractedDeposit d) {
        return d.name() + "|" + d.amount() + "|" + d.datetime() + "|" + d.type();
    }

    static String commentKey(ExtractedComment c) {
        return c.author() + "|" + c.content();
    }

    private static String limitNotice(CaptureUsageService.Reservation r, int requested) {
        if (r.remainingBefore() <= 0) {
            return Notice.CAPTURE_LIMIT_REACHED.text(r.dailyLimit());
        }
        return Notice.CAPTURE_LIMIT_OVER.text(r.remainingBefore(), requested);
    }

    private static String notConfigured() {
        return Notice.VISION_NOT_CONFIGURED.text();
    }

    private static void checked(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new ApiException(ErrorCode.IMAGE_REQUIRED);
        }
        if (files.size() > MAX_IMAGES) {
            throw new ApiException(ErrorCode.TOO_MANY_IMAGES, MAX_IMAGES);
        }
        for (MultipartFile f : files) {
            if (!IMAGE_TYPES.contains(mediaType(f))) {
                throw new ApiException(ErrorCode.IMAGE_TYPE_NOT_ALLOWED, f.getOriginalFilename());
            }
        }
    }

    private static String mediaType(MultipartFile file) {
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        return type.equals("image/jpg") ? "image/jpeg" : type;
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.IMAGE_UNREADABLE);
        }
    }
}
