package com.depositcock.order;

import java.util.List;

import com.depositcock.common.ApiException;
import com.depositcock.common.ErrorCode;
import com.depositcock.groupbuy.GroupBuy;
import com.depositcock.groupbuy.PickupPoint;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 수령 방법·결제 방식·픽업 지점 규칙 (개발 전 인터뷰 결과 1번). 고객 주문서와 판매자의 주문 변경이 같이 쓴다.
 * - 픽업·배송 둘 다 켜져 있으면 골라야 하고, 하나만 켜져 있으면 그것
 * - 배송은 선입금만. 픽업은 공구의 픽업 결제 설정(선입금·현장결제)을 따른다
 * - 픽업 지점이 2개 이상이면 골라야 한다
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
}
