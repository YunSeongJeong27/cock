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
import com.depositcock.common.ErrorCode;
import com.depositcock.common.DataCipher;
import com.depositcock.groupbuy.GroupBuy;
import com.depositcock.groupbuy.GroupBuyService;
import com.depositcock.groupbuy.PickupPoint;
import com.depositcock.groupbuy.ProductRepository;
import com.depositcock.order.OrderDtos.OrderItemRequest;
import com.depositcock.order.OrderDtos.SellerEditRequest;
import com.depositcock.order.OrderDtos.SellerOrder;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 주문 관리: 목록, 수령 체크, 취소, 환불 완료, 수동 입금 확정/확정 취소.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SellerOrderService {

    private final GroupBuyService groupBuyService;
    private final CustomerOrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final EntityManager entityManager;
    private final Clock clock;
    private final StockAlerts stockAlerts;
    private final DataCipher cipher;

    public List<SellerOrder> list(Long sellerId, Long groupBuyId) {
        GroupBuy groupBuy = groupBuyService.getOwned(sellerId, groupBuyId);
        return OrderMapper.toSellerOrders(orderRepository.findByGroupBuyIdOrderByOrderNoAsc(groupBuy.getId()), cipher);
    }

    /** 다른 판매자의 주문이면 404. 주문을 바꾸는 요청이므로 공구를 잠근 뒤 최신 주문을 읽는다 */
    private CustomerOrder getOwnedOrderForUpdate(Long sellerId, Long groupBuyId, Long orderId) {
        GroupBuy groupBuy = groupBuyService.lockOwned(sellerId, groupBuyId);
        return orderRepository.findByIdAndGroupBuyId(orderId, groupBuy.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
    }

    /**
     * 고객 환불 신청이 기다리는 주문은 판매자가 [취소하고 환불]·[취소 안 함]으로 먼저 처리해야 한다 (2026-10-19).
     * 그 전에 수령 체크·주문 변경·일반 취소를 하면 환불 기록이 사라지거나, 물건을 받은 주문이 환불로 취소될 수 있다
     */
    private static void requireNoRefundPending(CustomerOrder order) {
        if (order.isRefundPending()) {
            throw new ApiException(ErrorCode.REFUND_PENDING_DECIDE_FIRST);
        }
    }

    @Transactional
    public SellerOrder setPickedUp(Long sellerId, Long groupBuyId, Long orderId, boolean pickedUp) {
        CustomerOrder order = getOwnedOrderForUpdate(sellerId, groupBuyId, orderId);
        if (!order.isActive()) {
            throw new ApiException(ErrorCode.ORDER_CANCELLED);
        }
        if (pickedUp) {
            requireNoRefundPending(order);
        }
        order.markPickedUp(pickedUp, LocalDateTime.now(clock));
        return OrderMapper.toSellerOrder(order, false, cipher);
    }

    @Transactional
    public SellerOrder cancel(Long sellerId, Long groupBuyId, Long orderId) {
        CustomerOrder order = getOwnedOrderForUpdate(sellerId, groupBuyId, orderId);
        if (!order.isActive()) {
            throw new ApiException(ErrorCode.ORDER_ALREADY_CANCELLED);
        }
        requireNoRefundPending(order);
        // 물건을 건넨 주문을 바로 취소하면 재고가 다시 늘어난다. 잘못 체크했으면 수령 체크를 해제한 뒤 취소 (2026-10-19)
        if (order.isPickedUp()) {
            throw new ApiException(ErrorCode.PICKED_UP_UNCHECK_FIRST);
        }
        OrderCancellation.cancel(order, productRepository, entityManager, stockAlerts, LocalDateTime.now(clock));
        return OrderMapper.toSellerOrder(order, false, cipher);
    }

    /**
     * 고객의 환불 신청 처리 (2026-10-18 사용자 결정: 판매자가 확인 후 취소). 판매자가 직접 계좌로 돈을 돌려보내야 하므로
     * approve = 주문 취소 + "환불 필요"(판매자가 송금 후 "환불 완료"), reject = 취소 안 함 (주문 그대로, 고객 화면에 반려 표시)
     */
    @Transactional
    public SellerOrder decideRefund(Long sellerId, Long groupBuyId, Long orderId, boolean approve) {
        CustomerOrder order = getOwnedOrderForUpdate(sellerId, groupBuyId, orderId);
        if (!order.isRefundPending()) {
            throw new ApiException(ErrorCode.NO_PENDING_REFUND);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (approve) {
            OrderCancellation.cancelForRefund(order, productRepository, entityManager, stockAlerts, now);
        } else {
            order.rejectRefund(now);
        }
        return OrderMapper.toSellerOrder(order, false, cipher);
    }

    @Transactional
    public SellerOrder markRefunded(Long sellerId, Long groupBuyId, Long orderId) {
        CustomerOrder order = getOwnedOrderForUpdate(sellerId, groupBuyId, orderId);
        if (!order.isRefundRequired()) {
            throw new ApiException(ErrorCode.REFUND_NOT_REQUIRED);
        }
        if (order.getRefundedAt() != null) {
            throw new ApiException(ErrorCode.ALREADY_REFUNDED);
        }
        order.markRefunded(LocalDateTime.now(clock));
        return OrderMapper.toSellerOrder(order, false, cipher);
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
            throw new ApiException(ErrorCode.CANCELLED_CANNOT_EDIT);
        }
        if (order.isPickedUp()) {
            throw new ApiException(ErrorCode.PICKED_UP_CANNOT_EDIT);
        }
        requireNoRefundPending(order);
        if (order.isPaid()) {
            throw new ApiException(ErrorCode.PAID_CANNOT_EDIT);
        }
        LocalDateTime now = LocalDateTime.now(clock);

        // 1) 수령·결제. 공구에서 그 방법을 꺼도(예: 픽업 끄고 배송만) 이미 받은 주문은 같은 방법 그대로 고칠 수 있다 (2026-10-19)
        boolean sameReceive = r.receiveMethod() != null && r.receiveMethod() == order.getReceiveMethod();
        ReceiveMethod receive = sameReceive ? order.getReceiveMethod() : OrderRules.receiveMethod(groupBuy, r.receiveMethod());
        PaymentMethod payment = sameReceive && r.paymentMethod() != null && r.paymentMethod() == order.getPaymentMethod()
                ? order.getPaymentMethod() : OrderRules.paymentMethod(groupBuy, receive, r.paymentMethod());
        PickupPoint point = receive == ReceiveMethod.PICKUP ? OrderRules.pickupPoint(groupBuy, r.pickupPointId()) : null;
        String recipientPhone = null;
        if (receive == ReceiveMethod.DELIVERY) {
            recipientPhone = PhoneNumbers.normalize(r.recipientPhone());
            if (trimToNull(r.recipientName()) == null || recipientPhone == null || trimToNull(r.address()) == null) {
                throw new ApiException(ErrorCode.DELIVERY_INFO_REQUIRED_FOR_CHANGE);
            }
        }
        String depositorName = trimToNull(r.depositorName());
        if (order.getSource() == OrderSource.FORM && depositorName == null) {
            throw new ApiException(ErrorCode.DEPOSITOR_NAME_REQUIRED);
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
                throw new ApiException(ErrorCode.PRODUCT_NOT_FOUND);
            }
            wanted.merge(item.productId(), item.quantity(), Integer::sum);
        }
        if (wanted.isEmpty()) {
            throw new ApiException(ErrorCode.QUANTITY_REQUIRED_FOR_CHANGE);
        }

        boolean delivery = receive == ReceiveMethod.DELIVERY;
        OrderUpdate.apply(order, OrderUpdate.Values.builder()
                .quantities(wanted)
                .receive(receive)
                .point(point)
                .payment(payment)
                .depositorName(depositorName)
                .nickname(trimToNull(r.nickname()))
                .unitAddress(trimToNull(r.unitAddress()))
                .recipientName(delivery ? r.recipientName().trim() : null)
                .recipientPhone(recipientPhone)
                .zipcode(delivery ? trimToNull(r.zipcode()) : null)
                .address(delivery ? r.address().trim() : null)
                .addressDetail(delivery ? trimToNull(r.addressDetail()) : null)
                .build(),
                ChangeActor.SELLER, null, productRepository, entityManager, stockAlerts, now);
        orderRepository.flush();
        return OrderMapper.toSellerOrder(order, false, cipher);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
