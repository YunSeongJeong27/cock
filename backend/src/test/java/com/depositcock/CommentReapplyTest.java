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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 댓글 캡처를 두 번 이상 올리는 경우 (2026-10-15 사용자 결정 "1번"):
 * 오후에 댓글을 반영하고 일부는 입금 확정 → 마감 전까지 새 손님이 댓글 → 캡처를 다시 올려 반영하면
 * 이미 반영된 손님은 그대로 두고 처음 보는 손님만 주문으로 추가한다. 전에는 확정된 주문이 있으면 다시 반영이 막혔다.
 */
class CommentReapplyTest extends IntegrationTest {

    private void addComments(Cookie seller, String base, List<Map<String, String>> comments) throws Exception {
        mvc.perform(withJson(post(base).cookie(seller), Map.of("source", "TEXT", "comments", comments))).andExpect(status().isOk());
    }

    @Test
    void secondUploadAddsOnlyNewCustomersAndKeepsConfirmedOrders() throws Exception {
        Cookie seller = signup(uniqueEmail());
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller),
                groupBuyBody("파프리카", List.of(product("파프리카", 1900, null))))).andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();
        long productId = created.get("products").get(0).get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        String base = "/api/seller/group-buys/" + id + "/comments";
        String orders = "/api/seller/group-buys/" + id + "/orders";

        // 1차: 손님 3명 반영
        addComments(seller, base, List.of(Map.of("author", "꼭지맘", "content", "1"), Map.of("author", "망고맘", "content", "2"),
                Map.of("author", "달콤", "content", "1")));
        mvc.perform(withJson(post(base + "/apply").cookie(seller), Map.of("productId", productId)))
                .andExpect(jsonPath("$.createdCount").value(3));
        // 꼭지맘 입금 확정
        long kkokji = orderId(seller, orders, "꼭지맘");
        mvc.perform(withJson(post(orders + "/" + kkokji + "/manual-paid").cookie(seller),
                Map.of("depositorName", "김꼭지", "amount", 1900))).andExpect(status().isOk());

        // 2차: 새 손님 2명 + 망고맘이 "1개 더요" + 달콤이 "취소"
        addComments(seller, base, List.of(Map.of("author", "새손님", "content", "1"), Map.of("author", "늦은맘", "content", "3"),
                Map.of("author", "망고맘", "content", "1개 더요"), Map.of("author", "달콤", "content", "취소할게요")));
        JsonNode before = read(mvc.perform(get(base + "?productId=" + productId).cookie(seller)).andReturn());
        Map<String, String> states = new HashMap<>();
        before.get("customers").forEach(c -> states.put(c.get("authorName").asText(), c.get("applyState").asText()));
        assertThat(states).containsEntry("꼭지맘", "SAME").containsEntry("망고맘", "DIFFERENT")
                .containsEntry("새손님", "NEW").containsEntry("늦은맘", "NEW");
        // 달콤은 댓글로 취소해서 집계에서 빠짐 → 반영된 주문이 "집계에 없는 주문"으로 표시 (자동 취소 안 함)
        assertThat(before.get("staleOrders")).hasSize(1);
        assertThat(before.get("staleOrders").get(0).get("nickname").asText()).isEqualTo("달콤");

        mvc.perform(withJson(post(base + "/apply").cookie(seller), Map.of("productId", productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(2))
                .andExpect(jsonPath("$.reviewCount").value(2)); // 망고맘 수량 다름 + 달콤 취소 댓글

        JsonNode all = read(mvc.perform(get(orders).cookie(seller)).andReturn());
        Map<String, Integer> qty = new HashMap<>();
        all.forEach(o -> qty.put(o.get("nickname").asText(), o.get("active").asBoolean() ? o.get("totalQuantity").asInt() : -1));
        assertThat(all).hasSize(5);
        assertThat(qty).containsEntry("꼭지맘", 1).containsEntry("망고맘", 2).containsEntry("달콤", 1)
                .containsEntry("새손님", 1).containsEntry("늦은맘", 3);
        assertThat(read(mvc.perform(get(orders).cookie(seller)).andReturn()).findValuesAsText("paid")).contains("true");

        // 3차: 같은 내용으로 또 반영해도 아무것도 늘지 않는다
        mvc.perform(withJson(post(base + "/apply").cookie(seller), Map.of("productId", productId)))
                .andExpect(jsonPath("$.createdCount").value(0));
        assertThat(read(mvc.perform(get(orders).cookie(seller)).andReturn())).hasSize(5);
    }

    /** 판매자가 취소한 댓글 주문(예: 링크 주문과 중복이라 취소)은 다시 반영해도 되살리지 않는다 */
    @Test
    void cancelledCommentOrderIsNotRecreated() throws Exception {
        Cookie seller = signup(uniqueEmail());
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller),
                groupBuyBody("파프리카", List.of(product("파프리카", 1900, null))))).andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();
        long productId = created.get("products").get(0).get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        String base = "/api/seller/group-buys/" + id + "/comments";
        String orders = "/api/seller/group-buys/" + id + "/orders";
        addComments(seller, base, List.of(Map.of("author", "꼭지맘", "content", "1")));
        mvc.perform(withJson(post(base + "/apply").cookie(seller), Map.of("productId", productId))).andExpect(status().isOk());
        mvc.perform(post(orders + "/" + orderId(seller, orders, "꼭지맘") + "/cancel").cookie(seller)).andExpect(status().isOk());

        mvc.perform(withJson(post(base + "/apply").cookie(seller), Map.of("productId", productId)))
                .andExpect(jsonPath("$.createdCount").value(0));
        JsonNode customers = read(mvc.perform(get(base + "?productId=" + productId).cookie(seller)).andReturn()).get("customers");
        assertThat(customers.get(0).get("applyState").asText()).isEqualTo("CANCELLED");
        assertThat(read(mvc.perform(get(orders).cookie(seller)).andReturn())).hasSize(1);
    }

    private long orderId(Cookie seller, String orders, String nickname) throws Exception {
        for (JsonNode o : read(mvc.perform(get(orders).cookie(seller)).andReturn())) {
            if (nickname.equals(o.get("nickname").asText())) {
                return o.get("id").asLong();
            }
        }
        throw new AssertionError(nickname + " 주문 없음");
    }
}
