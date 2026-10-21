package com.depositcock.order;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.depositcock.common.ApiException;
import com.depositcock.common.ErrorCode;
import com.depositcock.common.Notice;
import com.depositcock.common.Tokens;
import com.depositcock.customer.CustomerRepository;
import com.depositcock.groupbuy.GroupBuy;
import com.depositcock.groupbuy.GroupBuyDtos.PickupPointResponse;
import com.depositcock.groupbuy.GroupBuyRepository;
import com.depositcock.groupbuy.GroupBuyStatus;
import com.depositcock.order.OrderDtos.AccountInfo;
import com.depositcock.order.OrderDtos.MyOrder;
import com.depositcock.order.OrderDtos.Prefill;
import com.depositcock.order.OrderDtos.PublicGroupBuy;
import com.depositcock.order.OrderDtos.PublicProduct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 고객 화면(주문서 링크)에서 주문·취소·환불 신청이 같이 쓰는 부분: 공구 찾기, 기기 기억, 이 기기가 볼 수 있는 주문, 화면 응답.
 * 트랜잭션은 부르는 쪽(PublicOrderService·CustomerCancelService)의 것을 그대로 쓴다.
 */
@Component
@RequiredArgsConstructor
class PublicPage {

    private final GroupBuyRepository groupBuyRepository;
    private final CustomerOrderRepository orderRepository;
    private final CustomerDeviceRepository deviceRepository;
    private final CustomerRepository customerRepository;

    /** 기억한 기기 + 새로 만든 쿠키 토큰(기존 쿠키를 그대로 쓰면 null) */
    record RememberedDevice(CustomerDevice device, String newCookie) {
    }

    GroupBuy findVisible(String publicToken) {
        GroupBuy groupBuy = groupBuyRepository.findByPublicToken(publicToken)
                .orElseThrow(() -> new ApiException(ErrorCode.GROUP_BUY_NOT_FOUND));
        if (groupBuy.getStatus() == GroupBuyStatus.DRAFT) {
            throw new ApiException(ErrorCode.GROUP_BUY_NOT_OPEN);
        }
        return groupBuy;
    }

    /** 판매자가 주문을 잠시 멈췄으면 고객 요청(주문·수정·취소·환불 신청)을 막는다. 잠근 뒤 확인해서 멈춘 직후 요청도 막힌다 */
    static void requireNotPaused(GroupBuy groupBuy) {
        if (groupBuy.isPaused()) {
            throw ApiException.withMessage(ErrorCode.ORDER_PAUSED, groupBuy.effectivePauseMessage());
        }
    }

    Optional<CustomerDevice> device(GroupBuy groupBuy, String cookieToken) {
        if (cookieToken == null || cookieToken.isBlank()) {
            return Optional.empty();
        }
        return deviceRepository.findByCookieTokenAndGroupBuyId(cookieToken, groupBuy.getId());
    }

    /**
     * 기기를 기억한다. 쿠키로 찾은 기기가 있으면 그 기기에 고객을 적고, 없으면 새 쿠키 토큰으로 만든다.
     * 기억한 기기를 같이 돌려줘서 부르는 쪽이 같은 기기를 다시 찾지 않는다 (전에는 기억한 뒤 한 번 더 읽었음, 2026-10-21).
     *
     * @param identified 본인 확인(휴대폰 번호 전체)을 거쳤는지
     */
    RememberedDevice rememberDevice(GroupBuy groupBuy, String cookieToken, Long customerId, boolean identified,
                                    LocalDateTime now) {
        Optional<CustomerDevice> device = device(groupBuy, cookieToken);
        if (device.isPresent()) {
            device.get().seen(customerId, identified, now);
            return new RememberedDevice(device.get(), null);
        }
        String token = Tokens.random(24);
        CustomerDevice created = deviceRepository.saveAndFlush(
                new CustomerDevice(groupBuy.getId(), token, customerId, identified, now));
        return new RememberedDevice(created, token);
    }

    /** 이 공구에서 이 휴대폰 번호(정규화한 값)로 넣은 주문. 댓글 고객은 휴대폰 번호가 없으므로 주문서 주문만 나온다 */
    List<CustomerOrder> customerOrdersByPhone(GroupBuy groupBuy, String phone) {
        return customerRepository.findBySellerIdAndPhone(groupBuy.getSeller().getId(), phone)
                .map(c -> orderRepository.findByGroupBuyIdAndCustomer_IdOrderByOrderNoAsc(groupBuy.getId(), c.getId()))
                .orElse(List.of());
    }

    /**
     * 이 기기가 볼 수 있는 주문: 이 기기에서 넣은 주문 + (본인 확인을 거쳤으면) 그 고객의 주문.
     */
    private List<CustomerOrder> visibleOrders(GroupBuy g, CustomerDevice device) {
        if (device == null) {
            return List.of();
        }
        Map<Long, CustomerOrder> orders = new LinkedHashMap<>();
        orderRepository.findByGroupBuyIdAndDeviceTokenOrderByOrderNoAsc(g.getId(), device.getCookieToken())
                .forEach(o -> orders.put(o.getId(), o));
        if (device.isIdentified()) {
            orderRepository.findByGroupBuyIdAndCustomer_IdOrderByOrderNoAsc(g.getId(), device.getCustomerId())
                    .forEach(o -> orders.putIfAbsent(o.getId(), o));
        }
        return orders.values().stream().sorted(Comparator.comparingInt(CustomerOrder::getOrderNo)).toList();
    }

    /** 쿠키로 찾은 기기 기준 화면 응답 (응답은 이 기기가 볼 수 있는 주문만) */
    PublicGroupBuy toPublic(GroupBuy g, String cookieToken, LocalDateTime now) {
        return toPublic(g, device(g, cookieToken).orElse(null), now);
    }

