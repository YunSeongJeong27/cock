package com.depositcock.order;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.depositcock.common.ApiException;
import com.depositcock.common.ErrorCode;
import com.depositcock.common.Texts;
import com.depositcock.groupbuy.GroupBuy;
import com.depositcock.groupbuy.PickupPoint;
import com.depositcock.groupbuy.Product;
import com.depositcock.groupbuy.ShippingFeeType;
import com.depositcock.order.OrderDtos.DeliveryInput;
import com.depositcock.order.OrderDtos.OrderItemRequest;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 수령 방법·결제 방식·픽업 지점 규칙 (개발 전 인터뷰 결과 1번). 고객 주문서와 판매자의 주문 변경이 같이 쓴다.
 * - 픽업·배송 둘 다 켜져 있으면 골라야 하고, 하나만 켜져 있으면 그것
 * - 배송은 선입금만. 픽업은 공구의 픽업 결제 설정(선입금·현장결제)을 따른다
 * - 픽업 지점이 2개 이상이면 골라야 한다
 * - 상품 수량·배송 정보 읽기·배송비도 두 곳이 같이 쓴다 (전에는 두 서비스에 같은 코드가 따로 있었음, 2026-10-21)
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class OrderRules {

    static ReceiveMethod receiveMethod(GroupBuy groupBuy, ReceiveMethod requested) {
        if (groupBuy.isPickupEnabled() && groupBuy.isDeliveryEnabled()) {
            if (requested == null) {
                throw new ApiException(ErrorCode.RECEIVE_METHOD_REQUIRED);
            }
            return requested;
        }
        ReceiveMethod only = groupBuy.isPickupEnabled() ? ReceiveMethod.PICKUP : ReceiveMethod.DELIVERY;
        if (requested != null && requested != only) {
            throw new ApiException(ErrorCode.RECEIVE_METHOD_NOT_ALLOWED);
        }
        return only;
    }

    static PaymentMethod paymentMethod(GroupBuy groupBuy, ReceiveMethod receiveMethod, PaymentMethod requested) {
        if (receiveMethod == ReceiveMethod.DELIVERY) {
            return PaymentMethod.PREPAID; // 배송은 선입금만
        }
        if (groupBuy.isPickupPrepayEnabled() && groupBuy.isPickupOnsiteEnabled()) {
            if (requested == null) {
                throw new ApiException(ErrorCode.PAYMENT_METHOD_REQUIRED);
            }
            return requested;
        }
        PaymentMethod only = groupBuy.isPickupPrepayEnabled() ? PaymentMethod.PREPAID : PaymentMethod.ONSITE;
        if (requested != null && requested != only) {
            throw new ApiException(ErrorCode.PAYMENT_METHOD_NOT_ALLOWED);
        }
        return only;
    }

    static PickupPoint pickupPoint(GroupBuy groupBuy, Long pickupPointId) {
        List<PickupPoint> points = groupBuy.sortedPickupPoints();
        if (points.isEmpty()) {
            return null;
        }
        if (points.size() == 1) {
            return points.get(0);
        }
        if (pickupPointId == null) {
            throw new ApiException(ErrorCode.PICKUP_POINT_REQUIRED);
        }
        return points.stream().filter(p -> p.getId().equals(pickupPointId)).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.PICKUP_POINT_NOT_FOUND));
    }

    /** 상품별 수량: 0 이하는 빼고 같은 상품은 합친다. 공구에 없는 상품이면 400, 남는 상품이 없으면 emptyError */
    static Map<Long, Integer> quantities(GroupBuy groupBuy, List<OrderItemRequest> items, ErrorCode emptyError) {
        Set<Long> productIds = groupBuy.getProducts().stream().map(Product::getId).collect(Collectors.toSet());
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (OrderItemRequest item : items) {
            if (item.quantity() <= 0) {
                continue;
            }
            if (!productIds.contains(item.productId())) {
                throw new ApiException(ErrorCode.PRODUCT_NOT_FOUND);
            }
            quantities.merge(item.productId(), item.quantity(), Integer::sum);
        }
        if (quantities.isEmpty()) {
            throw new ApiException(emptyError);
        }
        return quantities;
    }

    /** 배송 정보. 배송이 아니면 Delivery.NONE. 받는 분·연락처·주소 중 하나라도 없으면 missingError */
    static Delivery delivery(ReceiveMethod receive, DeliveryInput r, ErrorCode missingError) {
        if (receive != ReceiveMethod.DELIVERY) {
            return Delivery.NONE;
        }
        String name = Texts.blankToNull(r.recipientName());
        String phone = PhoneNumbers.normalize(r.recipientPhone());
        String address = Texts.blankToNull(r.address());
        if (name == null || phone == null || address == null) {
            throw new ApiException(missingError);
        }
        return new Delivery(name, phone, Texts.blankToNull(r.zipcode()), address, Texts.blankToNull(r.addressDetail()));
    }

    /** 배송비: 배송 주문이고 공구가 고정 배송비일 때만 (새 주문·주문 변경이 같이 씀) */
    static int shippingFee(GroupBuy groupBuy, ReceiveMethod receive) {
        return receive == ReceiveMethod.DELIVERY && groupBuy.getShippingFeeType() == ShippingFeeType.FIXED
                ? groupBuy.getShippingFee() : 0;
    }
}
