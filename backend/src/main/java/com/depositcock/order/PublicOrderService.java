package com.depositcock.order;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.depositcock.common.ApiException;
import com.depositcock.common.ApiExceptionHandler;
import com.depositcock.common.ErrorCode;
import com.depositcock.common.Notice;
import com.depositcock.common.Texts;
import com.depositcock.common.Tokens;
import com.depositcock.customer.Customer;
import com.depositcock.customer.CustomerRepository;
import com.depositcock.customer.Customers;
import com.depositcock.groupbuy.GroupBuy;
import com.depositcock.groupbuy.GroupBuyService;
import com.depositcock.groupbuy.PickupPoint;
import com.depositcock.groupbuy.Product;
import com.depositcock.groupbuy.ProductRepository;
import com.depositcock.groupbuy.ShippingFeeType;
import com.depositcock.groupbuy.StockAlerts;
import com.depositcock.notify.MailText;
import com.depositcock.notify.SellerNotices;
import com.depositcock.order.OrderDtos.AccountInfo;
import com.depositcock.order.OrderDtos.FinalOrder;
import com.depositcock.order.OrderDtos.IdentifyRequest;
import com.depositcock.order.OrderDtos.MyOrder;
import com.depositcock.order.OrderDtos.PlaceOrderRequest;
import com.depositcock.order.OrderDtos.PlaceOrderResponse;
import com.depositcock.order.OrderDtos.PublicGroupBuy;
import com.depositcock.order.PublicPage.RememberedDevice;
import com.depositcock.seller.NotificationType;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

