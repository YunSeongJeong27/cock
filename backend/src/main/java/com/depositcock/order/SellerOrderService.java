package com.depositcock.order;

import com.depositcock.groupbuy.StockAlerts;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.depositcock.common.ApiException;
import com.depositcock.groupbuy.GroupBuy;
import com.depositcock.groupbuy.GroupBuyService;
import com.depositcock.groupbuy.PickupPoint;
import com.depositcock.groupbuy.Product;
import com.depositcock.groupbuy.ProductRepository;
import com.depositcock.groupbuy.ShippingFeeType;
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
     * 판매자가 고객의 변경 요청(댓글·채팅)을 대신 반영한다. 고객 화면에는 수정 기능이 없다 (인터뷰 결과 4번: 고객은 취소 후 재주문).
     * - 입금 확정·수령·취소된 주문은 바꿀 수 없다 (입금 확정된 주문은 먼저 확정을 취소)
     * - 마감 뒤에도 판매자는 바꿀 수 있다. 1인 최대 수량은 판매자 판단이라 보지 않고, 재고 한도는 지킨다
     * - 원래 있던 상품은 주문 당시 단가 그대로, 새로 넣은 상품은 지금 단가
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
        List<String> changes = new ArrayList<>();

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
        Map<Long, Product> products = new HashMap<>();
        groupBuy.getProducts().forEach(p -> products.put(p.getId(), p));
        Map<Long, Integer> wanted = new LinkedHashMap<>();
        for (OrderItemRequest item : r.items()) {
            if (item.quantity() <= 0) {
                continue;
            }
            if (!products.containsKey(item.productId())) {
                throw ApiException.badRequest("상품을 찾을 수 없습니다.");
            }
            wanted.merge(item.productId(), item.quantity(), Integer::sum);
        }
        if (wanted.isEmpty()) {
            throw ApiException.badRequest("상품 수량을 1개 이상 넣어 주세요. 주문을 없애려면 주문 취소를 눌러 주세요.");
        }
        Map<Long, OrderItem> current = new LinkedHashMap<>();
        order.getItems().forEach(i -> current.put(i.getProduct().getId(), i));
        Set<Long> productIds = new LinkedHashSet<>(current.keySet());
        productIds.addAll(wanted.keySet());
        for (Long productId : productIds) {
            OrderItem item = current.get(productId);
            int before = item == null ? 0 : item.getQuantity();
            int after = wanted.getOrDefault(productId, 0);
            if (before == after) {
                continue;
            }
            Product product = products.get(productId);
            if (product == null) {
                // 주문 뒤에 상품이 지워진 경우는 없다 (주문 있는 상품은 삭제 불가). 안전하게 막는다
                throw ApiException.badRequest("상품을 찾을 수 없습니다.");
            }
            // 판매 수량: 늘어난 만큼 더하고 줄어든 만큼 돌려놓는다. 기본은 재고를 넘어도 막지 않고,
            // "다 팔리면 품절"을 켠 상품만 준비 수량 안에서 더한다 (2026-10-16)
            if (after > before) {
                if (product.isBlockWhenSoldOut()) {
                    if (productRepository.increaseSoldWithinStock(productId, after - before) == 0) {
                        entityManager.refresh(product);
                        throw ApiException.conflict("'" + product.getName() + "'의 남은 수량이 부족합니다. (남은 수량 "
                                + Math.max(0, product.remaining()) + "개, 품절로 막는 상품)");
                    }
                } else {
                    productRepository.increaseSold(productId, after - before);
                }
            }
            if (after < before) {
                productRepository.decreaseSold(productId, before - after);
            }
            String name = item == null ? product.getName() : item.getProductNameSnapshot();
            changes.add(name + " " + before + "→" + after + "개");
            if (item == null) {
                order.addItem(new OrderItem(order, product, after));
            } else if (after == 0) {
                order.removeItem(item);
            } else {
                item.changeQuantity(after);
            }
        }
        products.values().forEach(p -> {
            entityManager.refresh(p);
            stockAlerts.check(p);
        });

        // 3) 고객이 적은 값
        if (receive != order.getReceiveMethod() || point != order.getPickupPoint()) {
            changes.add("수령 " + receiveLabel(order.getReceiveMethod(), order.getPickupPoint()) + "→" + receiveLabel(receive, point));
        }
        if (payment != order.getPaymentMethod()) {
            changes.add("결제 " + paymentLabel(order.getPaymentMethod()) + "→" + paymentLabel(payment));
        }
        addIfChanged(changes, "입금자명", order.getDepositorName(), depositorName);
        addIfChanged(changes, "닉네임", order.getNickname(), trimToNull(r.nickname()));
        addIfChanged(changes, "동·호수", order.getUnitAddress(), trimToNull(r.unitAddress()));
        if (receive == ReceiveMethod.DELIVERY) {
            String before = deliveryText(order.getRecipientName(), order.getRecipientPhone(), order.getZipcode(),
                    order.getAddress(), order.getAddressDetail());
            String after = deliveryText(r.recipientName().trim(), recipientPhone, trimToNull(r.zipcode()), r.address().trim(),
                    trimToNull(r.addressDetail()));
            if (!after.equals(before)) {
                changes.add("배송지 변경");
            }
        }

        // 4) 금액
        long itemsAmount = order.getItems().stream().mapToLong(i -> (long) i.getUnitPriceSnapshot() * i.getQuantity()).sum();
        int shippingFee = receive == ReceiveMethod.DELIVERY && groupBuy.getShippingFeeType() == ShippingFeeType.FIXED
                ? groupBuy.getShippingFee() : 0;
        if (itemsAmount + shippingFee > PublicOrderService.MAX_ORDER_AMOUNT) {
            throw ApiException.badRequest("주문 금액이 너무 큽니다. 수량을 확인해 주세요.");
        }
        int beforeAmount = order.getExpectedAmount();
        if (changes.isEmpty()) {
            throw ApiException.badRequest("바뀐 내용이 없습니다.");
        }
        order.fillCustomer(depositorName, trimToNull(r.nickname()), trimToNull(r.unitAddress()));
        order.fillReceive(receive, point, payment);
        if (receive == ReceiveMethod.DELIVERY) {
            order.fillDelivery(r.recipientName().trim(), recipientPhone, trimToNull(r.zipcode()), r.address().trim(),
                    trimToNull(r.addressDetail()));
        } else {
            order.fillDelivery(null, null, null, null, null);
        }
        order.fillAmounts((int) itemsAmount, shippingFee);
        if (order.getExpectedAmount() != beforeAmount) {
            changes.add("금액 " + won(beforeAmount) + "→" + won(order.getExpectedAmount()));
        }
        order.recordChange(String.join(", ", changes), now);
        orderRepository.flush();
        return OrderMapper.toSellerOrder(order, false);
    }

    private static void addIfChanged(List<String> changes, String label, String before, String after) {
        if (!Objects.equals(before, after)) {
            changes.add(label + " " + (before == null ? "(없음)" : before) + "→" + (after == null ? "(없음)" : after));
        }
    }

    private static String receiveLabel(ReceiveMethod method, PickupPoint point) {
        if (method == ReceiveMethod.DELIVERY) {
            return "배송";
        }
        return point == null ? "픽업" : "픽업(" + point.getName() + ")";
    }

    private static String paymentLabel(PaymentMethod method) {
        return method == PaymentMethod.PREPAID ? "선입금" : "현장결제";
    }

    private static String deliveryText(String name, String phone, String zipcode, String address, String detail) {
        return String.join("|", Objects.toString(name, ""), Objects.toString(phone, ""), Objects.toString(zipcode, ""),
                Objects.toString(address, ""), Objects.toString(detail, ""));
    }

    private static String won(int amount) {
        return String.format("%,d원", amount);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
