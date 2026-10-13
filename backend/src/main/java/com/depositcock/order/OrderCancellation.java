package com.depositcock.order;

import java.time.LocalDateTime;

import com.depositcock.groupbuy.ProductRepository;
import jakarta.persistence.EntityManager;

/**
 * 주문 취소 공통 처리: 상태 변경 + 판매 수량(sold) 복구.
 */
final class OrderCancellation {

    private OrderCancellation() {
    }

    static void cancel(CustomerOrder order, ProductRepository productRepository, EntityManager em, LocalDateTime now) {
        order.cancel(now);
        for (OrderItem item : order.getItems()) {
            productRepository.decreaseSold(item.getProduct().getId(), item.getQuantity());
            em.refresh(item.getProduct());
        }
    }
}
