package com.depositcock.order;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.depositcock.common.DataCipher;
import com.depositcock.order.OrderDtos.ChangeRecord;
import com.depositcock.order.OrderDtos.MyOrder;
import com.depositcock.order.OrderDtos.PaymentRecord;
import com.depositcock.order.OrderDtos.Prefill;
import com.depositcock.order.OrderDtos.PublicItem;
import com.depositcock.order.OrderDtos.SellerOrder;
import com.depositcock.order.OrderDtos.SellerOrderItem;

final class OrderMapper {

    private OrderMapper() {
    }

    static MyOrder toMyOrder(CustomerOrder o, boolean acceptingOrders) {
        return toMyOrder(o, acceptingOrders, null);
    }

    static MyOrder toMyOrder(CustomerOrder o, boolean acceptingOrders, Prefill editValues) {
        DisplayStatus status = o.displayStatus();
        return new MyOrder(o.getOrderNo(),
                o.getItems().stream().map(i -> new PublicItem(i.getProduct().getId(), i.getProductNameSnapshot(), i.getQuantity(),
                        i.getUnitPriceSnapshot(), i.amount())).toList(),
                o.getItemsAmount(), o.getShippingFee(), o.getExpectedAmount(), o.getReceiveMethod(),
                o.getPickupPoint() == null ? null : o.getPickupPoint().getName(), o.getPaymentMethod(),
                o.getDepositorName(), status, status.getLabel(),
                status == DisplayStatus.AWAITING_DEPOSIT,
                o.isActive() && acceptingOrders && !o.isPickedUp() && !o.isConfirmed() && !o.isRefundPending(),
                o.canRequestRefund(),
                o.isRefundPending() ? "REQUESTED" : o.isActive() && o.getRefundRejectedAt() != null ? "REJECTED" : null,
                o.isRefundRequired() && o.getRefundedAt() == null,
                o.isRefundRequired() ? o.getRefundedAt() : null,
                o.getOrderedAt(),
                lastChange(o, ChangeActor.SELLER), lastChange(o, ChangeActor.CUSTOMER),
                acceptingOrders && o.isReplaceableByCustomer(), editValues);
    }

    private static java.time.LocalDateTime lastChange(CustomerOrder o, ChangeActor actor) {
        return o.getChanges().stream().filter(c -> c.getChangedBy() == actor).map(OrderChange::getChangedAt)
                .reduce((a, b) -> b).orElse(null);
    }

    static List<SellerOrder> toSellerOrders(List<CustomerOrder> orders, DataCipher cipher) {
        Map<Long, Long> activeByKey = orders.stream().filter(CustomerOrder::isActive)
                .filter(o -> o.getSource() == OrderSource.FORM)
                .collect(Collectors.groupingBy(CustomerOrder::getCustomerId, Collectors.counting()));
        // 같은 닉네임의 링크 주문·댓글 주문 (같은 사람이 양쪽으로 주문했을 수 있음. 경고만)
        Map<String, List<CustomerOrder>> byNickname = orders.stream().filter(CustomerOrder::isActive)
                .filter(o -> Nicknames.key(o.getNickname()) != null)
                .collect(Collectors.groupingBy(o -> Nicknames.key(o.getNickname())));
        return orders.stream().map(o -> toSellerOrder(o, activeByKey.getOrDefault(o.getCustomerId(), 0L) > 1,
                sameNicknameOtherSource(o, byNickname), cipher)).toList();
    }

    private static List<Integer> sameNicknameOtherSource(CustomerOrder o, Map<String, List<CustomerOrder>> byNickname) {
        String key = Nicknames.key(o.getNickname());
        if (!o.isActive() || key == null) {
            return List.of();
        }
        return byNickname.getOrDefault(key, List.of()).stream().filter(x -> x.getSource() != o.getSource())
                .map(CustomerOrder::getOrderNo).toList();
    }

    static SellerOrder toSellerOrder(CustomerOrder o, boolean duplicateContact, DataCipher cipher) {
        return toSellerOrder(o, duplicateContact, List.of(), cipher);
    }

    static SellerOrder toSellerOrder(CustomerOrder o, boolean duplicateContact, List<Integer> sameNicknameOrderNos,
                                     DataCipher cipher) {
        DisplayStatus status = o.displayStatus();
        return new SellerOrder(o.getId(), o.getOrderNo(), o.getSource(), o.getCustomerId(),
                PhoneNumbers.format(o.getPhone()), o.getDepositorName(), o.getNickname(), o.getUnitAddress(),
                o.getReceiveMethod(), o.getPickupPoint() == null ? null : o.getPickupPoint().getId(),
                o.getPickupPoint() == null ? null : o.getPickupPoint().getName(), o.getRecipientName(),
                PhoneNumbers.format(o.getRecipientPhone()), o.getZipcode(), o.getAddress(), o.getAddressDetail(),
                o.getPaymentMethod(),
                o.getItems().stream().map(i -> new SellerOrderItem(i.getProduct().getId(), i.getProductNameSnapshot(),
                        i.getQuantity(), i.getUnitPriceSnapshot())).toList(),
                o.totalQuantity(), o.getItemsAmount(), o.getShippingFee(), o.getExpectedAmount(), status,
                status.getLabel(), o.isActive(), o.isPaid(), o.getPaidAt(),
                o.getPaidDepositorName(), o.getPaidAmount(), o.isOnsitePaid(), o.isPickedUp(), o.getPickedUpAt(),
                o.isRefundRequired(), o.getRefundedAt(), o.getRefundRequestedAt(), o.getRefundRejectedAt(),
                o.getRefundBank(), cipher.decrypt(o.getRefundAccount()), o.getRefundHolder(), duplicateContact, o.getOrderedAt(), o.getUpdatedAt(), o.getCancelledAt(),
                o.getConfirmations().stream().map(c -> new PaymentRecord(c.getDepositorName(), c.getAmount(),
                        c.isManual(), c.getConfirmedAt(), c.getCancelledAt())).toList(),
                o.getChanges().stream().map(c -> new ChangeRecord(c.getSummary(), c.getChangedBy(), c.getChangedAt())).toList(),
                sameNicknameOrderNos);
    }
}
