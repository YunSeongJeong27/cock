package com.depositcock.deposit;

import java.time.LocalDateTime;
import java.util.List;

import com.depositcock.deposit.DepositMatcher.Status;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class DepositDtos {

    private DepositDtos() {
    }

    public record PreviewResponse(ParsedStatement parsed, int duplicateCount, String presetName) {
    }

    public record UploadResponse(Long uploadId, int savedCount, int duplicateCount, ParsedStatement parsed) {
    }

    public record UploadSummary(Long id, DepositSource source, String fileName, LocalDateTime uploadedAt, int rowCount,
                                int usedCount) {
    }

    public record OrderBrief(Long orderId, int orderNo, String depositorName, String nickname, String phoneLast4,
                             int expectedAmount, LocalDateTime orderedAt) {
    }

    public record CandidateView(OrderBrief order, String evidence, int amountDiff) {
    }

    public record MatchRow(
            Long rowId,
            int rowNo,
            String depositorName,
            int amount,
            String datetimeText,
            Status status,
            String statusLabel,
            OrderBrief order,
            String evidence,
            int amountDiff,
            List<CandidateView> candidates,
            /** 관련 없는 입금에 붙는 확인 안내 (예: 취소된 주문의 입금자 → 환불 확인). 없으면 null */
            String note,
            /** 취소된 주문의 입금자이고 그 주문을 "입금 확정 + 환불 필요"로 표시할 수 있으면 그 주문 id (2026-10-19) */
            Long refundCheckOrderId) {
    }

    public record UsedRow(Long rowId, String depositorName, int amount, String datetimeText, Long orderId, int orderNo) {
    }

    /**
     * @param unpaid     어떤 입금과도 연결되지 않은 미입금 주문
     * @param openOrders 판매자가 직접 고를 수 있는 모든 미입금 선입금 주문 (다른 입금에 추천된 주문 포함)
     */
    public record MatchResponse(
            List<MatchRow> rows,
            List<OrderBrief> unpaid,
            List<OrderBrief> openOrders,
            List<UsedRow> used,
            int matchedCount,
            int suggestedCount,
            int ambiguousCount,
            int unrelatedCount) {
    }

    public record ConfirmItem(@NotNull(message = "입금을 골라 주세요.") Long rowId,
                              @NotNull(message = "주문을 골라 주세요.") Long orderId) {
    }

    public record ConfirmRequest(@Valid @NotEmpty(message = "확정할 입금을 골라 주세요.") List<ConfirmItem> items) {
    }

    public record ConfirmResponse(int confirmedCount, List<String> errors) {
    }

    public record PresetRequest(
            @NotBlank(message = "설정 이름을 입력해 주세요.") @Size(max = 100, message = "설정 이름은 100자까지 입력할 수 있습니다.") String name,
            @Size(max = 100, message = "열 이름은 100자까지 입력할 수 있습니다.") String dateColumn,
            @NotBlank(message = "입금자명 열을 골라 주세요.") @Size(max = 100, message = "열 이름은 100자까지 입력할 수 있습니다.") String nameColumn,
            @NotBlank(message = "금액 열을 골라 주세요.") @Size(max = 100, message = "열 이름은 100자까지 입력할 수 있습니다.") String amountColumn,
            @Size(max = 100, message = "열 이름은 100자까지 입력할 수 있습니다.") String typeColumn) {
    }

    public record PresetResponse(Long id, String name, String dateColumn, String nameColumn, String amountColumn,
                                 String typeColumn) {

        public static PresetResponse of(BankColumnPreset p) {
            return new PresetResponse(p.getId(), p.getName(), p.getDateColumn(), p.getNameColumn(),
                    p.getAmountColumn(), p.getTypeColumn());
        }
    }

    public record AliasResponse(Long id, String depositorName, Long customerId, String customerLabel,
                                LocalDateTime createdAt) {
    }

    public record DefaultColumns(List<String> date, List<String> name, List<String> amount, List<String> type) {
    }

    /** 입금 확인 문구 (밴드·카톡에 붙여넣기용) + 닉네임 목록 */
    public record AnnouncementResponse(String text, List<AnnouncementLine> lines) {
    }

    public record AnnouncementLine(Long orderId, int orderNo, String displayName, String nickname, String depositorName,
                                   String itemsText, LocalDateTime paidAt, boolean pickedUp) {
    }
}
