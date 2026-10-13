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
 * 입금 확정 취소 때 별칭은 그 확정이 만든·바꾼 것만 되돌린다 (2026-10-13 코드 리뷰).
 * 전에는 "입금자명 + 이 주문의 고객"인 별칭이면 무조건 지워서, 다른 공구에서 먼저 생긴 별칭까지 지워졌다.
 */
class AliasUndoTest extends IntegrationTest {

    private long openGroupBuy(Cookie seller, String title) throws Exception {
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller),
                groupBuyBody(title, List.of(product("상품", 1000, null))))).andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        return id;
    }

    /** 주문 1건을 넣고 수동 입금 확정. 주문 경로(/api/seller/group-buys/{id}/orders/{orderId})를 돌려준다 */
    private String orderAndConfirm(Cookie seller, long groupBuyId, String phone, String depositor) throws Exception {
        JsonNode g = read(mvc.perform(get("/api/seller/group-buys/" + groupBuyId).cookie(seller)).andReturn());
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", g.get("products").get(0).get("id").asLong(), "quantity", 1)));
        o.put("phone", phone);
        o.put("depositorName", depositor);
        o.put("acknowledgeWarnings", true);
        mvc.perform(withJson(post("/api/public/g/" + g.get("publicToken").asText() + "/orders"), o))
                .andExpect(status().isOk());
        JsonNode orders = read(mvc.perform(get("/api/seller/group-buys/" + groupBuyId + "/orders").cookie(seller)).andReturn());
        String path = "/api/seller/group-buys/" + groupBuyId + "/orders/" + orders.get(orders.size() - 1).get("id").asLong();
        mvc.perform(withJson(post(path + "/manual-paid").cookie(seller), Map.of("depositorName", depositor, "amount", 1000)))
                .andExpect(status().isOk());
        return path;
    }

    private Map<String, String> aliases(Cookie seller) throws Exception {
        Map<String, String> map = new HashMap<>();
        for (JsonNode a : read(mvc.perform(get("/api/seller/aliases").cookie(seller)).andReturn())) {
            map.put(a.get("depositorName").asText(), a.get("customerKey").asText());
        }
        return map;
    }

    private void unconfirm(Cookie seller, String orderPath) throws Exception {
        mvc.perform(post(orderPath + "/unconfirm-paid").cookie(seller)).andExpect(status().isOk());
    }

    @Test
    void aliasFromEarlierGroupBuySurvivesUnconfirm() throws Exception {
        Cookie seller = signup(uniqueEmail());
        String first = orderAndConfirm(seller, openGroupBuy(seller, "1차"), "010-7100-0001", "김가나");
        assertThat(aliases(seller)).containsEntry("김가나", "01071000001");

        // 2차 확정은 이미 있던 별칭을 건드리지 않았으므로, 2차를 취소해도 별칭은 남는다
        String second = orderAndConfirm(seller, openGroupBuy(seller, "2차"), "010-7100-0001", "김가나");
        unconfirm(seller, second);
        assertThat(aliases(seller)).containsEntry("김가나", "01071000001");

        // 별칭을 만든 1차 확정을 취소하면 (다른 근거가 없으니) 지운다
        unconfirm(seller, first);
        assertThat(aliases(seller)).doesNotContainKey("김가나");
    }

    @Test
    void aliasStaysWhileAnotherConfirmedOrderBacksIt() throws Exception {
        Cookie seller = signup(uniqueEmail());
        String first = orderAndConfirm(seller, openGroupBuy(seller, "1차"), "010-7200-0001", "이다라");
        orderAndConfirm(seller, openGroupBuy(seller, "2차"), "010-7200-0001", "이다라");
        // 1차가 별칭을 만들었지만, 2차 확정이 아직 같은 입금자명 → 같은 고객의 근거이므로 남긴다
        unconfirm(seller, first);
        assertThat(aliases(seller)).containsEntry("이다라", "01072000001");
    }

    @Test
    void changedAliasIsRestoredToPreviousCustomer() throws Exception {
        Cookie seller = signup(uniqueEmail());
        orderAndConfirm(seller, openGroupBuy(seller, "1차"), "010-7300-0001", "엄마");
        // 같은 입금자명이 다른 고객으로 확정 → 별칭이 바뀜
        String second = orderAndConfirm(seller, openGroupBuy(seller, "2차"), "010-7300-0002", "엄 마");
        assertThat(aliases(seller)).containsEntry("엄마", "01073000002");
        // 바꾼 확정을 취소하면 원래 고객으로 되돌린다
        unconfirm(seller, second);
        assertThat(aliases(seller)).containsEntry("엄마", "01073000001");
    }

    @Test
    void aliasChangedLaterIsLeftAlone() throws Exception {
        Cookie seller = signup(uniqueEmail());
        String first = orderAndConfirm(seller, openGroupBuy(seller, "1차"), "010-7400-0001", "박마바");
        orderAndConfirm(seller, openGroupBuy(seller, "2차"), "010-7400-0002", "박마바");
        // 1차가 만든 별칭은 그 뒤 2차 확정으로 다른 고객을 가리키게 됐다 → 1차를 취소해도 건드리지 않는다
        unconfirm(seller, first);
        assertThat(aliases(seller)).containsEntry("박마바", "01074000002");
    }
}
