package com.depositcock.order;

import java.time.LocalDateTime;
import java.util.List;

import com.depositcock.groupbuy.GroupBuyDtos.PickupPointResponse;
import com.depositcock.groupbuy.ShippingFeeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

public final class OrderDtos {

    private OrderDtos() {
    }

    // ---------- 고객(공개) ----------

    public record OrderItemRequest(@NotNull(message = "PRODUCT_NOT_CHOSEN") Long productId,
                                   @Min(value = 0, message = "QUANTITY_RANGE")
                                   @Max(value = 9999, message = "QUANTITY_RANGE") int quantity) {
    }

    public record PlaceOrderRequest(
            @Valid @NotEmpty(message = "QUANTITY_REQUIRED") List<@NotNull(message = "PRODUCT_NOT_CHOSEN") OrderItemRequest> items,
            @NotBlank(message = "PHONE_REQUIRED") @Size(max = 20, message = "PHONE_INVALID") String phone,
            @NotBlank(message = "DEPOSITOR_NAME_REQUIRED") @Size(max = 50, message = "DEPOSITOR_NAME_TOO_LONG") String depositorName,
            @Size(max = 50, message = "NICKNAME_TOO_LONG") String nickname,
            @Size(max = 50, message = "UNIT_ADDRESS_TOO_LONG") String unitAddress,
            ReceiveMethod receiveMethod,
            Long pickupPointId,
            PaymentMethod paymentMethod,
            @Size(max = 50, message = "RECIPIENT_NAME_TOO_LONG") String recipientName,
            @Size(max = 20, message = "RECIPIENT_PHONE_TOO_LONG") String recipientPhone,
            @Size(max = 10, message = "ZIPCODE_TOO_LONG") String zipcode,
            @Size(max = 300, message = "ADDRESS_TOO_LONG") String address,
            @Size(max = 300, message = "ADDRESS_DETAIL_TOO_LONG") String addressDetail,
            /** 경고(같은 연락처의 기존 주문 등)를 확인하고 그래도 주문하는지 */
            boolean acknowledgeWarnings,
            /**
             * 경고에서 "이 내용으로 바뀝니다"라고 보여준 주문번호 (409 의 replaceOrderNo 를 그대로).
             * 그 사이에 바뀔 주문이 달라졌으면(입금 확정 등) 다시 경고한다
             */
            @Min(value = 1, message = "ORDER_NO_FORMAT") Integer replaceOrderNo) {
    }

    /** 내 주문 찾기: 휴대폰 번호 전체만 (2026-10-15 사용자 결정) */
    public record IdentifyRequest(@NotBlank(message = "PHONE_REQUIRED") String phone) {
    }

    /** 주문 확정 전 바로 취소. orderNo 는 같은 정보의 주문이 여러 건일 때만 필요 */
    public record CancelRequest(@Min(value = 1, message = "ORDER_NO_FORMAT") Integer orderNo,
                                @NotBlank(message = "PHONE_REQUIRED") String phone,
                                @NotBlank(message = "DEPOSITOR_NAME_REQUIRED") String depositorName) {
    }

    /** 환불 신청 (입금한 고객, 주문 확정 전후 언제든. 판매자가 확인 후 취소·환불). orderNo 는 주문이 여러 건일 때만 필요 */
    public record RefundRequest(
            @Min(value = 1, message = "ORDER_NO_FORMAT") Integer orderNo,
            @NotBlank(message = "PHONE_REQUIRED") String phone,
            @NotBlank(message = "DEPOSITOR_NAME_REQUIRED") String depositorName,
            @NotBlank(message = "REFUND_BANK_REQUIRED") @Size(max = 30, message = "BANK_NAME_TOO_LONG") String bankName,
            @NotBlank(message = "REFUND_ACCOUNT_REQUIRED") @Size(max = 30, message = "ACCOUNT_NUMBER_TOO_LONG") String accountNumber) {
    }

    /**
     * 손님용. remaining 은 1개 이상일 때만 값이 있고 0 이하면 null.
     * blockWhenSoldOut 이면 남은 수량까지만 주문할 수 있고 soldOut 이면 품절(주문 막힘). 아니면 수량 표시만 숨기고 주문을 받음
     */
    public record PublicProduct(Long id, String name, int unitPrice, Integer remaining, Integer perOrderLimit,
                                boolean blockWhenSoldOut, boolean soldOut) {
    }

    public record AccountInfo(String bankName, String accountNumber, String accountHolder) {
    }

    public record PublicItem(Long productId, String productName, int quantity, int unitPrice, int amount) {
    }

    @Builder

