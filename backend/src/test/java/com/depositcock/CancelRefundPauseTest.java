package com.depositcock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.depositcock.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2026-10-18 사용자 결정
 * - 고객 취소: 입금 전은 바로 취소, 입금 후("입금했어요" 또는 입금 확인)는 환불 신청 → 판매자가 확인 후 취소·환불
 * - 주문 잠시 멈춤: 판매자가 고객의 주문·수정·취소·환불 신청을 잠시 막는다 ("입금했어요"·내 주문 보기는 됨)
 */
class CancelRefundPauseTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    record Opened(Cookie seller, long id, String token, long productId) {
        String pub() {
            return "/api/public/g/" + token;
        }

        String sellerBase() {
            return "/api/seller/group-buys/" + id;
        }
    }

    private Opened open(boolean onsite) throws Exception {
        Cookie seller = signup(uniqueEmail());
        Map<String, Object> body = groupBuyBody("취소·환불", List.of(product("상품", 1000, null)));
        body.put("pickupOnsiteEnabled", onsite);
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body))
                .andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        return new Opened(seller, id, created.get("publicToken").asText(), created.get("products").get(0).get("id").asLong());
    }

    private Map<String, Object> order(Opened g, String phone, String depositor) {
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", g.productId(), "quantity", 1)));
        o.put("phone", phone);
        o.put("depositorName", depositor);
        o.put("acknowledgeWarnings", true);
        return o;
    }

    private Cookie place(Opened g, String phone, String depositor) throws Exception {
        return mvc.perform(withJson(post(g.pub() + "/orders"), order(g, phone, depositor)))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("dcg_" + g.token());
    }

    private Map<String, Object> refund(String phone, String depositor, String account) {
        return Map.of("phone", phone, "depositorName", depositor, "bankName", "국민", "accountNumber", account,
                "accountHolder", depositor);
    }

    private JsonNode sellerOrder(Opened g, int index) throws Exception {
        return read(mvc.perform(get(g.sellerBase() + "/orders").cookie(g.seller())).andReturn()).get(index);
    }

    @Test
    void beforeDepositCancelsImmediatelyAfterDepositNeedsRefundRequest() throws Exception {
        Opened g = open(false);
        Cookie device = place(g, "010-8800-0001", "김바로");
        mvc.perform(get(g.pub()).cookie(device))
                .andExpect(jsonPath("$.myOrders[0].cancellable").value(true))
                .andExpect(jsonPath("$.myOrders[0].refundRequestable").value(true));
        // 입금 전: 바로 취소, 환불 없음
        mvc.perform(withJson(post(g.pub() + "/cancel").cookie(device), Map.of("phone", "01088000001", "depositorName", "김바로")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myOrders[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.myOrders[0].refundRequired").value(false));

        // "입금했어요"를 누르면 입금 후: 바로 취소 대신 환불 신청 안내
        Cookie other = place(g, "010-8800-0002", "이입금");
        mvc.perform(post(g.pub() + "/orders/2/report-deposit").cookie(other)).andExpect(status().isOk());
        mvc.perform(get(g.pub()).cookie(other))
                .andExpect(jsonPath("$.myOrders[0].cancellable").value(false))
                .andExpect(jsonPath("$.myOrders[0].refundRequestable").value(true));
        mvc.perform(withJson(post(g.pub() + "/cancel"), Map.of("phone", "01088000002", "depositorName", "이입금")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.refundRequired").value(true));
    }

    @Test
    void refundRequestIsDecidedBySellerAndAccountIsEncryptedThenDeleted() throws Exception {
        Opened g = open(false);
        Cookie device = place(g, "010-8810-0001", "박환불");
        String phone = "01088100001";
        // 계좌번호 검사
        mvc.perform(withJson(post(g.pub() + "/refund-request"), refund(phone, "박환불", "12")))
                .andExpect(status().isBadRequest());
        // "입금했어요"를 안 눌렀어도 환불 신청 = 입금 신고 겸함
        mvc.perform(withJson(post(g.pub() + "/refund-request").cookie(device), refund(phone, "박환불", "110-123-456789")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myOrders[0].refundState").value("REQUESTED"))
                .andExpect(jsonPath("$.myOrders[0].status").value("DEPOSIT_REPORTED"))
                .andExpect(jsonPath("$.myOrders[0].refundRequestable").value(false));
        mvc.perform(withJson(post(g.pub() + "/refund-request"), refund(phone, "박환불", "110123456789")))
                .andExpect(status().isBadRequest());

        // DB 에는 암호화된 값만, 판매자 화면에는 원래 번호
        String stored = jdbc.queryForObject("select refund_account from customer_order where group_buy_id = ?", String.class, g.id());
        assertThat(stored).startsWith("v1:").doesNotContain("110123456789");
        JsonNode o = sellerOrder(g, 0);
        assertThat(o.get("refundAccount").asText()).isEqualTo("110123456789");
        assertThat(o.get("refundBank").asText()).isEqualTo("국민");
        assertThat(o.get("refundRequestedAt").isNull()).isFalse();
        mvc.perform(get(g.sellerBase() + "/dashboard").cookie(g.seller())).andExpect(jsonPath("$.refundRequestCount").value(1));

        // 판매자 [취소 안 함]: 주문 그대로, 고객 화면 "반려", 계좌 삭제, 다시 신청 불가
        long orderId = o.get("id").asLong();
        mvc.perform(post(g.sellerBase() + "/orders/" + orderId + "/refund-request/reject").cookie(g.seller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.refundAccount").isEmpty());
        assertThat(jdbc.queryForObject("select refund_account from customer_order where id = ?", String.class, orderId)).isNull();
        mvc.perform(get(g.pub()).cookie(device)).andExpect(jsonPath("$.myOrders[0].refundState").value("REJECTED"));
        mvc.perform(withJson(post(g.pub() + "/refund-request"), refund(phone, "박환불", "110123456789")))
                .andExpect(status().isBadRequest());
        mvc.perform(post(g.sellerBase() + "/orders/" + orderId + "/refund-request/approve").cookie(g.seller()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void approvedRefundCancelsWithRefundAndAccountIsDeletedWhenRefunded() throws Exception {
        Opened g = open(false);
        Cookie device = place(g, "010-8820-0001", "최승인");
        long orderId = sellerOrder(g, 0).get("id").asLong();
        mvc.perform(withJson(post(g.sellerBase() + "/orders/" + orderId + "/manual-paid").cookie(g.seller()),
                Map.of("depositorName", "최승인", "amount", 1000))).andExpect(status().isOk());
        mvc.perform(withJson(post(g.pub() + "/refund-request"), refund("01088200001", "최승인", "3333-01-1234567")))
                .andExpect(status().isOk());
        mvc.perform(post(g.sellerBase() + "/orders/" + orderId + "/refund-request/approve").cookie(g.seller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.refundRequired").value(true))
                .andExpect(jsonPath("$.refundAccount").value("3333011234567"));
        mvc.perform(get(g.pub()).cookie(device))
                .andExpect(jsonPath("$.myOrders[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.myOrders[0].refundRequired").value(true));
        mvc.perform(post(g.sellerBase() + "/orders/" + orderId + "/refunded").cookie(g.seller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundAccount").isEmpty())
                .andExpect(jsonPath("$.refundBank").isEmpty());
    }

    @Test
    void afterCloseOnlyRefundRequestIsAllowedAndOnsiteHasNoRefund() throws Exception {
        Opened g = open(false);
        place(g, "010-8830-0001", "정마감");
        mvc.perform(post(g.sellerBase() + "/close").cookie(g.seller())).andExpect(status().isOk());
        mvc.perform(withJson(post(g.pub() + "/cancel"), Map.of("phone", "01088300001", "depositorName", "정마감")))
                .andExpect(status().isBadRequest());
        mvc.perform(withJson(post(g.pub() + "/refund-request"), refund("01088300001", "정마감", "1234567890")))
                .andExpect(status().isOk());

        Opened onsite = open(true);
        Map<String, Object> o = order(onsite, "010-8830-0002", "현장");
        o.put("paymentMethod", "ONSITE");
        mvc.perform(withJson(post(onsite.pub() + "/orders"), o)).andExpect(status().isOk());
        mvc.perform(withJson(post(onsite.pub() + "/refund-request"), refund("01088300002", "현장", "1234567890")))
                .andExpect(status().isBadRequest());
        mvc.perform(withJson(post(onsite.pub() + "/cancel"), Map.of("phone", "01088300002", "depositorName", "현장")))
                .andExpect(status().isOk());
    }

    @Test
    void pauseBlocksCustomerChangesButNotDepositReport() throws Exception {
        Opened g = open(false);
        Cookie device = place(g, "010-8840-0001", "한멈춤");
        // 기본 문구로 멈춤
        mvc.perform(withJson(put(g.sellerBase() + "/pause").cookie(g.seller()), Map.of("paused", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true))
                .andExpect(jsonPath("$.pauseMessage").isEmpty());
        mvc.perform(get(g.pub()))
                .andExpect(jsonPath("$.paused").value(true))
                .andExpect(jsonPath("$.pauseMessage").value("현재 주문 집계 중입니다. 판매자에게 문의해 주시면 감사하겠습니다."));

        // 새 주문·수정·취소·환불 신청 막힘
        mvc.perform(withJson(post(g.pub() + "/orders"), order(g, "010-8840-0002", "새손님")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("현재 주문 집계 중입니다. 판매자에게 문의해 주시면 감사하겠습니다."));
        Map<String, Object> edit = order(g, "010-8840-0001", "한멈춤");
        edit.put("replaceOrderNo", 1);
        mvc.perform(withJson(post(g.pub() + "/orders").cookie(device), edit)).andExpect(status().isConflict());
        mvc.perform(withJson(post(g.pub() + "/cancel"), Map.of("phone", "01088400001", "depositorName", "한멈춤")))
                .andExpect(status().isConflict());
        mvc.perform(withJson(post(g.pub() + "/refund-request"), refund("01088400001", "한멈춤", "1234567890")))
                .andExpect(status().isConflict());
        // "입금했어요"·내 주문 찾기는 됨
        mvc.perform(post(g.pub() + "/orders/1/report-deposit").cookie(device)).andExpect(status().isOk());
        mvc.perform(withJson(post(g.pub() + "/identify"), Map.of("phone", "01088400001"))).andExpect(status().isOk());

        // 판매자 문구·링크
        mvc.perform(withJson(put(g.sellerBase() + "/pause").cookie(g.seller()),
                Map.of("paused", true, "message", "입금 확인 중이에요. 밴드 공지를 봐 주세요.", "linkUrl", "ftp://x")))
                .andExpect(status().isBadRequest());
        mvc.perform(withJson(put(g.sellerBase() + "/pause").cookie(g.seller()),
                Map.of("paused", true, "message", "입금 확인 중이에요. 밴드 공지를 봐 주세요.", "linkUrl", "https://band.us/x")))
                .andExpect(status().isOk());
        mvc.perform(get(g.pub()))
                .andExpect(jsonPath("$.pauseMessage").value("입금 확인 중이에요. 밴드 공지를 봐 주세요."))
                .andExpect(jsonPath("$.pauseLinkUrl").value("https://band.us/x"));

        // 풀면 다시 주문. 문구·링크는 판매자 화면에 남아 다음에 다시 씀
        mvc.perform(withJson(put(g.sellerBase() + "/pause").cookie(g.seller()),
                        Map.of("paused", false, "message", "입금 확인 중이에요. 밴드 공지를 봐 주세요.", "linkUrl", "https://band.us/x")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false))
                .andExpect(jsonPath("$.pauseLinkUrl").value("https://band.us/x"));
        mvc.perform(get(g.pub())).andExpect(jsonPath("$.paused").value(false)).andExpect(jsonPath("$.pauseMessage").isEmpty());
        mvc.perform(withJson(post(g.pub() + "/orders"), order(g, "010-8840-0002", "새손님"))).andExpect(status().isOk());
    }
}
