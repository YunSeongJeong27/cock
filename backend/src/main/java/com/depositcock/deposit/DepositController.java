package com.depositcock.deposit;

import java.util.List;

import com.depositcock.deposit.DepositDtos.AliasResponse;
import com.depositcock.deposit.DepositDtos.BankHeaderRequest;
import com.depositcock.deposit.DepositDtos.BankHeaderView;
import com.depositcock.deposit.DepositDtos.BankSettingsResponse;
import com.depositcock.deposit.DepositDtos.ConfirmRequest;
import com.depositcock.deposit.DepositDtos.ConfirmResponse;
import com.depositcock.deposit.DepositDtos.MatchResponse;
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
import org.springframework.web.bind.annotation.PutMapping;
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
    private final BankHeaders bankHeaders;

    /** 은행 헤더(컬럼) 설정 화면 한 번에: 은행별 헤더(컬럼)명 + 입금자 별칭 (2026-10-27) */
    @GetMapping("/api/seller/bank-settings")
    public BankSettingsResponse bankSettings() {
        return service.bankSettings(CurrentSeller.id());
    }

    /**
     * bankName: 어느 은행의 헤더(컬럼)명으로 읽을지 (은행 헤더 설정 목록의 이름). 없으면 공구 입금 계좌의 은행 (2026-10-27).
     * 전에는 열 이름을 요청마다 직접 지정하거나 저장한 설정(presetId)을 골랐음.
     * password: 암호 걸린 PDF·엑셀의 비밀번호 (요청 칸, 저장 안 함)
     */
    @PostMapping(BASE + "/preview")
    public PreviewResponse preview(@PathVariable Long groupBuyId, @RequestPart("file") MultipartFile file,
                                   @RequestParam(required = false) String bankName,
                                   @RequestParam(required = false) String password) {
        return service.preview(CurrentSeller.id(), groupBuyId, file, bankName, password);
    }

    @PostMapping(BASE + "/uploads")
    public UploadResponse upload(@PathVariable Long groupBuyId, @RequestPart("file") MultipartFile file,
                                 @RequestParam(required = false) String bankName,
                                 @RequestParam(required = false) String password) {
        return service.upload(CurrentSeller.id(), groupBuyId, file, bankName, password);
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

    /** "이미 처리 완료된 입금"으로 옮기기 / 되돌리기 (2026-10-26) */
    @PostMapping(BASE + "/rows/{rowId}/handled")
    public void markHandled(@PathVariable Long groupBuyId, @PathVariable Long rowId) {
        service.markHandled(CurrentSeller.id(), groupBuyId, rowId, true);
    }

    @DeleteMapping(BASE + "/rows/{rowId}/handled")
    public void unmarkHandled(@PathVariable Long groupBuyId, @PathVariable Long rowId) {
        service.markHandled(CurrentSeller.id(), groupBuyId, rowId, false);
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

    /** 은행별 헤더(컬럼)명 저장: 목록에 있는 은행이면 판매자 값으로 바꾸고, 없는 은행이면 추가 (2026-10-27) */
    @PutMapping("/api/seller/bank-headers")
    public BankHeaderView saveBankHeader(@Valid @RequestBody BankHeaderRequest request) {
        return bankHeaders.save(CurrentSeller.id(), request);
    }

    /** 판매자 값 지우기: 운영자 은행은 원래 값으로, 판매자가 추가한 은행은 목록에서 빠짐 */
    @DeleteMapping("/api/seller/bank-headers")
    public void deleteBankHeader(@RequestParam String bankName) {
        bankHeaders.delete(CurrentSeller.id(), bankName);
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
