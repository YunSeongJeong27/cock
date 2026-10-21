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
                .andExpect(jsonPath("$.expectedAmount").value(2500))
                // 완료 화면에 보여줄 최종 주문
                .andExpect(jsonPath("$.finalOrder.order.orderNo").value(1))
                .andExpect(jsonPath("$.finalOrder.order.items.length()").value(2))
                .andExpect(jsonPath("$.finalOrder.order.expectedAmount").value(2500))
                .andExpect(jsonPath("$.finalOrder.order.depositorName").value("김사과"));
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
                .andExpect(jsonPath("$.detail").value("기존 주문과 같은 내용입니다. 바뀐 내용이 없습니다."));
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
                .andExpect(jsonPath("$.warnings[0]").value("이 주문은 수정할 수 없습니다. 주문이 확정됐거나 휴대폰 번호가 다릅니다."))
                .andExpect(jsonPath("$.warnings[1]").value("이 번호로 확정된 주문이 있어, 이번 주문은 새 주문으로 추가됩니다."));
        again.put("replaceOrderNo", null);
        mvc.perform(withJson(post(url + "/orders"), again)).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(2))
                .andExpect(jsonPath("$.changed").value(false));

        JsonNode orders = sellerOrders(g);
        assertThat(orders.get(0).get("items").get(0).get("productName").asText()).isEqualTo("사과");
        assertThat(orders.get(0).get("changes")).isEmpty();
    }

    /**
     * "입금했어요"는 수정을 막지 않는다. 주문 확정(판매자가 대조해서 입금 확정) 뒤에만 막힌다
     * (2026-10-19 사용자 결정: 시스템은 "입금했어요"가 사실인지 모르므로 판단에 쓰지 않음)
     */
    @Test
    void onlyConfirmationBlocksEdit() throws Exception {
        Opened g = open(false);
        String url = "/api/public/g/" + g.token();
        Cookie device = mvc.perform(withJson(post(url + "/orders"), order(Map.of(g.apple(), 1), "010-7300-0001", "박입금", null)))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("dcg_" + g.token());
        mvc.perform(get(url).cookie(device))
                .andExpect(jsonPath("$.myOrders[0].statusLabel").value("주문 확정 전"))
                .andExpect(jsonPath("$.myOrders[0].replaceable").value(true))
                .andExpect(jsonPath("$.myOrders[0].editValues.phone").value("010-7300-0001"));
        mvc.perform(withJson(post(url + "/orders").cookie(device), order(Map.of(g.apple(), 2), "010-7300-0001", "박입금", 1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(true));

        // 판매자가 입금 확정 → 주문 확정: 수정 안 됨, 같은 번호로 다시 주문하면 새 주문 (안내 경고)
        long orderId = sellerOrders(g).get(0).get("id").asLong();
        mvc.perform(withJson(post("/api/seller/group-buys/" + g.id() + "/orders/" + orderId + "/manual-paid").cookie(g.seller()),
                Map.of("depositorName", "박입금", "amount", 2000))).andExpect(status().isOk());
        mvc.perform(get(url).cookie(device))
                .andExpect(jsonPath("$.myOrders[0].statusLabel").value("주문 확정"))
                .andExpect(jsonPath("$.myOrders[0].replaceable").value(false))
                .andExpect(jsonPath("$.myOrders[0].editValues").isEmpty());
        Map<String, Object> again = order(Map.of(g.apple(), 3), "010-7300-0001", "박입금", null);
        again.put("acknowledgeWarnings", false);
        mvc.perform(withJson(post(url + "/orders").cookie(device), again)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.replaceOrderNo").isEmpty())
                .andExpect(jsonPath("$.warnings[0]").value("이 번호로 확정된 주문이 있어, 이번 주문은 새 주문으로 추가됩니다."));
        mvc.perform(withJson(post(url + "/orders").cookie(device), order(Map.of(g.apple(), 3), "010-7300-0001", "박입금", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.warnings[0]").value("이 주문은 수정할 수 없습니다. 주문이 확정됐거나 휴대폰 번호가 다릅니다."));
        again.put("acknowledgeWarnings", true);
        mvc.perform(withJson(post(url + "/orders").cookie(device), again)).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(2)).andExpect(jsonPath("$.changed").value(false));

        // 판매자 변경은 고객 화면의 "판매자가 주문을 변경했어요"(changedAt), 고객 수정은 customerChangedAt
        long second = sellerOrders(g).get(1).get("id").asLong();
        mvc.perform(withJson(put("/api/seller/group-buys/" + g.id() + "/orders/" + second).cookie(g.seller()),
                Map.of("items", List.of(Map.of("productId", g.apple(), "quantity", 4)), "depositorName", "박입금")))
                .andExpect(status().isOk());
        mvc.perform(get(url).cookie(device))
                .andExpect(jsonPath("$.myOrders[0].orderNo").value(2))
                .andExpect(jsonPath("$.myOrders[0].changedAt").isNotEmpty())
                .andExpect(jsonPath("$.myOrders[0].customerChangedAt").isEmpty())
                .andExpect(jsonPath("$.myOrders[1].customerChangedAt").isNotEmpty());
        assertThat(sellerOrders(g).get(1).get("changes").get(0).get("changedBy").asText()).isEqualTo("SELLER");
    }

    /** "내 주문"의 [주문 수정]: 고른 주문번호를 보내면 경고 없이 그 주문을 수정한다 */
    @Test
    void editButtonUpdatesTheChosenOrder() throws Exception {
        Opened g = open(false);
        String url = "/api/public/g/" + g.token();
        String phone = "010-7400-0001";
        Cookie device = mvc.perform(withJson(post(url + "/orders"), order(Map.of(g.apple(), 1), phone, "정수정", null)))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("dcg_" + g.token());
        // 1번 입금 확인 → 2번 새 주문 → 1번 입금 확인 취소: 수정할 수 있는 주문이 두 건
        long first = sellerOrders(g).get(0).get("id").asLong();
        String sellerOrder = "/api/seller/group-buys/" + g.id() + "/orders/" + first;
        mvc.perform(withJson(post(sellerOrder + "/manual-paid").cookie(g.seller()), Map.of("depositorName", "정수정", "amount", 1000)))
                .andExpect(status().isOk());
        mvc.perform(withJson(post(url + "/orders").cookie(device), order(Map.of(g.pear(), 1), phone, "정수정", null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orderNo").value(2));
        mvc.perform(post(sellerOrder + "/unconfirm-paid").cookie(g.seller())).andExpect(status().isOk());

        // 1번을 골라 수정 → 1번이 바뀌고 2번은 그대로
        mvc.perform(withJson(post(url + "/orders").cookie(device), order(Map.of(g.apple(), 3), phone, "정수정", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(1))
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.finalOrder.order.items[0].quantity").value(3));
        JsonNode orders = sellerOrders(g);
        assertThat(orders.get(0).get("items").get(0).get("quantity").asInt()).isEqualTo(3);
        assertThat(orders.get(1).get("items").get(0).get("productName").asText()).isEqualTo("배");
        assertThat(orders.get(1).get("changes")).isEmpty();

        // 휴대폰 번호를 다르게 적으면 수정하지 않고 다시 확인
        mvc.perform(withJson(post(url + "/orders").cookie(device), order(Map.of(g.apple(), 2), "010-7400-0002", "정수정", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.replaceOrderNo").isEmpty())
                .andExpect(jsonPath("$.warnings[0]").value("이 주문은 수정할 수 없습니다. 주문이 확정됐거나 휴대폰 번호가 다릅니다."));

        // 다른 기기(내 주문 찾기)에서는 수정용 값에 휴대폰·주소가 없다
        Cookie other = mvc.perform(withJson(post(url + "/identify"), Map.of("phone", phone))).andExpect(status().isOk())
                .andReturn().getResponse().getCookie("dcg_" + g.token());
        mvc.perform(get(url).cookie(other))
                .andExpect(jsonPath("$.myOrders[1].orderNo").value(1))
                .andExpect(jsonPath("$.myOrders[1].editValues.limited").value(true))
                .andExpect(jsonPath("$.myOrders[1].editValues.phone").isEmpty())
                .andExpect(jsonPath("$.myOrders[1].editValues.depositorName").value("정수정"));
    }
}
