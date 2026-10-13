package com.depositcock.order;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.depositcock.common.ApiException;
import com.depositcock.common.Tokens;
import com.depositcock.groupbuy.GroupBuy;
import com.depositcock.groupbuy.GroupBuyDtos.PickupPointResponse;
import com.depositcock.groupbuy.GroupBuyRepository;
import com.depositcock.groupbuy.GroupBuyStatus;
import com.depositcock.groupbuy.PickupPoint;
import com.depositcock.groupbuy.Product;
import com.depositcock.groupbuy.ProductRepository;
import com.depositcock.groupbuy.ShippingFeeType;
import com.depositcock.order.OrderDtos.AccountInfo;
import com.depositcock.order.OrderDtos.CancelRequest;
import com.depositcock.order.OrderDtos.IdentifyRequest;
import com.depositcock.order.OrderDtos.MyOrder;
import com.depositcock.order.OrderDtos.OrderItemRequest;
import com.depositcock.order.OrderDtos.PlaceOrderRequest;
import com.depositcock.order.OrderDtos.PlaceOrderResponse;
import com.depositcock.order.OrderDtos.Prefill;
import com.depositcock.order.OrderDtos.PublicGroupBuy;
import com.depositcock.order.OrderDtos.PublicProduct;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

/**
 * 고객 화면 (주문서 링크, 로그인 없음). 정리본 4장 + 개발 전 인터뷰 결과 1·2·4번.
 */
@Service
@Transactional(readOnly = true)
public class PublicOrderService {

    private final GroupBuyRepository groupBuyRepository;
    private final ProductRepository productRepository;
    private final CustomerOrderRepository orderRepository;
    private final CustomerDeviceRepository deviceRepository;
    private final EntityManager entityManager;
    private final Clock clock;

    public PublicOrderService(GroupBuyRepository groupBuyRepository, ProductRepository productRepository,
                              CustomerOrderRepository orderRepository, CustomerDeviceRepository deviceRepository,
                              EntityManager entityManager, Clock clock) {
        this.entityManager = entityManager;
        this.groupBuyRepository = groupBuyRepository;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.deviceRepository = deviceRepository;
        this.clock = clock;
    }

    /** 공개 화면 결과 + 쿠키에 새로 넣을 토큰(있으면) */
    /** 주문 1건 금액 상한 (int 넘침 방지). 공구 주문으로 1억원은 넘지 않는다고 본다 [판단] */
    static final long MAX_ORDER_AMOUNT = 100_000_000L;

    public record WithCookie<T>(T body, String cookieToken) {
    }

    public PublicGroupBuy view(String publicToken, String cookieToken) {
        GroupBuy groupBuy = findVisible(publicToken);
        return toPublic(groupBuy, device(groupBuy, cookieToken).orElse(null), LocalDateTime.now(clock));
    }

