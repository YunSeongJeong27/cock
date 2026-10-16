package com.depositcock.groupbuy;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 재고 알림 (2026-10-16 사용자 결정). 남은 수량이 판매자가 정한 기준 이하가 되면 한 번 알린다(메일).
 * 화면 알림은 판매자 화면이 현재 남은 수량으로 직접 보여 준다. 남은 수량이 기준보다 다시 많아지면(취소, 재고 늘림)
 * 다시 알릴 수 있게 되돌린다. 판매 수량을 바꾼 뒤(공구 잠금 안에서) 호출한다.
 */
@Service
public class StockAlerts {

    /** 메일 보내기는 트랜잭션이 끝난 뒤 (주문이 롤백되면 보내지 않음) */
    public record StockAlertEvent(String sellerEmail, Long groupBuyId, String groupBuyTitle, String productName,
                                  int remaining) {
    }

    private final ApplicationEventPublisher events;

    public StockAlerts(ApplicationEventPublisher events) {
        this.events = events;
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
            events.publishEvent(new StockAlertEvent(groupBuy.getSeller().getEmail(), groupBuy.getId(),
                    groupBuy.getTitle(), product.getName(), remaining));
        } else if (remaining > threshold && product.isStockAlertSent()) {
            product.markStockAlertSent(false);
        }
    }
}
