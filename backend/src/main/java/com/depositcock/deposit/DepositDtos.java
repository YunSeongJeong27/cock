package com.depositcock.deposit;

import java.time.LocalDateTime;
import java.util.List;

import com.depositcock.deposit.DepositMatcher.Status;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class DepositDtos {

    /**
     * 거래내역서 계좌번호 확인 (2026-10-27)
     * @param check  같음 / 다름 / 모름(못 찾음)
     * @param notice 화면에 보일 안내 (같으면 null)
     */
    public record AccountCheckView(String statementAccount, AccountNumbers.Check check, String notice) {
    }

    /** @param bankName·fileType 이 은행·파일 종류의 헤더(컬럼)명으로 읽었다 */
    public record PreviewResponse(ParsedStatement parsed, int duplicateCount, String bankName, StatementType fileType,
                                  AccountCheckView account) {
    }

    public record UploadResponse(Long uploadId, int savedCount, int duplicateCount, ParsedStatement parsed, String bankName,
                                 StatementType fileType, AccountCheckView account) {
    }

    public record UploadSummary(Long id, DepositSource source, String fileName, LocalDateTime uploadedAt, int rowCount,
                                int usedCount) {
    }

    /** groupBuyId·groupBuyTitle: 같은 입금 계좌를 쓰는 다른 공구의 주문일 수 있어서 (2026-10-27) */
    public record OrderBrief(Long orderId, int orderNo, String depositorName, String nickname, String phoneLast4,
                             int expectedAmount, LocalDateTime orderedAt, Long groupBuyId, String groupBuyTitle) {
    }

    public record CandidateView(OrderBrief order, String evidence, int amountDiff) {
    }

    @Builder
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
            Long refundCheckOrderId,
            /** 거래내역 없이 입금 확정한 주문의 입금자로 보이면 그 주문 id. 화면에서 미리 골라 둔다 (2026-10-26) */
            Long linkOrderId,
            /** 확인 필요: 같은 고객의 미입금 주문이나 입금이 여러 건, 또는 입금 시각이 1시간 넘게 차이 (자동 매칭이어도 "모두 확정"에서 뺀다) */
            String checkNote,
            /** 주문(금액이 정해진 시각)과 입금 시각의 차이 안내 (예: "주문 12분 뒤 입금"). 비교할 수 없으면 null (2026-10-27) */
            String timeNote) {
    }

    /** 이미 처리 완료된 입금 (대조에서 뺀 입금 줄, 2026-10-26) */
    public record HandledRow(Long rowId, String depositorName, int amount, String datetimeText, LocalDateTime handledAt) {
    }

    /**
     * 확정한 입금. 대조 화면 "확정한 입금" 목록 (2026-10-26).
     * rowId 는 거래내역 줄 (수동 확정이거나 올린 파일을 지웠으면 null). 이때 입금자명·금액은 확정할 때 적은 값
     */
    public record UsedRow(Long rowId, String depositorName, int amount, String datetimeText, Long orderId, int orderNo,
                          OrderBrief order, boolean manual) {
    }

    /**
     * @param uploads    올린 거래내역 파일 목록 (대조와 같은 입금 줄로 만든다. 화면이 따로 부르지 않게 함께 내려 줌)
     * @param unpaid     어떤 입금과도 연결되지 않은 미입금 주문
     * @param openOrders 판매자가 직접 고를 수 있는 모든 미입금 선입금 주문 (다른 입금에 추천된 주문 포함)
     */
    @Builder
    public record MatchResponse(
            List<UploadSummary> uploads,
            List<MatchRow> rows,
            List<OrderBrief> unpaid,
            List<OrderBrief> openOrders,
            List<UsedRow> used,
            /** 거래내역 없이 입금 확정한 주문 (수동 확정·올린 파일을 지운 확정). 입금 줄을 연결할 수 있다 (2026-10-26) */
            List<OrderBrief> manualOrders,
            List<HandledRow> handled,
            /** 같은 입금 계좌를 쓰는 다른 공구의 주문으로 보이는 입금 (그 공구 화면에도, 여기서도 확정할 수 있음, 2026-10-27) */
            List<MatchRow> otherRows,
            /** 같은 입금 계좌를 쓰는 다른 공구 제목 */
            List<String> sharedGroupBuys,
            /** 이 공구 입금 계좌의 은행 헤더(컬럼) 설정 (거래내역을 이 은행 헤더명으로 읽는다) */
            String bankName,
            int matchedCount,
            int suggestedCount,
            int ambiguousCount,
            int unrelatedCount) {
    }

    public record ConfirmItem(@NotNull(message = "DEPOSIT_NOT_CHOSEN") Long rowId,
                              @NotNull(message = "ORDER_NOT_CHOSEN") Long orderId) {
    }

    public record ConfirmRequest(@Valid @NotEmpty(message = "CONFIRM_NOTHING_SELECTED")
                                 List<@NotNull(message = "DEPOSIT_NOT_CHOSEN") ConfirmItem> items) {
    }

    public record ConfirmResponse(int confirmedCount, List<String> errors) {
    }

    /** 헤더(컬럼)명이 어디서 왔는지: 운영자 값 그대로 / 판매자가 바꿈 / 판매자가 추가한 은행·파일 종류 */
    public enum BankHeaderSource { SYSTEM, CHANGED, CUSTOM }

    /** 헤더(컬럼)명 값 묶음 */
    public record BankHeaderValues(String nameColumn, String amountColumn, String dateColumn, String accountLabel,
                                   String typeColumn) {

        public ColumnMapping toMapping() {
            return new ColumnMapping(dateColumn, nameColumn, amountColumn, typeColumn);
        }
    }

    /**
     * 판매자에게 적용되는 은행·파일 종류별 헤더(컬럼)명 (2026-10-27).
     * @param values   지금 쓰는 값 (판매자가 바꿨으면 바꾼 값)
     * @param original 운영자가 넣은 원래 값 (판매자가 추가한 값이면 null)
     */
    public record BankHeaderView(String bankName, StatementType fileType, BankHeaderValues values, BankHeaderSource source,
                                 boolean defaultBank, BankHeaderValues original) {
    }

    /** 은행·파일 종류별 헤더(컬럼)명 저장: 운영자 값이 있으면 판매자 값으로 바꾸고, 없으면 새로 추가 */
    public record BankHeaderRequest(
            @NotBlank(message = "BANK_NAME_REQUIRED") @Size(max = 50, message = "BANK_NAME_TOO_LONG") String bankName,
            @NotNull(message = "FILE_TYPE_REQUIRED") StatementType fileType,
            @NotBlank(message = "NAME_COLUMN_REQUIRED") @Size(max = 100, message = "COLUMN_NAME_TOO_LONG") String nameColumn,
            @NotBlank(message = "AMOUNT_COLUMN_REQUIRED") @Size(max = 100, message = "COLUMN_NAME_TOO_LONG") String amountColumn,
            @NotBlank(message = "DATE_COLUMN_REQUIRED") @Size(max = 100, message = "COLUMN_NAME_TOO_LONG") String dateColumn,
            @Size(max = 100, message = "COLUMN_NAME_TOO_LONG") String accountLabel,
            @Size(max = 100, message = "COLUMN_NAME_TOO_LONG") String typeColumn) {
    }

    public record AliasResponse(Long id, String depositorName, Long customerId, String customerLabel,
                                LocalDateTime createdAt) {
    }

    /** 은행 헤더(컬럼) 설정 화면 한 번에: 은행별 헤더(컬럼)명 + 입금자 별칭 */
    public record BankSettingsResponse(List<BankHeaderView> banks, List<AliasResponse> aliases) {
    }

    /** 입금 확인 문구 (밴드·카톡에 붙여넣기용) + 닉네임 목록 */
    public record AnnouncementResponse(String text, List<AnnouncementLine> lines) {
    }

    @Builder
    public record AnnouncementLine(Long orderId, int orderNo, String displayName, String nickname, String depositorName,
                                   String itemsText, LocalDateTime paidAt, boolean pickedUp) {
    }
}
