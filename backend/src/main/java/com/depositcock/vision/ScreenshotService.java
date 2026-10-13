package com.depositcock.vision;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.depositcock.common.ApiException;
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
        CaptureUsageService.Reservation reservation = usageService.reserve(sellerId, files.size());
        if (!reservation.allowed()) {
            return new DepositExtractResponse(List.of(), 0, extractor.provider(),
                    limitNotice(reservation, files.size()), true, reservation.remainingBefore());
        }
        int remainingAfter = reservation.remainingBefore() - files.size();
        List<List<ExtractedDeposit>> perImage = new ArrayList<>();
        for (MultipartFile file : files) {
            List<ExtractedDeposit> list = new ArrayList<>(extractor.extractDeposits(bytes(file), mediaType(file)));
            list.sort(Comparator.comparingInt(ExtractedDeposit::order));
            perImage.add(list);
        }
        List<Item<ExtractedDeposit>> items = OverlapDeduplicator.dedupe(perImage,
                d -> d.name() + "|" + d.amount() + "|" + d.datetime() + "|" + d.type());
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
                "AI가 읽은 결과입니다. 화면과 비교해 이름·금액을 꼭 확인하세요. (오늘 남은 캡처 " + remainingAfter + "장)",
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
            if (r.name().contains("[?]")) {
                throw ApiException.badRequest("읽지 못한 글자([?])가 남아 있습니다: " + r.name());
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
        groupBuyService.getOwned(sellerId, groupBuyId);
        if (!extractor.available()) {
            return new CommentExtractResponse(List.of(), extractor.provider(), notConfigured(), false, null);
        }
        checked(files);
        CaptureUsageService.Reservation reservation = usageService.reserve(sellerId, files.size());
        if (!reservation.allowed()) {
            return new CommentExtractResponse(List.of(), extractor.provider(), limitNotice(reservation, files.size()),
                    true, reservation.remainingBefore());
        }
        int remainingAfter = reservation.remainingBefore() - files.size();
        List<List<ExtractedComment>> perImage = new ArrayList<>();
        for (MultipartFile file : files) {
            List<ExtractedComment> list = new ArrayList<>(extractor.extractComments(bytes(file), mediaType(file)));
            list.sort(Comparator.comparingInt(ExtractedComment::order));
            perImage.add(list);
        }
        List<CommentDraft> drafts = OverlapDeduplicator.dedupe(perImage, c -> c.author() + "|" + c.content()).stream()
                .map(i -> new CommentDraft(nullToEmpty(i.value().author()).trim(), nullToEmpty(i.value().content()).trim(),
                        i.duplicateSuspected(), i.imageIndex()))
                .toList();
        return new CommentExtractResponse(drafts, extractor.provider(),
                "AI가 읽은 결과입니다. 작성자·내용을 확인하세요. (오늘 남은 캡처 " + remainingAfter + "장)", false, remainingAfter);
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
