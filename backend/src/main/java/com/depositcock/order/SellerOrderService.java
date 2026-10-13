package com.depositcock.order;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import com.depositcock.common.ApiException;
import com.depositcock.groupbuy.GroupBuy;
import com.depositcock.groupbuy.GroupBuyService;
import com.depositcock.groupbuy.ProductRepository;
import com.depositcock.order.OrderDtos.SellerOrder;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 주문 관리: 목록, 수령 체크, 취소, 환불 완료, 수동 입금 확정/확정 취소.
 */
@Service
@Transactional(readOnly = true)
public class SellerOrderService {

    private final GroupBuyService groupBuyService;
    private final CustomerOrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final EntityManager entityManager;
    private final Clock clock;

    public SellerOrderService(GroupBuyService groupBuyService, CustomerOrderRepository orderRepository,
                              ProductRepository productRepository, EntityManager entityManager, Clock clock) {
        this.entityManager = entityManager;
        this.groupBuyService = groupBuyService;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.clock = clock;
    }

    public List<SellerOrder> list(Long sellerId, Long groupBuyId) {
        GroupBuy groupBuy = groupBuyService.getOwned(sellerId, groupBuyId);
        return OrderMapper.toSellerOrders(orderRepository.findByGroupBuyIdOrderByOrderNoAsc(groupBuy.getId()));
    }

    /** 다른 판매자의 주문이면 404. 주문을 바꾸는 요청이므로 공구를 잠근 뒤 최신 주문을 읽는다 */
    private CustomerOrder getOwnedOrderForUpdate(Long sellerId, Long groupBuyId, Long orderId) {
        GroupBuy groupBuy = groupBuyService.lockOwned(sellerId, groupBuyId);
        return orderRepository.findByIdAndGroupBuyId(orderId, groupBuy.getId())
                .orElseThrow(() -> ApiException.notFound("주문을 찾을 수 없습니다."));
    }

    @Transactional
    public SellerOrder setPickedUp(Long sellerId, Long groupBuyId, Long orderId, boolean pickedUp) {
        CustomerOrder order = getOwnedOrderForUpdate(sellerId, groupBuyId, orderId);
        if (!order.isActive()) {
            throw ApiException.badRequest("취소된 주문입니다.");
        }
        order.markPickedUp(pickedUp, LocalDateTime.now(clock));
        return OrderMapper.toSellerOrder(order, false);
    }

    @Transactional
    public SellerOrder cancel(Long sellerId, Long groupBuyId, Long orderId) {
        CustomerOrder order = getOwnedOrderForUpdate(sellerId, groupBuyId, orderId);
        if (!order.isActive()) {
            throw ApiException.badRequest("이미 취소된 주문입니다.");
        }
        OrderCancellation.cancel(order, productRepository, entityManager, LocalDateTime.now(clock));
        return OrderMapper.toSellerOrder(order, false);
    }

    @Transactional
    public SellerOrder markRefunded(Long sellerId, Long groupBuyId, Long orderId) {
        CustomerOrder order = getOwnedOrderForUpdate(sellerId, groupBuyId, orderId);
        if (!order.isRefundRequired()) {
            throw ApiException.badRequest("환불이 필요한 주문이 아닙니다.");
        }
        order.markRefunded(LocalDateTime.now(clock));
        return OrderMapper.toSellerOrder(order, false);
    }
}