/**
 * 고객 화면 (주문서 링크, 로그인 없음): 화면 조회·주문(같은 번호면 수정)·본인 확인. 정리본 4장 + 개발 전 인터뷰 결과 1·2·4번.
 * 취소·환불 신청은 CustomerCancelService, 두 곳이 같이 쓰는 화면 부분은 PublicPage (2026-10-21 기능별로 나눔).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PublicOrderService {

    private final PublicPage page;
    private final GroupBuyService groupBuyService;
    private final ProductRepository productRepository;
    private final CustomerOrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final Customers customers;
    private final EntityManager entityManager;
    private final Clock clock;
    private final StockAlerts stockAlerts;
    private final SellerNotices notices;

    /** 주문 1건 금액 상한 (int 넘침 방지). 공구 주문으로 1억원은 넘지 않는다고 본다 [판단] */
    public static final long MAX_ORDER_AMOUNT = 100_000_000L;

    /** 공개 화면 결과 + 쿠키에 새로 넣을 토큰(있으면) */
    public record WithCookie<T>(T body, String cookieToken) {
    }

    public PublicGroupBuy view(String publicToken, String cookieToken) {
        return page.toPublic(page.findVisible(publicToken), cookieToken, LocalDateTime.now(clock));
    }

    @Transactional
    public WithCookie<PlaceOrderResponse> placeOrder(String publicToken, String cookieToken, PlaceOrderRequest r) {
        // 공구 행을 잠가서 같은 공구의 주문·취소를 한 줄로 세운다 (주문번호 중복, 재고 이중 처리 방지).
        // 잠그기 전에 읽은 상태(마감 여부 등)는 낡았을 수 있으므로 잠근 뒤 다시 읽는다.
        GroupBuy groupBuy = groupBuyService.lock(page.findVisible(publicToken));
        LocalDateTime now = LocalDateTime.now(clock);
        if (!groupBuy.isAcceptingOrders(now)) {
            throw new ApiException(ErrorCode.ORDER_CLOSED);
        }
        PublicPage.requireNotPaused(groupBuy);

        String phone = PhoneNumbers.normalize(r.phone());
        if (phone == null) {
            throw new ApiException(ErrorCode.PHONE_INVALID);
        }
        String depositorName = r.depositorName().trim();
        String nickname = Texts.blankToNull(r.nickname());
        String unitAddress = Texts.blankToNull(r.unitAddress());
        if (groupBuy.isNicknameRequired() && nickname == null) {
            throw new ApiException(ErrorCode.NICKNAME_REQUIRED);
        }
        if (groupBuy.isUnitAddressFieldEnabled() && unitAddress == null) {
            throw new ApiException(ErrorCode.UNIT_ADDRESS_REQUIRED);
        }

        ReceiveMethod receiveMethod = OrderRules.receiveMethod(groupBuy, r.receiveMethod());
        PaymentMethod paymentMethod = OrderRules.paymentMethod(groupBuy, receiveMethod, r.paymentMethod());
        PickupPoint pickupPoint = receiveMethod == ReceiveMethod.PICKUP ? OrderRules.pickupPoint(groupBuy, r.pickupPointId()) : null;

        Map<Long, Product> products = new HashMap<>();
        groupBuy.getProducts().forEach(p -> products.put(p.getId(), p));
        Map<Long, Integer> quantities = OrderRules.quantities(groupBuy, r.items(), ErrorCode.QUANTITY_REQUIRED);
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
        String name = Texts.squash(depositorName);
        CustomerOrder target = replaceable.stream().filter(o -> Objects.equals(o.getOrderNo(), r.replaceOrderNo()))
                .findFirst()
                .or(() -> replaceable.stream().filter(o -> name.equals(Texts.squash(o.getDepositorName()))).reduce((a, b) -> b))
                .orElse(replaceable.isEmpty() ? null : replaceable.get(replaceable.size() - 1));
        // 수정은 휴대폰 번호 + 입금자명이 기존 주문과 같아야 한다 (2026-10-19 사용자 결정, 취소·환불 신청과 같은 기준).
        // 그래서 고객은 수정으로 입금자명을 바꿀 수 없다 (판매자 "주문 변경"으로). 기존 주문 내용도 이름이 맞을 때만 보여준다
        if (target != null && !name.equals(Texts.squash(target.getDepositorName()))) {
            throw new ApiException(ErrorCode.REORDER_NAME_MISMATCH);
        }
        checkPerPersonLimit(products, quantities, previous, target);

        // 배송 정보 검사는 경고(409)보다 먼저 한다 (경고를 확인한 뒤에 입력 오류가 나오지 않게)
        Delivery delivery = OrderRules.delivery(receiveMethod, r, ErrorCode.DELIVERY_INFO_REQUIRED);

        // 고른(또는 경고에서 보여준) 주문을 수정할 수 없게 됐으면(입금·취소, 다른 번호 등) 확인을 다시 받는다
        Integer targetNo = target == null ? null : target.getOrderNo();
        boolean mismatch = r.replaceOrderNo() != null && !Objects.equals(r.replaceOrderNo(), targetNo);
        boolean confirmed = r.acknowledgeWarnings() && Objects.equals(r.replaceOrderNo(), targetNo);
        if (!confirmed) {
            List<String> messages = new ArrayList<>();
            if (mismatch) {
                messages.add(ErrorCode.REORDER_CANNOT_REPLACE.message());
            }
            warnings(previous, target).forEach(w -> messages.add(w.message()));
            if (!messages.isEmpty()) {
                ProblemDetail problem = ApiExceptionHandler.problem(ErrorCode.REORDER_WARNING, String.join("\n", messages));
                problem.setProperty("warnings", messages);
                problem.setProperty("replaceOrderNo", targetNo);
                // "기존 주문 확인" 버튼으로 보여줄 바뀔 주문. "내 주문 찾기"(휴대폰 번호 전체)로 보이는 것과 같은 정보만
                // (상품·금액·수령·결제·입금자명. 휴대폰·주소·닉네임·동호수는 없음)
                problem.setProperty("replaceOrder", target == null ? null : OrderMapper.toMyOrder(target, true));
                throw new ErrorResponseException(HttpStatus.CONFLICT, problem, null);
            }
        }

        OrderUpdate.Values values = OrderUpdate.Values.builder()
                .quantities(quantities)
                .receive(receiveMethod)
                .point(pickupPoint)
                .payment(paymentMethod)
                .depositorName(depositorName)
                .nickname(nickname)
                .unitAddress(unitAddress)
                .delivery(delivery)
                .build();
        CustomerOrder order;
        RememberedDevice remembered;
        if (target != null) {
            // 같은 번호의 주문을 바꾼 기기는 그 고객의 주문을 볼 수 있게 한다 ("내 주문 찾기"와 같은 기준: 휴대폰 번호 전체).
            // 주문을 넣은 기기 표시(device_token)는 그대로 둔다 (원래 기기에서도 계속 보이게)
            remembered = page.rememberDevice(groupBuy, cookieToken, target.getCustomerId(), true, now);
            order = target;
            // 번호만 알면 남이 바꿀 수도 있어서, 주문한 기기가 아닌 곳에서 바꾸면 판매자 기록에 표시한다
            boolean otherDevice = !Objects.equals(target.getDeviceToken(), remembered.device().getCookieToken());
            String summary = OrderUpdate.apply(order, values, ChangeActor.CUSTOMER,
                    otherDevice ? Notice.CHANGED_ON_OTHER_DEVICE.text() : null, productRepository, entityManager, stockAlerts, now);
            orderRepository.flush();
            notices.notify(groupBuy, NotificationType.MAIL_CUSTOMER_CHANGE,
                    MailText.CUSTOMER_CHANGED.text(order.getOrderNo()), List.of(summary));
        } else {
            Customer customer = customers.byPhone(sellerId, phone, now);
            // 새 주문만으로는 본인 확인으로 치지 않는다 (남의 번호로 주문해 그 사람의 다른 주문을 보는 것 방지)
            remembered = page.rememberDevice(groupBuy, cookieToken, customer.getId(), false, now);
            order = newOrder(groupBuy, customer, remembered.device().getCookieToken(), values, products, now);
            notices.notify(groupBuy, NotificationType.MAIL_NEW_ORDER, MailText.NEW_ORDER.text(order.getOrderNo()),
                    List.of(PublicPage.noticeLine(order)));
        }

        List<MyOrder> myOrders = page.myOrders(groupBuy, remembered.device(), now);
        AccountInfo account = order.getPaymentMethod() == PaymentMethod.PREPAID ? PublicPage.account(groupBuy) : null;
        FinalOrder finalOrder = FinalOrder.builder()
                .order(OrderMapper.toMyOrder(order, groupBuy.isAcceptingOrders(now)))
                .nickname(order.getNickname())
                .unitAddress(order.getUnitAddress())
                .recipientName(order.getRecipientName())
                .recipientPhone(PhoneNumbers.format(order.getRecipientPhone()))
                .zipcode(order.getZipcode())
                .address(order.getAddress())
                .addressDetail(order.getAddressDetail())
                .build();
        return new WithCookie<>(PlaceOrderResponse.builder()
                .orderNo(order.getOrderNo())
                .expectedAmount(order.getExpectedAmount())
                .paymentMethod(order.getPaymentMethod())
                .depositorName(order.getDepositorName())
                .account(account)
                .myOrders(myOrders)
                .changed(target != null)
                .finalOrder(finalOrder)
                .build(),
                remembered.newCookie());
    }

    private CustomerOrder newOrder(GroupBuy groupBuy, Customer customer, String deviceToken, OrderUpdate.Values v,
                                   Map<Long, Product> products, LocalDateTime now) {
        CustomerOrder order = new CustomerOrder(groupBuy, groupBuy.nextOrderNo(orderRepository.maxOrderNo(groupBuy.getId())),
                OrderSource.FORM, customer, Tokens.random(16), now);
        order.setDeviceToken(deviceToken);
        OrderUpdate.fill(order, v);

        long itemsAmount = 0;
        for (Map.Entry<Long, Integer> e : v.quantities().entrySet()) {
            Product product = products.get(e.getKey());
            // 기본은 재고를 넘어도 막지 않는다 (2026-10-16). 판매자가 "다 팔리면 품절"을 켠 상품만 준비 수량 안에서 원자적으로
            // 더하고, 넘으면 예외 → 트랜잭션 롤백으로 앞서 늘린 수량도 되돌아간다
            if (product.isBlockWhenSoldOut()) {
                if (productRepository.increaseSoldWithinStock(product.getId(), e.getValue()) == 0) {
                    entityManager.refresh(product);
                    Integer left = product.publicRemaining();
                    throw left == null ? new ApiException(ErrorCode.SOLD_OUT, product.getName())
                            : new ApiException(ErrorCode.OVER_REMAINING, product.getName(), left);
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
            throw new ApiException(ErrorCode.ORDER_AMOUNT_TOO_LARGE);
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
        GroupBuy groupBuy = page.findVisible(publicToken);
        LocalDateTime now = LocalDateTime.now(clock);
        String phone = PhoneNumbers.normalize(r.phone());
        if (phone == null) {
            throw new ApiException(ErrorCode.PHONE_FULL_REQUIRED);
        }
        // 휴대폰 번호 = 판매자별 고객 하나. 이 공구에 그 고객의 주문이 있는지만 본다
        // (전에는 공구의 주문을 전부 읽어서 비교했음, 2026-10-20 점검)
        Long customerId = page.customerOrdersByPhone(groupBuy, phone).stream().map(CustomerOrder::getCustomerId).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND_BY_PHONE));
        // 본인 확인을 거친 기기는 이 고객의 주문 상태를 볼 수 있다. 개인정보(휴대폰 전체·동호수·배송지) 자동 채움은
        // 그 기기에서 넣은 주문에서만 가져오므로, 다른 기기 주문의 개인정보는 여기서도 나가지 않는다.
        RememberedDevice remembered = page.rememberDevice(groupBuy, cookieToken, customerId, true, now);
        return new WithCookie<>(page.toPublic(groupBuy, remembered.device(), now), remembered.newCookie());
    }

    /**
     * 1인 최대 수량 (정리본 5장): 같은 사람(휴대폰 번호)의 이 공구 주문(취소 제외) 합계로 본다.
     * 기존 주문을 바꾸는 경우(target)는 그 주문 수량을 빼고 새로 적은 수량으로 센다 (더하기가 아니라 바꾸기, 2026-10-18)
     */
    private static void checkPerPersonLimit(Map<Long, Product> products, Map<Long, Integer> quantities,
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
                String name = product.getName();
                int limit = product.getPerOrderLimit();
                if (already == 0) {
                    throw new ApiException(ErrorCode.PER_ORDER_LIMIT, name, limit);
                }
                throw target != null
                        ? new ApiException(ErrorCode.PER_ORDER_LIMIT_REPLACING, name, limit, already, target.getOrderNo())
                        : new ApiException(ErrorCode.PER_ORDER_LIMIT_WITH_PREVIOUS, name, limit, already);
            }
        }
    }

    /**
     * 경고는 이 번호로 주문했는지만 본다 (2026-10-18 사용자 결정: 같은 입금자명 경고는 뺌, 문구에 주문번호도 넣지 않음).
     *
     * @param previous 이 번호의 이 공구 주문 (취소 제외)
     * @param target   같은 번호로 다시 주문해서 수정될 주문 (없으면 null)
     */
    private static List<ErrorCode> warnings(List<CustomerOrder> previous, CustomerOrder target) {
        // 수정은 주문 확정 전에만 (2026-10-19). 수정될 주문 내용은 화면의 "기존 주문 확인"으로 본다
        if (target != null) {
            return List.of(target.isPrepaid() ? ErrorCode.REORDER_WILL_UPDATE : ErrorCode.REORDER_WILL_UPDATE_ONSITE);
        }
        if (!previous.isEmpty()) {
            return List.of(previous.stream().anyMatch(CustomerOrder::isRefundPending) ? ErrorCode.REORDER_NEW_AFTER_REFUND_REQUEST
                    : previous.stream().anyMatch(CustomerOrder::isConfirmed) ? ErrorCode.REORDER_NEW_AFTER_CONFIRMED
                    : ErrorCode.REORDER_NEW_AFTER_PICKUP);
        }
        return List.of();
    }
}
