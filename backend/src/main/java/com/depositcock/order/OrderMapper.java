package com.depositcock.order;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.depositcock.order.OrderDtos.ChangeRecord;
import com.depositcock.order.OrderDtos.MyOrder;
import com.depositcock.order.OrderDtos.PaymentRecord;
import com.depositcock.order.OrderDtos.PublicItem;
import com.depositcock.order.OrderDtos.SellerOrder;
import com.depositcock.order.OrderDtos.SellerOrderItem;

final class OrderMapper {

    private OrderMapper() {
    }

    static MyOrder toMyOrder(CustomerOrder o, boolean acceptingOrders) {
        DisplayStatus status = o.displayStatus();
        return new MyOrder(o.getOrderNo(),
                o.getItems().stream().map(i -> new PublicItem(i.getProductNameSnapshot(), i.getQuantity(),
                        i.getUnitPriceSnapshot(), i.amount())).toList(),
                o.getItemsAmount(), o.getShippingFee(), o.getExpectedAmount(), o.getReceiveMethod(),
                o.getPickupPoint() == null ? null : o.getPickupPoint().getName(), o.getPaymentMethod(),
                o.getDepositorName(), status, status.getLabel(),
                status == DisplayStatus.AWAITING_DEPOSIT,
                o.isActive() && acceptingOrders && !o.isPickedUp(), // 수령한 주문은 서버도 취소를 거절한다
                o.isRefundRequired() && o.getRefundedAt() == null,
                o.getOrderedAt(),
                o.getChanges().isEmpty() ? null : o.getChanges().get(o.getChanges().size() - 1).getChangedAt());
    }

    static List<SellerOrder> toSellerOrders(List<CustomerOrder> orders) {
        Map<Long, Long> activeByKey = orders.stream().filter(CustomerOrder::isActive)
                .filter(o -> o.getSource() == OrderSource.FORM)
                .collect(Collectors.groupingBy(CustomerOrder::getCustomerId, Collectors.counting()));
        return orders.stream().map(o -> toSellerOrder(o, activeByKey.getOrDefault(o.getCustomerId(), 0L) > 1)).toList();
    }

    static SellerOrder toSellerOrder(CustomerOrder o, boolean duplicateContact) {
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
                status.getLabel(), o.isActive(), o.getDepositReportedAt(), o.isPaid(), o.getPaidAt(),
                o.getPaidDepositorName(), o.getPaidAmount(), o.isOnsitePaid(), o.isPickedUp(), o.getPickedUpAt(),
                o.isRefundRequired(), o.getRefundedAt(), duplicateContact, o.getOrderedAt(), o.getCancelledAt(),
                o.getConfirmations().stream().map(c -> new PaymentRecord(c.getDepositorName(), c.getAmount(),
                        c.isManual(), c.getConfirmedAt(), c.getCancelledAt())).toList(),
                o.getChanges().stream().map(c -> new ChangeRecord(c.getSummary(), c.getChangedAt())).toList());
    }
}
