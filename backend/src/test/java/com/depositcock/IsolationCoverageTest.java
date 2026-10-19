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
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2026-10-14 점검: 기존 테스트가 덮지 않던 API 의 판매자 간·고객 간 분리.
 * 밥(bob)이 앨리스(alice)의 공구·주문·입금·댓글 id 를 자기 경로나 앨리스 경로에 넣어 봐도 막혀야 한다.
 */
class IsolationCoverageTest extends IntegrationTest {

    record Shop(Cookie seller, long gb, String token, long productId, long orderId, int orderNo, long uploadId,
                long rowId, long commentId, long pickupPointId) {
    }

    private Shop shop(String name, String phone) throws Exception {
        Cookie seller = signup(uniqueEmail());
        Map<String, Object> body = groupBuyBody(name, List.of(product("상품", 1000, null)));
        body.put("pickupPoints", List.of(Map.of("name", "1지점"), Map.of("name", "2지점")));
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body))
                .andExpect(status().isOk()).andReturn());
        long gb = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + gb + "/open").cookie(seller)).andExpect(status().isOk());
        String token = created.get("publicToken").asText();
        long productId = created.get("products").get(0).get("id").asLong();
        long pickupPointId = created.get("pickupPoints").get(0).get("id").asLong();
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", productId, "quantity", 1)));
        o.put("phone", phone);
        o.put("depositorName", "다른입금자");
        o.put("pickupPointId", pickupPointId);
        o.put("acknowledgeWarnings", true);
        int orderNo = read(mvc.perform(withJson(post("/api/public/g/" + token + "/orders"), o))
                .andExpect(status().isOk()).andReturn()).get("orderNo").asInt();
        long orderId = read(mvc.perform(get("/api/seller/group-buys/" + gb + "/orders").cookie(seller)).andReturn())
                .get(0).get("id").asLong();
        long uploadId = read(mvc.perform(multipart("/api/seller/group-buys/" + gb + "/deposits/uploads")
                .file(new MockMultipartFile("file", "a.csv", "text/csv", "입금자명,입금액\n관계없음,777\n".getBytes(StandardCharsets.UTF_8)))
                .cookie(seller)).andExpect(status().isOk()).andReturn()).get("uploadId").asLong();
        long rowId = read(mvc.perform(get("/api/seller/group-buys/" + gb + "/deposits/match").cookie(seller)).andReturn())
                .get("rows").get(0).get("rowId").asLong();
        mvc.perform(withJson(post("/api/seller/group-buys/" + gb + "/comments").cookie(seller),
                Map.of("source", "TEXT", "comments", List.of(Map.of("author", "꼭지맘", "content", "1"))))).andExpect(status().isOk());
        long commentId = read(mvc.perform(get("/api/seller/group-buys/" + gb + "/comments").cookie(seller)).andReturn())
                .get("comments").get(0).get("id").asLong();
        return new Shop(seller, gb, token, productId, orderId, orderNo, uploadId, rowId, commentId, pickupPointId);
    }

    @Test
    void sellerCannotTouchAnotherSellersData() throws Exception {
        Shop a = shop("앨리스", "010-9100-0001");
        Shop b = shop("밥", "010-9100-0002");
        Cookie bob = b.seller();
        String aBase = "/api/seller/group-buys/" + a.gb();
        String bBase = "/api/seller/group-buys/" + b.gb();

        // 앨리스 공구 경로 → 404
        mvc.perform(post(aBase + "/close").cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(withJson(post(aBase + "/orders/" + a.orderId() + "/manual-paid").cookie(bob),
                Map.of("depositorName", "x", "amount", 1000))).andExpect(status().isNotFound());
        mvc.perform(post(aBase + "/orders/" + a.orderId() + "/refunded").cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(multipart(aBase + "/deposits/preview")
                .file(new MockMultipartFile("file", "a.csv", "text/csv", "입금자명,입금액\n가,1\n".getBytes(StandardCharsets.UTF_8)))
                .cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(multipart(aBase + "/deposits/uploads")
                .file(new MockMultipartFile("file", "a.csv", "text/csv", "입금자명,입금액\n가,1\n".getBytes(StandardCharsets.UTF_8)))
                .cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(delete(aBase + "/deposits/uploads/" + a.uploadId()).cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(withJson(post(aBase + "/deposits/screenshots/save").cookie(bob),
                Map.of("rows", List.of(Map.of("name", "가", "amount", 1))))).andExpect(status().isNotFound());
        mvc.perform(multipart(aBase + "/comments/extract")
                .file(new MockMultipartFile("files", "a.png", "image/png", new byte[]{1})).cookie(bob))
                .andExpect(status().isNotFound());
        mvc.perform(withJson(post(aBase + "/comments").cookie(bob),
                Map.of("source", "TEXT", "comments", List.of(Map.of("author", "x", "content", "1"))))).andExpect(status().isNotFound());
        mvc.perform(delete(aBase + "/comments").cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(withJson(post(aBase + "/comments/parse-text").cookie(bob), Map.of("text", "가 | 1")))
                .andExpect(status().isNotFound());

        // 밥 공구 경로 + 앨리스 id → 404 / 400
        mvc.perform(withJson(post(bBase + "/orders/" + a.orderId() + "/picked-up").cookie(bob), Map.of("pickedUp", true)))
                .andExpect(status().isNotFound());
        mvc.perform(withJson(post(bBase + "/orders/" + a.orderId() + "/manual-paid").cookie(bob),
                Map.of("depositorName", "x", "amount", 1000))).andExpect(status().isNotFound());
        mvc.perform(post(bBase + "/orders/" + a.orderId() + "/unconfirm-paid").cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(post(bBase + "/orders/" + a.orderId() + "/refunded").cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(delete(bBase + "/deposits/uploads/" + a.uploadId()).cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(withJson(put(bBase + "/comments/" + a.commentId()).cookie(bob),
                Map.of("authorName", "바꿈", "content", "9"))).andExpect(status().isNotFound());
        mvc.perform(delete(bBase + "/comments/" + a.commentId()).cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(withJson(post(bBase + "/comments/apply").cookie(bob), Map.of("productId", a.productId())))
                .andExpect(status().is4xxClientError());
        // 입금 확정에 밥의 입금 + 앨리스 주문, 앨리스 입금 + 밥 주문을 섞어도 확정되지 않는다
        JsonNode mixed = read(mvc.perform(withJson(post(bBase + "/deposits/confirm").cookie(bob), Map.of("items", List.of(
                Map.of("rowId", b.rowId(), "orderId", a.orderId()),
                Map.of("rowId", a.rowId(), "orderId", b.orderId()))))).andReturn());
        assertThat(mixed.get("confirmedCount").asInt()).isZero();
        // 공구 수정 본문에 앨리스 상품 id → 400, 앨리스 픽업 지점 id → 앨리스 지점은 그대로
        Map<String, Object> body = groupBuyBody("밥", List.of(Map.of("id", a.productId(), "name", "탈취", "unitPrice", 1)));
        mvc.perform(withJson(put(bBase).cookie(bob), body)).andExpect(status().isBadRequest());

        // 앨리스 데이터는 그대로
        JsonNode aliceOrders = read(mvc.perform(get(aBase + "/orders").cookie(a.seller())).andReturn());
        assertThat(aliceOrders.get(0).get("paid").asBoolean()).isFalse();
        assertThat(aliceOrders.get(0).get("pickedUp").asBoolean()).isFalse();
        JsonNode aliceComments = read(mvc.perform(get(aBase + "/comments").cookie(a.seller())).andReturn());
        assertThat(aliceComments.get("comments").get(0).get("authorName").asText()).isEqualTo("꼭지맘");
        JsonNode aliceGb = read(mvc.perform(get(aBase).cookie(a.seller())).andReturn());
        assertThat(aliceGb.get("products").get(0).get("name").asText()).isEqualTo("상품");
        assertThat(aliceGb.get("status").asText()).isEqualTo("OPEN");
        assertThat(read(mvc.perform(get(aBase + "/deposits/uploads").cookie(a.seller())).andReturn()).size()).isEqualTo(1);
    }

    @Test
    void nonAdminCannotChangeOrDeleteSellers() throws Exception {
        Shop a = shop("앨리스", "010-9200-0001");
        Cookie mallory = signup(uniqueEmail());
        long aliceId = read(mvc.perform(get("/api/me").cookie(a.seller())).andReturn()).get("id").asLong();
        Map<String, Object> update = new HashMap<>();
        update.put("plan", "PAID");
        update.put("suspended", true);
        mvc.perform(withJson(put("/api/admin/sellers/" + aliceId).cookie(mallory), update)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/sellers/" + aliceId).cookie(mallory)).andExpect(status().isForbidden());
        mvc.perform(get("/api/seller/group-buys/" + a.gb()).cookie(a.seller())).andExpect(status().isOk());
    }

    @Test
    void customerCannotUseAnotherGroupBuysIdsOrOrders() throws Exception {
        Shop a = shop("앨리스", "010-9300-0001");
        Shop b = shop("밥", "010-9300-0002");
        // 밥 공구 주문서에 앨리스 상품 / 앨리스 픽업 지점 → 400
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", a.productId(), "quantity", 1)));
        o.put("phone", "010-9300-0003");
        o.put("depositorName", "손님");
        o.put("pickupPointId", b.pickupPointId());
        o.put("acknowledgeWarnings", true);
        mvc.perform(withJson(post("/api/public/g/" + b.token() + "/orders"), o)).andExpect(status().isBadRequest());
        o.put("items", List.of(Map.of("productId", b.productId(), "quantity", 1)));
        o.put("pickupPointId", a.pickupPointId());
        mvc.perform(withJson(post("/api/public/g/" + b.token() + "/orders"), o)).andExpect(status().isBadRequest());
    }
}
