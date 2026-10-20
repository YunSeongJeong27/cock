package com.depositcock.groupbuy;

import java.time.LocalDateTime;
import java.util.List;

import com.depositcock.common.Notice;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class GroupBuyDtos {

    public record ProductRequest(
            Long id,
            @NotBlank(message = "PRODUCT_NAME_REQUIRED") @Size(max = 200, message = "PRODUCT_NAME_TOO_LONG") String name,
            @Min(value = 0, message = "PRICE_RANGE")
            @Max(value = 100_000_000, message = "PRICE_RANGE") int unitPrice,
            @Min(value = 1, message = "STOCK_LIMIT_MIN") Integer stockLimit,
            @Min(value = 1, message = "PER_ORDER_LIMIT_MIN") Integer perOrderLimit,
            /** 남은 수량이 이 값 이하가 되면 판매자에게 알림. 비우면 알림 없음 */
            @Min(value = 0, message = "STOCK_ALERT_RANGE")
            @Max(value = 100_000, message = "STOCK_ALERT_RANGE") Integer stockAlertThreshold,
            /** 켜면 준비 수량에 도달했을 때 품절로 주문을 막음 (기본 꺼짐) */
            boolean blockWhenSoldOut) {
    }

    public record PickupPointRequest(
            Long id,
            @NotBlank(message = "PICKUP_POINT_NAME_REQUIRED") @Size(max = 100, message = "PICKUP_POINT_NAME_TOO_LONG") String name,
            @Size(max = 500, message = "PICKUP_POINT_INFO_TOO_LONG") String info) {
    }

    public record GroupBuyRequest(
            @NotBlank(message = "TITLE_REQUIRED") @Size(max = 200, message = "TITLE_TOO_LONG") String title,
            @Size(max = 5000, message = "DESCRIPTION_TOO_LONG") String description,
            LocalDateTime closeAt,
            @Size(max = 50, message = "BANK_NAME_TOO_LONG") String bankName,
            @Size(max = 50, message = "ACCOUNT_NUMBER_TOO_LONG") String accountNumber,
            @Size(max = 50, message = "ACCOUNT_HOLDER_TOO_LONG") String accountHolder,
            boolean pickupEnabled,
            boolean pickupPrepayEnabled,
            boolean pickupOnsiteEnabled,
            boolean deliveryEnabled,
            @NotNull(message = "SHIPPING_FEE_TYPE_REQUIRED") ShippingFeeType shippingFeeType,
            @Min(value = 0, message = "SHIPPING_FEE_RANGE")
            @Max(value = 1_000_000, message = "SHIPPING_FEE_RANGE") int shippingFee,
            @NotNull(message = "ORDER_CHANNEL_REQUIRED") OrderChannel orderChannel,
            boolean unitAddressFieldEnabled,
            @Size(max = 500, message = "KAKAO_LINK_TOO_LONG") String kakaoInquiryUrl,
            @Size(max = 500, message = "ADMIN_NAMES_TOO_LONG") String adminNames,
            @Valid @NotNull(message = "PRODUCTS_MISSING") List<@NotNull(message = "PRODUCT_NAME_REQUIRED") ProductRequest> products,
            @Valid @NotNull(message = "PICKUP_POINTS_MISSING") List<@NotNull(message = "PICKUP_POINT_NAME_REQUIRED") PickupPointRequest> pickupPoints) {
    }

    /** 판매자용. remaining 은 재고보다 더 주문되면 음수 (초과 수량) */
    /** stockAlert: 판매자 화면 알림 칸 문장 (남은 수량이 알림 기준 이하이거나 준비 수량 초과일 때만, 아니면 null) */
    @Builder
    public record ProductResponse(Long id, String name, int unitPrice, Integer stockLimit, int sold,
                                  Integer remaining, Integer perOrderLimit, Integer stockAlertThreshold,
                                  boolean blockWhenSoldOut, boolean soldOut, String stockAlert) {

        public static ProductResponse of(Product p) {
            return ProductResponse.builder()
                    .id(p.getId())
                    .name(p.getName())
                    .unitPrice(p.getUnitPrice())
                    .stockLimit(p.getStockLimit())
                    .sold(p.getSold())
                    .remaining(p.remaining())
                    .perOrderLimit(p.getPerOrderLimit())
                    .stockAlertThreshold(p.getStockAlertThreshold())
                    .blockWhenSoldOut(p.isBlockWhenSoldOut())
                    .soldOut(p.isSoldOut())
                    .stockAlert(StockAlerts.screenAlert(p))
                    .build();
        }
    }

    public record PickupPointResponse(Long id, String name, String info) {

        public static PickupPointResponse of(PickupPoint p) {
            return new PickupPointResponse(p.getId(), p.getName(), p.getInfo());
        }
    }

    @Builder
    public record GroupBuyResponse(
            Long id,
            String title,
            String description,
            String publicToken,
            GroupBuyStatus status,
            String statusLabel,
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
            String pauseLinkUrl,
            /** 멈춤 기본 문구 (판매자가 문구를 안 적었을 때 고객에게 보이는 문장) */
            String defaultPauseMessage,
            /** 재고 알림 칸 아래 경고 (메일과 같은 문장) */
            String stockAlertWarning) {

        public static GroupBuyResponse of(GroupBuy g, LocalDateTime now) {
            return GroupBuyResponse.builder()
                    .id(g.getId())
                    .title(g.getTitle())
                    .description(g.getDescription())
                    .publicToken(g.getPublicToken())
                    .status(g.getStatus())
                    .statusLabel(g.statusLabel(now))
                    .acceptingOrders(g.isAcceptingOrders(now))
                    .closeAt(g.getCloseAt())
                    .bankName(g.getBankName())
                    .accountNumber(g.getAccountNumber())
                    .accountHolder(g.getAccountHolder())
                    .pickupEnabled(g.isPickupEnabled())
                    .pickupPrepayEnabled(g.isPickupPrepayEnabled())
                    .pickupOnsiteEnabled(g.isPickupOnsiteEnabled())
                    .deliveryEnabled(g.isDeliveryEnabled())
                    .shippingFeeType(g.getShippingFeeType())
                    .shippingFee(g.getShippingFee())
                    .orderChannel(g.getOrderChannel())
                    .unitAddressFieldEnabled(g.isUnitAddressFieldEnabled())
                    .kakaoInquiryUrl(g.getKakaoInquiryUrl())
                    .adminNames(g.getAdminNames())
                    .createdAt(g.getCreatedAt())
                    .products(g.sortedProducts().stream().map(ProductResponse::of).toList())
                    .pickupPoints(g.sortedPickupPoints().stream().map(PickupPointResponse::of).toList())
                    .paused(g.isPaused())
                    .pauseMessage(g.getPauseMessage())
                    .pauseLinkUrl(g.getPauseLinkUrl())
                    .defaultPauseMessage(Notice.PAUSE_DEFAULT.text())
                    .stockAlertWarning(Notice.STOCK_ABUSE_WARNING.text())
                    .build();
        }
    }

    /** 주문 잠시 멈춤 켜기/끄기 (문구·링크는 비우면 기본 문구, 링크 없음) */
    public record PauseRequest(boolean paused,
                               @Size(max = 300, message = "PAUSE_MESSAGE_TOO_LONG") String message,
                               @Size(max = 500, message = "PAUSE_LINK_TOO_LONG") String linkUrl) {
    }

    /** 대시보드 (정리본 9장): 총 수량, 총 금액, 입금 완료 수, 미입금 수, 수령 수 */
    @Builder
    public record Dashboard(
            int orderCount,
            int totalQuantity,
            long totalAmount,
            int prepaidCount,
            int paidCount,
            int unpaidCount,
            int onsiteCount,
            int pickedUpCount,
            int refundPendingCount,
            /** 판매자 확인을 기다리는 고객 환불 신청 수 */
            int refundRequestCount,
            int cancelledCount) {
    }

    @Builder
    public record GroupBuySummary(
            Long id,
            String title,
            GroupBuyStatus status,
            String statusLabel,
            boolean acceptingOrders,
            LocalDateTime closeAt,
            String publicToken,
            LocalDateTime createdAt,
            Dashboard dashboard,
            /** 주문 잠시 멈춤 중 (목록에서 풀어야 할 공구가 보이게) */
            boolean paused) {
    }
}
