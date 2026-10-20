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

public final class GroupBuyDtos {

    private GroupBuyDtos() {
    }

    public record ProductRequest(
            Long id,
            @NotBlank(message = "상품 이름을 입력해 주세요.") @Size(max = 200, message = "상품 이름은 200자까지 입력할 수 있습니다.") String name,
            @Min(value = 0, message = "가격은 0원 이상이어야 합니다.")
            @Max(value = 100_000_000, message = "가격은 1억 원까지 넣을 수 있습니다.") int unitPrice,
            @Min(value = 1, message = "준비 수량은 1개 이상이어야 합니다. 제한이 없으면 비워 두세요.") Integer stockLimit,
            @Min(value = 1, message = "1인 최대 수량은 1개 이상이어야 합니다. 제한이 없으면 비워 두세요.") Integer perOrderLimit,
            /** 남은 수량이 이 값 이하가 되면 판매자에게 알림. 비우면 알림 없음 */
            @Min(value = 0, message = "재고 알림 기준은 0개 이상이어야 합니다.")
            @Max(value = 100_000, message = "재고 알림 기준은 100,000개까지 넣을 수 있습니다.") Integer stockAlertThreshold,
            /** 켜면 준비 수량에 도달했을 때 품절로 주문을 막음 (기본 꺼짐) */
            boolean blockWhenSoldOut) {
    }

    public record PickupPointRequest(
            Long id,
            @NotBlank(message = "픽업 지점 이름을 입력해 주세요.") @Size(max = 100, message = "픽업 지점 이름은 100자까지 입력할 수 있습니다.") String name,
            @Size(max = 500, message = "픽업 안내는 500자까지 입력할 수 있습니다.") String info) {
    }

    public record GroupBuyRequest(
            @NotBlank(message = "공구 제목을 입력해 주세요.") @Size(max = 200, message = "공구 제목은 200자까지 입력할 수 있습니다.") String title,
            @Size(max = 5000, message = "공구 설명은 5,000자까지 입력할 수 있습니다.") String description,
            LocalDateTime closeAt,
            @Size(max = 50, message = "은행 이름은 50자까지 입력할 수 있습니다.") String bankName,
            @Size(max = 50, message = "계좌번호가 너무 깁니다.") String accountNumber,
            @Size(max = 50, message = "예금주는 50자까지 입력할 수 있습니다.") String accountHolder,
            boolean pickupEnabled,
            boolean pickupPrepayEnabled,
            boolean pickupOnsiteEnabled,
            boolean deliveryEnabled,
            @NotNull(message = "배송비 방식을 골라 주세요.") ShippingFeeType shippingFeeType,
            @Min(value = 0, message = "배송비는 0원 이상이어야 합니다.")
            @Max(value = 1_000_000, message = "배송비는 100만 원까지 넣을 수 있습니다.") int shippingFee,
            @NotNull(message = "주문 받는 방법(주문 링크만 / 주문 링크 + 댓글)을 골라 주세요.") OrderChannel orderChannel,
            boolean unitAddressFieldEnabled,
            @Size(max = 500, message = "카카오톡 문의 링크가 너무 깁니다.") String kakaoInquiryUrl,
            @Size(max = 500, message = "관리자 닉네임은 500자까지 입력할 수 있습니다.") String adminNames,
            @Valid @NotNull(message = "상품 목록이 없습니다.") List<ProductRequest> products,
            @Valid @NotNull(message = "픽업 지점 목록이 없습니다.") List<PickupPointRequest> pickupPoints) {
    }

    /** 판매자용. remaining 은 재고보다 더 주문되면 음수 (초과 수량) */
    /** stockAlert: 판매자 화면 알림 칸 문장 (남은 수량이 알림 기준 이하이거나 준비 수량 초과일 때만, 아니면 null) */
    public record ProductResponse(Long id, String name, int unitPrice, Integer stockLimit, int sold,
                                  Integer remaining, Integer perOrderLimit, Integer stockAlertThreshold,
                                  boolean blockWhenSoldOut, boolean soldOut, String stockAlert) {

        public static ProductResponse of(Product p) {
            return new ProductResponse(p.getId(), p.getName(), p.getUnitPrice(), p.getStockLimit(), p.getSold(),
                    p.remaining(), p.getPerOrderLimit(), p.getStockAlertThreshold(), p.isBlockWhenSoldOut(), p.isSoldOut(),
                    StockAlerts.screenAlert(p));
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
            return new GroupBuyResponse(g.getId(), g.getTitle(), g.getDescription(), g.getPublicToken(), g.getStatus(),
                    g.statusLabel(now), g.isAcceptingOrders(now), g.getCloseAt(), g.getBankName(), g.getAccountNumber(),
                    g.getAccountHolder(), g.isPickupEnabled(), g.isPickupPrepayEnabled(), g.isPickupOnsiteEnabled(),
                    g.isDeliveryEnabled(), g.getShippingFeeType(), g.getShippingFee(), g.getOrderChannel(),
                    g.isUnitAddressFieldEnabled(), g.getKakaoInquiryUrl(), g.getAdminNames(), g.getCreatedAt(),
                    g.sortedProducts().stream().map(ProductResponse::of).toList(),
                    g.sortedPickupPoints().stream().map(PickupPointResponse::of).toList(),
                    g.isPaused(), g.getPauseMessage(), g.getPauseLinkUrl(), Notice.PAUSE_DEFAULT.text(),
                    Notice.STOCK_ABUSE_WARNING.text());
        }
    }

    /** 주문 잠시 멈춤 켜기/끄기 (문구·링크는 비우면 기본 문구, 링크 없음) */
    public record PauseRequest(boolean paused,
                               @Size(max = 300, message = "멈춤 안내 문구는 300자까지 입력할 수 있습니다.") String message,
                               @Size(max = 500, message = "멈춤 안내 링크가 너무 깁니다.") String linkUrl) {
    }

    /** 대시보드 (정리본 9장): 총 수량, 총 금액, 입금 완료 수, 미입금 수, 수령 수 */
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
