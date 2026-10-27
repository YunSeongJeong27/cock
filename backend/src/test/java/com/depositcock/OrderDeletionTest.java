package com.depositcock;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.depositcock.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2026-10-19 사용자 결정
 * - "완료" = 처리가 끝난 주문: 취소(환불 필요 없음/환불 완료) + 수령하고 돈도 받은 주문. 주문 목록에서 따로 모음
 * - 수령 완료 주문은 바로 취소하지 않고 수령 체크를 먼저 해제 (재고가 다시 늘어나지 않게)
 * - 완료된 주문은 체크해서 여러 건 삭제. 진행 중인 주문은 못 지움
 */
class OrderDeletionTest extends IntegrationTest {

    record Opened(Cookie seller, long id, String token, long productId) {
        String pub() {
            return "/api/public/g/" + token;
        }

        String base() {
            return "/api/seller/group-buys/" + id;
        }
    }

    private Opened open(Map<String, Object> body) throws Exception {
        Cookie seller = signup(uniqueEmail());
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body))
                .andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        return new Opened(seller, id, created.get("publicToken").asText(), created.get("products").get(0).get("id").asLong());
    }

    private Opened open() throws Exception {
        return open(groupBuyBody("완료·삭제", List.of(product("상품", 1000, null))));
    }

    private void place(Opened g, String phone, String depositor) throws Exception {
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", g.productId(), "quantity", 1)));
        o.put("phone", phone);
        o.put("depositorName", depositor);
        o.put("acknowledgeWarnings", true);
        mvc.perform(withJson(post(g.pub() + "/orders"), o)).andExpect(status().isOk());
    }

    private JsonNode orders(Opened g) throws Exception {
        return read(mvc.perform(get(g.base() + "/orders").cookie(g.seller())).andReturn());
    }

    private JsonNode order(Opened g, int orderNo) throws Exception {
        for (JsonNode o : orders(g)) {
            if (o.get("orderNo").asInt() == orderNo) {
                return o;
            }
        }
        return null;
    }

    private String path(Opened g, int orderNo, String action) throws Exception {
        return g.base() + "/orders/" + order(g, orderNo).get("id").asLong() + "/" + action;
    }

    private void post200(Opened g, String url) throws Exception {
        mvc.perform(post(url).cookie(g.seller())).andExpect(status().isOk());
    }

    private void pickedUp(Opened g, int orderNo, boolean on) throws Exception {
        mvc.perform(withJson(post(path(g, orderNo, "picked-up")).cookie(g.seller()), Map.of("pickedUp", on)))
                .andExpect(status().isOk());
    }

    private void manualPaid(Opened g, int orderNo) throws Exception {
        mvc.perform(withJson(post(path(g, orderNo, "manual-paid")).cookie(g.seller()),
                Map.of("depositorName", "입금", "amount", 1000))).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions delete(Opened g, int... orderNos) throws Exception {
        List<Long> ids = new java.util.ArrayList<>();
        for (int no : orderNos) {
            ids.add(order(g, no).get("id").asLong());
        }
        return mvc.perform(withJson(post(g.base() + "/orders/delete").cookie(g.seller()), Map.of("orderIds", ids)));
    }

    @Test
    void pickedUpAndPaidIsFinishedAndMustBeUnpickedBeforeCancel() throws Exception {
        Opened g = open();
        place(g, "010-9200-0001", "김수령");
        place(g, "010-9200-0002", "이미입금");
        manualPaid(g, 1);
        pickedUp(g, 1, true);
        assertThat(order(g, 1).get("finished").asBoolean()).isTrue();
        // 수령했지만 입금 확인이 안 된 선입금 주문은 받을 돈이 남아 있으므로 완료 아님
        pickedUp(g, 2, true);
        assertThat(order(g, 2).get("finished").asBoolean()).isFalse();

        // 수령 완료 주문은 바로 취소 안 됨 (재고가 다시 늘어나지 않게) → 수령 체크 해제 뒤 취소
        mvc.perform(post(path(g, 1, "cancel")).cookie(g.seller())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("수령 완료된 주문입니다. 취소하려면 수령 체크를 먼저 해제해 주세요."));
        pickedUp(g, 1, false);
        assertThat(order(g, 1).get("finished").asBoolean()).isFalse();
        post200(g, path(g, 1, "cancel"));
        assertThat(order(g, 1).get("refundRequired").asBoolean()).isTrue();
    }

    @Test
    void onsitePickedUpIsFinished() throws Exception {
        Map<String, Object> body = groupBuyBody("현장결제", List.of(product("상품", 1000, null)));
        body.put("pickupPrepayEnabled", false);
        body.put("pickupOnsiteEnabled", true);
        Opened g = open(body);
        place(g, "010-9200-0003", "현장");
        assertThat(order(g, 1).get("finished").asBoolean()).isFalse();
        pickedUp(g, 1, true);
        assertThat(order(g, 1).get("finished").asBoolean()).isTrue();
    }

    @Test
    void onlyFinishedOrdersCanBeDeletedAndNumbersAreNotReused() throws Exception {
        Opened g = open();
        place(g, "010-9200-0004", "진행");
        place(g, "010-9200-0005", "취소");
        place(g, "010-9200-0006", "수령");
        post200(g, path(g, 2, "cancel"));
        manualPaid(g, 3);
        pickedUp(g, 3, true);

        // 진행 중인 주문이 섞이면 아무것도 안 지움
        delete(g, 1, 2).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("완료된 주문만 지울 수 있습니다. 아직 처리 중인 주문: 1번"));
        assertThat(orders(g)).hasSize(3);

        delete(g, 2, 3).andExpect(status().isOk()).andExpect(jsonPath("$.deletedCount").value(2));
        assertThat(orders(g)).hasSize(1);
        // 지운 주문의 고객(휴대폰 번호)도 파기 → 내 주문 찾기에 안 나옴
        mvc.perform(withJson(post(g.pub() + "/identify"), Map.of("phone", "01092000006")))
                .andExpect(status().isNotFound());
        // 지운 번호(3번)를 다시 쓰지 않는다
        place(g, "010-9200-0007", "새주문");
        assertThat(order(g, 4)).isNotNull();
        assertThat(order(g, 3)).isNull();
        // 다른 판매자의 주문은 못 지움
        Cookie other = signup(uniqueEmail());
        mvc.perform(withJson(post(g.base() + "/orders/delete").cookie(other), Map.of("orderIds", List.of(order(g, 1).get("id").asLong()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingRemovesItsConfirmedDepositSoItDoesNotReappear() throws Exception {
        Opened g = open();
        place(g, "010-9200-0008", "오대조");
        mvc.perform(multipart(g.base() + "/deposits/uploads").file(new MockMultipartFile("file", "거래내역.csv", "text/csv",
                "거래 일시,적요,거래 금액\n2026.10.19 10:00:00,오대조,1000\n".getBytes(StandardCharsets.UTF_8))).cookie(g.seller()))
                .andExpect(status().isOk());
        JsonNode row = read(mvc.perform(get(g.base() + "/deposits/match").cookie(g.seller())).andReturn()).get("rows").get(0);
        mvc.perform(withJson(post(g.base() + "/deposits/confirm").cookie(g.seller()), Map.of("items",
                List.of(Map.of("rowId", row.get("rowId").asLong(), "orderId", row.get("order").get("orderId").asLong())))))
                .andExpect(jsonPath("$.confirmedCount").value(1));
        pickedUp(g, 1, true);
        delete(g, 1).andExpect(status().isOk());
        // 남겨 두면 입금 대조에 "관련 없는 입금"으로 다시 뜬다
        JsonNode match = read(mvc.perform(get(g.base() + "/deposits/match").cookie(g.seller())).andReturn());
        assertThat(match.get("rows")).isEmpty();
        assertThat(match.get("used")).isEmpty();
    }

    @Test
    void deletingCommentOrderRemovesItsCommentsSoReapplyDoesNotRecreate() throws Exception {
        Map<String, Object> body = groupBuyBody("댓글", List.of(product("상품", 1000, null)));
        body.put("orderChannel", "LINK_AND_COMMENT");
        Opened g = open(body);
        String comments = g.base() + "/comments";
        mvc.perform(withJson(post(comments).cookie(g.seller()), Map.of("source", "TEXT", "comments",
                List.of(Map.of("author", "꼭지맘", "content", "1"), Map.of("author", "망고맘", "content", "2")))))
                .andExpect(status().isOk());
        mvc.perform(withJson(post(comments + "/apply").cookie(g.seller()), Map.of("productId", g.productId())))
                .andExpect(jsonPath("$.createdCount").value(2));
        post200(g, path(g, 1, "cancel"));
        delete(g, 1).andExpect(status().isOk());

        JsonNode left = read(mvc.perform(get(comments + "?productId=" + g.productId()).cookie(g.seller())).andReturn());
        assertThat(left.get("customers").findValuesAsText("authorName")).containsExactly("망고맘");
        mvc.perform(withJson(post(comments + "/apply").cookie(g.seller()), Map.of("productId", g.productId())))
                .andExpect(jsonPath("$.createdCount").value(0));
        assertThat(orders(g)).hasSize(1);
    }
}