    public record MyOrder(
            int orderNo,
            List<PublicItem> items,
            int itemsAmount,
            int shippingFee,
            int expectedAmount,
            ReceiveMethod receiveMethod,
            String pickupPointName,
            PaymentMethod paymentMethod,
            String depositorName,
            DisplayStatus status,
            String statusLabel,
            /** 선입금 주문이 주문 확정 전 (입금 계좌를 보여줌) */
            boolean awaitingConfirm,
            /** 바로 취소할 수 있는지 (주문 확정 전, 수령 전, 주문 받는 중) */
            boolean cancellable,
            /** 환불 신청을 할 수 있는지 (선입금 주문, 수령 전, 신청한 적 없음. 마감 뒤에도 됨) */
            boolean refundRequestable,
            /** 환불 신청 상태: REQUESTED(판매자 확인 중) / REJECTED(반려) / null */
            String refundState,
            /** 취소됐고 판매자가 환불할 예정 (환불 완료 전) */
            boolean refundRequired,
            /** 판매자가 "환불 완료"를 누른 시각 (환불 안 했으면 null) */
            LocalDateTime refundedAt,
            LocalDateTime orderedAt,
            /** 판매자가 주문을 마지막으로 바꾼 시각 (안 바꿨으면 null) */
            LocalDateTime changedAt,
            /** 고객이 같은 번호로 다시 주문해서 마지막으로 바꾼 시각 (안 바꿨으면 null) */
            LocalDateTime customerChangedAt,
            /** 고객이 수정할 수 있는지 (주문 링크 주문, 주문 확정 전, 수령 전, 주문 받는 중) */
            boolean replaceable,
            /**
             * [주문 수정]을 누르면 주문서에 채울 값 (수정할 수 있는 주문만, 그 외 null).
             * 이 기기에서 넣은 주문이 아니면 limited: 휴대폰·동호수·배송지는 비움 (자동 채움 규칙과 같음)
             */
            Prefill editValues) {
    }

    /**
     * @param limited true 면 이 기기에서 넣은 주문이 아니라(내 주문 찾기 등) 휴대폰·동호수·배송지를 비워서 준다
     */
    @Builder
    public record Prefill(String phone, String depositorName, String nickname, String unitAddress,
                          ReceiveMethod receiveMethod, Long pickupPointId, PaymentMethod paymentMethod,
                          String recipientName, String recipientPhone, String zipcode, String address,
                          String addressDetail, boolean limited) {
    }

    @Builder

    public record PublicGroupBuy(
            String title,
            String description,
            String sellerName,
            boolean acceptingOrders,
            String closedReason,
            LocalDateTime closeAt,
            List<PublicProduct> products,
            List<PickupPointResponse> pickupPoints,
            boolean pickupEnabled,
            boolean pickupPrepayEnabled,
            boolean pickupOnsiteEnabled,
            boolean deliveryEnabled,
            ShippingFeeType shippingFeeType,
            int shippingFee,
            /** 닉네임 칸은 항상 보이고, true 면 필수 (주문 링크 + 댓글 공구) */
            boolean nicknameRequired,
            boolean unitAddressFieldEnabled,
            String kakaoInquiryUrl,
            AccountInfo account,
            List<MyOrder> myOrders,
            Prefill prefill,
            /** 판매자가 주문을 잠시 멈췄는지. 멈춤 중에는 주문·수정·취소·환불 신청이 막힌다 (내 주문 보기는 됨) */
            boolean paused,
            String pauseMessage,
            String pauseLinkUrl) {
    }

    public record PlaceOrderResponse(
            int orderNo,
            int expectedAmount,
            PaymentMethod paymentMethod,
            String depositorName,
            AccountInfo account,
            List<MyOrder> myOrders,
            /** true 면 새 주문이 아니라 같은 번호의 기존 주문(orderNo)을 바꾼 것 */
            boolean changed,
            FinalOrder finalOrder) {
    }

    /**
     * 방금 넣거나 바꾼 주문의 최종 내용 (완료 화면에 보여줌, 2026-10-18).
     * 바꾼 경우에도 모든 칸이 이번 요청에 적은 값으로 바뀌므로, 요청한 사람이 적지 않은 값(바뀌기 전 주소 등)은 들어 있지 않다
     */
    @Builder
    public record FinalOrder(MyOrder order, String nickname, String unitAddress, String recipientName,
                             String recipientPhone, String zipcode, String address, String addressDetail) {
    }

    // ---------- 판매자 ----------

    public record SellerOrderItem(Long productId, String productName, int quantity, int unitPrice) {
    }

    @Builder

