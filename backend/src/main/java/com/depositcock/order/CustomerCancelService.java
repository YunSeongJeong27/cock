package com.depositcock.order;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Predicate;

import com.depositcock.common.ApiException;
import com.depositcock.common.ApiExceptionHandler;
import com.depositcock.common.DataCipher;
import com.depositcock.common.ErrorCode;
import com.depositcock.common.Texts;
import com.depositcock.groupbuy.GroupBuy;
import com.depositcock.groupbuy.GroupBuyService;
import com.depositcock.groupbuy.ProductRepository;
import com.depositcock.groupbuy.StockAlerts;
import com.depositcock.notify.MailText;
import com.depositcock.notify.SellerNotices;
import com.depositcock.order.OrderDtos.CancelRequest;
import com.depositcock.order.OrderDtos.PublicGroupBuy;
import com.depositcock.order.OrderDtos.RefundRequest;
import com.depositcock.seller.NotificationType;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

/**
 * 고객 화면의 취소·환불 신청 (개발 전 인터뷰 결과 4번). 휴대폰 번호 + 입금자명이 맞아야 한다.
 * 전에는 PublicOrderService 안에 있었음 (2026-10-21 기능별로 나눔).
 */
@Service
@Transactional
@RequiredArgsConstructor
public class CustomerCancelService {

    private final PublicPage page;
    private final GroupBuyService groupBuyService;
    private final ProductRepository productRepository;
    private final EntityManager entityManager;
    private final StockAlerts stockAlerts;
    private final DataCipher cipher;
    private final SellerNotices notices;
    private final Clock clock;

    /**
     * 바로 취소 (주문 확정 전): 휴대폰 번호 + 입금자명이 맞아야 한다 (개발 전 인터뷰 결과 4번). 마감 후·멈춤 중에는 불가.
     * 확정된 주문(판매자가 대조해서 입금 확정)은 바로 취소하지 않고 환불 신청으로 안내한다 (409 + refundRequired).
     * 확정 전이면 입금했어도 바로 취소가 되므로, 화면에서 "입금하셨다면 환불 신청"을 안내한다. 입금하고 바로 취소한 경우는
     * 입금 대조 화면이 "취소된 주문의 입금자 → 환불 필요?"로 알려준다 (2026-10-19 사용자 결정).
     * 같은 정보의 주문이 여러 건이면 409 + 주문 목록(orders).
     */
    public PublicGroupBuy cancel(String publicToken, String cookieToken, CancelRequest r) {
        // 같은 주문을 동시에 두 번 취소해도 재고가 한 번만 돌아오게 공구를 잠근다
        GroupBuy groupBuy = groupBuyService.lock(page.findVisible(publicToken));
        LocalDateTime now = LocalDateTime.now(clock);
        PublicPage.requireNotPaused(groupBuy);
        if (!groupBuy.isAcceptingOrders(now)) {
            throw new ApiException(ErrorCode.CANCEL_AFTER_CLOSE);
        }
        List<CustomerOrder> matches = matchByPhoneAndName(groupBuy, r.phone(), r.depositorName());
        if (r.orderNo() == null && matches.stream().noneMatch(CustomerCancelService::cancellableBeforeConfirm)
                && matches.stream().anyMatch(o -> o.isActive() && !o.isPickedUp() && o.isConfirmed() && !o.isRefundPending())) {
            throw refundInstead();
        }
        CustomerOrder order = pick(matches, r.orderNo(), CustomerCancelService::cancellableBeforeConfirm,
                ErrorCode.CHOOSE_ORDER_TO_CANCEL, ErrorCode.NOTHING_TO_CANCEL);
        if (!order.isActive()) {
            throw new ApiException(ErrorCode.ORDER_ALREADY_CANCELLED);
        }
        if (order.isPickedUp()) {
            throw new ApiException(ErrorCode.PICKED_UP_CANNOT_CANCEL);
        }
        if (order.isRefundPending()) {
            throw new ApiException(ErrorCode.REFUND_PENDING);
        }
        if (order.isConfirmed()) {
            throw refundInstead();
        }
        OrderCancellation.cancel(order, productRepository, entityManager, stockAlerts, now);
        notices.notify(groupBuy, NotificationType.MAIL_CUSTOMER_CANCEL, MailText.CUSTOMER_CANCELLED.text(order.getOrderNo()),
                List.of(PublicPage.noticeLine(order)));
        // 응답은 이 기기가 볼 수 있는 주문만 (취소 정보를 안다고 그 사람의 다른 주문까지 보여주지 않음)
        return page.toPublic(groupBuy, cookieToken, now);
    }

