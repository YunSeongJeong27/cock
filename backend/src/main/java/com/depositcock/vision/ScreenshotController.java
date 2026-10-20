package com.depositcock.vision;

import java.util.List;

import com.depositcock.deposit.DepositService;
import com.depositcock.security.CurrentSeller;
import com.depositcock.vision.ScreenshotService.CommentExtractResponse;
import com.depositcock.vision.ScreenshotService.DepositExtractResponse;
import com.depositcock.vision.ScreenshotService.SaveRow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class ScreenshotController {

    private final ScreenshotService service;
    private final CaptureUsageService usageService;
    private final VisionExtractor extractor;

    public record CaptureStatus(boolean visionAvailable, CaptureUsageService.Usage usage) {
    }

    /** 판매자 화면: 비전 AI 켜짐 여부 + 오늘 남은 캡처 장수 */
    @GetMapping("/api/seller/capture-usage")
    public CaptureStatus captureUsage() {
        return new CaptureStatus(extractor.available(), usageService.usage(CurrentSeller.id()));
    }

    public record SaveRequest(@NotNull(message = "SCREENSHOT_NOTHING_TO_SAVE")
                              List<@NotNull(message = "INVALID_INPUT") SaveRow> rows) {
    }

    @PostMapping("/api/seller/group-buys/{groupBuyId}/deposits/screenshots/extract")
    public DepositExtractResponse extractDeposits(@PathVariable Long groupBuyId,
                                                  @RequestPart("files") List<MultipartFile> files) {
        return service.extractDeposits(CurrentSeller.id(), groupBuyId, files);
    }

    @PostMapping("/api/seller/group-buys/{groupBuyId}/deposits/screenshots/save")
    public DepositService.SaveResult saveDeposits(@PathVariable Long groupBuyId, @Valid @RequestBody SaveRequest request) {
        return service.saveDeposits(CurrentSeller.id(), groupBuyId, request.rows());
    }

    @PostMapping("/api/seller/group-buys/{groupBuyId}/comments/extract")
    public CommentExtractResponse extractComments(@PathVariable Long groupBuyId,
                                                  @RequestPart("files") List<MultipartFile> files) {
        return service.extractComments(CurrentSeller.id(), groupBuyId, files);
    }
}
