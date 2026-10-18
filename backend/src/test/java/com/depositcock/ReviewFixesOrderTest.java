package com.depositcock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.depositcock.groupbuy.Product;
import com.depositcock.groupbuy.ProductRepository;
import com.depositcock.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2026-10-13 전체 점검에서 고친 주문 쪽 문제들의 테스트.
 */
class ReviewFixesOrderTest extends IntegrationTest {

    @Autowired
    ProductRepository productRepository;

    @Autowired
    TransactionTemplate tx;

    record Opened(Cookie seller, long id, String token, long productId) {
    }

    private Opened open(Map<String, Object> body) throws Exception {
        Cookie seller = signup(uniqueEmail());
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body))
                .andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        return new Opened(seller, id, created.get("publicToken").asText(), created.get("products").get(0).get("id").asLong());
    }

    private Map<String, Object> order(long productId, int qty, String phone, String depositor, boolean ack) {
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", productId, "quantity", qty)));
        o.put("phone", phone);
        o.put("depositorName", depositor);
        o.put("acknowledgeWarnings", ack);
        return o;
    }

    @Test
    void perPersonLimitTreatsReorderAsReplacement() throws Exception {
        Map<String, Object> p = product("한정", 1000, null);
        p.put("perOrderLimit", 3);
        Opened g = open(groupBuyBody("1인 한도", List.of(p)));
        String url = "/api/public/g/" + g.token() + "/orders";
        mvc.perform(withJson(post(url), order(g.productId(), 2, "010-5000-0001", "가", true))).andExpect(status().isOk());
        // 같은 번호로 3개: 2 + 3 이 아니라 2개 → 3개로 바꾸기라 한도 안 (2026-10-18)
        Map<String, Object> again = order(g.productId(), 3, "010-5000-0001", "가", true);
        again.put("replaceOrderNo", 1);
        mvc.perform(withJson(post(url), again)).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(1)).andExpect(jsonPath("$.changed").value(true));
        Map<String, Object> over = order(g.productId(), 4, "010-5000-0001", "가", true);
        over.put("replaceOrderNo", 1);
        mvc.perform(withJson(post(url), over)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("1인 최대 3개")));

        // 입금 확정된 주문은 바뀌지 않으므로 그 수량은 합쳐서 센다: 3 + 1 > 3
        long orderId = read(mvc.perform(get("/api/seller/group-buys/" + g.id() + "/orders").cookie(g.seller())).andReturn())
                .get(0).get("id").asLong();
        mvc.perform(withJson(post("/api/seller/group-buys/" + g.id() + "/orders/" + orderId + "/manual-paid").cookie(g.seller()),
                Map.of("depositorName", "가", "amount", 3000))).andExpect(status().isOk());
        mvc.perform(withJson(post(url), order(g.productId(), 1, "010-5000-0001", "가", true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("이미 주문한 3개")));
        // 다른 사람은 따로 센다
        mvc.perform(withJson(post(url), order(g.productId(), 3, "010-5000-0002", "나", true))).andExpect(status().isOk());
    }

    @Test
    void deliveryValidationComesBeforeWarnings() throws Exception {
        Map<String, Object> body = groupBuyBody("배송", List.of(product("상품", 1000, null)));
        body.put("pickupEnabled", false);
        body.put("deliveryEnabled", true);
        Opened g = open(body);
        Map<String, Object> first = order(g.productId(), 1, "010-5100-0001", "가", true);
        first.put("recipientName", "받는분");
        first.put("recipientPhone", "010-5100-0009");
        first.put("address", "주소");
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), first)).andExpect(status().isOk());
        // 같은 연락처(경고 대상) + 주소 없음 → 경고(409)가 아니라 입력 오류(400)가 먼저
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), order(g.productId(), 1, "010-5100-0001", "가", false)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancelWithPhoneAndNameOnly() throws Exception {
        Opened g = open(groupBuyBody("취소", List.of(product("상품", 1000, null))));
        String url = "/api/public/g/" + g.token();
        MvcResult first = mvc.perform(withJson(post(url + "/orders"), order(g.productId(), 1, "010-5200-0001", "김 지영", true)))
                .andExpect(status().isOk()).andReturn();
        Cookie device = first.getResponse().getCookie("dcg_" + g.token());

        // 주문 1건: 주문번호 없이 휴대폰 + 입금자명(띄어쓰기 달라도)으로 취소. 주문한 기기에서는 취소된 주문이 보인다
        Map<String, Object> cancel = new HashMap<>();
        cancel.put("phone", "01052000001");
        cancel.put("depositorName", "김지영");
        mvc.perform(withJson(post(url + "/cancel").cookie(device), cancel)).andExpect(status().isOk())
                .andExpect(jsonPath("$.myOrders[0].status").value("CANCELLED"));

        // 주문 2건(입금 확정된 주문 + 추가 주문): 고르라는 409 + 주문 목록 → 주문번호로 취소
        mvc.perform(withJson(post(url + "/orders"), order(g.productId(), 1, "010-5200-0001", "김지영", true))).andExpect(status().isOk());
        JsonNode orders = read(mvc.perform(get("/api/seller/group-buys/" + g.id() + "/orders").cookie(g.seller())).andReturn());
        mvc.perform(withJson(post("/api/seller/group-buys/" + g.id() + "/orders/" + orders.get(1).get("id").asLong()
                + "/manual-paid").cookie(g.seller()), Map.of("depositorName", "김지영", "amount", 1000))).andExpect(status().isOk());
        mvc.perform(withJson(post(url + "/orders"), order(g.productId(), 2, "010-5200-0001", "김지영", true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orderNo").value(3)).andExpect(jsonPath("$.changed").value(false));
        JsonNode conflict = read(mvc.perform(withJson(post(url + "/cancel"), cancel))
                .andExpect(status().isConflict()).andReturn());
        assertThat(conflict.get("orders")).hasSize(2);
        int target = conflict.get("orders").get(0).get("orderNo").asInt();
        cancel.put("orderNo", target);
        mvc.perform(withJson(post(url + "/cancel"), cancel)).andExpect(status().isOk());

        cancel.put("depositorName", "다른사람");
        mvc.perform(withJson(post(url + "/cancel"), cancel)).andExpect(status().isNotFound());
    }

    @Test
    void identifyFromAnotherDeviceDoesNotExposePersonalInfo() throws Exception {
        Map<String, Object> body = groupBuyBody("개인정보", List.of(product("상품", 1000, null)));
        body.put("deliveryEnabled", true);
        body.put("unitAddressFieldEnabled", true);
        Opened g = open(body);
        String url = "/api/public/g/" + g.token();
        Map<String, Object> o = order(g.productId(), 1, "010-5300-1234", "박서준", true);
        o.put("receiveMethod", "DELIVERY");
        o.put("unitAddress", "101동 1203호");
        o.put("recipientName", "박서준");
        o.put("recipientPhone", "010-5300-1234");
        o.put("zipcode", "12345");
        o.put("address", "서울시 비밀구 1");
        MvcResult placed = mvc.perform(withJson(post(url + "/orders"), o)).andExpect(status().isOk()).andReturn();
        Cookie ownerDevice = placed.getResponse().getCookie("dcg_" + g.token());

        // 주문한 기기: 이전 입력값 전부
        mvc.perform(get(url).cookie(ownerDevice))
                .andExpect(jsonPath("$.prefill.limited").value(false))
                .andExpect(jsonPath("$.prefill.address").value("서울시 비밀구 1"));

        // 뒷4자리만으로는 본인 확인 불가 (2026-10-15 사용자 결정: 휴대폰 전체 번호)
        mvc.perform(withJson(post(url + "/identify"), Map.of("phone", "1234")))
                .andExpect(status().isBadRequest());
        // 다른 기기에서 휴대폰 전체 번호로 확인: 주문 상태는 보이지만 휴대폰·동호수·주소는 안 보임
        MvcResult weak = mvc.perform(withJson(post(url + "/identify"), Map.of("phone", "010-5300-1234")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myOrders.length()").value(1))
                .andExpect(jsonPath("$.prefill.limited").value(true))
                .andExpect(jsonPath("$.prefill.phone").doesNotExist())
                .andExpect(jsonPath("$.prefill.address").doesNotExist())
                .andExpect(jsonPath("$.prefill.unitAddress").doesNotExist())
                .andReturn();
        Cookie weakDevice = weak.getResponse().getCookie("dcg_" + g.token());
        mvc.perform(get(url).cookie(weakDevice))
                .andExpect(jsonPath("$.prefill.limited").value(true))
                .andExpect(jsonPath("$.prefill.address").doesNotExist());

    }

    @Test
    void reportDepositOnPaidOrderIsRejected() throws Exception {
        Opened g = open(groupBuyBody("신고", List.of(product("상품", 1000, null))));
        String url = "/api/public/g/" + g.token();
        MvcResult placed = mvc.perform(withJson(post(url + "/orders"), order(g.productId(), 1, "010-5400-0001", "가", true)))
                .andReturn();
        Cookie device = placed.getResponse().getCookie("dcg_" + g.token());
        JsonNode orders = read(mvc.perform(get("/api/seller/group-buys/" + g.id() + "/orders").cookie(g.seller())).andReturn());
        mvc.perform(withJson(post("/api/seller/group-buys/" + g.id() + "/orders/" + orders.get(0).get("id").asLong()
                + "/manual-paid").cookie(g.seller()), Map.of("depositorName", "가", "amount", 1000))).andExpect(status().isOk());
        mvc.perform(post(url + "/orders/1/report-deposit").cookie(device)).andExpect(status().isBadRequest());
    }

    /** 판매자가 상품을 수정해도 판매 수량(sold)을 옛 값으로 덮어쓰지 않는다 */
    @Test
    void sellerProductEditDoesNotOverwriteSold() throws Exception {
        Opened g = open(groupBuyBody("재고", List.of(product("상품", 1000, 10))));
        tx.executeWithoutResult(status -> {
            Product loaded = productRepository.findById(g.productId()).orElseThrow(); // sold = 0 인 상태로 읽음
            productRepository.increaseSold(g.productId(), 3);                         // 그 사이 다른 주문이 3개
            loaded.update("상품 이름 변경", 1000, 10, null, 1, null, false);                         // 판매자 수정 (옛 sold 를 들고 있음)
        });
        assertThat(productRepository.findById(g.productId()).orElseThrow().getSold()).isEqualTo(3);
    }

    @Test
    void kakaoLinkMustBeHttp() throws Exception {
        Cookie seller = signup(uniqueEmail());
        Map<String, Object> body = groupBuyBody("링크", List.of(product("상품", 1000, null)));
        body.put("kakaoInquiryUrl", "javascript:alert(1)");
        mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body)).andExpect(status().isBadRequest());
        body.put("kakaoInquiryUrl", "https://pf.kakao.com/_abc/chat");
        mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body)).andExpect(status().isOk());
    }

    /** 주문·입금이 있는 공구도 삭제되고 하위 데이터가 함께 지워진다 */
    @Test
    void deleteGroupBuyWithOrdersAndDeposits() throws Exception {
        Opened g = open(groupBuyBody("삭제", List.of(product("상품", 1000, null))));
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), order(g.productId(), 1, "010-5500-0001", "가", true)))
                .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/seller/group-buys/" + g.id() + "/deposits/uploads")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "a.csv", "text/csv",
                                "입금자명,입금액\n가,1000\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .cookie(g.seller()))
                .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/seller/group-buys/" + g.id()).cookie(g.seller())).andExpect(status().isOk());
        mvc.perform(get("/api/seller/group-buys/" + g.id()).cookie(g.seller())).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/g/" + g.token())).andExpect(status().isNotFound());
    }

    @Test
    void unconfirmOnCancelledOrderClearsRefund() throws Exception {
        Opened g = open(groupBuyBody("환불", List.of(product("상품", 1000, null))));
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), order(g.productId(), 1, "010-5600-0001", "가", true)))
                .andExpect(status().isOk());
        JsonNode orders = read(mvc.perform(get("/api/seller/group-buys/" + g.id() + "/orders").cookie(g.seller())).andReturn());
        String base = "/api/seller/group-buys/" + g.id() + "/orders/" + orders.get(0).get("id").asLong();
        mvc.perform(withJson(post(base + "/manual-paid").cookie(g.seller()), Map.of("depositorName", "가", "amount", 1000)));
        mvc.perform(post(base + "/cancel").cookie(g.seller())).andExpect(jsonPath("$.refundRequired").value(true));
        mvc.perform(post(base + "/unconfirm-paid").cookie(g.seller())).andExpect(status().isOk());
        JsonNode after = read(mvc.perform(get("/api/seller/group-buys/" + g.id() + "/orders").cookie(g.seller())).andReturn());
        assertThat(after.get(0).get("refundRequired").asBoolean()).isFalse();
        assertThat(after.get(0).get("paid").asBoolean()).isFalse();
    }

    @Test
    void malformedJsonIsProblemDetail400() throws Exception {
        Cookie seller = signup(uniqueEmail());
        mvc.perform(post("/api/seller/group-buys").cookie(seller)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        // PUT 으로 없는 공구 → 404 ProblemDetail
        mvc.perform(withJson(put("/api/seller/group-buys/999999").cookie(seller),
                groupBuyBody("x", List.of(product("x", 1, null))))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").exists());
    }
}