    /**
     * 환불 신청 (2026-10-18 사용자 결정): 입금한 고객이 취소할 때. 주문 확정 전후 언제든 되고, 바로 취소하지 않고 판매자가
     * 확인한 뒤 취소하고 이 계좌로 돌려보낸다. 휴대폰 번호 + 입금자명이 맞아야 한다. 마감 뒤에도 되고(판매자가 확인하므로),
     * 멈춤 중에는 안 된다. 계좌번호는 암호화해서 저장하고 환불 완료·반려 때 지운다.
     */
    public PublicGroupBuy requestRefund(String publicToken, String cookieToken, RefundRequest r) {
        GroupBuy groupBuy = groupBuyService.lock(page.findVisible(publicToken));
        LocalDateTime now = LocalDateTime.now(clock);
        PublicPage.requireNotPaused(groupBuy);
        String account = r.accountNumber().replaceAll("[\\s-]", "");
        if (!account.matches("\\d{8,20}")) {
            throw new ApiException(ErrorCode.ACCOUNT_NUMBER_INVALID);
        }
        List<CustomerOrder> matches = matchByPhoneAndName(groupBuy, r.phone(), r.depositorName());
        CustomerOrder order = pick(matches, r.orderNo(), CustomerOrder::canRequestRefund,
                ErrorCode.CHOOSE_ORDER_TO_REFUND, ErrorCode.NOTHING_TO_REFUND);
        if (!order.isActive()) {
            throw new ApiException(ErrorCode.ORDER_ALREADY_CANCELLED);
        }
        if (order.isPickedUp()) {
            throw new ApiException(ErrorCode.PICKED_UP_CANNOT_REFUND);
        }
        if (!order.isPrepaid()) {
            throw new ApiException(ErrorCode.ONSITE_NO_REFUND);
        }
        if (order.getRefundRequestedAt() != null) {
            throw new ApiException(order.getRefundRejectedAt() == null
                    ? ErrorCode.REFUND_ALREADY_REQUESTED : ErrorCode.REFUND_REJECTED);
        }
        // 예금주는 받지 않는다 (2026-10-20 사용자 결정): 송금에는 은행·계좌번호면 되고, 고객이 적는 이름은 확인할 수 없어서.
        // 휴대폰 번호 + 입금자명만 알면 남이 자기 계좌로 신청할 수 있으므로, 판매자 화면에서 송금할 때 은행 앱에 뜨는
        // 받는 분 이름이 실제 입금된 이름(대조 전에는 주문서 입금자명)과 같은지 확인하게 안내한다
        order.requestRefund(r.bankName().trim(), cipher.encrypt(account), now);
        notices.notify(groupBuy, NotificationType.MAIL_REFUND_REQUEST, MailText.REFUND_REQUESTED.text(order.getOrderNo()),
                List.of(PublicPage.noticeLine(order),
                        (order.isConfirmed() ? MailText.REFUND_ON_CONFIRMED : MailText.REFUND_ON_UNCONFIRMED).text(),
                        MailText.REFUND_HOW_TO.text()));
        return page.toPublic(groupBuy, cookieToken, now);
    }

    /** 바로 취소 대상: 주문 확정 전, 수령 전, 환불 신청 중이 아님 (환불 신청한 주문은 판매자가 처리) */
    private static boolean cancellableBeforeConfirm(CustomerOrder o) {
        return o.isActive() && !o.isPickedUp() && !o.isConfirmed() && !o.isRefundPending();
    }

    /** 확정된 주문은 바로 취소 대신 환불 신청 (화면이 환불 신청 칸으로 바뀜) */
    private static ErrorResponseException refundInstead() {
        ProblemDetail problem = ApiExceptionHandler.problem(ErrorCode.REFUND_INSTEAD);
        problem.setProperty("refundRequired", true);
        return new ErrorResponseException(HttpStatus.CONFLICT, problem, null);
    }

    private List<CustomerOrder> matchByPhoneAndName(GroupBuy groupBuy, String rawPhone, String rawName) {
        String phone = PhoneNumbers.normalize(rawPhone);
        String name = Texts.squash(rawName);
        List<CustomerOrder> matches = phone == null ? List.of() : page.customerOrdersByPhone(groupBuy, phone).stream()
                .filter(o -> name.equals(Texts.squash(o.getDepositorName())))
                .toList();
        if (matches.isEmpty()) {
            throw new ApiException(ErrorCode.ORDER_NOT_FOUND_BY_PHONE_NAME);
        }
        return matches;
    }

    /** 고른 주문번호의 주문, 아니면 조건에 맞는 주문이 하나일 때 그 주문. 여러 건이면 409 + 주문 목록(orders) */
    private static CustomerOrder pick(List<CustomerOrder> matches, Integer orderNo, Predicate<CustomerOrder> eligible,
                                      ErrorCode choose, ErrorCode none) {
        if (orderNo != null) {
            return matches.stream().filter(o -> o.getOrderNo() == orderNo).findFirst()
                    .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NO_INVALID));
        }
        List<CustomerOrder> candidates = matches.stream().filter(eligible).toList();
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.isEmpty()) {
            throw new ApiException(none);
        }
        ProblemDetail problem = ApiExceptionHandler.problem(choose);
        problem.setProperty("orders", candidates.stream().map(o -> OrderMapper.toMyOrder(o, true)).toList());
        throw new ErrorResponseException(HttpStatus.CONFLICT, problem, null);
    }
}
