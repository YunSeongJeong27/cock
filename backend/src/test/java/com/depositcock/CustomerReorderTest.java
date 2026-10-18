package com.depositcock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.depositcock.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 같은 휴대폰 번호로 다시 주문하면 새 주문이 아니라 기존 주문(입금 확정·수령 전)이 그 내용으로 바뀐다 (2026-10-18 사용자 결정).
 */
class CustomerReorderTest extends IntegrationTest {

    record Opened(Cookie seller, long id, String token, long apple, long pear) {
    }

    private Opened open(boolean blockApple) throws Exception {
        Cookie seller = signup(uniqueEmail());
        Map<String, Object> apple = new HashMap<>(product("사과", 1000, 2));
        apple.put("blockWhenSoldOut", blockApple);
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller),
                groupBuyBody("다시 주문", List.of(apple, product("배", 500, null))))).andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        return new Opened(seller, id, created.get("publicToken").asText(),
                created.get("products").get(0).get("id").asLong(), created.get("products").get(1).get("id").asLong());
    }

    private Map<String, Object> order(Map<Long, Integer> items, String phone, String depositor, Integer replaceOrderNo) {
        Map<String, Object> o = new HashMap<>();
        o.put("items", items.entrySet().stream().map(e -> Map.of("productId", e.getKey(), "quantity", e.getValue())).toList());
        o.put("phone", phone);
        o.put("depositorName", depositor);
        o.put("acknowledgeWarnings", true);
        o.put("replaceOrderNo", replaceOrderNo);
        return o;
    }

    private JsonNode sellerOrders(Opened g) throws Exception {
        return read(mvc.perform(get("/api/seller/group-buys/" + g.id() + "/orders").cookie(g.seller())).andReturn());
    }

    @Test
    void reorderReplacesStockByDifferenceOnly() throws Exception {
        Opened g = open(true); // 사과: 준비 2개, 다 팔리면 품절
        String url = "/api/public/g/" + g.token();
        Cookie device = mvc.perform(withJson(post(url + "/orders"), order(Map.of(g.apple(), 2), "010-7100-0001", "김사과", null)))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("dcg_" + g.token());
        mvc.perform(get(url)).andExpect(jsonPath("$.products[0].soldOut").value(true));

        // 품절이어도 이미 주문한 2개는 그대로 두고 배를 더할 수 있다 (늘어난 만큼만 재고를 씀)
        mvc.perform(withJson(post(url + "/orders").cookie(device), order(Map.of(g.apple(), 2, g.pear(), 1), "010-7100-0001", "김사과", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(1))
                .andExpect(jsonPath("$.expectedAmount").value(2500));
        // 사과를 3개로 늘리는 건 품절이라 막힘
        mvc.perform(withJson(post(url + "/orders").cookie(device), order(Map.of(g.apple(), 3), "010-7100-0001", "김사과", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("최대 2개까지 주문할 수 있습니다. (지금 주문한 2개 포함)")));
        // 사과를 1개로 줄이면 1개가 다시 남는다
        mvc.perform(withJson(post(url + "/orders").cookie(device), order(Map.of(g.apple(), 1), "010-7100-0001", "김사과", 1)))
                .andExpect(status().isOk());
        mvc.perform(get(url))
                .andExpect(jsonPath("$.products[0].soldOut").value(false))
                .andExpect(jsonPath("$.products[0].remaining").value(1));

        JsonNode orders = sellerOrders(g);
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).get("items")).hasSize(1);
        JsonNode changes = orders.get(0).get("changes");
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).get("changedBy").asText()).isEqualTo("CUSTOMER");
        assertThat(changes.get(0).get("summary").asText()).isEqualTo("배 0→1개, 금액 2,000원→2,500원");
        assertThat(changes.get(1).get("summary").asText()).isEqualTo("사과 2→1개, 배 1→0개, 금액 2,500원→1,000원");

        // 같은 내용이면 바꿀 게 없다
        mvc.perform(withJson(post(url + "/orders").cookie(device), order(Map.of(g.apple(), 1), "010-7100-0001", "김사과", 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("1번 주문과 같은 내용입니다. 바뀐 내용이 없습니다."));
    }

    @Test
    void paidOrderIsNotReplacedAndReorderBecomesNewOrder() throws Exception {
        Opened g = open(false);
        String url = "/api/public/g/" + g.token();
        mvc.perform(withJson(post(url + "/orders"), order(Map.of(g.apple(), 1), "010-7200-0001", "이배", null)))
                .andExpect(status().isOk());

        // 경고에서 1번이 바뀐다고 보여준 뒤, 확인 전에 판매자가 입금 확정
        Map<String, Object> again = order(Map.of(g.pear(), 2), "010-7200-0001", "이배", null);
        again.put("acknowledgeWarnings", false);
        mvc.perform(withJson(post(url + "/orders"), again)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.replaceOrderNo").value(1));
        long orderId = sellerOrders(g).get(0).get("id").asLong();
        mvc.perform(withJson(post("/api/seller/group-buys/" + g.id() + "/orders/" + orderId + "/manual-paid").cookie(g.seller()),
                Map.of("depositorName", "이배", "amount", 1000))).andExpect(status().isOk());

        // 1번을 바꾸겠다고 확인해도, 바뀔 주문이 없어졌으므로 다시 경고 (새 주문으로 들어간다는 안내)
        again.put("acknowledgeWarnings", true);
        again.put("replaceOrderNo", 1);
        mvc.perform(withJson(post(url + "/orders"), again)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.replaceOrderNo").isEmpty())
                .andExpect(jsonPath("$.warnings[0]").value(containsString("새 주문으로 추가됩니다")));
        again.put("replaceOrderNo", null);
        mvc.perform(withJson(post(url + "/orders"), again)).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(2))
                .andExpect(jsonPath("$.changed").value(false));

        JsonNode orders = sellerOrders(g);
        assertThat(orders.get(0).get("items").get(0).get("productName").asText()).isEqualTo("사과");
        assertThat(orders.get(0).get("changes")).isEmpty();
    }

    @Test
    void reportedDepositGetsExtraWarningAndSellerChangeIsShownSeparately() throws Exception {
        Opened g = open(false);
        String url = "/api/public/g/" + g.token();
        Cookie device = mvc.perform(withJson(post(url + "/orders"), order(Map.of(g.apple(), 1), "010-7300-0001", "박입금", null)))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("dcg_" + g.token());
        mvc.perform(post(url + "/orders/1/report-deposit").cookie(device)).andExpect(status().isOk());

        Map<String, Object> again = order(Map.of(g.apple(), 2), "010-7300-0001", "박입금", null);
        again.put("acknowledgeWarnings", false);
        mvc.perform(withJson(post(url + "/orders").cookie(device), again)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.warnings[1]").value(containsString("차액은 판매자에게 문의해 주세요")));

        // 판매자 변경은 고객 화면의 "판매자가 주문을 변경했어요"(changedAt), 고객 변경은 customerChangedAt
        long orderId = sellerOrders(g).get(0).get("id").asLong();
        mvc.perform(withJson(put("/api/seller/group-buys/" + g.id() + "/orders/" + orderId).cookie(g.seller()),
                Map.of("items", List.of(Map.of("productId", g.apple(), "quantity", 3)), "depositorName", "박입금")))
                .andExpect(status().isOk());
        mvc.perform(get(url).cookie(device))
                .andExpect(jsonPath("$.myOrders[0].changedAt").isNotEmpty())
                .andExpect(jsonPath("$.myOrders[0].customerChangedAt").isEmpty())
                .andExpect(jsonPath("$.myOrders[0].replaceable").value(true));
        assertThat(sellerOrders(g).get(0).get("changes").get(0).get("changedBy").asText()).isEqualTo("SELLER");
    }
}
