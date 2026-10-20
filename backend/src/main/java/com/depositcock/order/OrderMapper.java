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
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class OrderMapper {

    static MyOrder toMyOrder(CustomerOrder o, boolean acceptingOrders) {
        return toMyOrder(o, acceptingOrders, null);
    }

    static MyOrder toMyOrder(CustomerOrder o, boolean acceptingOrders, Prefill editValues) {
        DisplayStatus status = o.displayStatus();
        return MyOrder.builder()
                .orderNo(o.getOrderNo())
                .items(o.getItems().stream().map(i -> new PublicItem(i.getProduct().getId(), i.getProductNameSnapshot(), i.getQuantity(), i.getUnitPriceSnapshot(), i.amount())).toList())
                .itemsAmount(o.getItemsAmount())
                .shippingFee(o.getShippingFee())
                .expectedAmount(o.getExpectedAmount())
                .receiveMethod(o.getReceiveMethod())
                .pickupPointName(o.getPickupPoint() == null ? null : o.getPickupPoint().getName())
                .paymentMethod(o.getPaymentMethod())
                .depositorName(o.getDepositorName())
                .status(status)
                .statusLabel(status.getLabel())
                .awaitingConfirm(status == DisplayStatus.AWAITING_DEPOSIT)
                .cancellable(o.isActive() && acceptingOrders && !o.isPickedUp() && !o.isConfirmed() && !o.isRefundPending())
                .refundRequestable(o.canRequestRefund())
                .refundState(o.isRefundPending() ? "REQUESTED" : o.isActive() && o.getRefundRejectedAt() != null ? "REJECTED" : null)
                .refundRequired(o.isRefundRequired() && o.getRefundedAt() == null)
                .refundedAt(o.isRefundRequired() ? o.getRefundedAt() : null)
                .orderedAt(o.getOrderedAt())
                .changedAt(lastChange(o, ChangeActor.SELLER))
                .customerChangedAt(lastChange(o, ChangeActor.CUSTOMER))
                .replaceable(acceptingOrders && o.isReplaceableByCustomer())
                .editValues(editValues)
                .build();
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
        return SellerOrder.builder()
                .id(o.getId())
                .orderNo(o.getOrderNo())
                .source(o.getSource())
                .customerId(o.getCustomerId())
                .phone(PhoneNumbers.format(o.getPhone()))
                .depositorName(o.getDepositorName())
                .nickname(o.getNickname())
                .unitAddress(o.getUnitAddress())
                .receiveMethod(o.getReceiveMethod())
                .pickupPointId(o.getPickupPoint() == null ? null : o.getPickupPoint().getId())
                .pickupPointName(o.getPickupPoint() == null ? null : o.getPickupPoint().getName())
                .recipientName(o.getRecipientName())
                .recipientPhone(PhoneNumbers.format(o.getRecipientPhone()))
                .zipcode(o.getZipcode())
                .address(o.getAddress())
                .addressDetail(o.getAddressDetail())
                .paymentMethod(o.getPaymentMethod())
                .items(o.getItems().stream().map(i -> new SellerOrderItem(i.getProduct().getId(), i.getProductNameSnapshot(), i.getQuantity(), i.getUnitPriceSnapshot())).toList())
                .totalQuantity(o.totalQuantity())
                .itemsAmount(o.getItemsAmount())
                .shippingFee(o.getShippingFee())
                .expectedAmount(o.getExpectedAmount())
                .status(status)
                .statusLabel(status.getLabel())
                .active(o.isActive())
                .finished(o.isFinished())
                .paid(o.isPaid())
                .paidAt(o.getPaidAt())
                .paidDepositorName(o.getPaidDepositorName())
                .paidAmount(o.getPaidAmount())
                .onsitePaid(o.isOnsitePaid())
                .pickedUp(o.isPickedUp())
                .pickedUpAt(o.getPickedUpAt())
                .refundRequired(o.isRefundRequired())
                .refundedAt(o.getRefundedAt())
                .refundRequestedAt(o.getRefundRequestedAt())
                .refundRejectedAt(o.getRefundRejectedAt())
                .refundBank(o.getRefundBank())
                .refundAccount(cipher.decrypt(o.getRefundAccount()))
                .duplicateContact(duplicateContact)
                .orderedAt(o.getOrderedAt())
                .updatedAt(o.getUpdatedAt())
                .cancelledAt(o.getCancelledAt())
                .payments(o.getConfirmations().stream().map(c -> new PaymentRecord(c.getDepositorName(), c.getAmount(), c.isManual(), c.getConfirmedAt(), c.getCancelledAt())).toList())
                .changes(o.getChanges().stream().map(c -> new ChangeRecord(c.getSummary(), c.getChangedBy(), c.getChangedAt())).toList())
                .sameNicknameOrderNos(sameNicknameOrderNos)
                .build();
    }
}
