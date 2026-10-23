package com.depositcock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.depositcock.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공구 목록 요약(DB 집계, CustomerOrderRepository.totalsBySeller)이 공구 상세 요약(주문을 읽어 셈, GroupBuyService.dashboard)과
 * 같은지 (2026-10-21 공구 목록을 DB 집계로 바꿈). 요약이 세는 주문 상태를 모두 만들어 비교한다.
 * 한쪽 규칙만 바꾸면 여기서 걸린다.
 */
class GroupBuyListTotalsTest extends IntegrationTest {

    record Opened(Cookie seller, long id, String token, long apple, long pear) {
        String base() {
            return "/api/seller/group-buys/" + id;
        }
    }

    private Opened open(Cookie seller, String title) throws Exception {
        Map<String, Object> body = groupBuyBody(title, List.of(product("사과", 1000, null), product("배", 2500, null)));
        body.put("pickupOnsiteEnabled", true); // 선입금·현장결제 둘 다
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body))
                .andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        JsonNode products = created.get("products");
        return new Opened(seller, id, created.get("publicToken").asText(), products.get(0).get("id").asLong(),
                products.get(1).get("id").asLong());
    }

    /** @return 주문 id */
    private long place(Opened g, String phone, String depositor, String payment, int apples, int pears) throws Exception {
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", g.apple(), "quantity", apples), Map.of("productId", g.pear(), "quantity", pears)));
        o.put("phone", phone);
        o.put("depositorName", depositor);
        o.put("paymentMethod", payment);
        o.put("acknowledgeWarnings", true);
        int orderNo = read(mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), o))
                .andExpect(status().isOk()).andReturn()).get("orderNo").asInt();
        for (JsonNode order : read(mvc.perform(get(g.base() + "/orders").cookie(g.seller())).andReturn())) {
            if (order.get("orderNo").asInt() == orderNo) {
                return order.get("id").asLong();
            }
        }
        throw new IllegalStateException("주문을 못 찾음");
    }

    private void seller(Opened g, long orderId, String action, Object body) throws Exception {
        mvc.perform(withJson(post(g.base() + "/orders/" + orderId + "/" + action).cookie(g.seller()), body))
                .andExpect(status().isOk());
    }

    private void paid(Opened g, long orderId, String depositor) throws Exception {
        seller(g, orderId, "manual-paid", Map.of("depositorName", depositor, "amount", 1000));
    }

    private void refundRequest(Opened g, String phone, String depositor) throws Exception {
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/refund-request"),
                        Map.of("phone", phone, "depositorName", depositor, "bankName", "국민은행", "accountNumber", "1234567890123")))
                .andExpect(status().isOk());
    }

    private JsonNode listed(Cookie seller, long groupBuyId) throws Exception {
        for (JsonNode g : read(mvc.perform(get("/api/seller/group-buys").cookie(seller)).andExpect(status().isOk()).andReturn())) {
            if (g.get("id").asLong() == groupBuyId) {
                return g.get("dashboard");
            }
        }
        throw new IllegalStateException("목록에 공구가 없음");
    }

    private JsonNode detail(Opened g) throws Exception {
        return read(mvc.perform(get(g.base() + "/detail").cookie(g.seller())).andExpect(status().isOk()).andReturn()).get("dashboard");
    }

    @Test
    void listTotalsMatchDetailDashboardForEveryOrderState() throws Exception {
        Cookie seller = signup(uniqueEmail());
        Opened g = open(seller, "모든 상태");
        Opened empty = open(seller, "주문 없음");

        place(g, "010-9400-0001", "미입금", "PREPAID", 2, 1);                       // 선입금 미입금
        long paidPickedUp = place(g, "010-9400-0002", "입금수령", "PREPAID", 1, 0);
        paid(g, paidPickedUp, "입금수령");
        seller(g, paidPickedUp, "picked-up", Map.of("pickedUp", true));             // 입금 + 수령
        long refundAsked = place(g, "010-9400-0003", "환불신청", "PREPAID", 3, 0);
        paid(g, refundAsked, "환불신청");
        refundRequest(g, "010-9400-0003", "환불신청");                                // 환불 신청 중 (살아 있음)
        long rejected = place(g, "010-9400-0004", "반려", "PREPAID", 0, 2);
        paid(g, rejected, "반려");
        refundRequest(g, "010-9400-0004", "반려");
        seller(g, rejected, "refund-request/reject", Map.of());                      // 환불 신청 반려 (살아 있음)
        place(g, "010-9400-0005", "현장", "ONSITE", 1, 1);                           // 현장결제
        long onsitePickedUp = place(g, "010-9400-0006", "현장수령", "ONSITE", 2, 0);
        seller(g, onsitePickedUp, "picked-up", Map.of("pickedUp", true));           // 현장결제 + 수령
        long cancelled = place(g, "010-9400-0007", "취소", "PREPAID", 1, 0);
        seller(g, cancelled, "cancel", Map.of());                                    // 취소 (환불 필요 없음)
        long refundNeeded = place(g, "010-9400-0008", "환불필요", "PREPAID", 1, 0);
        paid(g, refundNeeded, "환불필요");
        seller(g, refundNeeded, "cancel", Map.of());                                 // 입금 후 취소 → 환불 필요
        long refunded = place(g, "010-9400-0009", "환불완료", "PREPAID", 1, 0);
        paid(g, refunded, "환불완료");
        seller(g, refunded, "cancel", Map.of());
        seller(g, refunded, "refunded", Map.of());                                   // 환불 완료
        long unconfirmed = place(g, "010-9400-0010", "확정취소", "PREPAID", 1, 1);
        paid(g, unconfirmed, "확정취소");
        seller(g, unconfirmed, "unconfirm-paid", Map.of());                          // 확정 취소 이력만 남은 미입금
        long approved = place(g, "010-9400-0011", "승인", "PREPAID", 1, 0);
        paid(g, approved, "승인");
        refundRequest(g, "010-9400-0011", "승인");
        seller(g, approved, "refund-request/approve", Map.of());                     // 환불 신청 승인 → 취소 + 환불 필요

        // 다른 판매자의 주문은 섞이지 않는다
        Opened other = open(signup(uniqueEmail()), "다른 판매자");
        place(other, "010-9400-0001", "남", "PREPAID", 5, 5);

        JsonNode expected = detail(g);
        assertThat(listed(seller, g.id())).isEqualTo(expected);
        // 위에서 만든 상태가 실제로 숫자에 잡혔는지 (모두 0 이면 비교가 의미 없음)
        assertThat(expected.get("orderCount").asInt()).isEqualTo(7);
        assertThat(expected.get("cancelledCount").asInt()).isEqualTo(4);
        assertThat(expected.get("paidCount").asInt()).isEqualTo(3);
        assertThat(expected.get("unpaidCount").asInt()).isEqualTo(2);
        assertThat(expected.get("onsiteCount").asInt()).isEqualTo(2);
        assertThat(expected.get("pickedUpCount").asInt()).isEqualTo(2);
        assertThat(expected.get("refundRequestCount").asInt()).isEqualTo(1);
        assertThat(expected.get("refundPendingCount").asInt()).isEqualTo(2);
        assertThat(expected.get("totalQuantity").asInt()).isEqualTo(3 + 1 + 3 + 2 + 2 + 2 + 2);

        // 주문이 없는 공구는 모두 0
        assertThat(listed(seller, empty.id())).isEqualTo(detail(empty));
        assertThat(detail(empty).get("orderCount").asInt()).isZero();
    }
}
