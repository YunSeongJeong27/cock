package com.depositcock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.depositcock.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 판매자가 고객의 변경 요청(댓글·채팅)을 대신 반영 (2026-10-15 사용자 요청).
 * 고객 화면에는 수정 기능이 없고(인터뷰 결과 4번), 판매자가 주문 목록에서 바꾼다.
 */
class SellerOrderEditTest extends IntegrationTest {

    record Opened(Cookie seller, long id, String token, long productA, long productB) {
        String base() {
            return "/api/seller/group-buys/" + id;
        }
    }

    private Opened open() throws Exception {
        return open(List.of(product("파프리카", 1900, 10), product("한우", 8900, null)));
    }

    /** 댓글 주문 테스트용: 상품이 여러 개인 공구는 댓글 주문을 막으므로(2026-10-15) 상품 1개. productB 는 productA 와 같음 */
    private Opened openSingle() throws Exception {
        return open(List.of(product("파프리카", 1900, 10)));
    }

    private Opened open(List<Map<String, Object>> products) throws Exception {
        Cookie seller = signup(uniqueEmail());
        Map<String, Object> body = groupBuyBody("변경", products);
        body.put("orderChannel", "LINK_AND_COMMENT");
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body))
                .andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        return new Opened(seller, id, created.get("publicToken").asText(),
                created.get("products").get(0).get("id").asLong(),
                created.get("products").get(created.get("products").size() - 1).get("id").asLong());
    }

    private MvcResult order(Opened g, String phone, int qtyA) throws Exception {
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", g.productA(), "quantity", qtyA)));
        o.put("phone", phone);
        o.put("depositorName", "김변경");
        o.put("nickname", "꼭지맘");
        o.put("acknowledgeWarnings", true);
        return mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), o)).andExpect(status().isOk()).andReturn();
    }

    private Map<String, Object> edit(Opened g, int qtyA, int qtyB) {
        Map<String, Object> e = new HashMap<>();
        e.put("items", List.of(Map.of("productId", g.productA(), "quantity", qtyA), Map.of("productId", g.productB(), "quantity", qtyB)));
        e.put("depositorName", "김변경");
        e.put("nickname", "꼭지맘");
        return e;
    }

    private JsonNode firstOrder(Opened g) throws Exception {
        return read(mvc.perform(get(g.base() + "/orders").cookie(g.seller())).andReturn()).get(0);
    }

    private int remainingA(Opened g) throws Exception {
        return read(mvc.perform(get("/api/public/g/" + g.token())).andReturn()).get("products").get(0).get("remaining").asInt();
    }

    @Test
    void sellerChangesItemsAndAmountAndCustomerSeesIt() throws Exception {
        Opened g = open();
        Cookie device = order(g, "010-9500-1001", 2).getResponse().getCookie("dcg_" + g.token());
        long orderId = firstOrder(g).get("id").asLong();
        assertThat(remainingA(g)).isEqualTo(8);

        // 파프리카 2 → 1, 한우 0 → 1
        mvc.perform(withJson(put(g.base() + "/orders/" + orderId).cookie(g.seller()), edit(g, 1, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedAmount").value(1900 + 8900))
                .andExpect(jsonPath("$.changes.length()").value(1));
        JsonNode o = firstOrder(g);
        String summary = o.get("changes").get(0).get("summary").asText();
        assertThat(summary).contains("파프리카 2→1개").contains("한우 0→1개").contains("금액 3,800원→10,800원");
        assertThat(remainingA(g)).isEqualTo(9); // 줄어든 1개는 재고로 돌아감

        // 고객 화면: 바뀐 금액과 "판매자가 변경함" 시각
        mvc.perform(get("/api/public/g/" + g.token()).cookie(device))
                .andExpect(jsonPath("$.myOrders[0].expectedAmount").value(10800))
                .andExpect(jsonPath("$.myOrders[0].changedAt").exists());

        // 한우 빼기 → 상품 줄이 지워진다
        mvc.perform(withJson(put(g.base() + "/orders/" + orderId).cookie(g.seller()), edit(g, 1, 0))).andExpect(status().isOk());
        assertThat(firstOrder(g).get("items").size()).isEqualTo(1);
    }

    /** 재고를 넘겨도 막지 않는다 (2026-10-16 사용자 결정). 판매자 화면에는 초과 수량이 음수로 보인다 */
    @Test
    void editOverStockIsAllowedAndShownAsOver() throws Exception {
        Opened g = open();
        order(g, "010-9500-2001", 2);
        long orderId = firstOrder(g).get("id").asLong();
        // 재고 10 → 11개로 늘림: 막지 않음
        mvc.perform(withJson(put(g.base() + "/orders/" + orderId).cookie(g.seller()), edit(g, 11, 0)))
                .andExpect(status().isOk());
        mvc.perform(get(g.base()).cookie(g.seller()))
                .andExpect(jsonPath("$.products[0].sold").value(11))
                .andExpect(jsonPath("$.products[0].remaining").value(-1));
        // 손님 화면은 0 이하면 수량을 숨긴다
        mvc.perform(get("/api/public/g/" + g.token())).andExpect(jsonPath("$.products[0].remaining").doesNotExist());
    }

    @Test
    void paidPickedUpCancelledOrUnchangedOrdersCannotBeEdited() throws Exception {
        Opened g = open();
        order(g, "010-9500-3001", 1);
        long orderId = firstOrder(g).get("id").asLong();
        String path = g.base() + "/orders/" + orderId;
        // 바뀐 내용 없음
        mvc.perform(withJson(put(path).cookie(g.seller()), edit(g, 1, 0))).andExpect(status().isBadRequest());
        // 입금 확정 → 수정 불가, 확정 취소 후에는 가능
        mvc.perform(withJson(post(path + "/manual-paid").cookie(g.seller()), Map.of("depositorName", "김변경", "amount", 1900)))
                .andExpect(status().isOk());
        mvc.perform(withJson(put(path).cookie(g.seller()), edit(g, 2, 0))).andExpect(status().isBadRequest());
        mvc.perform(post(path + "/unconfirm-paid").cookie(g.seller())).andExpect(status().isOk());
        mvc.perform(withJson(put(path).cookie(g.seller()), edit(g, 2, 0))).andExpect(status().isOk());
        // 수령 완료 → 불가
        mvc.perform(withJson(post(path + "/picked-up").cookie(g.seller()), Map.of("pickedUp", true))).andExpect(status().isOk());
        mvc.perform(withJson(put(path).cookie(g.seller()), edit(g, 3, 0))).andExpect(status().isBadRequest());
        mvc.perform(withJson(post(path + "/picked-up").cookie(g.seller()), Map.of("pickedUp", false))).andExpect(status().isOk());
        // 취소 → 불가
        mvc.perform(post(path + "/cancel").cookie(g.seller())).andExpect(status().isOk());
        mvc.perform(withJson(put(path).cookie(g.seller()), edit(g, 3, 0))).andExpect(status().isBadRequest());
    }

    @Test
    void anotherSellerCannotEdit() throws Exception {
        Opened g = open();
        order(g, "010-9500-4001", 1);
        long orderId = firstOrder(g).get("id").asLong();
        Cookie other = signup(uniqueEmail());
        mvc.perform(withJson(put(g.base() + "/orders/" + orderId).cookie(other), edit(g, 2, 0))).andExpect(status().isNotFound());
        assertThat(firstOrder(g).get("totalQuantity").asInt()).isEqualTo(1);
    }

    /** 판매자가 바꾼 댓글 주문은 다시 반영해도 그대로 남는다 (다시 반영은 처음 보는 손님만 추가) */
    @Test
    void editedCommentOrdersSurviveReapplying() throws Exception {
        Opened g = openSingle();
        String comments = g.base() + "/comments";
        mvc.perform(withJson(post(comments).cookie(g.seller()),
                Map.of("source", "TEXT", "comments", List.of(Map.of("author", "망고맘", "content", "2"))))).andExpect(status().isOk());
        mvc.perform(withJson(post(comments + "/apply").cookie(g.seller()), Map.of("productId", g.productA()))).andExpect(status().isOk());
        JsonNode commentOrder = firstOrder(g);
        Map<String, Object> e = edit(g, 3, 0);
        e.put("depositorName", null); // 댓글 주문은 입금자명이 없을 수 있다
        e.put("nickname", "망고맘");
        mvc.perform(withJson(put(g.base() + "/orders/" + commentOrder.get("id").asLong()).cookie(g.seller()), e))
                .andExpect(status().isOk());
        // 다시 반영해도 판매자가 바꾼 3개는 그대로, 댓글(2개)과 다르니 확인 필요로만 알려 준다
        mvc.perform(withJson(post(comments + "/apply").cookie(g.seller()), Map.of("productId", g.productA())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(0))
                .andExpect(jsonPath("$.reviewCount").value(1));
        assertThat(firstOrder(g).get("totalQuantity").asInt()).isEqualTo(3);
    }

    /** 댓글에는 휴대폰 번호가 없어 링크 주문과 같은 사람인지는 닉네임으로만 의심할 수 있다 → 경고만 (2026-10-15 사용자 결정) */
    @Test
    void sameNicknameOnLinkAndCommentOrdersIsFlagged() throws Exception {
        Opened g = openSingle();
        order(g, "010-9500-6001", 1); // 링크 주문 1번, 닉네임 "꼭지맘"
        String comments = g.base() + "/comments";
        mvc.perform(withJson(post(comments).cookie(g.seller()), Map.of("source", "TEXT", "comments", List.of(
                Map.of("author", "꼭지 맘", "content", "2"), Map.of("author", "망고맘", "content", "1"))))).andExpect(status().isOk());

        // 반영 전: 댓글 집계 화면에서 경고
        JsonNode customers = read(mvc.perform(get(comments + "?productId=" + g.productA()).cookie(g.seller())).andReturn())
                .get("customers");
        Map<String, String> linkOrders = new HashMap<>();
        customers.forEach(c -> linkOrders.put(c.get("authorName").asText(), c.get("linkOrderNos").toString()));
        assertThat(linkOrders).containsEntry("꼭지 맘", "[1]").containsEntry("망고맘", "[]");

        // 반영 후: 자동으로 합치지 않고 2건 그대로, 주문 목록에서 서로를 가리킨다
        mvc.perform(withJson(post(comments + "/apply").cookie(g.seller()), Map.of("productId", g.productA()))).andExpect(status().isOk());
        Map<String, String> same = new HashMap<>();
        read(mvc.perform(get(g.base() + "/orders").cookie(g.seller())).andReturn())
                .forEach(o -> same.put(o.get("source").asText() + o.get("orderNo").asInt(), o.get("sameNicknameOrderNos").toString()));
        assertThat(same).containsEntry("FORM1", "[2]").containsEntry("COMMENT2", "[1]").containsEntry("COMMENT3", "[]");
    }
}
