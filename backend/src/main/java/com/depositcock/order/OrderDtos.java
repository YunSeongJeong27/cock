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

public final class OrderDtos {

    private OrderDtos() {
    }

    // ---------- 고객(공개) ----------

    public record OrderItemRequest(@NotNull Long productId, @Min(0) @Max(9999) int quantity) {
    }

    public record PlaceOrderRequest(
            @Valid @NotEmpty List<OrderItemRequest> items,
            @NotBlank @Size(max = 20) String phone,
            @NotBlank @Size(max = 50) String depositorName,
            @Size(max = 50) String nickname,
            @Size(max = 50) String unitAddress,
            ReceiveMethod receiveMethod,
            Long pickupPointId,
            PaymentMethod paymentMethod,
            @Size(max = 50) String recipientName,
            @Size(max = 20) String recipientPhone,
            @Size(max = 10) String zipcode,
            @Size(max = 300) String address,
            @Size(max = 300) String addressDetail,
            /** 경고(같은 연락처 중복 주문 등)를 확인하고 그래도 주문하는지 */
            boolean acknowledgeWarnings) {
    }

    public record IdentifyRequest(@NotBlank String depositorName, @NotBlank String phone) {
    }

    /** orderNo 는 같은 정보의 주문이 여러 건일 때만 필요 */
    public record CancelRequest(@Min(1) Integer orderNo, @NotBlank String phone, @NotBlank String depositorName) {
    }

    public record PublicProduct(Long id, String name, int unitPrice, Integer remaining, Integer perOrderLimit,
                                boolean soldOut) {
    }

    public record AccountInfo(String bankName, String accountNumber, String accountHolder) {
    }

    public record PublicItem(String productName, int quantity, int unitPrice, int amount) {
    }

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
            boolean canReportDeposit,
            boolean cancellable,
            boolean refundRequired,
            LocalDateTime orderedAt,
            /** 판매자가 주문을 마지막으로 바꾼 시각 (안 바꿨으면 null) */
            LocalDateTime changedAt) {
    }

    /**
     * @param limited true 면 "입금자명 + 뒷4자리"로만 확인한 기기라 휴대폰·동호수·배송지를 비워서 준다
     */
    public record Prefill(String phone, String depositorName, String nickname, String unitAddress,
                          ReceiveMethod receiveMethod, Long pickupPointId, PaymentMethod paymentMethod,
                          String recipientName, String recipientPhone, String zipcode, String address,
                          String addressDetail, boolean limited) {
    }

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
            boolean nicknameFieldEnabled,
            boolean unitAddressFieldEnabled,
            String kakaoInquiryUrl,
            AccountInfo account,
            List<MyOrder> myOrders,
            Prefill prefill) {
    }

    public record PlaceOrderResponse(
            int orderNo,
            int expectedAmount,
            PaymentMethod paymentMethod,
            String depositorName,
            AccountInfo account,
            List<MyOrder> myOrders) {
    }

    // ---------- 판매자 ----------

    public record SellerOrderItem(Long productId, String productName, int quantity, int unitPrice) {
    }

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
            LocalDateTime depositReportedAt,
            boolean paid,
            LocalDateTime paidAt,
            String paidDepositorName,
            Integer paidAmount,
            boolean onsitePaid,
            boolean pickedUp,
            LocalDateTime pickedUpAt,
            boolean refundRequired,
            LocalDateTime refundedAt,
            /** 같은 공구에 같은 연락처의 다른 주문이 있음 (경고 표시용) */
            boolean duplicateContact,
            LocalDateTime orderedAt,
            LocalDateTime cancelledAt,
            /** 입금 확정 이력 (취소된 확정 포함, 오래된 순) */
            List<PaymentRecord> payments,
            /** 판매자가 주문을 바꾼 기록 (오래된 순) */
            List<ChangeRecord> changes,
            /** 닉네임이 같은 다른 경로(링크↔댓글) 주문 번호. 같은 사람이 양쪽으로 주문했을 수 있음 (경고만) */
            List<Integer> sameNicknameOrderNos) {
    }

    public record ChangeRecord(String summary, LocalDateTime changedAt) {
    }

    /**
     * 판매자가 고객의 변경 요청을 대신 반영한다 (고객 화면에는 수정 기능 없음, 인터뷰 결과 4번).
     * 휴대폰 번호(고객)는 바꿀 수 없다. 번호를 바꾸려면 취소 후 다시 주문.
     */
    public record SellerEditRequest(
            @Valid @NotEmpty List<OrderItemRequest> items,
            ReceiveMethod receiveMethod,
            Long pickupPointId,
            PaymentMethod paymentMethod,
            @Size(max = 50) String depositorName,
            @Size(max = 200) String nickname,
            @Size(max = 50) String unitAddress,
            @Size(max = 50) String recipientName,
            @Size(max = 20) String recipientPhone,
            @Size(max = 10) String zipcode,
            @Size(max = 300) String address,
            @Size(max = 300) String addressDetail) {
    }

    /** 입금 확정 1건. manual = 거래내역 없이 수동 확정, cancelledAt 이 있으면 취소된 확정 */
    public record PaymentRecord(String depositorName, int amount, boolean manual, LocalDateTime confirmedAt,
                                LocalDateTime cancelledAt) {
    }

    public record PickedUpRequest(boolean pickedUp) {
    }

    public record ManualPaidRequest(@NotBlank @Size(max = 100) String depositorName, @Min(0) int amount) {
    }
}
