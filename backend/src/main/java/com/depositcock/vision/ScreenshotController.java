package com.depositcock.vision;

import java.util.List;

import com.depositcock.deposit.DepositService;
import com.depositcock.security.CurrentSeller;
import com.depositcock.vision.ScreenshotService.CommentExtractResponse;
import com.depositcock.vision.ScreenshotService.DepositExtractResponse;
import com.depositcock.vision.ScreenshotService.SaveRow;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ScreenshotController {

    private final ScreenshotService service;

    public ScreenshotController(ScreenshotService service) {
        this.service = service;
    }

    public record SaveRequest(@NotNull List<SaveRow> rows) {
    }

    @PostMapping("/api/seller/group-buys/{groupBuyId}/deposits/screenshots/extract")
    public DepositExtractResponse extractDeposits(@PathVariable Long groupBuyId,
                                                  @RequestPart("files") List<MultipartFile> files) {
        return service.extractDeposits(CurrentSeller.id(), groupBuyId, files);
    }

    @PostMapping("/api/seller/group-buys/{groupBuyId}/deposits/screenshots/save")
    public DepositService.SaveResult saveDeposits(@PathVariable Long groupBuyId, @RequestBody SaveRequest request) {
        return service.saveDeposits(CurrentSeller.id(), groupBuyId, request.rows());
    }

    @PostMapping("/api/seller/group-buys/{groupBuyId}/comments/extract")
    public CommentExtractResponse extractComments(@PathVariable Long groupBuyId,
                                                  @RequestPart("files") List<MultipartFile> files) {
        return service.extractComments(CurrentSeller.id(), groupBuyId, files);
    }
}
