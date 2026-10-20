package com.depositcock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.depositcock.groupbuy.StockAlerts.StockAlertEvent;
import com.depositcock.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 재고 알림 (2026-10-16 사용자 결정): 재고로 주문을 막지 않는 대신, 남은 수량이 판매자가 정한 기준 이하가 되면 알린다.
 * 메일은 기준을 넘을 때 한 번만, 취소 등으로 다시 기준보다 많아지면 다음에 또 보낸다.
 */
@RecordApplicationEvents
class StockAlertTest extends IntegrationTest {

    @Autowired
    ApplicationEvents events;

    record Opened(Cookie seller, long id, String token, long productId) {
    }

    private Opened open(int stock, Integer threshold) throws Exception {
        Cookie seller = signup(uniqueEmail());
        Map<String, Object> product = new HashMap<>(product("홍게", 1000, stock));
        product.put("stockAlertThreshold", threshold);
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller),
                groupBuyBody("홍게 공구", List.of(product)))).andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        return new Opened(seller, id, created.get("publicToken").asText(), created.get("products").get(0).get("id").asLong());
    }

    private void order(Opened g, String phone, int qty) throws Exception {
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", g.productId(), "quantity", qty)));
        o.put("phone", phone);
        o.put("depositorName", "김" + phone.substring(phone.length() - 4));
        o.put("acknowledgeWarnings", true);
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), o)).andExpect(status().isOk());
    }

    private List<StockAlertEvent> alerts() {
        return events.stream(StockAlertEvent.class).toList();
    }

    @Test
    void alertsOnceWhenCrossingThresholdAndAgainAfterRecovering() throws Exception {
        Opened g = open(10, 3);
        mvc.perform(get("/api/seller/group-buys/" + g.id()).cookie(g.seller()))
                .andExpect(jsonPath("$.products[0].stockAlertThreshold").value(3))
                .andExpect(jsonPath("$.statusLabel").value("주문 받는 중"))
                .andExpect(jsonPath("$.stockAlertWarning").value(containsString("입금내역 대조")));
        order(g, "010-8800-0001", 6); // 남은 4
        assertThat(alerts()).isEmpty();
        mvc.perform(get("/api/seller/group-buys/" + g.id()).cookie(g.seller()))
                .andExpect(jsonPath("$.products[0].stockAlert").doesNotExist());
        order(g, "010-8800-0002", 1); // 남은 3 → 알림
        assertThat(alerts()).hasSize(1);
        // 화면 알림 칸 문장도 서버가 만들어 보낸다 (메일과 같은 문장)
        mvc.perform(get("/api/seller/group-buys/" + g.id()).cookie(g.seller()))
                .andExpect(jsonPath("$.products[0].stockAlert").value("'홍게' 재고가 3개 남았습니다."));
        assertThat(alerts().get(0).remaining()).isEqualTo(3);
        assertThat(alerts().get(0).productName()).isEqualTo("홍게");
        order(g, "010-8800-0003", 4); // 남은 -1 (초과) → 이미 알렸으므로 또 보내지 않음
        assertThat(alerts()).hasSize(1);

        // 판매자가 초과·취소를 처리해 남은 수량이 다시 기준보다 많아지면 다음에 또 알린다
        JsonNode orders = read(mvc.perform(get("/api/seller/group-buys/" + g.id() + "/orders").cookie(g.seller())).andReturn());
        for (JsonNode o : orders) {
            if (o.get("totalQuantity").asInt() == 4 || o.get("totalQuantity").asInt() == 1) {
                mvc.perform(post("/api/seller/group-buys/" + g.id() + "/orders/" + o.get("id").asLong() + "/cancel")
                        .cookie(g.seller())).andExpect(status().isOk());
            }
        }
        mvc.perform(get("/api/seller/group-buys/" + g.id()).cookie(g.seller()))
                .andExpect(jsonPath("$.products[0].remaining").value(4));
        order(g, "010-8800-0004", 2); // 남은 2 → 다시 알림
        assertThat(alerts()).hasSize(2);
        assertThat(alerts().get(1).remaining()).isEqualTo(2);
    }

    @Test
    void noThresholdMeansNoAlert() throws Exception {
        Opened g = open(2, null);
        order(g, "010-8801-0001", 5);
        assertThat(alerts()).isEmpty();
    }
}