    public record SellerOrder(
            Long id,
            int orderNo,
            OrderSource source,
            Long customerId,
            String phone,
            String depositorName,
            String nickname,
            String unitAddress,
            ReceiveMethod receiveMethod,
            Long pickupPointId,
            String pickupPointName,
            String recipientName,
            String recipientPhone,
            String zipcode,
            String address,
            String addressDetail,
            PaymentMethod paymentMethod,
            List<SellerOrderItem> items,
            int totalQuantity,
            int itemsAmount,
            int shippingFee,
            int expectedAmount,
            DisplayStatus status,
            String statusLabel,
            boolean active,
            /** 처리가 끝난 주문 (취소 + 환불 필요 없음/환불 완료, 또는 수령 + 입금·현장결제 완료) → 판매자 주문 목록의 "완료"에만 보임 (2026-10-19) */
            boolean finished,
            boolean paid,
            LocalDateTime paidAt,
            String paidDepositorName,
            Integer paidAmount,
            boolean onsitePaid,
            boolean pickedUp,
            LocalDateTime pickedUpAt,
            boolean refundRequired,
            LocalDateTime refundedAt,
            /** 고객의 환불 신청 (판매자 확인 대기면 refundRejectedAt 이 null 이고 주문이 살아 있음) */
            LocalDateTime refundRequestedAt,
            LocalDateTime refundRejectedAt,
            /** 환불받을 계좌 (계좌번호는 복호화한 값. 키가 바뀌어 못 읽으면 null) */
            String refundBank,
            String refundAccount,
            /** 같은 공구에 같은 연락처의 다른 주문이 있음 (경고 표시용) */
            boolean duplicateContact,
            LocalDateTime orderedAt,
            /** 마지막으로 바뀐 시각 (입금 신고·확정·확정 취소·수령·취소·환불·판매자 변경 때 갱신) */
            LocalDateTime updatedAt,
            LocalDateTime cancelledAt,
            /** 입금 확정 이력 (취소된 확정 포함, 오래된 순) */
            List<PaymentRecord> payments,
            /** 판매자가 주문을 바꾼 기록 (오래된 순) */
            List<ChangeRecord> changes,
            /** 닉네임이 같은 다른 경로(링크↔댓글) 주문 번호. 같은 사람이 양쪽으로 주문했을 수 있음 (경고만) */
            List<Integer> sameNicknameOrderNos) {
    }

    public record ChangeRecord(String summary, ChangeActor changedBy, LocalDateTime changedAt) {
    }

    /**
     * 판매자가 고객의 변경 요청을 대신 반영한다 (고객 화면에는 수정 기능 없음, 인터뷰 결과 4번).
     * 휴대폰 번호(고객)는 바꿀 수 없다. 번호를 바꾸려면 취소 후 다시 주문.
     */
    public record SellerEditRequest(
            @Valid @NotEmpty(message = "QUANTITY_REQUIRED_FOR_CHANGE") List<@NotNull(message = "PRODUCT_NOT_CHOSEN") OrderItemRequest> items,
            ReceiveMethod receiveMethod,
            Long pickupPointId,
            PaymentMethod paymentMethod,
            @Size(max = 50, message = "DEPOSITOR_NAME_TOO_LONG") String depositorName,
            @Size(max = 200, message = "NICKNAME_TOO_LONG") String nickname,
            @Size(max = 50, message = "UNIT_ADDRESS_TOO_LONG") String unitAddress,
            @Size(max = 50, message = "RECIPIENT_NAME_TOO_LONG") String recipientName,
            @Size(max = 20, message = "RECIPIENT_PHONE_TOO_LONG") String recipientPhone,
            @Size(max = 10, message = "ZIPCODE_TOO_LONG") String zipcode,
            @Size(max = 300, message = "ADDRESS_TOO_LONG") String address,
            @Size(max = 300, message = "ADDRESS_DETAIL_TOO_LONG") String addressDetail) {
    }

    /** 입금 확정 1건. manual = 거래내역 없이 수동 확정, cancelledAt 이 있으면 취소된 확정 */
    public record PaymentRecord(String depositorName, int amount, boolean manual, LocalDateTime confirmedAt,
                                LocalDateTime cancelledAt) {
    }

    public record PickedUpRequest(boolean pickedUp) {
    }

    /** 완료된 주문 삭제 (여러 건) */
    public record DeleteOrdersRequest(@NotEmpty(message = "DELETE_NOTHING_SELECTED")
                                      @Size(max = 1000, message = "DELETE_TOO_MANY")
                                      List<@NotNull(message = "DELETE_NOTHING_SELECTED") Long> orderIds) {
    }

    public record DeleteOrdersResponse(int deletedCount) {
    }

    /** amount 를 빼거나 숫자가 아니게 보내면 0원으로 확정되던 것을 막는다 (2026-10-20 점검) */
    public record ManualPaidRequest(@NotBlank(message = "DEPOSITOR_NAME_REQUIRED") @Size(max = 100, message = "DEPOSITOR_NAME_TOO_LONG") String depositorName,
                                    @NotNull(message = "AMOUNT_REQUIRED") @Min(value = 0, message = "AMOUNT_MIN") Integer amount) {
    }
}
