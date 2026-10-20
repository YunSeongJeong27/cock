package com.depositcock.groupbuy;

import com.depositcock.common.Notice;
import com.depositcock.seller.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 재고 알림 (2026-10-16 사용자 결정). 남은 수량이 판매자가 정한 기준 이하가 되면 한 번 알린다(메일).
 * 화면 알림은 공구 응답(ProductResponse.stockAlert)에 현재 남은 수량으로 만든 문장을 담아 보낸다. 남은 수량이 기준보다 다시 많아지면(취소, 재고 늘림)
 * 다시 알릴 수 있게 되돌린다. 판매 수량을 바꾼 뒤(공구 잠금 안에서) 호출한다.
 * 판매자가 알림 설정에서 재고 메일을 껐으면 메일만 안 보낸다 (보낸 것으로 표시는 그대로, 2026-10-19).
 */
@Service
@RequiredArgsConstructor
public class StockAlerts {

    /** 메일 보내기는 트랜잭션이 끝난 뒤 (주문이 롤백되면 보내지 않음) */
    public record StockAlertEvent(String sellerEmail, Long groupBuyId, String groupBuyTitle, String productName,
                                  int remaining) {
    }

    private final ApplicationEventPublisher events;

    /** 알림 문장 (화면 알림 칸·메일 공통). 남은 수량이 0 이하면 소진·초과로 쓴다 */
    public static String headline(String productName, int remaining) {
        if (remaining > 0) {
            return Notice.STOCK_LEFT.text(productName, remaining);
        }
        return remaining == 0 ? Notice.STOCK_ALL_ORDERED.text(productName) : Notice.STOCK_OVER.text(productName, -remaining);
    }

    /** 판매자 화면 알림 칸에 띄울 문장. 남은 수량이 기준 이하이거나 준비 수량을 넘었을 때만 (아니면 null) */
    public static String screenAlert(Product product) {
        Integer remaining = product.remaining();
        if (remaining == null) {
            return null;
        }
        Integer threshold = product.getStockAlertThreshold();
        return remaining < 0 || (threshold != null && remaining <= threshold) ? headline(product.getName(), remaining) : null;
    }

    public void check(Product product) {
        Integer threshold = product.getStockAlertThreshold();
        Integer remaining = product.remaining();
        if (threshold == null || remaining == null) {
            if (product.isStockAlertSent()) {
                product.markStockAlertSent(false);
            }
            return;
        }
        if (remaining <= threshold && !product.isStockAlertSent()) {
            product.markStockAlertSent(true);
            GroupBuy groupBuy = product.getGroupBuy();
            if (!groupBuy.getSeller().isNotificationOn(NotificationType.MAIL_STOCK)) {
                return;
            }
            events.publishEvent(new StockAlertEvent(groupBuy.getSeller().getEmail(), groupBuy.getId(),
                    groupBuy.getTitle(), product.getName(), remaining));
        } else if (remaining > threshold && product.isStockAlertSent()) {
            product.markStockAlertSent(false);
        }
    }
}