    @Transactional
    public WithCookie<PlaceOrderResponse> placeOrder(String publicToken, String cookieToken, PlaceOrderRequest r) {
        // 공구 행을 잠가서 같은 공구의 주문·취소를 한 줄로 세운다 (주문번호 중복, 재고 이중 처리 방지).
        // 잠그기 전에 읽은 상태(마감 여부 등)는 낡았을 수 있으므로 잠근 뒤 다시 읽는다.
        GroupBuy groupBuy = lock(findVisible(publicToken));
        LocalDateTime now = LocalDateTime.now(clock);
        if (!groupBuy.isAcceptingOrders(now)) {
            throw ApiException.badRequest("주문이 마감되었습니다.");
        }

        String phone = PhoneNumbers.normalize(r.phone());
        if (phone == null) {
            throw ApiException.badRequest("휴대폰 번호를 확인해 주세요. (예: 010-1234-5678)");
        }
        String depositorName = r.depositorName().trim();
        String nickname = trimToNull(r.nickname());
        String unitAddress = trimToNull(r.unitAddress());
        if (groupBuy.isNicknameFieldEnabled() && nickname == null) {
            throw ApiException.badRequest("닉네임을 입력해 주세요.");
        }
        if (groupBuy.isUnitAddressFieldEnabled() && unitAddress == null) {
            throw ApiException.badRequest("동·호수를 입력해 주세요.");
        }

        ReceiveMethod receiveMethod = resolveReceiveMethod(groupBuy, r.receiveMethod());
        PaymentMethod paymentMethod = resolvePaymentMethod(groupBuy, receiveMethod, r.paymentMethod());
        PickupPoint pickupPoint = receiveMethod == ReceiveMethod.PICKUP ? resolvePickupPoint(groupBuy, r.pickupPointId()) : null;

        Map<Long, Product> products = new HashMap<>();
        groupBuy.getProducts().forEach(p -> products.put(p.getId(), p));
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (OrderItemRequest item : r.items()) {
            if (item.quantity() <= 0) {
                continue;
            }
            if (!products.containsKey(item.productId())) {
                throw ApiException.badRequest("상품을 찾을 수 없습니다.");
            }
            quantities.merge(item.productId(), item.quantity(), Integer::sum);
        }
        if (quantities.isEmpty()) {
            throw ApiException.badRequest("상품 수량을 1개 이상 선택해 주세요.");
        }
        checkPerPersonLimit(groupBuy, phone, products, quantities);

        // 배송 정보 검사는 경고(409)보다 먼저 한다 (경고를 확인한 뒤에 입력 오류가 나오지 않게)
        String recipientPhone = null;
        if (receiveMethod == ReceiveMethod.DELIVERY) {
            recipientPhone = PhoneNumbers.normalize(r.recipientPhone());
            if (trimToNull(r.recipientName()) == null || recipientPhone == null || trimToNull(r.address()) == null) {
                throw ApiException.badRequest("배송을 고르면 받는 분, 받는 분 연락처, 주소를 모두 입력해야 합니다.");
            }
        }

        if (!r.acknowledgeWarnings()) {
            List<String> warnings = warnings(groupBuy, phone, depositorName);
            if (!warnings.isEmpty()) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, String.join("\n", warnings));
                problem.setProperty("warnings", warnings);
                throw new ErrorResponseException(HttpStatus.CONFLICT, problem, null);
            }
        }

        // 주문만으로는 본인 확인으로 치지 않는다 (남의 번호로 주문해 그 사람의 주문·개인정보를 보는 것 방지)
        String newCookie = rememberDevice(groupBuy, cookieToken, phone, false, now);
        String deviceToken = newCookie != null ? newCookie : cookieToken;
        CustomerOrder order = new CustomerOrder(groupBuy, orderRepository.maxOrderNo(groupBuy.getId()) + 1,
                OrderSource.FORM, phone, Tokens.random(16), now);
        order.placedFrom(deviceToken);
        order.fillCustomer(phone, depositorName, nickname, unitAddress);
        order.fillReceive(receiveMethod, pickupPoint, paymentMethod);
        if (receiveMethod == ReceiveMethod.DELIVERY) {
            order.fillDelivery(r.recipientName().trim(), recipientPhone, trimToNull(r.zipcode()), r.address().trim(),
                    trimToNull(r.addressDetail()));
        }

        long itemsAmount = 0;
        for (Map.Entry<Long, Integer> e : quantities.entrySet()) {
            Product product = products.get(e.getKey());
            // 재고 원자 처리. 실패하면 예외 → 트랜잭션 롤백으로 앞서 늘린 수량도 되돌아간다.
            if (productRepository.increaseSold(product.getId(), e.getValue()) == 0) {
                Product fresh = product;
                entityManager.refresh(fresh);
                throw ApiException.conflict("'" + fresh.getName() + "'이(가) 품절되었거나 남은 수량이 부족합니다. (남은 수량 "
                        + fresh.remaining() + "개)");
            }
            entityManager.refresh(product);
            order.addItem(new OrderItem(order, product, e.getValue()));
            itemsAmount += (long) product.getUnitPrice() * e.getValue();
        }
        int shippingFee = receiveMethod == ReceiveMethod.DELIVERY && groupBuy.getShippingFeeType() == ShippingFeeType.FIXED
                ? groupBuy.getShippingFee() : 0;
        if (itemsAmount + shippingFee > MAX_ORDER_AMOUNT) {
            throw ApiException.badRequest("주문 금액이 너무 큽니다. 수량을 확인해 주세요.");
        }
        order.fillAmounts((int) itemsAmount, shippingFee);
        orderRepository.save(order);

        List<MyOrder> myOrders = myOrders(groupBuy, deviceRepository.findByCookieTokenAndGroupBuyId(deviceToken,
                groupBuy.getId()).orElse(null), now);
        AccountInfo account = paymentMethod == PaymentMethod.PREPAID ? account(groupBuy) : null;
        return new WithCookie<>(new PlaceOrderResponse(order.getOrderNo(), order.getExpectedAmount(), paymentMethod,
                depositorName, account, myOrders), newCookie);
    }

    /**
     * 쿠키로 못 알아볼 때 "입금자명 + 휴대폰 뒷4자리(또는 전체 번호)"로 본인 확인 후 그 기기를 기억한다.
     */
    @Transactional
    public WithCookie<PublicGroupBuy> identify(String publicToken, String cookieToken, IdentifyRequest r) {
        GroupBuy groupBuy = findVisible(publicToken);
        LocalDateTime now = LocalDateTime.now(clock);
        String depositorName = squash(r.depositorName());
        String digits = r.phone().replaceAll("\\D", "");
        String full = PhoneNumbers.normalize(r.phone());
        List<String> keys = orderRepository.findByGroupBuyIdOrderByOrderNoAsc(groupBuy.getId()).stream()
                .filter(o -> o.getSource() == OrderSource.FORM)
                .filter(o -> depositorName.equals(squash(o.getDepositorName())))
                .filter(o -> full != null ? full.equals(o.getPhone())
                        : digits.length() == 4 && PhoneNumbers.last4(o.getPhone()).equals(digits))
                .map(CustomerOrder::getCustomerKey)
                .distinct()
                .toList();
        if (keys.isEmpty()) {
            throw ApiException.notFound("일치하는 주문이 없습니다. 입금자명과 휴대폰 번호를 확인해 주세요.");
        }
        if (keys.size() > 1) {
            throw ApiException.conflict("같은 정보의 주문이 여러 명 있습니다. 휴대폰 번호 전체를 입력해 주세요.");
        }
        // 본인 확인을 거친 기기는 이 고객의 주문 상태를 볼 수 있다. 개인정보(휴대폰 전체·동호수·배송지) 자동 채움은
        // 그 기기에서 넣은 주문에서만 가져오므로, 다른 기기 주문의 개인정보는 여기서도 나가지 않는다.
        String newCookie = rememberDevice(groupBuy, cookieToken, keys.get(0), true, now);
        CustomerDevice device = deviceRepository.findByCookieTokenAndGroupBuyId(
                newCookie != null ? newCookie : cookieToken, groupBuy.getId()).orElseThrow();
        return new WithCookie<>(toPublic(groupBuy, device, now), newCookie);
    }

    /** 고객의 "입금했어요". 확정이 아니라 신고이며, 확정은 판매자가 입금내역 대조 후 누른다. */
    @Transactional
    public PublicGroupBuy reportDeposit(String publicToken, String cookieToken, int orderNo) {
        GroupBuy groupBuy = findVisible(publicToken);
        LocalDateTime now = LocalDateTime.now(clock);
        CustomerDevice device = device(groupBuy, cookieToken)
                .orElseThrow(() -> ApiException.forbidden("본인 확인이 필요합니다."));
        CustomerOrder order = visibleOrders(groupBuy, device).stream()
                .filter(o -> o.getOrderNo() == orderNo).findFirst()
                .orElseThrow(() -> ApiException.notFound("주문을 찾을 수 없습니다."));
        if (!order.isActive() || !order.isPrepaid()) {
            throw ApiException.badRequest("입금 신고를 할 수 없는 주문입니다.");
        }
        if (order.isPaid()) {
            throw ApiException.badRequest("이미 입금이 확인된 주문입니다.");
        }
        order.reportDeposit(now);
        return toPublic(groupBuy, device, now);
    }

    /**
     * 주문 취소: 휴대폰 번호 + 입금자명이 맞아야 한다 (개발 전 인터뷰 결과 4번). 마감 후에는 불가.
     * 같은 정보의 취소 가능한 주문이 여러 건이면 409 와 함께 주문 목록(orders)을 돌려주고, 주문번호를 골라 다시 요청한다.
     * 입금 확정된 주문은 바로 취소하고 판매자 화면에 "환불 필요"로 표시한다.
     */
    @Transactional
    public PublicGroupBuy cancel(String publicToken, String cookieToken, CancelRequest r) {
        // 같은 주문을 동시에 두 번 취소해도 재고가 한 번만 돌아오게 공구를 잠근다
        GroupBuy groupBuy = lock(findVisible(publicToken));
        LocalDateTime now = LocalDateTime.now(clock);
        if (!groupBuy.isAcceptingOrders(now)) {
            throw ApiException.badRequest("마감된 공구는 취소할 수 없습니다. 판매자에게 문의해 주세요.");
        }
        String phone = PhoneNumbers.normalize(r.phone());
        String depositorName = squash(r.depositorName());
        List<CustomerOrder> matches = orderRepository.findByGroupBuyIdOrderByOrderNoAsc(groupBuy.getId()).stream()
                .filter(o -> phone != null && phone.equals(o.getPhone()))
                .filter(o -> depositorName.equals(squash(o.getDepositorName())))
                .toList();
        if (matches.isEmpty()) {
            throw ApiException.notFound("휴대폰 번호와 입금자명이 일치하는 주문이 없습니다.");
        }
        List<CustomerOrder> cancellable = matches.stream()
                .filter(o -> o.isActive() && !o.isPickedUp())
                .toList();
        CustomerOrder order;
        if (r.orderNo() != null) {
            order = matches.stream().filter(o -> o.getOrderNo() == r.orderNo()).findFirst()
                    .orElseThrow(() -> ApiException.notFound("주문번호를 확인해 주세요."));
        } else if (cancellable.size() == 1) {
            order = cancellable.get(0);
        } else if (cancellable.isEmpty()) {
            throw ApiException.badRequest("취소할 수 있는 주문이 없습니다. (이미 취소했거나 수령한 주문)");
        } else {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "주문이 여러 건 있습니다. 취소할 주문을 골라주세요.");
            problem.setProperty("orders", cancellable.stream()
                    .map(o -> OrderMapper.toMyOrder(o, true)).toList());
            throw new ErrorResponseException(HttpStatus.CONFLICT, problem, null);
        }
        if (!order.isActive()) {
            throw ApiException.badRequest("이미 취소된 주문입니다.");
        }
        if (order.isPickedUp()) {
            throw ApiException.badRequest("이미 수령한 주문은 취소할 수 없습니다.");
        }
        OrderCancellation.cancel(order, productRepository, entityManager, now);
        // 응답은 이 기기가 볼 수 있는 주문만 (취소 정보를 안다고 그 사람의 다른 주문까지 보여주지 않음)
        return toPublic(groupBuy, device(groupBuy, cookieToken).orElse(null), now);
    }

    // ---------- 내부 ----------

    private GroupBuy findVisible(String publicToken) {
        GroupBuy groupBuy = groupBuyRepository.findByPublicToken(publicToken)
                .orElseThrow(() -> ApiException.notFound("공구를 찾을 수 없습니다."));
        if (groupBuy.getStatus() == GroupBuyStatus.DRAFT) {
            throw ApiException.notFound("아직 열리지 않은 공구입니다.");
        }
        return groupBuy;
    }

    private GroupBuy lock(GroupBuy groupBuy) {
        groupBuyRepository.findByIdForUpdate(groupBuy.getId());
        entityManager.refresh(groupBuy);
        return groupBuy;
    }

    private Optional<CustomerDevice> device(GroupBuy groupBuy, String cookieToken) {
        if (cookieToken == null || cookieToken.isBlank()) {
            return Optional.empty();
        }
        return deviceRepository.findByCookieTokenAndGroupBuyId(cookieToken, groupBuy.getId());
    }

    /**
     * 기기를 기억한다. 새 쿠키 토큰을 만들었으면 그 값을, 기존 쿠키를 그대로 쓰면 null 을 돌려준다.
     *
     * @param identified 본인 확인(입금자명 + 휴대폰)을 거쳤는지
     */
    private String rememberDevice(GroupBuy groupBuy, String cookieToken, String customerKey, boolean identified,
                                  LocalDateTime now) {
        Optional<CustomerDevice> device = device(groupBuy, cookieToken);
        if (device.isPresent()) {
            device.get().seen(customerKey, identified, now);
            return null;
        }
        String token = Tokens.random(24);
        deviceRepository.saveAndFlush(new CustomerDevice(groupBuy.getId(), token, customerKey, identified, now));
        return token;
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
            orderRepository.findByGroupBuyIdAndCustomerKeyOrderByOrderNoAsc(g.getId(), device.getCustomerKey())
                    .forEach(o -> orders.putIfAbsent(o.getId(), o));
        }
        return orders.values().stream().sorted(Comparator.comparingInt(CustomerOrder::getOrderNo)).toList();
    }

    /**
     * 1인 최대 수량 (정리본 5장): 같은 사람(휴대폰 번호)이 이 공구에 이미 주문한 수량(취소 제외)까지 합쳐서 본다.
     */
    private void checkPerPersonLimit(GroupBuy groupBuy, String customerKey, Map<Long, Product> products,
                                     Map<Long, Integer> quantities) {
        List<CustomerOrder> previous = orderRepository
                .findByGroupBuyIdAndCustomerKeyOrderByOrderNoAsc(groupBuy.getId(), customerKey).stream()
                .filter(CustomerOrder::isActive)
                .toList();
        for (Map.Entry<Long, Integer> e : quantities.entrySet()) {
            Product product = products.get(e.getKey());
            if (product.getPerOrderLimit() == null) {
                continue;
            }
            int already = previous.stream().flatMap(o -> o.getItems().stream())
                    .filter(i -> i.getProduct().getId().equals(product.getId()))
                    .mapToInt(OrderItem::getQuantity).sum();
            if (already + e.getValue() > product.getPerOrderLimit()) {
                String detail = already > 0 ? " (이미 주문한 " + already + "개 포함)" : "";
                throw ApiException.badRequest("'" + product.getName() + "'은(는) 1인 최대 " + product.getPerOrderLimit()
                        + "개까지 주문할 수 있습니다." + detail);
            }
        }
    }

    private List<String> warnings(GroupBuy groupBuy, String phone, String depositorName) {
        List<String> warnings = new ArrayList<>();
        if (orderRepository.existsByGroupBuyIdAndCustomerKeyAndStatus(groupBuy.getId(), phone, OrderStatus.ORDERED)) {
            warnings.add("이 휴대폰 번호로 이미 주문한 내역이 있습니다. 추가 주문이 맞으면 그대로 진행해 주세요.");
        }
        // 띄어쓰기·대소문자만 다른 이름도 같은 이름으로 본다 (입금 대조도 그렇게 비교함)
        String name = squash(depositorName).toLowerCase(java.util.Locale.ROOT);
        boolean sameNameByOther = orderRepository.findByGroupBuyIdOrderByOrderNoAsc(groupBuy.getId()).stream()
                .anyMatch(o -> o.getStatus() == OrderStatus.ORDERED && !phone.equals(o.getCustomerKey())
                        && name.equals(squash(o.getDepositorName()).toLowerCase(java.util.Locale.ROOT)));
        if (sameNameByOther) {
            warnings.add("같은 입금자명으로 주문한 다른 분이 있습니다. 입금 확인이 헷갈리지 않게 입금자명 뒤에 동·호수나 숫자를 "
                    + "붙여주세요. (예: 김민지1505)");
        }
        return warnings;
    }

    private ReceiveMethod resolveReceiveMethod(GroupBuy groupBuy, ReceiveMethod requested) {
        if (groupBuy.isPickupEnabled() && groupBuy.isDeliveryEnabled()) {
            if (requested == null) {
                throw ApiException.badRequest("수령 방법(픽업/배송)을 선택해 주세요.");
            }
            return requested;
        }
        ReceiveMethod only = groupBuy.isPickupEnabled() ? ReceiveMethod.PICKUP : ReceiveMethod.DELIVERY;
        if (requested != null && requested != only) {
            throw ApiException.badRequest("선택할 수 없는 수령 방법입니다.");
        }
        return only;
    }

    private PaymentMethod resolvePaymentMethod(GroupBuy groupBuy, ReceiveMethod receiveMethod, PaymentMethod requested) {
        if (receiveMethod == ReceiveMethod.DELIVERY) {
            return PaymentMethod.PREPAID; // 배송은 선입금만
        }
        if (groupBuy.isPickupPrepayEnabled() && groupBuy.isPickupOnsiteEnabled()) {
            if (requested == null) {
                throw ApiException.badRequest("결제 방식(선입금/현장결제)을 선택해 주세요.");
            }
            return requested;
        }
        PaymentMethod only = groupBuy.isPickupPrepayEnabled() ? PaymentMethod.PREPAID : PaymentMethod.ONSITE;
        if (requested != null && requested != only) {
            throw ApiException.badRequest("선택할 수 없는 결제 방식입니다.");
        }
        return only;
    }

    private PickupPoint resolvePickupPoint(GroupBuy groupBuy, Long pickupPointId) {
        List<PickupPoint> points = groupBuy.sortedPickupPoints();
        if (points.isEmpty()) {
            return null;
        }
        if (points.size() == 1) {
            return points.get(0);
        }
        if (pickupPointId == null) {
            throw ApiException.badRequest("픽업 지점을 선택해 주세요.");
        }
        return points.stream().filter(p -> p.getId().equals(pickupPointId)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("픽업 지점을 찾을 수 없습니다."));
    }

    private PublicGroupBuy toPublic(GroupBuy g, CustomerDevice device, LocalDateTime now) {
        boolean accepting = g.isAcceptingOrders(now);
        String closedReason = accepting ? null : "주문이 마감되었습니다.";
        List<MyOrder> myOrders = myOrders(g, device, now);
        Prefill prefill = prefill(g, device);
        return new PublicGroupBuy(g.getTitle(), g.getDescription(), g.getSeller().getDisplayName(), accepting,
                closedReason, g.getCloseAt(),
                g.sortedProducts().stream().map(p -> new PublicProduct(p.getId(), p.getName(), p.getUnitPrice(),
                        p.remaining(), p.getPerOrderLimit(), p.isSoldOut())).toList(),
                g.sortedPickupPoints().stream().map(PickupPointResponse::of).toList(),
                g.isPickupEnabled(), g.isPickupPrepayEnabled(), g.isPickupOnsiteEnabled(), g.isDeliveryEnabled(),
                g.getShippingFeeType(), g.getShippingFee(), g.isNicknameFieldEnabled(), g.isUnitAddressFieldEnabled(),
                g.getKakaoInquiryUrl(), g.usesPrepay() ? account(g) : null, myOrders, prefill);
    }

    private List<MyOrder> myOrders(GroupBuy g, CustomerDevice device, LocalDateTime now) {
        boolean accepting = g.isAcceptingOrders(now);
        return visibleOrders(g, device).stream()
                .sorted(Comparator.comparingInt(CustomerOrder::getOrderNo).reversed())
                .map(o -> OrderMapper.toMyOrder(o, accepting))
                .toList();
    }

    /**
     * 다음 주문 때 이전 입력값을 자동으로 채운다 (같은 공구 안에서).
     * 휴대폰 전체 번호·동호수·배송지는 이 기기에서 넣은 주문에서만 가져온다.
     * 본인 확인만 거친 기기(다른 기기에서 주문)에는 입금자명·닉네임·수령 방법만 채운다 (limited).
     */
    private Prefill prefill(GroupBuy g, CustomerDevice device) {
        if (device == null) {
            return null;
        }
        List<CustomerOrder> own = orderRepository.findByGroupBuyIdAndDeviceTokenOrderByOrderNoAsc(g.getId(),
                device.getCookieToken());
        boolean fromOwn = !own.isEmpty();
        List<CustomerOrder> orders = fromOwn ? own : visibleOrders(g, device);
        if (orders.isEmpty()) {
            return null;
        }
        CustomerOrder last = orders.get(orders.size() - 1);
        if (!fromOwn) {
            return new Prefill(null, last.getDepositorName(), last.getNickname(), null, last.getReceiveMethod(),
                    last.getPickupPoint() == null ? null : last.getPickupPoint().getId(), last.getPaymentMethod(),
                    null, null, null, null, null, true);
        }
        return new Prefill(PhoneNumbers.format(last.getPhone()), last.getDepositorName(), last.getNickname(),
                last.getUnitAddress(), last.getReceiveMethod(),
                last.getPickupPoint() == null ? null : last.getPickupPoint().getId(), last.getPaymentMethod(),
                last.getRecipientName(), PhoneNumbers.format(last.getRecipientPhone()), last.getZipcode(),
                last.getAddress(), last.getAddressDetail(), false);
    }

    private static AccountInfo account(GroupBuy g) {
        return new AccountInfo(g.getBankName(), g.getAccountNumber(), g.getAccountHolder());
    }

    /** 이름 비교용: 공백을 모두 지운다 ("김 지영" = "김지영") */
    static String squash(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
