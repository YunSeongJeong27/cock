package com.depositcock;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.depositcock.groupbuy.AutoCloseScheduler;
import com.depositcock.groupbuy.StockAlerts.StockAlertEvent;
import com.depositcock.notify.SellerNotices.SellerNoticeEvent;
import com.depositcock.seller.NotificationType;
import com.depositcock.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 판매자 알림 설정 (2026-10-19 사용자 요청): 알림마다 켜고 끈다. 처음에는 꼭 필요한 것(재고·환불 신청 메일, 화면 알림)만 켜져 있다.
 * 메일 이벤트가 나가는지로 확인한다 (실제 발송은 SellerMail, SMTP 설정 전에는 기록만).
 */
class NotificationSettingsTest extends IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AutoCloseScheduler autoClose;

    /** 마감 스케줄러는 다른 스레드에서도 돌기 때문에, 스레드와 상관없이 받는 리스너로 모은다 */
    private final List<Object> published = new CopyOnWriteArrayList<>();
    private final ApplicationListener<ApplicationEvent> listener = e -> {
        if (e instanceof PayloadApplicationEvent<?> p) {
            published.add(p.getPayload());
        }
    };

    private ApplicationEventMulticaster multicaster() {
        return context.getBean(AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME,
                ApplicationEventMulticaster.class);
    }

    @BeforeEach
    void listen() {
        multicaster().addApplicationListener(listener);
    }

    @AfterEach
    void stop() {
        multicaster().removeApplicationListener(listener);
    }

    private List<SellerNoticeEvent> notices(long groupBuyId) {
        return published.stream().filter(SellerNoticeEvent.class::isInstance).map(SellerNoticeEvent.class::cast)
                .filter(n -> n.groupBuyId() == groupBuyId).toList();
    }

    record Opened(Cookie seller, long id, String token, long productId) {
        String pub() {
            return "/api/public/g/" + token;
        }
    }

    private Opened open(Cookie seller, Integer stockAlertThreshold) throws Exception {
        Map<String, Object> product = new HashMap<>(product("상품", 1000, stockAlertThreshold == null ? null : 10));
        product.put("stockAlertThreshold", stockAlertThreshold);
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller),
                groupBuyBody("알림", List.of(product)))).andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        return new Opened(seller, id, created.get("publicToken").asText(), created.get("products").get(0).get("id").asLong());
    }

    /** replaceOrderNo: 같은 번호로 다시 주문해 그 주문을 수정할 때 (경고 확인 후 진행) */
    private void place(Opened g, String phone, String depositor, int qty, Integer replaceOrderNo) throws Exception {
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", g.productId(), "quantity", qty)));
        o.put("phone", phone);
        o.put("depositorName", depositor);
        o.put("acknowledgeWarnings", true);
        o.put("replaceOrderNo", replaceOrderNo);
        mvc.perform(withJson(post(g.pub() + "/orders"), o)).andExpect(status().isOk());
    }

    private void requestRefund(Opened g, String phone, String depositor) throws Exception {
        mvc.perform(withJson(post(g.pub() + "/refund-request"), Map.of("phone", phone, "depositorName", depositor,
                "bankName", "국민", "accountNumber", "110123456789"))).andExpect(status().isOk());
    }

    private void set(Cookie seller, Map<String, Boolean> changes) throws Exception {
        mvc.perform(withJson(put("/api/seller/notifications").cookie(seller), Map.of("notifications", changes)))
                .andExpect(status().isOk());
    }

    @Test
    void defaultsAreEssentialOnlyAndSellerCanToggleEach() throws Exception {
        Cookie seller = signup(uniqueEmail());
        mvc.perform(get("/api/me").cookie(seller))
                .andExpect(jsonPath("$.notifications.MAIL_STOCK").value(true))
                .andExpect(jsonPath("$.notifications.MAIL_REFUND_REQUEST").value(true))
                .andExpect(jsonPath("$.notifications.MAIL_NEW_ORDER").value(false))
                .andExpect(jsonPath("$.notifications.MAIL_CUSTOMER_CHANGE").value(false))
                .andExpect(jsonPath("$.notifications.MAIL_CUSTOMER_CANCEL").value(false))
                .andExpect(jsonPath("$.notifications.MAIL_AUTO_CLOSE").value(false))
                .andExpect(jsonPath("$.notifications.SCREEN_STOCK").value(true))
                .andExpect(jsonPath("$.notifications.SCREEN_REFUND_REQUEST").value(true))
                .andExpect(jsonPath("$.notifications.SCREEN_SAME_NICKNAME").value(true));

        // 보낸 것만 바뀌고 나머지는 그대로
        mvc.perform(withJson(put("/api/seller/notifications").cookie(seller),
                        Map.of("notifications", Map.of("MAIL_NEW_ORDER", true, "SCREEN_STOCK", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications.MAIL_NEW_ORDER").value(true))
                .andExpect(jsonPath("$.notifications.SCREEN_STOCK").value(false))
                .andExpect(jsonPath("$.notifications.MAIL_REFUND_REQUEST").value(true));
        set(seller, Map.of("MAIL_REFUND_REQUEST", false));
        mvc.perform(get("/api/me").cookie(seller))
                .andExpect(jsonPath("$.notifications.MAIL_NEW_ORDER").value(true))
                .andExpect(jsonPath("$.notifications.SCREEN_STOCK").value(false))
                .andExpect(jsonPath("$.notifications.MAIL_REFUND_REQUEST").value(false))
                .andExpect(jsonPath("$.notifications.MAIL_STOCK").value(true));

        // 없는 종류는 400, 다른 판매자 설정은 그대로
        mvc.perform(withJson(put("/api/seller/notifications").cookie(seller), Map.of("notifications", Map.of("SMS", true))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/me").cookie(signup(uniqueEmail())))
                .andExpect(jsonPath("$.notifications.MAIL_NEW_ORDER").value(false))
                .andExpect(jsonPath("$.notifications.SCREEN_STOCK").value(true));
        mvc.perform(withJson(put("/api/seller/notifications"), Map.of("notifications", Map.of("MAIL_NEW_ORDER", true))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void byDefaultOnlyRefundRequestMailIsSent() throws Exception {
        Opened g = open(signup(uniqueEmail()), null);
        place(g, "010-8900-0001", "김알림", 1, null); // 새 주문
        place(g, "010-8900-0001", "김알림", 2, 1);    // 같은 번호 → 수정
        place(g, "010-8900-0002", "이취소", 1, null);
        mvc.perform(withJson(post(g.pub() + "/cancel"), Map.of("phone", "01089000002", "depositorName", "이취소")))
                .andExpect(status().isOk());
        assertThat(notices(g.id())).isEmpty();

        requestRefund(g, "01089000001", "김알림");
        List<SellerNoticeEvent> sent = notices(g.id());
        assertThat(sent).hasSize(1);
        SellerNoticeEvent refund = sent.get(0);
        assertThat(refund.type()).isEqualTo(NotificationType.MAIL_REFUND_REQUEST);
        assertThat(refund.headline()).isEqualTo("1번 주문에 환불 신청이 들어왔습니다.");
        assertThat(refund.lines()).containsExactly("상품 2개 · 2,000원", "주문 확정 전 주문입니다. 입금 내역을 먼저 확인해 주세요.",
                "주문 목록에서 [주문 취소 후 환불 진행] 또는 [환불 신청 취소]를 눌러 주세요. 환불 계좌는 판매자 화면에서 볼 수 있습니다.");
        // 메일에는 이름·연락처·계좌를 넣지 않는다
        assertThat(String.join(" ", refund.lines())).doesNotContain("김알림").doesNotContain("8900").doesNotContain("110123456789");
    }

    @Test
    void sellerTurnedOnMailsAreSentAndTurnedOffAreNot() throws Exception {
        Cookie seller = signup(uniqueEmail());
        set(seller, Map.of("MAIL_NEW_ORDER", true, "MAIL_CUSTOMER_CHANGE", true, "MAIL_CUSTOMER_CANCEL", true,
                "MAIL_REFUND_REQUEST", false));
        Opened g = open(seller, null);
        place(g, "010-8910-0001", "박켜짐", 1, null);
        place(g, "010-8910-0001", "박켜짐", 3, 1);
        mvc.perform(withJson(post(g.pub() + "/cancel"), Map.of("phone", "01089100001", "depositorName", "박켜짐")))
                .andExpect(status().isOk());
        place(g, "010-8910-0002", "최환불", 1, null);
        requestRefund(g, "01089100002", "최환불");

        List<SellerNoticeEvent> sent = notices(g.id());
        assertThat(sent).extracting(SellerNoticeEvent::type).containsExactly(NotificationType.MAIL_NEW_ORDER,
                NotificationType.MAIL_CUSTOMER_CHANGE, NotificationType.MAIL_CUSTOMER_CANCEL, NotificationType.MAIL_NEW_ORDER);
        assertThat(sent.get(0).headline()).isEqualTo("1번 새 주문이 들어왔습니다.");
        assertThat(sent.get(0).lines()).containsExactly("상품 1개 · 1,000원");
        assertThat(sent.get(1).headline()).isEqualTo("1번 주문을 고객이 수정했습니다.");
        // 테스트는 주문한 기기 쿠키 없이 바꿨으므로 판매자 기록과 같은 표시가 붙는다
        assertThat(sent.get(1).lines()).containsExactly("상품 1→3개, 금액 1,000원→3,000원, 주문한 기기와 다른 기기에서 바꿈");
        assertThat(sent.get(2).headline()).isEqualTo("1번 주문을 고객이 취소했습니다.");
        assertThat(sent.get(3).headline()).isEqualTo("2번 새 주문이 들어왔습니다.");
    }

    @Test
    void stockMailCanBeTurnedOff() throws Exception {
        Cookie seller = signup(uniqueEmail());
        set(seller, Map.of("MAIL_STOCK", false));
        Opened g = open(seller, 3);
        place(g, "010-8920-0001", "재고끔", 8, null); // 남은 2 ≤ 기준 3
        assertThat(published.stream().filter(StockAlertEvent.class::isInstance).map(StockAlertEvent.class::cast)
                .filter(e -> e.groupBuyId() == g.id())).isEmpty();
        // 화면에서 보는 남은 수량은 그대로 (화면 알림은 판매자 화면이 이 값으로 보여 줌)
        mvc.perform(get("/api/seller/group-buys/" + g.id()).cookie(seller))
                .andExpect(jsonPath("$.products[0].stockAlertThreshold").value(3));
    }

    @Test
    void autoCloseMailOnlyForSellersWhoTurnedItOn() throws Exception {
        Cookie on = signup(uniqueEmail());
        set(on, Map.of("MAIL_AUTO_CLOSE", true));
        Opened withMail = open(on, null);
        Opened withoutMail = open(signup(uniqueEmail()), null);
        LocalDateTime past = LocalDateTime.now().minusMinutes(5);
        jdbc.update("update group_buy set close_at = ? where id in (?, ?)", past, withMail.id(), withoutMail.id());

        autoClose.closeExpired();

        assertThat(jdbc.queryForList("select status from group_buy where id in (?, ?)", String.class,
                withMail.id(), withoutMail.id())).containsOnly("CLOSED");
        assertThat(notices(withMail.id())).extracting(SellerNoticeEvent::type).containsExactly(NotificationType.MAIL_AUTO_CLOSE);
        assertThat(notices(withMail.id()).get(0).headline()).isEqualTo("공구가 마감 시각이 돼 마감됐습니다.");
        assertThat(notices(withoutMail.id())).isEmpty();
    }
}
