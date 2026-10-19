package com.depositcock.groupbuy;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class GroupBuyDtos {

    private GroupBuyDtos() {
    }

    public record ProductRequest(
            Long id,
            @NotBlank @Size(max = 200) String name,
            @Min(0) @Max(100_000_000) int unitPrice,
            @Min(1) Integer stockLimit,
            @Min(1) Integer perOrderLimit,
            /** 남은 수량이 이 값 이하가 되면 판매자에게 알림. 비우면 알림 없음 */
            @Min(0) @Max(100_000) Integer stockAlertThreshold,
            /** 켜면 준비 수량에 도달했을 때 품절로 주문을 막음 (기본 꺼짐) */
            boolean blockWhenSoldOut) {
    }

    public record PickupPointRequest(
            Long id,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String info) {
    }

    public record GroupBuyRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 5000) String description,
            LocalDateTime closeAt,
            @Size(max = 50) String bankName,
            @Size(max = 50) String accountNumber,
            @Size(max = 50) String accountHolder,
            boolean pickupEnabled,
            boolean pickupPrepayEnabled,
            boolean pickupOnsiteEnabled,
            boolean deliveryEnabled,
            @NotNull ShippingFeeType shippingFeeType,
            @Min(0) @Max(1_000_000) int shippingFee,
            @NotNull(message = "주문 받는 방법(주문 링크만 / 주문 링크 + 댓글)을 골라 주세요.") OrderChannel orderChannel,
            boolean unitAddressFieldEnabled,
            @Size(max = 500) String kakaoInquiryUrl,
            @Size(max = 500) String adminNames,
            @Valid @NotNull List<ProductRequest> products,
            @Valid @NotNull List<PickupPointRequest> pickupPoints) {
    }

    /** 판매자용. remaining 은 재고보다 더 주문되면 음수 (초과 수량) */
    public record ProductResponse(Long id, String name, int unitPrice, Integer stockLimit, int sold,
                                  Integer remaining, Integer perOrderLimit, Integer stockAlertThreshold,
                                  boolean blockWhenSoldOut, boolean soldOut) {

        public static ProductResponse of(Product p) {
            return new ProductResponse(p.getId(), p.getName(), p.getUnitPrice(), p.getStockLimit(), p.getSold(),
                    p.remaining(), p.getPerOrderLimit(), p.getStockAlertThreshold(), p.isBlockWhenSoldOut(), p.isSoldOut());
        }
    }

    public record PickupPointResponse(Long id, String name, String info) {

        public static PickupPointResponse of(PickupPoint p) {
            return new PickupPointResponse(p.getId(), p.getName(), p.getInfo());
        }
    }

    public record GroupBuyResponse(
            Long id,
            String title,
            String description,
            String publicToken,
            GroupBuyStatus status,
            boolean acceptingOrders,
            LocalDateTime closeAt,
            String bankName,
            String accountNumber,
            String accountHolder,
            boolean pickupEnabled,
            boolean pickupPrepayEnabled,
            boolean pickupOnsiteEnabled,
            boolean deliveryEnabled,
            ShippingFeeType shippingFeeType,
            int shippingFee,
            OrderChannel orderChannel,
            boolean unitAddressFieldEnabled,
            String kakaoInquiryUrl,
            String adminNames,
            LocalDateTime createdAt,
            List<ProductResponse> products,
            List<PickupPointResponse> pickupPoints,
            boolean paused,
            /** 판매자가 적은 멈춤 문구 (null 이면 기본 문구를 씀) */
            String pauseMessage,
            String pauseLinkUrl) {

        public static GroupBuyResponse of(GroupBuy g, LocalDateTime now) {
            return new GroupBuyResponse(g.getId(), g.getTitle(), g.getDescription(), g.getPublicToken(), g.getStatus(),
                    g.isAcceptingOrders(now), g.getCloseAt(), g.getBankName(), g.getAccountNumber(),
                    g.getAccountHolder(), g.isPickupEnabled(), g.isPickupPrepayEnabled(), g.isPickupOnsiteEnabled(),
                    g.isDeliveryEnabled(), g.getShippingFeeType(), g.getShippingFee(), g.getOrderChannel(),
                    g.isUnitAddressFieldEnabled(), g.getKakaoInquiryUrl(), g.getAdminNames(), g.getCreatedAt(),
                    g.sortedProducts().stream().map(ProductResponse::of).toList(),
                    g.sortedPickupPoints().stream().map(PickupPointResponse::of).toList(),
                    g.isPaused(), g.getPauseMessage(), g.getPauseLinkUrl());
        }
    }

    /** 주문 잠시 멈춤 켜기/끄기 (문구·링크는 비우면 기본 문구, 링크 없음) */
    public record PauseRequest(boolean paused, @Size(max = 300) String message, @Size(max = 500) String linkUrl) {
    }

    /** 대시보드 (정리본 9장): 총 수량, 총 금액, 입금 완료 수, 미입금 수, 수령 수 */
    public record Dashboard(
            int orderCount,
            int totalQuantity,
            long totalAmount,
            int prepaidCount,
            int paidCount,
            int unpaidCount,
            int depositReportedCount,
            int onsiteCount,
            int pickedUpCount,
            int refundPendingCount,
            /** 판매자 확인을 기다리는 고객 환불 신청 수 */
            int refundRequestCount,
            int cancelledCount) {
    }

    public record GroupBuySummary(
            Long id,
            String title,
            GroupBuyStatus status,
            boolean acceptingOrders,
            LocalDateTime closeAt,
            String publicToken,
            LocalDateTime createdAt,
            Dashboard dashboard,
            /** 주문 잠시 멈춤 중 (목록에서 풀어야 할 공구가 보이게) */
            boolean paused) {
    }
}