    PublicGroupBuy toPublic(GroupBuy g, CustomerDevice device, LocalDateTime now) {
        boolean accepting = g.isAcceptingOrders(now);
        String closedReason = accepting ? null : Notice.ORDER_CLOSED.text();
        // 이 기기가 볼 수 있는 주문을 한 번만 읽어 "내 주문"과 자동 채움에 같이 쓴다 (전에는 각각 다시 읽었음, 2026-10-21)
        List<CustomerOrder> visible = visibleOrders(g, device);
        List<MyOrder> myOrders = myOrders(device, visible, accepting);
        Prefill prefill = prefill(device, visible);
        return PublicGroupBuy.builder()
                .title(g.getTitle())
                .description(g.getDescription())
                .sellerName(g.getSeller().getDisplayName())
                .acceptingOrders(accepting)
                .closedReason(closedReason)
                .closeAt(g.getCloseAt())
                .products(g.sortedProducts().stream().map(PublicProduct::of).toList())
                .pickupPoints(g.sortedPickupPoints().stream().map(PickupPointResponse::of).toList())
                .pickupEnabled(g.isPickupEnabled())
                .pickupPrepayEnabled(g.isPickupPrepayEnabled())
                .pickupOnsiteEnabled(g.isPickupOnsiteEnabled())
                .deliveryEnabled(g.isDeliveryEnabled())
                .shippingFeeType(g.getShippingFeeType())
                .shippingFee(g.getShippingFee())
                .nicknameRequired(g.isNicknameRequired())
                .unitAddressFieldEnabled(g.isUnitAddressFieldEnabled())
                .kakaoInquiryUrl(g.getKakaoInquiryUrl())
                .account(g.usesPrepay() ? account(g) : null)
                .myOrders(myOrders)
                .prefill(prefill)
                .paused(g.isPaused())
                .pauseMessage(g.isPaused() ? g.effectivePauseMessage() : null)
                .pauseLinkUrl(g.isPaused() ? g.getPauseLinkUrl() : null)
                .build();
    }

    List<MyOrder> myOrders(GroupBuy g, CustomerDevice device, LocalDateTime now) {
        return myOrders(device, visibleOrders(g, device), g.isAcceptingOrders(now));
    }

    private static List<MyOrder> myOrders(CustomerDevice device, List<CustomerOrder> visible, boolean accepting) {
        return visible.stream()
                .sorted(Comparator.comparingInt(CustomerOrder::getOrderNo).reversed())
                .map(o -> OrderMapper.toMyOrder(o, accepting, accepting && o.isReplaceableByCustomer()
                        ? prefillOf(o, o.getDeviceToken() != null && o.getDeviceToken().equals(device.getCookieToken()))
                        : null))
                .toList();
    }

    /**
     * 다음 주문 때 이전 입력값을 자동으로 채운다 (같은 공구 안에서).
     * 휴대폰 전체 번호·동호수·배송지는 이 기기에서 넣은 주문에서만 가져온다.
     * 본인 확인만 거친 기기(다른 기기에서 주문)에는 입금자명·닉네임·수령 방법만 채운다 (limited).
     */
    private static Prefill prefill(CustomerDevice device, List<CustomerOrder> visible) {
        if (device == null) {
            return null;
        }
        // 볼 수 있는 주문에는 이 기기에서 넣은 주문이 모두 들어 있다 (visibleOrders)
        List<CustomerOrder> own = visible.stream().filter(o -> device.getCookieToken().equals(o.getDeviceToken())).toList();
        boolean fromOwn = !own.isEmpty();
        List<CustomerOrder> orders = fromOwn ? own : visible;
        if (orders.isEmpty()) {
            return null;
        }
        return prefillOf(orders.get(orders.size() - 1), fromOwn);
    }

    /** @param full 이 기기에서 넣은 주문이면 true (휴대폰·동호수·배송지까지), 아니면 입금자명·닉네임·수령·결제만 */
    private static Prefill prefillOf(CustomerOrder o, boolean full) {
        Long pointId = o.getPickupPoint() == null ? null : o.getPickupPoint().getId();
        if (!full) {
            return Prefill.builder()
                    .depositorName(o.getDepositorName())
                    .nickname(o.getNickname())
                    .receiveMethod(o.getReceiveMethod())
                    .pickupPointId(pointId)
                    .paymentMethod(o.getPaymentMethod())
                    .limited(true)
                    .build();
        }
        return Prefill.builder()
                .phone(PhoneNumbers.format(o.getPhone()))
                .depositorName(o.getDepositorName())
                .nickname(o.getNickname())
                .unitAddress(o.getUnitAddress())
                .receiveMethod(o.getReceiveMethod())
                .pickupPointId(pointId)
                .paymentMethod(o.getPaymentMethod())
                .recipientName(o.getRecipientName())
                .recipientPhone(PhoneNumbers.format(o.getRecipientPhone()))
                .zipcode(o.getZipcode())
                .address(o.getAddress())
                .addressDetail(o.getAddressDetail())
                .limited(false)
                .build();
    }

    static AccountInfo account(GroupBuy g) {
        return new AccountInfo(g.getBankName(), g.getAccountNumber(), g.getAccountHolder());
    }

    /** 알림 메일 한 줄: 상품·금액만 (이름·연락처·계좌는 메일에 넣지 않는다, 판매자 화면에서 봄) */
    static String noticeLine(CustomerOrder order) {
        return order.getItems().stream().map(i -> i.getProductNameSnapshot() + " " + i.getQuantity() + "개")
                .collect(Collectors.joining(", ")) + " · " + String.format("%,d원", order.getExpectedAmount());
    }
}
