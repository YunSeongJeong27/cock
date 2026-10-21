package com.depositcock.deposit;

import java.util.List;

import com.depositcock.deposit.DepositDtos.AliasResponse;
import com.depositcock.deposit.DepositDtos.ConfirmRequest;
import com.depositcock.deposit.DepositDtos.ConfirmResponse;
import com.depositcock.deposit.DepositDtos.DefaultColumns;
import com.depositcock.deposit.DepositDtos.MatchResponse;
import com.depositcock.deposit.DepositDtos.PresetRequest;
import com.depositcock.deposit.DepositDtos.PresetResponse;
import com.depositcock.deposit.DepositDtos.PreviewResponse;
import com.depositcock.deposit.DepositDtos.UploadResponse;
import com.depositcock.deposit.DepositDtos.UploadSummary;
import com.depositcock.order.OrderDtos.ManualPaidRequest;
import com.depositcock.security.CurrentSeller;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class DepositController {

    private static final String BASE = "/api/seller/group-buys/{groupBuyId}/deposits";

    private final DepositService service;

    /** 자동 탐지에 쓰는 기본 열 이름 후보 (화면 안내용) */
    @GetMapping("/api/seller/bank-columns/defaults")
    public DefaultColumns defaults() {
        return new DefaultColumns(BankStatementReader.DATE_CANDIDATES, BankStatementReader.NAME_CANDIDATES,
                BankStatementReader.AMOUNT_CANDIDATES, BankStatementReader.TYPE_CANDIDATES);
    }

    /**
     * 열을 직접 지정할 때는 요청 칸 dateColumn·nameColumn·amountColumn·typeColumn 을 ColumnMapping 으로 묶어 받는다
     * (없으면 모두 null → 자동 탐지). 전에는 두 메서드가 칸 4개를 따로 받아 직접 묶었음 (2026-10-21)
     */
    @PostMapping(BASE + "/preview")
    public PreviewResponse preview(@PathVariable Long groupBuyId, @RequestPart("file") MultipartFile file,
                                   ColumnMapping mapping, @RequestParam(required = false) Long presetId) {
        return service.preview(CurrentSeller.id(), groupBuyId, file, mapping, presetId);
    }

    @PostMapping(BASE + "/uploads")
    public UploadResponse upload(@PathVariable Long groupBuyId, @RequestPart("file") MultipartFile file,
                                 ColumnMapping mapping, @RequestParam(required = false) Long presetId) {
        return service.upload(CurrentSeller.id(), groupBuyId, file, mapping, presetId);
    }

    @GetMapping(BASE + "/uploads")
    public List<UploadSummary> uploads(@PathVariable Long groupBuyId) {
        return service.uploads(CurrentSeller.id(), groupBuyId);
    }

    @DeleteMapping(BASE + "/uploads/{uploadId}")
    public void deleteUpload(@PathVariable Long groupBuyId, @PathVariable Long uploadId) {
        service.deleteUpload(CurrentSeller.id(), groupBuyId, uploadId);
    }

    @GetMapping(BASE + "/match")
    public MatchResponse match(@PathVariable Long groupBuyId) {
        return service.match(CurrentSeller.id(), groupBuyId);
    }

    @PostMapping(BASE + "/confirm")
    public ConfirmResponse confirm(@PathVariable Long groupBuyId, @Valid @RequestBody ConfirmRequest request) {
        return service.confirm(CurrentSeller.id(), groupBuyId, request.items());
    }

    @PostMapping("/api/seller/group-buys/{groupBuyId}/orders/{orderId}/unconfirm-paid")
    public void unconfirm(@PathVariable Long groupBuyId, @PathVariable Long orderId) {
        service.unconfirm(CurrentSeller.id(), groupBuyId, orderId);
    }

    @PostMapping("/api/seller/group-buys/{groupBuyId}/orders/{orderId}/manual-paid")
    public void manualPaid(@PathVariable Long groupBuyId, @PathVariable Long orderId,
                           @Valid @RequestBody ManualPaidRequest request) {
        service.manualConfirm(CurrentSeller.id(), groupBuyId, orderId, request.depositorName(), request.amount());
    }

    @GetMapping("/api/seller/bank-columns/presets")
    public List<PresetResponse> presets() {
        return service.presets(CurrentSeller.id());
    }

    @PostMapping("/api/seller/bank-columns/presets")
    public PresetResponse savePreset(@Valid @RequestBody PresetRequest request) {
        return service.savePreset(CurrentSeller.id(), request);
    }

    @DeleteMapping("/api/seller/bank-columns/presets/{presetId}")
    public void deletePreset(@PathVariable Long presetId) {
        service.deletePreset(CurrentSeller.id(), presetId);
    }

    @GetMapping("/api/seller/aliases")
    public List<AliasResponse> aliases() {
        return service.aliases(CurrentSeller.id());
    }

    @DeleteMapping("/api/seller/aliases/{aliasId}")
    public void deleteAlias(@PathVariable Long aliasId) {
        service.deleteAlias(CurrentSeller.id(), aliasId);
    }
}
