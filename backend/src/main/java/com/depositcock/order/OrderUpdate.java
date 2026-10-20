package com.depositcock.order;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.depositcock.common.ApiException;
import com.depositcock.common.ErrorCode;
import com.depositcock.groupbuy.GroupBuy;
import com.depositcock.groupbuy.PickupPoint;
import com.depositcock.groupbuy.Product;
import com.depositcock.groupbuy.ProductRepository;
import com.depositcock.groupbuy.ShippingFeeType;
import com.depositcock.groupbuy.StockAlerts;
import jakarta.persistence.EntityManager;

/**
 * 주문 내용 바꾸기 공통 처리: 판매자 "주문 변경"(2026-10-15)과 고객이 같은 휴대폰 번호로 다시 주문한 경우(2026-10-18).
 * - 상품 수량은 늘어난 만큼 판매 수량에 더하고 줄어든 만큼 돌려놓는다. 기본은 재고를 넘어도 막지 않고,
 *   "다 팔리면 품절"을 켠 상품만 준비 수량 안에서 더한다 (2026-10-16)
 * - 원래 있던 상품은 주문 당시 단가 그대로, 새로 넣은 상품은 지금 단가
 * - 바뀐 내용을 사람이 읽는 문장으로 order_change 에 남긴다
 */
final class OrderUpdate {

    private OrderUpdate() {
    }

    /** 바꿀 값. 입력 검사(필수값, 수령·결제 규칙, 배송 정보)는 부르는 쪽에서 끝낸 값 */
    record Values(Map<Long, Integer> quantities, ReceiveMethod receive, PickupPoint point, PaymentMethod payment,
                  String depositorName, String nickname, String unitAddress, String recipientName,
                  String recipientPhone, String zipcode, String address, String addressDetail) {
    }

    /** 상품 수량이 어떻게 바뀌는지 ("사과 2→3개", 뺀 상품 "사과 2→0개", 새 상품 "배 0→1개") */
    private static List<String> itemChanges(CustomerOrder order, Map<Long, Integer> quantities, Map<Long, Product> products) {
        List<String> changes = new ArrayList<>();
        Map<Long, OrderItem> current = currentItems(order);
        for (Long productId : productIds(current, quantities)) {
            OrderItem item = current.get(productId);
            int before = item == null ? 0 : item.getQuantity();
            int after = quantities.getOrDefault(productId, 0);
            if (before != after) {
                String name = item == null ? products.get(productId).getName() : item.getProductNameSnapshot();
                changes.add(name + " " + before + "→" + after + "개");
            }
        }
        return changes;
    }

