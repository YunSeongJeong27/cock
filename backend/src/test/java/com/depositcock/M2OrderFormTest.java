package com.depositcock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.depositcock.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2: 공구 생성·오픈, 공개 주문 폼, 재고 원자 처리, 쿠키 재방문, 본인 확인, 입금 신고, 취소, 대시보드.
 */
class M2OrderFormTest extends IntegrationTest {

    record Opened(Cookie seller, long id, String token, long productId) {
    }

    private Opened openGroupBuy(Map<String, Object> body) throws Exception {
        Cookie seller = signup(uniqueEmail());
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body))
                .andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        return new Opened(seller, id, created.get("publicToken").asText(),
                created.get("products").get(0).get("id").asLong());
    }

    private Map<String, Object> order(long productId, int qty, String phone, String depositor) {
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", productId, "quantity", qty)));
        o.put("phone", phone);
        o.put("depositorName", depositor);
        o.put("acknowledgeWarnings", false);
        return o;
    }

    @Test
    void draftIsHiddenAndOrderFlowWorks() throws Exception {
        Cookie seller = signup(uniqueEmail());
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller),
                groupBuyBody("파프리카", List.of(product("파프리카 한봉지", 1900, 10))))).andReturn());
        String token = created.get("publicToken").asText();
        long productId = created.get("products").get(0).get("id").asLong();
        mvc.perform(get("/api/public/g/" + token)).andExpect(status().isNotFound());

        mvc.perform(post("/api/seller/group-buys/" + created.get("id").asLong() + "/open").cookie(seller))
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/g/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptingOrders").value(true))
                .andExpect(jsonPath("$.myOrders").isEmpty());

        MvcResult placed = mvc.perform(withJson(post("/api/public/g/" + token + "/orders"),
                        order(productId, 2, "010-1234-5678", "김지영")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(1))
                .andExpect(jsonPath("$.expectedAmount").value(3800))
                .andExpect(jsonPath("$.paymentMethod").value("PREPAID"))
                .andExpect(jsonPath("$.account.bankName").value("토스뱅크"))
                .andReturn();
        Cookie device = placed.getResponse().getCookie("dcg_" + token);
        assertThat(device).isNotNull();

        // 재방문: 쿠키로 알아보고 이전 입력값을 채운다
        mvc.perform(get("/api/public/g/" + token).cookie(device))
                .andExpect(jsonPath("$.myOrders[0].orderNo").value(1))
                .andExpect(jsonPath("$.myOrders[0].statusLabel").value("주문 확정 전"))
                .andExpect(jsonPath("$.prefill.depositorName").value("김지영"))
                .andExpect(jsonPath("$.prefill.phone").value("010-1234-5678"))
                .andExpect(jsonPath("$.products[0].remaining").value(8));

        // 같은 연락처로 다시 주문하면 새 주문이 아니라 1번 주문이 바뀐다 (2026-10-18). 먼저 짧은 경고(409) + 바뀔 주문 내용
        String orders = "/api/public/g/" + token + "/orders";
        Map<String, Object> again = order(productId, 3, "01012345678", "김지영");
        mvc.perform(withJson(post(orders).cookie(device), again))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.replaceOrderNo").value(1))
                .andExpect(jsonPath("$.warnings[0]").value("이 번호로 주문 확정 전인 주문이 있습니다. 계속하면 지금 내용으로 수정됩니다."))
                .andExpect(jsonPath("$.replaceOrder.orderNo").value(1))
                .andExpect(jsonPath("$.replaceOrder.items[0].quantity").value(2))
                .andExpect(jsonPath("$.replaceOrder.expectedAmount").value(3800));
        // 바뀔 주문번호 없이 확인만 보내면 다시 경고 (경고를 본 뒤 바뀔 주문이 달라지는 경우 대비)
        again.put("acknowledgeWarnings", true);
        mvc.perform(withJson(post(orders).cookie(device), again)).andExpect(status().isConflict());
        again.put("replaceOrderNo", 1);
        mvc.perform(withJson(post(orders).cookie(device), again))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(1))
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.expectedAmount").value(5700))
                .andExpect(jsonPath("$.myOrders.length()").value(1));
        mvc.perform(get("/api/public/g/" + token).cookie(device))
                .andExpect(jsonPath("$.products[0].remaining").value(7))
                .andExpect(jsonPath("$.myOrders[0].customerChangedAt").isNotEmpty())
                .andExpect(jsonPath("$.myOrders[0].changedAt").isEmpty());

        // 같은 기기라도 다른 번호로 주문하면 새 주문 (2번)
        mvc.perform(withJson(post(orders).cookie(device), order(productId, 2, "010-2222-3333", "박민수")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(2))
                .andExpect(jsonPath("$.changed").value(false));

        // 입금 신고 (확정 아님)
        mvc.perform(post("/api/public/g/" + token + "/orders/1/report-deposit").cookie(device))
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/g/" + token).cookie(device))
                .andExpect(jsonPath("$.myOrders[1].statusLabel").value("주문 확정 전 (입금 알림 보냄)"));
        // 쿠키 없이 입금 신고 불가
        mvc.perform(post("/api/public/g/" + token + "/orders/2/report-deposit")).andExpect(status().isForbidden());

        // 다른 기기: 휴대폰 전체 번호만으로 본인 확인 (뒷4자리만은 불가, 입금자명은 묻지 않음). 그 번호의 주문만 보인다
        mvc.perform(withJson(post("/api/public/g/" + token + "/identify"),
                Map.of("phone", "5678"))).andExpect(status().isBadRequest());
        MvcResult identified = mvc.perform(withJson(post("/api/public/g/" + token + "/identify"),
                        Map.of("phone", "01012345678")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myOrders.length()").value(1))
                .andReturn();
        assertThat(identified.getResponse().getCookie("dcg_" + token)).isNotNull();
        mvc.perform(withJson(post("/api/public/g/" + token + "/identify"),
                Map.of("phone", "010-1234-0000"))).andExpect(status().isNotFound());

        // 취소: 주문번호 + 휴대폰 + 입금자명이 맞아야 함, 재고 복구
        mvc.perform(withJson(post("/api/public/g/" + token + "/cancel"),
                Map.of("orderNo", 2, "phone", "01099999999", "depositorName", "박민수"))).andExpect(status().isNotFound());
        mvc.perform(withJson(post("/api/public/g/" + token + "/cancel"),
                        Map.of("orderNo", 2, "phone", "010-2222-3333", "depositorName", "박민수")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].remaining").value(7));

        mvc.perform(get("/api/seller/group-buys/" + created.get("id").asLong() + "/dashboard").cookie(seller))
                .andExpect(jsonPath("$.orderCount").value(1))
                .andExpect(jsonPath("$.totalQuantity").value(3))
                .andExpect(jsonPath("$.totalAmount").value(5700))
                .andExpect(jsonPath("$.unpaidCount").value(1))
                .andExpect(jsonPath("$.depositReportedCount").value(1))
                .andExpect(jsonPath("$.cancelledCount").value(1));
    }

    @Test
    void concurrentOrdersAreAllCountedEvenOverStock() throws Exception {
        Opened g = openGroupBuy(groupBuyBody("선착순", List.of(product("홍게", 21900, 5))));
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String phone = String.format("0101111%04d", i);
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"),
                        order(g.productId(), 1, phone, "고객" + phone))).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        int ok = 0;
        for (Future<Integer> f : results) {
            if (f.get() == 200) {
                ok++;
            }
        }
        pool.shutdown();
        // 재고(5)를 넘어도 막지 않는다 (2026-10-16 사용자 결정). 동시에 들어와도 판매 수량은 빠짐없이 더해진다
        assertThat(ok).isEqualTo(threads);
        // 손님 화면: 남은 수량이 0 이하면 수량 표시를 숨긴다 ("1개 남음" 같은 거짓 표시 안 함)
        mvc.perform(get("/api/public/g/" + g.token()))
                .andExpect(jsonPath("$.products[0].remaining").doesNotExist());
        // 판매자 화면: 초과 수량까지 음수로 보인다
        mvc.perform(get("/api/seller/group-buys/" + g.id()).cookie(g.seller()))
                .andExpect(jsonPath("$.products[0].sold").value(threads))
                .andExpect(jsonPath("$.products[0].remaining").value(5 - threads));
    }

    @Test
    void deliveryRequiresAddressAndAddsShippingFee() throws Exception {
        Map<String, Object> body = groupBuyBody("한치", List.of(product("한치 3세트", 23700, null)));
        body.put("pickupEnabled", false);
        body.put("deliveryEnabled", true);
        body.put("shippingFeeType", "FIXED");
        body.put("shippingFee", 4000);
        Opened g = openGroupBuy(body);

        Map<String, Object> o = order(g.productId(), 1, "010-2222-3333", "이체자");
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), o)).andExpect(status().isBadRequest());
        o.put("recipientName", "받는분");
        o.put("recipientPhone", "010-4444-5555");
        o.put("zipcode", "12345");
        o.put("address", "서울시 어딘가 1");
        o.put("addressDetail", "101호");
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), o))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedAmount").value(27700))
                .andExpect(jsonPath("$.paymentMethod").value("PREPAID"));
    }

    @Test
    void pickupOnsiteHasNoAccountAndPickupCheckPays() throws Exception {
        Map<String, Object> body = groupBuyBody("단감", List.of(product("단감 7과", 10000, null)));
        body.put("pickupPrepayEnabled", false);
        body.put("pickupOnsiteEnabled", true);
        body.put("bankName", null);
        body.put("accountNumber", null);
        body.put("accountHolder", null);
        body.put("pickupPoints", List.of(Map.of("name", "본점"), Map.of("name", "2호점")));
        Opened g = openGroupBuy(body);

        Map<String, Object> o = order(g.productId(), 1, "010-7777-8888", "박고객");
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), o)).andExpect(status().isBadRequest());
        JsonNode view = read(mvc.perform(get("/api/public/g/" + g.token())).andReturn());
        o.put("pickupPointId", view.get("pickupPoints").get(1).get("id").asLong());
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), o))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentMethod").value("ONSITE"))
                .andExpect(jsonPath("$.account").doesNotExist());

        JsonNode orders = read(mvc.perform(get("/api/seller/group-buys/" + g.id() + "/orders").cookie(g.seller())).andReturn());
        assertThat(orders.get(0).get("statusLabel").asText()).isEqualTo("예약됨 (현장결제)");
        assertThat(orders.get(0).get("pickupPointName").asText()).isEqualTo("2호점");
        long orderId = orders.get(0).get("id").asLong();
        mvc.perform(withJson(post("/api/seller/group-buys/" + g.id() + "/orders/" + orderId + "/picked-up").cookie(g.seller()),
                        Map.of("pickedUp", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onsitePaid").value(true))
                .andExpect(jsonPath("$.statusLabel").value("수령 완료"));
    }

    @Test
    void closedGroupBuyRejectsOrderAndCancel() throws Exception {
        Opened g = openGroupBuy(groupBuyBody("마감 테스트", List.of(product("떡", 13900, null))));
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), order(g.productId(), 1, "010-1111-2222", "가")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/seller/group-buys/" + g.id() + "/close").cookie(g.seller())).andExpect(status().isOk());
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), order(g.productId(), 1, "010-1111-3333", "나")))
                .andExpect(status().isBadRequest());
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/cancel"),
                Map.of("orderNo", 1, "phone", "010-1111-2222", "depositorName", "가"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/g/" + g.token())).andExpect(jsonPath("$.acceptingOrders").value(false));
    }

    @Test
    void openRequiresAccountForPrepay() throws Exception {
        Cookie seller = signup(uniqueEmail());
        Map<String, Object> body = groupBuyBody("계좌 없음", List.of(product("사과", 1000, null)));
        body.put("bankName", null);
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body)).andReturn());
        mvc.perform(post("/api/seller/group-buys/" + created.get("id").asLong() + "/open").cookie(seller))
                .andExpect(status().isBadRequest());

        Map<String, Object> none = groupBuyBody("둘 다 끔", List.of(product("사과", 1000, null)));
        none.put("pickupPrepayEnabled", false);
        none.put("pickupOnsiteEnabled", false);
        mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), none)).andExpect(status().isBadRequest());
    }
}
