package com.depositcock.order;

import com.depositcock.groupbuy.StockAlerts;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.depositcock.common.ApiException;
import com.depositcock.groupbuy.GroupBuy;
import com.depositcock.groupbuy.GroupBuyService;
import com.depositcock.groupbuy.PickupPoint;
import com.depositcock.groupbuy.ProductRepository;
import com.depositcock.order.OrderDtos.OrderItemRequest;
import com.depositcock.order.OrderDtos.SellerEditRequest;
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
    private final StockAlerts stockAlerts;

    public SellerOrderService(GroupBuyService groupBuyService, CustomerOrderRepository orderRepository,
                              ProductRepository productRepository, EntityManager entityManager, Clock clock, StockAlerts stockAlerts) {
        this.stockAlerts = stockAlerts;
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
        OrderCancellation.cancel(order, productRepository, entityManager, stockAlerts, LocalDateTime.now(clock));
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

    /**
     * 판매자가 고객의 변경 요청(댓글·채팅)을 대신 반영한다. 고객은 같은 휴대폰 번호로 다시 주문하면 입금 확정 전 주문이 바뀐다
     * (2026-10-18, PublicOrderService.placeOrder). 바꾸는 처리 자체는 OrderUpdate 공통.
     * - 입금 확정·수령·취소된 주문은 바꿀 수 없다 (입금 확정된 주문은 먼저 확정을 취소)
     * - 마감 뒤에도 판매자는 바꿀 수 있다. 1인 최대 수량은 판매자 판단이라 보지 않는다
     * - 무엇을 어떻게 바꿨는지 기록(order_change)을 남기고, 고객 화면에는 "판매자가 주문을 변경함"을 보여준다
     */
    @Transactional
    public SellerOrder edit(Long sellerId, Long groupBuyId, Long orderId, SellerEditRequest r) {
        CustomerOrder order = getOwnedOrderForUpdate(sellerId, groupBuyId, orderId);
        GroupBuy groupBuy = order.getGroupBuy();
        if (!order.isActive()) {
            throw ApiException.badRequest("취소된 주문은 바꿀 수 없습니다.");
        }
        if (order.isPickedUp()) {
            throw ApiException.badRequest("수령 완료된 주문은 바꿀 수 없습니다.");
        }
        if (order.isPaid()) {
            throw ApiException.badRequest("입금 확정된 주문입니다. 바꾸려면 먼저 입금 확정을 취소해 주세요.");
        }
        LocalDateTime now = LocalDateTime.now(clock);

        // 1) 수령·결제
        ReceiveMethod receive = OrderRules.receiveMethod(groupBuy, r.receiveMethod());
        PaymentMethod payment = OrderRules.paymentMethod(groupBuy, receive, r.paymentMethod());
        PickupPoint point = receive == ReceiveMethod.PICKUP ? OrderRules.pickupPoint(groupBuy, r.pickupPointId()) : null;
        String recipientPhone = null;
        if (receive == ReceiveMethod.DELIVERY) {
            recipientPhone = PhoneNumbers.normalize(r.recipientPhone());
            if (trimToNull(r.recipientName()) == null || recipientPhone == null || trimToNull(r.address()) == null) {
                throw ApiException.badRequest("배송으로 바꾸려면 받는 분, 받는 분 연락처, 주소를 모두 입력해야 합니다.");
            }
        }
        String depositorName = trimToNull(r.depositorName());
        if (order.getSource() == OrderSource.FORM && depositorName == null) {
            throw ApiException.badRequest("입금자명을 입력해 주세요.");
        }

        // 2) 상품 수량
        Set<Long> productIds = new HashSet<>();
        groupBuy.getProducts().forEach(p -> productIds.add(p.getId()));
        Map<Long, Integer> wanted = new LinkedHashMap<>();
        for (OrderItemRequest item : r.items()) {
            if (item.quantity() <= 0) {
                continue;
            }
            if (!productIds.contains(item.productId())) {
                throw ApiException.badRequest("상품을 찾을 수 없습니다.");
            }
            wanted.merge(item.productId(), item.quantity(), Integer::sum);
        }
        if (wanted.isEmpty()) {
            throw ApiException.badRequest("상품 수량을 1개 이상 넣어 주세요. 주문을 없애려면 주문 취소를 눌러 주세요.");
        }

        boolean delivery = receive == ReceiveMethod.DELIVERY;
        OrderUpdate.apply(order, new OrderUpdate.Values(wanted, receive, point, payment, depositorName,
                trimToNull(r.nickname()), trimToNull(r.unitAddress()),
                delivery ? r.recipientName().trim() : null, recipientPhone, delivery ? trimToNull(r.zipcode()) : null,
                delivery ? r.address().trim() : null, delivery ? trimToNull(r.addressDetail()) : null),
                ChangeActor.SELLER, null, productRepository, entityManager, stockAlerts, now);
        orderRepository.flush();
        return OrderMapper.toSellerOrder(order, false);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
