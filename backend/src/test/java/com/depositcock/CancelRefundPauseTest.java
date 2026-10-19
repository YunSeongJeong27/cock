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
 * - 고객 취소: 주문 확정 전은 바로 취소(입금했으면 고객이 환불 신청을 고름), 확정 후는 환불 신청만 → 판매자가 확인 후 취소·환불
 *   (2026-10-19: 기준은 주문 확정 = 판매자가 대조해서 입금 확정. "입금했어요"는 판단에 안 씀)
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
    void unconfirmedCancelsImmediatelyConfirmedNeedsRefundRequest() throws Exception {
        Opened g = open(false);
        Cookie device = place(g, "010-8800-0001", "김바로");
        // 주문 확정 전이면 바로 취소 가능, 환불 없음
        mvc.perform(get(g.pub()).cookie(device))
                .andExpect(jsonPath("$.myOrders[0].cancellable").value(true))
                .andExpect(jsonPath("$.myOrders[0].refundRequestable").value(true));
        mvc.perform(withJson(post(g.pub() + "/cancel").cookie(device), Map.of("phone", "01088000001", "depositorName", "김바로")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myOrders[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.myOrders[0].refundRequired").value(false));

        // 판매자가 입금 확정(주문 확정)하면 바로 취소 대신 환불 신청 안내
        Cookie other = place(g, "010-8800-0002", "이확정");
        long orderId = sellerOrder(g, 1).get("id").asLong();
        mvc.perform(withJson(post(g.sellerBase() + "/orders/" + orderId + "/manual-paid").cookie(g.seller()),
                Map.of("depositorName", "이확정", "amount", 1000))).andExpect(status().isOk());
        mvc.perform(get(g.pub()).cookie(other))
                .andExpect(jsonPath("$.myOrders[0].statusLabel").value("주문 확정"))
                .andExpect(jsonPath("$.myOrders[0].cancellable").value(false))
                .andExpect(jsonPath("$.myOrders[0].refundRequestable").value(true));
        mvc.perform(withJson(post(g.pub() + "/cancel"), Map.of("phone", "01088000002", "depositorName", "이확정")))
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
        // 주문 확정 전에도 환불 신청 가능 (입금한 고객이 고름). "입금했어요" 상태는 건드리지 않음
        mvc.perform(withJson(post(g.pub() + "/refund-request").cookie(device), refund(phone, "박환불", "110-123-456789")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myOrders[0].refundState").value("REQUESTED"))
                .andExpect(jsonPath("$.myOrders[0].status").value("AWAITING_DEPOSIT"))
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

    /**
     * 환불 신청 중인 주문은 판매자가 처리할 때까지 그대로: 같은 번호로 다시 주문하면 새 주문으로 쌓이고, 수정·바로 취소는 안 됨.
     * 판매자가 [취소하고 환불]하면 그 주문은 취소(환불 필요)로 남고 새 주문은 그대로 (2026-10-19)
     */
    @Test
    void reorderWhileRefundPendingAddsNewOrder() throws Exception {
        Opened g = open(false);
        Cookie device = place(g, "010-8850-0001", "다시주문");
        mvc.perform(withJson(post(g.pub() + "/refund-request").cookie(device), refund("01088500001", "다시주문", "1234567890")))
                .andExpect(status().isOk());
        mvc.perform(get(g.pub()).cookie(device))
                .andExpect(jsonPath("$.myOrders[0].refundState").value("REQUESTED"))
                .andExpect(jsonPath("$.myOrders[0].replaceable").value(false))
                .andExpect(jsonPath("$.myOrders[0].cancellable").value(false))
                .andExpect(jsonPath("$.myOrders[0].refundRequestable").value(false));
        mvc.perform(withJson(post(g.pub() + "/cancel"), Map.of("phone", "01088500001", "depositorName", "다시주문")))
                .andExpect(status().isBadRequest());
        mvc.perform(withJson(post(g.pub() + "/cancel"), Map.of("orderNo", 1, "phone", "01088500001", "depositorName", "다시주문")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("환불 신청한 주문입니다. 판매자가 확인 중입니다."));

        // 같은 번호·이름으로 다시 주문: 수정이 아니라 새 주문 (안내 경고)
        Map<String, Object> again = order(g, "010-8850-0001", "다시주문");
        again.put("acknowledgeWarnings", false);
        mvc.perform(withJson(post(g.pub() + "/orders").cookie(device), again))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.replaceOrderNo").isEmpty())
                .andExpect(jsonPath("$.warnings[0]").value("이 번호로 환불 신청한 주문이 있어, 이번 주문은 새 주문으로 추가됩니다."));
        again.put("acknowledgeWarnings", true);
        mvc.perform(withJson(post(g.pub() + "/orders").cookie(device), again))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(2))
                .andExpect(jsonPath("$.changed").value(false));

        // 판매자가 1번을 취소하고 환불 → 1번은 취소(환불 필요), 2번은 그대로
        long first = sellerOrder(g, 0).get("id").asLong();
        mvc.perform(post(g.sellerBase() + "/orders/" + first + "/refund-request/approve").cookie(g.seller()))
                .andExpect(status().isOk());
        mvc.perform(get(g.pub()).cookie(device))
                .andExpect(jsonPath("$.myOrders.length()").value(2))
                .andExpect(jsonPath("$.myOrders[0].orderNo").value(2))
                .andExpect(jsonPath("$.myOrders[0].status").value("AWAITING_DEPOSIT"))
                .andExpect(jsonPath("$.myOrders[1].orderNo").value(1))
                .andExpect(jsonPath("$.myOrders[1].status").value("CANCELLED"))
                .andExpect(jsonPath("$.myOrders[1].refundRequired").value(true));
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
        // 고객 화면(주문한 기기·내 주문 찾기 모두): 취소됨 + 환불 완료 시각
        mvc.perform(get(g.pub()).cookie(device))
                .andExpect(jsonPath("$.myOrders[0].refundRequired").value(false))
                .andExpect(jsonPath("$.myOrders[0].refundedAt").isNotEmpty());
        mvc.perform(withJson(post(g.pub() + "/identify"), Map.of("phone", "01088200001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myOrders[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.myOrders[0].refundedAt").isNotEmpty());
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
        // 내 주문 보기·찾기는 됨
        mvc.perform(get(g.pub()).cookie(device)).andExpect(jsonPath("$.myOrders.length()").value(1));
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
