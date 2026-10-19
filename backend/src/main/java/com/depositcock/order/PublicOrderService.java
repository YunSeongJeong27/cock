package com.depositcock.order;

import com.depositcock.groupbuy.StockAlerts;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.depositcock.common.ApiException;
import com.depositcock.common.DataCipher;
import com.depositcock.common.Tokens;
import com.depositcock.customer.Customer;
import com.depositcock.customer.CustomerRepository;
import com.depositcock.customer.Customers;
import com.depositcock.groupbuy.GroupBuy;
import com.depositcock.groupbuy.GroupBuyDtos.PickupPointResponse;
import com.depositcock.groupbuy.GroupBuyRepository;
import com.depositcock.groupbuy.GroupBuyStatus;
import com.depositcock.groupbuy.PickupPoint;
import com.depositcock.groupbuy.Product;
import com.depositcock.groupbuy.ProductRepository;
import com.depositcock.groupbuy.ShippingFeeType;
import com.depositcock.notify.SellerNotices;
import com.depositcock.order.OrderDtos.AccountInfo;
import com.depositcock.order.OrderDtos.CancelRequest;
import com.depositcock.order.OrderDtos.FinalOrder;
import com.depositcock.order.OrderDtos.IdentifyRequest;
import com.depositcock.order.OrderDtos.MyOrder;
import com.depositcock.order.OrderDtos.OrderItemRequest;
import com.depositcock.order.OrderDtos.PlaceOrderRequest;
import com.depositcock.order.OrderDtos.PlaceOrderResponse;
import com.depositcock.order.OrderDtos.Prefill;
import com.depositcock.order.OrderDtos.PublicGroupBuy;
import com.depositcock.order.OrderDtos.PublicProduct;
import com.depositcock.order.OrderDtos.RefundRequest;
import com.depositcock.seller.NotificationType;
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
    private final CustomerRepository customerRepository;
    private final Customers customers;
    private final EntityManager entityManager;
    private final Clock clock;
    private final StockAlerts stockAlerts;
    private final DataCipher cipher;
    private final SellerNotices notices;

    public PublicOrderService(GroupBuyRepository groupBuyRepository, ProductRepository productRepository,
                              CustomerOrderRepository orderRepository, CustomerDeviceRepository deviceRepository,
                              CustomerRepository customerRepository, Customers customers,
                              EntityManager entityManager, Clock clock, StockAlerts stockAlerts, DataCipher cipher,
                              SellerNotices notices) {
        this.notices = notices;
        this.cipher = cipher;
        this.stockAlerts = stockAlerts;
        this.customerRepository = customerRepository;
        this.customers = customers;
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
        requireNotPaused(groupBuy);

        String phone = PhoneNumbers.normalize(r.phone());
        if (phone == null) {
            throw ApiException.badRequest("휴대폰 번호를 확인해 주세요. (예: 010-1234-5678)");
        }
        String depositorName = r.depositorName().trim();
        String nickname = trimToNull(r.nickname());
        String unitAddress = trimToNull(r.unitAddress());
        if (groupBuy.isNicknameRequired() && nickname == null) {
            throw ApiException.badRequest("닉네임을 입력해 주세요.");
        }
        if (groupBuy.isUnitAddressFieldEnabled() && unitAddress == null) {
            throw ApiException.badRequest("동·호수를 입력해 주세요.");
        }

        ReceiveMethod receiveMethod = OrderRules.receiveMethod(groupBuy, r.receiveMethod());
        PaymentMethod paymentMethod = OrderRules.paymentMethod(groupBuy, receiveMethod, r.paymentMethod());
        PickupPoint pickupPoint = receiveMethod == ReceiveMethod.PICKUP ? OrderRules.pickupPoint(groupBuy, r.pickupPointId()) : null;

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
        Long sellerId = groupBuy.getSeller().getId();
        // 처음 주문하는 번호면 아직 고객이 없다. 고객은 검사를 모두 통과한 뒤에 만든다 (실패한 주문의 번호를 남기지 않게)
        Long existingCustomerId = customerRepository.findBySellerIdAndPhone(sellerId, phone).map(Customer::getId).orElse(null);
        List<CustomerOrder> previous = existingCustomerId == null ? List.of() : orderRepository
                .findByGroupBuyIdAndCustomer_IdOrderByOrderNoAsc(groupBuy.getId(), existingCustomerId).stream()
                .filter(CustomerOrder::isActive)
                .toList();
        // 같은 번호로 다시 주문하면 새 주문을 더하지 않고 주문 확정 전 주문을 이 내용으로 수정한다 (2026-10-18 사용자 결정).
        // "내 주문"의 [주문 수정]으로 고른 주문(replaceOrderNo)이 수정할 수 있는 주문이면 그 주문, 아니면 입금자명이 같은
        // 가장 최근 주문, 아니면 가장 최근 주문(아래에서 입금자명 불일치로 막힘). 여러 건: 판매자가 입금 확정을 취소한 경우 등
        List<CustomerOrder> replaceable = previous.stream().filter(CustomerOrder::isReplaceableByCustomer).toList();
        String name = squash(depositorName);
        CustomerOrder target = replaceable.stream().filter(o -> Objects.equals(o.getOrderNo(), r.replaceOrderNo()))
                .findFirst()
                .or(() -> replaceable.stream().filter(o -> name.equals(squash(o.getDepositorName()))).reduce((a, b) -> b))
                .orElse(replaceable.isEmpty() ? null : replaceable.get(replaceable.size() - 1));
        // 수정은 휴대폰 번호 + 입금자명이 기존 주문과 같아야 한다 (2026-10-19 사용자 결정, 취소·환불 신청과 같은 기준).
        // 그래서 고객은 수정으로 입금자명을 바꿀 수 없다 (판매자 "주문 변경"으로). 기존 주문 내용도 이름이 맞을 때만 보여준다
        if (target != null && !name.equals(squash(target.getDepositorName()))) {
            throw ApiException.badRequest("이 번호로 주문 확정 전인 주문이 있습니다. 수정하려면 그 주문의 입금자명을 똑같이 적어 주세요. "
                    + "(입금자명을 바꾸려면 판매자에게 문의해 주세요)");
        }
        checkPerPersonLimit(products, quantities, previous, target);

        // 배송 정보 검사는 경고(409)보다 먼저 한다 (경고를 확인한 뒤에 입력 오류가 나오지 않게)
        String recipientPhone = null;
        if (receiveMethod == ReceiveMethod.DELIVERY) {
            recipientPhone = PhoneNumbers.normalize(r.recipientPhone());
            if (trimToNull(r.recipientName()) == null || recipientPhone == null || trimToNull(r.address()) == null) {
                throw ApiException.badRequest("배송을 고르면 받는 분, 받는 분 연락처, 주소를 모두 입력해야 합니다.");
            }
        }

        // 고른(또는 경고에서 보여준) 주문을 수정할 수 없게 됐으면(입금·취소, 다른 번호 등) 확인을 다시 받는다
        Integer targetNo = target == null ? null : target.getOrderNo();
        boolean mismatch = r.replaceOrderNo() != null && !Objects.equals(r.replaceOrderNo(), targetNo);
        boolean confirmed = r.acknowledgeWarnings() && Objects.equals(r.replaceOrderNo(), targetNo);
        if (!confirmed) {
            List<String> messages = new ArrayList<>();
            if (mismatch) {
                messages.add("이 주문은 수정할 수 없습니다. 주문이 확정됐거나 휴대폰 번호가 다릅니다.");
            }
            messages.addAll(warnings(previous, target));
            if (!messages.isEmpty()) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, String.join("\n", messages));
                problem.setProperty("warnings", messages);
                problem.setProperty("replaceOrderNo", targetNo);
                // "기존 주문 확인" 버튼으로 보여줄 바뀔 주문. "내 주문 찾기"(휴대폰 번호 전체)로 보이는 것과 같은 정보만
                // (상품·금액·수령·결제·입금자명. 휴대폰·주소·닉네임·동호수는 없음)
                problem.setProperty("replaceOrder", target == null ? null : OrderMapper.toMyOrder(target, true));
                throw new ErrorResponseException(HttpStatus.CONFLICT, problem, null);
            }
        }

        boolean delivery = receiveMethod == ReceiveMethod.DELIVERY;
        OrderUpdate.Values values = new OrderUpdate.Values(quantities, receiveMethod, pickupPoint, paymentMethod,
                depositorName, nickname, unitAddress, delivery ? r.recipientName().trim() : null, recipientPhone,
                delivery ? trimToNull(r.zipcode()) : null, delivery ? r.address().trim() : null,
                delivery ? trimToNull(r.addressDetail()) : null);
        CustomerOrder order;
        String newCookie;
        if (target != null) {
            // 같은 번호의 주문을 바꾼 기기는 그 고객의 주문을 볼 수 있게 한다 ("내 주문 찾기"와 같은 기준: 휴대폰 번호 전체).
            // 주문을 넣은 기기 표시(device_token)는 그대로 둔다 (원래 기기에서도 계속 보이게)
            newCookie = rememberDevice(groupBuy, cookieToken, target.getCustomerId(), true, now);
            order = target;
            // 번호만 알면 남이 바꿀 수도 있어서, 주문한 기기가 아닌 곳에서 바꾸면 판매자 기록에 표시한다
            boolean otherDevice = !Objects.equals(target.getDeviceToken(), newCookie != null ? newCookie : cookieToken);
            String summary = OrderUpdate.apply(order, values, ChangeActor.CUSTOMER,
                    otherDevice ? "(주문한 기기와 다른 기기에서 바꿈)" : null, productRepository, entityManager, stockAlerts, now);
            orderRepository.flush();
            notices.notify(groupBuy, NotificationType.MAIL_CUSTOMER_CHANGE,
                    order.getOrderNo() + "번 주문을 고객이 수정했습니다.", List.of(summary));
        } else {
            Customer customer = customers.byPhone(sellerId, phone, now);
            // 새 주문만으로는 본인 확인으로 치지 않는다 (남의 번호로 주문해 그 사람의 다른 주문을 보는 것 방지)
            newCookie = rememberDevice(groupBuy, cookieToken, customer.getId(), false, now);
            order = newOrder(groupBuy, customer, newCookie != null ? newCookie : cookieToken, values, products, now);
            notices.notify(groupBuy, NotificationType.MAIL_NEW_ORDER, order.getOrderNo() + "번 새 주문이 들어왔습니다.",
                    List.of(noticeLine(order)));
        }

        String deviceToken = newCookie != null ? newCookie : cookieToken;
        List<MyOrder> myOrders = myOrders(groupBuy, deviceRepository.findByCookieTokenAndGroupBuyId(deviceToken,
                groupBuy.getId()).orElse(null), now);
        AccountInfo account = order.getPaymentMethod() == PaymentMethod.PREPAID ? account(groupBuy) : null;
        FinalOrder finalOrder = new FinalOrder(OrderMapper.toMyOrder(order, groupBuy.isAcceptingOrders(now)),
                order.getNickname(), order.getUnitAddress(), order.getRecipientName(),
                PhoneNumbers.format(order.getRecipientPhone()), order.getZipcode(), order.getAddress(),
                order.getAddressDetail());
        return new WithCookie<>(new PlaceOrderResponse(order.getOrderNo(), order.getExpectedAmount(),
                order.getPaymentMethod(), order.getDepositorName(), account, myOrders, target != null, finalOrder),
                newCookie);
    }

    /** 알림 메일 한 줄: 상품·금액만 (이름·연락처·계좌는 메일에 넣지 않는다, 판매자 화면에서 봄) */
    private static String noticeLine(CustomerOrder order) {
        return order.getItems().stream().map(i -> i.getProductNameSnapshot() + " " + i.getQuantity() + "개")
                .collect(Collectors.joining(", ")) + " · " + String.format("%,d원", order.getExpectedAmount());
    }

    private CustomerOrder newOrder(GroupBuy groupBuy, Customer customer, String deviceToken, OrderUpdate.Values v,
                                   Map<Long, Product> products, LocalDateTime now) {
        CustomerOrder order = new CustomerOrder(groupBuy, groupBuy.nextOrderNo(orderRepository.maxOrderNo(groupBuy.getId())),
                OrderSource.FORM, customer, Tokens.random(16), now);
        order.placedFrom(deviceToken);
        order.fillCustomer(v.depositorName(), v.nickname(), v.unitAddress());
        order.fillReceive(v.receive(), v.point(), v.payment());
        if (v.receive() == ReceiveMethod.DELIVERY) {
            order.fillDelivery(v.recipientName(), v.recipientPhone(), v.zipcode(), v.address(), v.addressDetail());
        }

        long itemsAmount = 0;
        for (Map.Entry<Long, Integer> e : v.quantities().entrySet()) {
            Product product = products.get(e.getKey());
            // 기본은 재고를 넘어도 막지 않는다 (2026-10-16). 판매자가 "다 팔리면 품절"을 켠 상품만 준비 수량 안에서 원자적으로
            // 더하고, 넘으면 예외 → 트랜잭션 롤백으로 앞서 늘린 수량도 되돌아간다
            if (product.isBlockWhenSoldOut()) {
                if (productRepository.increaseSoldWithinStock(product.getId(), e.getValue()) == 0) {
                    entityManager.refresh(product);
                    Integer left = product.publicRemaining();
                    throw ApiException.conflict("'" + product.getName() + "'이(가) "
                            + (left == null ? "품절되었습니다." : "남은 수량보다 많습니다. (남은 수량 " + left + "개)"));
                }
            } else {
                productRepository.increaseSold(product.getId(), e.getValue());
            }
            entityManager.refresh(product);
            stockAlerts.check(product);
            order.addItem(new OrderItem(order, product, e.getValue()));
            itemsAmount += (long) product.getUnitPrice() * e.getValue();
        }
        int shippingFee = shippingFee(groupBuy, v.receive());
        if (itemsAmount + shippingFee > MAX_ORDER_AMOUNT) {
            throw ApiException.badRequest("주문 금액이 너무 큽니다. 수량을 확인해 주세요.");
        }
        order.fillAmounts((int) itemsAmount, shippingFee);
        return orderRepository.save(order);
    }

    private static int shippingFee(GroupBuy groupBuy, ReceiveMethod receive) {
        return receive == ReceiveMethod.DELIVERY && groupBuy.getShippingFeeType() == ShippingFeeType.FIXED
                ? groupBuy.getShippingFee() : 0;
    }

    /**
     * 쿠키로 못 알아볼 때 "휴대폰 번호 전체"로 본인 확인 후 그 기기를 기억한다 (2026-10-15 사용자 결정).
     * 전에는 "입금자명 + 휴대폰 뒷4자리"였다. 뒷4자리는 겹칠 수 있고 밴드 닉네임에 뒷자리를 붙이는 경우("은정4885")가 있어
     * 전체 번호로 바꿨고, 입금자명은 빼고 휴대폰 번호만으로 찾는다. 휴대폰 번호 = 고객 하나 (판매자별 고객 유일 제약).
     * 남는 위험: 번호를 아는 사람은 그 고객의 이 공구 주문 상태를 볼 수 있다 (휴대폰·주소 자동 채움은 주문한 기기에서만).
     */
    @Transactional
    public WithCookie<PublicGroupBuy> identify(String publicToken, String cookieToken, IdentifyRequest r) {
        GroupBuy groupBuy = findVisible(publicToken);
        LocalDateTime now = LocalDateTime.now(clock);
        String phone = PhoneNumbers.normalize(r.phone());
        if (phone == null) {
            throw ApiException.badRequest("휴대폰 번호 전체를 입력해 주세요. (예: 010-1234-5678)");
        }
        List<Long> customerIds = orderRepository.findByGroupBuyIdOrderByOrderNoAsc(groupBuy.getId()).stream()
                .filter(o -> o.getSource() == OrderSource.FORM)
                .filter(o -> phone.equals(o.getPhone()))
                .map(CustomerOrder::getCustomerId)
                .distinct()
                .toList();
        if (customerIds.isEmpty()) {
            throw ApiException.notFound("일치하는 주문이 없습니다. 휴대폰 번호를 확인해 주세요.");
        }
        // 본인 확인을 거친 기기는 이 고객의 주문 상태를 볼 수 있다. 개인정보(휴대폰 전체·동호수·배송지) 자동 채움은
        // 그 기기에서 넣은 주문에서만 가져오므로, 다른 기기 주문의 개인정보는 여기서도 나가지 않는다.
        String newCookie = rememberDevice(groupBuy, cookieToken, customerIds.get(0), true, now);
        CustomerDevice device = deviceRepository.findByCookieTokenAndGroupBuyId(
                newCookie != null ? newCookie : cookieToken, groupBuy.getId()).orElseThrow();
        return new WithCookie<>(toPublic(groupBuy, device, now), newCookie);
    }

    /**
     * 바로 취소 (주문 확정 전): 휴대폰 번호 + 입금자명이 맞아야 한다 (개발 전 인터뷰 결과 4번). 마감 후·멈춤 중에는 불가.
     * 확정된 주문(판매자가 대조해서 입금 확정)은 바로 취소하지 않고 환불 신청으로 안내한다 (409 + refundRequired).
     * 확정 전이면 입금했어도 바로 취소가 되므로, 화면에서 "입금하셨다면 환불 신청"을 안내한다. 입금하고 바로 취소한 경우는
     * 입금 대조 화면이 "취소된 주문의 입금자 → 환불 필요?"로 알려준다 (2026-10-19 사용자 결정).
     * 같은 정보의 주문이 여러 건이면 409 + 주문 목록(orders).
     */
    @Transactional
    public PublicGroupBuy cancel(String publicToken, String cookieToken, CancelRequest r) {
        // 같은 주문을 동시에 두 번 취소해도 재고가 한 번만 돌아오게 공구를 잠근다
        GroupBuy groupBuy = lock(findVisible(publicToken));
        LocalDateTime now = LocalDateTime.now(clock);
        requireNotPaused(groupBuy);
        if (!groupBuy.isAcceptingOrders(now)) {
            throw ApiException.badRequest("마감된 공구는 여기서 취소할 수 없습니다. 판매자에게 문의해 주세요. "
                    + "(입금한 주문은 환불 신청을 할 수 있습니다)");
        }
        List<CustomerOrder> matches = matchByPhoneAndName(groupBuy, r.phone(), r.depositorName());
        if (r.orderNo() == null && matches.stream().noneMatch(PublicOrderService::cancellableBeforeConfirm)
                && matches.stream().anyMatch(o -> o.isActive() && !o.isPickedUp() && o.isConfirmed() && !o.isRefundPending())) {
            throw refundInstead();
        }
        CustomerOrder order = pick(matches, r.orderNo(), PublicOrderService::cancellableBeforeConfirm,
                "주문이 여러 건 있습니다. 취소할 주문을 골라주세요.",
                "취소할 수 있는 주문이 없습니다. (이미 취소·수령했거나 환불 신청한 주문)");
        if (!order.isActive()) {
            throw ApiException.badRequest("이미 취소된 주문입니다.");
        }
        if (order.isPickedUp()) {
            throw ApiException.badRequest("이미 수령한 주문은 취소할 수 없습니다.");
        }
        if (order.isRefundPending()) {
            throw ApiException.badRequest("환불 신청한 주문입니다. 판매자가 확인 중입니다.");
        }
        if (order.isConfirmed()) {
            throw refundInstead();
        }
        OrderCancellation.cancel(order, productRepository, entityManager, stockAlerts, now);
        notices.notify(groupBuy, NotificationType.MAIL_CUSTOMER_CANCEL, order.getOrderNo() + "번 주문을 고객이 취소했습니다.",
                List.of(noticeLine(order)));
        // 응답은 이 기기가 볼 수 있는 주문만 (취소 정보를 안다고 그 사람의 다른 주문까지 보여주지 않음)
        return toPublic(groupBuy, device(groupBuy, cookieToken).orElse(null), now);
    }

    /**
     * 환불 신청 (2026-10-18 사용자 결정): 입금한 고객이 취소할 때. 주문 확정 전후 언제든 되고, 바로 취소하지 않고 판매자가
     * 확인한 뒤 취소하고 이 계좌로 돌려보낸다. 휴대폰 번호 + 입금자명이 맞아야 한다. 마감 뒤에도 되고(판매자가 확인하므로),
     * 멈춤 중에는 안 된다. 계좌번호는 암호화해서 저장하고 환불 완료·반려 때 지운다.
     */
    @Transactional
    public PublicGroupBuy requestRefund(String publicToken, String cookieToken, RefundRequest r) {
        GroupBuy groupBuy = lock(findVisible(publicToken));
        LocalDateTime now = LocalDateTime.now(clock);
        requireNotPaused(groupBuy);
        String account = r.accountNumber().replaceAll("[\\s-]", "");
        if (!account.matches("\\d{8,20}")) {
            throw ApiException.badRequest("계좌번호를 숫자로 입력해 주세요. (8~20자리)");
        }
        List<CustomerOrder> matches = matchByPhoneAndName(groupBuy, r.phone(), r.depositorName());
        CustomerOrder order = pick(matches, r.orderNo(), CustomerOrder::canRequestRefund,
                "주문이 여러 건 있습니다. 환불 신청할 주문을 골라주세요.",
                "환불 신청할 수 있는 주문이 없습니다. (이미 신청했거나 취소·수령한 주문)");
        if (!order.isActive()) {
            throw ApiException.badRequest("이미 취소된 주문입니다.");
        }
        if (order.isPickedUp()) {
            throw ApiException.badRequest("이미 수령한 주문은 환불 신청을 할 수 없습니다. 판매자에게 문의해 주세요.");
        }
        if (!order.isPrepaid()) {
            throw ApiException.badRequest("현장결제 주문은 입금이 없어 환불 신청이 필요 없습니다. 주문 취소를 눌러 주세요.");
        }
        if (order.getRefundRequestedAt() != null) {
            throw ApiException.badRequest(order.getRefundRejectedAt() == null
                    ? "이미 환불 신청한 주문입니다. 판매자가 확인 중입니다."
                    : "환불 신청이 반려된 주문입니다. 판매자에게 문의해 주세요.");
        }
        order.requestRefund(r.bankName().trim(), cipher.encrypt(account), r.accountHolder().trim(), now);
        notices.notify(groupBuy, NotificationType.MAIL_REFUND_REQUEST, order.getOrderNo() + "번 주문에 환불 신청이 들어왔습니다.",
                List.of(noticeLine(order),
                        order.isConfirmed() ? "주문 확정된 주문입니다." : "주문 확정 전 주문입니다. 입금 내역을 먼저 확인해 주세요.",
                        "주문 목록에서 [취소하고 환불] 또는 [취소 안 함]을 눌러 주세요. 환불 계좌는 판매자 화면에서 볼 수 있습니다."));
        return toPublic(groupBuy, device(groupBuy, cookieToken).orElse(null), now);
    }

    /** 바로 취소 대상: 주문 확정 전, 수령 전, 환불 신청 중이 아님 (환불 신청한 주문은 판매자가 처리) */
    private static boolean cancellableBeforeConfirm(CustomerOrder o) {
        return o.isActive() && !o.isPickedUp() && !o.isConfirmed() && !o.isRefundPending();
    }

    /** 확정된 주문은 바로 취소 대신 환불 신청 (화면이 환불 신청 칸으로 바뀜) */
    private static ErrorResponseException refundInstead() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "확정된 주문입니다. 바로 취소 대신 환불 신청을 해 주세요.");
        problem.setProperty("refundRequired", true);
        return new ErrorResponseException(HttpStatus.CONFLICT, problem, null);
    }

    private List<CustomerOrder> matchByPhoneAndName(GroupBuy groupBuy, String rawPhone, String rawName) {
        String phone = PhoneNumbers.normalize(rawPhone);
        String name = squash(rawName);
        List<CustomerOrder> matches = orderRepository.findByGroupBuyIdOrderByOrderNoAsc(groupBuy.getId()).stream()
                .filter(o -> phone != null && phone.equals(o.getPhone()))
                .filter(o -> name.equals(squash(o.getDepositorName())))
                .toList();
        if (matches.isEmpty()) {
            throw ApiException.notFound("휴대폰 번호와 입금자명이 일치하는 주문이 없습니다.");
        }
        return matches;
    }

    /** 고른 주문번호의 주문, 아니면 조건에 맞는 주문이 하나일 때 그 주문. 여러 건이면 409 + 주문 목록(orders) */
    private static CustomerOrder pick(List<CustomerOrder> matches, Integer orderNo,
                                      java.util.function.Predicate<CustomerOrder> eligible, String chooseMessage,
                                      String noneMessage) {
        if (orderNo != null) {
            return matches.stream().filter(o -> o.getOrderNo() == orderNo).findFirst()
                    .orElseThrow(() -> ApiException.notFound("주문번호를 확인해 주세요."));
        }
        List<CustomerOrder> candidates = matches.stream().filter(eligible).toList();
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.isEmpty()) {
            throw ApiException.badRequest(noneMessage);
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, chooseMessage);
        problem.setProperty("orders", candidates.stream().map(o -> OrderMapper.toMyOrder(o, true)).toList());
        throw new ErrorResponseException(HttpStatus.CONFLICT, problem, null);
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

    /** 판매자가 주문을 잠시 멈췄으면 고객 요청(주문·수정·취소·환불 신청)을 막는다. 잠근 뒤 확인해서 멈춘 직후 요청도 막힌다 */
    private static void requireNotPaused(GroupBuy groupBuy) {
        if (groupBuy.isPaused()) {
            throw ApiException.conflict(groupBuy.effectivePauseMessage());
        }
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
    private String rememberDevice(GroupBuy groupBuy, String cookieToken, Long customerId, boolean identified,
                                  LocalDateTime now) {
        Optional<CustomerDevice> device = device(groupBuy, cookieToken);
        if (device.isPresent()) {
            device.get().seen(customerId, identified, now);
            return null;
        }
        String token = Tokens.random(24);
        deviceRepository.saveAndFlush(new CustomerDevice(groupBuy.getId(), token, customerId, identified, now));
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
            orderRepository.findByGroupBuyIdAndCustomer_IdOrderByOrderNoAsc(g.getId(), device.getCustomerId())
                    .forEach(o -> orders.putIfAbsent(o.getId(), o));
        }
        return orders.values().stream().sorted(Comparator.comparingInt(CustomerOrder::getOrderNo)).toList();
    }

    /**
     * 1인 최대 수량 (정리본 5장): 같은 사람(휴대폰 번호)의 이 공구 주문(취소 제외) 합계로 본다.
     * 기존 주문을 바꾸는 경우(target)는 그 주문 수량을 빼고 새로 적은 수량으로 센다 (더하기가 아니라 바꾸기, 2026-10-18)
     */
    private void checkPerPersonLimit(Map<Long, Product> products, Map<Long, Integer> quantities,
                                     List<CustomerOrder> previous, CustomerOrder target) {
        for (Map.Entry<Long, Integer> e : quantities.entrySet()) {
            Product product = products.get(e.getKey());
            if (product.getPerOrderLimit() == null) {
                continue;
            }
            int already = previous.stream().filter(o -> o != target).flatMap(o -> o.getItems().stream())
                    .filter(i -> i.getProduct().getId().equals(product.getId()))
                    .mapToInt(OrderItem::getQuantity).sum();
            if (already + e.getValue() > product.getPerOrderLimit()) {
                String detail = already > 0 ? " (이미 주문한 " + already + "개 포함"
                        + (target != null ? ", 바뀔 " + target.getOrderNo() + "번 주문 제외)" : ")") : "";
                throw ApiException.badRequest("'" + product.getName() + "'은(는) 1인 최대 " + product.getPerOrderLimit()
                        + "개까지 주문할 수 있습니다." + detail);
            }
        }
    }

    /**
     * 경고는 이 번호로 주문했는지만 본다 (2026-10-18 사용자 결정: 같은 입금자명 경고는 뺌, 문구에 주문번호도 넣지 않음).
     *
     * @param previous 이 번호의 이 공구 주문 (취소 제외)
     * @param target   같은 번호로 다시 주문해서 수정될 주문 (없으면 null)
     */
    private static List<String> warnings(List<CustomerOrder> previous, CustomerOrder target) {
        // 수정은 주문 확정 전에만 (2026-10-19). 수정될 주문 내용은 화면의 "기존 주문 확인"으로 본다
        if (target != null) {
            return List.of(target.isPrepaid()
                    ? "이 번호로 주문 확정 전인 주문이 있습니다. 계속하면 지금 내용으로 수정됩니다."
                    : "이 번호로 주문한 내역이 있습니다. 계속하면 지금 내용으로 수정됩니다.");
        }
        if (!previous.isEmpty()) {
            String which = previous.stream().anyMatch(CustomerOrder::isRefundPending) ? "환불 신청한"
                    : previous.stream().anyMatch(CustomerOrder::isConfirmed) ? "확정된" : "수령한";
            return List.of("이 번호로 " + which + " 주문이 있어, 이번 주문은 새 주문으로 추가됩니다.");
        }
        return List.of();
    }

    private PublicGroupBuy toPublic(GroupBuy g, CustomerDevice device, LocalDateTime now) {
        boolean accepting = g.isAcceptingOrders(now);
        String closedReason = accepting ? null : "주문이 마감되었습니다.";
        List<MyOrder> myOrders = myOrders(g, device, now);
        Prefill prefill = prefill(g, device);
        return new PublicGroupBuy(g.getTitle(), g.getDescription(), g.getSeller().getDisplayName(), accepting,
                closedReason, g.getCloseAt(),
                g.sortedProducts().stream().map(p -> new PublicProduct(p.getId(), p.getName(), p.getUnitPrice(),
                        p.publicRemaining(), p.getPerOrderLimit(), p.isBlockWhenSoldOut(), p.isSoldOut())).toList(),
                g.sortedPickupPoints().stream().map(PickupPointResponse::of).toList(),
                g.isPickupEnabled(), g.isPickupPrepayEnabled(), g.isPickupOnsiteEnabled(), g.isDeliveryEnabled(),
                g.getShippingFeeType(), g.getShippingFee(), g.isNicknameRequired(), g.isUnitAddressFieldEnabled(),
                g.getKakaoInquiryUrl(), g.usesPrepay() ? account(g) : null, myOrders, prefill, g.isPaused(),
                g.isPaused() ? g.effectivePauseMessage() : null, g.isPaused() ? g.getPauseLinkUrl() : null);
    }

    private List<MyOrder> myOrders(GroupBuy g, CustomerDevice device, LocalDateTime now) {
        boolean accepting = g.isAcceptingOrders(now);
        return visibleOrders(g, device).stream()
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
        return prefillOf(orders.get(orders.size() - 1), fromOwn);
    }

    /** @param full 이 기기에서 넣은 주문이면 true (휴대폰·동호수·배송지까지), 아니면 입금자명·닉네임·수령·결제만 */
    private static Prefill prefillOf(CustomerOrder o, boolean full) {
        Long pointId = o.getPickupPoint() == null ? null : o.getPickupPoint().getId();
        if (!full) {
            return new Prefill(null, o.getDepositorName(), o.getNickname(), null, o.getReceiveMethod(), pointId,
                    o.getPaymentMethod(), null, null, null, null, null, true);
        }
        return new Prefill(PhoneNumbers.format(o.getPhone()), o.getDepositorName(), o.getNickname(), o.getUnitAddress(),
                o.getReceiveMethod(), pointId, o.getPaymentMethod(), o.getRecipientName(),
                PhoneNumbers.format(o.getRecipientPhone()), o.getZipcode(), o.getAddress(), o.getAddressDetail(), false);
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
