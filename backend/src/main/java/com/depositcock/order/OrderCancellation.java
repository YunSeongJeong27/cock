package com.depositcock.order;

import java.time.LocalDateTime;

import com.depositcock.groupbuy.ProductRepository;
import com.depositcock.groupbuy.StockAlerts;
import jakarta.persistence.EntityManager;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 주문 취소 공통 처리: 상태 변경 + 판매 수량(sold) 복구.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class OrderCancellation {

    static void cancel(CustomerOrder order, ProductRepository productRepository, EntityManager em, StockAlerts stockAlerts,
                       LocalDateTime now) {
        order.cancel(now);
        restoreStock(order, productRepository, em, stockAlerts, now);
    }

    /** 판매자가 환불 신청을 받아들여 취소 (환불 필요) */
    static void cancelForRefund(CustomerOrder order, ProductRepository productRepository, EntityManager em,
                                StockAlerts stockAlerts, LocalDateTime now) {
        order.cancelForRefund(now);
        restoreStock(order, productRepository, em, stockAlerts, now);
    }

    private static void restoreStock(CustomerOrder order, ProductRepository productRepository, EntityManager em,
                                     StockAlerts stockAlerts, LocalDateTime now) {
        for (OrderItem item : order.getItems()) {
            productRepository.decreaseSold(item.getProduct().getId(), item.getQuantity(), now);
            em.refresh(item.getProduct());
            stockAlerts.check(item.getProduct()); // 남은 수량이 알림 기준보다 다시 많아지면 다음에 또 알림
        }
    }
}