    /**
     * 주문을 바꾸고 기록을 남긴다. 바뀐 것이 없으면 400.
     * 예외가 나면 트랜잭션 롤백으로 앞서 바꾼 판매 수량도 되돌아간다.
     *
     * @param note 기록 끝에 붙일 말 (없으면 null)
     * @return 남긴 기록 문장 (예: "사과 2→3개, 금액 2,000원→3,000원")
     */
    static String apply(CustomerOrder order, Values v, ChangeActor actor, String note, ProductRepository productRepository,
                      EntityManager em, StockAlerts stockAlerts, LocalDateTime now) {
        GroupBuy groupBuy = order.getGroupBuy();
        Map<Long, Product> products = new LinkedHashMap<>();
        groupBuy.getProducts().forEach(p -> products.put(p.getId(), p));

        // 1) 상품 수량
        List<String> changes = new ArrayList<>(itemChanges(order, v.quantities(), products));
        Map<Long, OrderItem> current = currentItems(order);
        List<Product> touched = new ArrayList<>();
        for (Long productId : productIds(current, v.quantities())) {
            OrderItem item = current.get(productId);
            int before = item == null ? 0 : item.getQuantity();
            int after = v.quantities().getOrDefault(productId, 0);
            if (before == after) {
                continue;
            }
            Product product = products.get(productId);
            if (product == null) {
                // 주문 뒤에 상품이 지워진 경우는 없다 (주문 있는 상품은 삭제 불가). 안전하게 막는다
                throw new ApiException(ErrorCode.PRODUCT_NOT_FOUND);
            }
            if (after > before) {
                if (product.isBlockWhenSoldOut()) {
                    if (productRepository.increaseSoldWithinStock(productId, after - before) == 0) {
                        em.refresh(product);
                        throw notEnoughStock(product, before, actor);
                    }
                } else {
                    productRepository.increaseSold(productId, after - before);
                }
            } else {
                productRepository.decreaseSold(productId, before - after);
            }
            touched.add(product);
            if (item == null) {
                order.addItem(new OrderItem(order, product, after));
            } else if (after == 0) {
                order.removeItem(item);
            } else {
                item.changeQuantity(after);
            }
        }
        // 판매 수량이 바뀐 상품만 다시 읽는다 (전에는 공구의 모든 상품을 다시 읽었음, 2026-10-20 점검)
        touched.forEach(p -> {
            em.refresh(p);
            stockAlerts.check(p);
        });

        // 2) 고객이 적은 값
        // 픽업 지점은 id 로 비교 (객체 비교는 지연 로딩 프록시와 실제 객체가 다르게 나올 수 있음)
        if (v.receive() != order.getReceiveMethod() || !Objects.equals(pointId(v.point()), pointId(order.getPickupPoint()))) {
            changes.add("수령 " + receiveLabel(order.getReceiveMethod(), order.getPickupPoint()) + "→"
                    + receiveLabel(v.receive(), v.point()));
        }
        if (v.payment() != order.getPaymentMethod()) {
            changes.add("결제 " + order.getPaymentMethod().label() + "→" + v.payment().label());
        }
        addIfChanged(changes, "입금자명", order.getDepositorName(), v.depositorName());
        addIfChanged(changes, "닉네임", order.getNickname(), v.nickname());
        addIfChanged(changes, "동·호수", order.getUnitAddress(), v.unitAddress());
        if (v.receive() == ReceiveMethod.DELIVERY) {
            String before = deliveryText(order.getRecipientName(), order.getRecipientPhone(), order.getZipcode(),
                    order.getAddress(), order.getAddressDetail());
            String after = deliveryText(v.recipientName(), v.recipientPhone(), v.zipcode(), v.address(), v.addressDetail());
            if (!after.equals(before)) {
                changes.add("배송지 변경");
            }
        }

        // 3) 금액
        long itemsAmount = order.getItems().stream().mapToLong(i -> (long) i.getUnitPriceSnapshot() * i.getQuantity()).sum();
        int shippingFee = v.receive() == ReceiveMethod.DELIVERY && groupBuy.getShippingFeeType() == ShippingFeeType.FIXED
                ? groupBuy.getShippingFee() : 0;
        if (itemsAmount + shippingFee > PublicOrderService.MAX_ORDER_AMOUNT) {
            throw new ApiException(ErrorCode.ORDER_AMOUNT_TOO_LARGE);
        }
        if (changes.isEmpty()) {
            throw actor == ChangeActor.CUSTOMER ? new ApiException(ErrorCode.NO_CHANGE_CUSTOMER, order.getOrderNo())
                    : new ApiException(ErrorCode.NO_CHANGE);
        }
        int beforeAmount = order.getExpectedAmount();
        order.fillCustomer(v.depositorName(), v.nickname(), v.unitAddress());
        order.fillReceive(v.receive(), v.point(), v.payment());
        if (v.receive() == ReceiveMethod.DELIVERY) {
            order.fillDelivery(v.recipientName(), v.recipientPhone(), v.zipcode(), v.address(), v.addressDetail());
        } else {
            order.fillDelivery(null, null, null, null, null);
        }
        order.fillAmounts((int) itemsAmount, shippingFee);
        if (order.getExpectedAmount() != beforeAmount) {
            changes.add("금액 " + won(beforeAmount) + "→" + won(order.getExpectedAmount()));
        }
        if (note != null) {
            changes.add(note);
        }
        String summary = String.join(", ", changes);
        order.recordChange(summary, actor, now);
        return summary;
    }

    private static ApiException notEnoughStock(Product product, int before, ChangeActor actor) {
        int left = Math.max(0, product.remaining());
        if (actor == ChangeActor.SELLER) {
            return new ApiException(ErrorCode.NOT_ENOUGH_STOCK_SELLER, product.getName(), left);
        }
        // 고객에게는 이 주문에 이미 있던 수량까지 더해서 고를 수 있는 최대를 알려준다
        int max = before + left;
        return before > 0 ? new ApiException(ErrorCode.NOT_ENOUGH_STOCK_WITH_CURRENT, product.getName(), max, before)
                : new ApiException(ErrorCode.NOT_ENOUGH_STOCK, product.getName(), max);
    }

    private static Map<Long, OrderItem> currentItems(CustomerOrder order) {
        Map<Long, OrderItem> current = new LinkedHashMap<>();
        order.getItems().forEach(i -> current.put(i.getProduct().getId(), i));
        return current;
    }

    private static Set<Long> productIds(Map<Long, OrderItem> current, Map<Long, Integer> quantities) {
        Set<Long> ids = new LinkedHashSet<>(current.keySet());
        ids.addAll(quantities.keySet());
        return ids;
    }

    private static void addIfChanged(List<String> changes, String label, String before, String after) {
        if (!Objects.equals(before, after)) {
            changes.add(label + " " + (before == null ? "(없음)" : before) + "→" + (after == null ? "(없음)" : after));
        }
    }

    private static Long pointId(PickupPoint point) {
        return point == null ? null : point.getId();
    }

    private static String receiveLabel(ReceiveMethod method, PickupPoint point) {
        if (method == ReceiveMethod.DELIVERY || point == null) {
            return method.label();
        }
        return method.label() + "(" + point.getName() + ")";
    }

    private static String deliveryText(String name, String phone, String zipcode, String address, String detail) {
        return String.join("|", Objects.toString(name, ""), Objects.toString(phone, ""), Objects.toString(zipcode, ""),
                Objects.toString(address, ""), Objects.toString(detail, ""));
    }

    private static String won(long amount) {
        return String.format("%,d원", amount);
    }
}
