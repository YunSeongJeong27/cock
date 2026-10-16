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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "다 팔리면 품절(주문 막기)" — 판매자가 상품마다 켜고 끈다 (2026-10-16 사용자 결정, 기본 꺼짐 = 막지 않음).
 * 켜면 준비 수량에 도달했을 때 손님 화면에 품절이 뜨고 주문이 막힌다.
 */
class StockBlockTest extends IntegrationTest {

    record Opened(Cookie seller, long id, String token, long productId) {
    }

    private Opened open(int stock, boolean block) throws Exception {
        Cookie seller = signup(uniqueEmail());
        Map<String, Object> product = new HashMap<>(product("홍게", 21900, stock));
        product.put("blockWhenSoldOut", block);
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller),
                groupBuyBody("선착순", List.of(product)))).andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        return new Opened(seller, id, created.get("publicToken").asText(), created.get("products").get(0).get("id").asLong());
    }

    private Map<String, Object> order(Opened g, String phone, int qty) {
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", g.productId(), "quantity", qty)));
        o.put("phone", phone);
        o.put("depositorName", "고객" + phone.substring(phone.length() - 4));
        o.put("acknowledgeWarnings", true);
        return o;
    }

    @Test
    void blockOnStopsAtStockEvenUnderConcurrency() throws Exception {
        Opened g = open(5, true);
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String phone = String.format("0102222%04d", i);
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), order(g, phone, 1)))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        int ok = 0;
        int soldOut = 0;
        for (Future<Integer> f : results) {
            int code = f.get();
            if (code == 200) {
                ok++;
            } else if (code == 409) {
                soldOut++;
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(5);
        assertThat(soldOut).isEqualTo(threads - 5);
        mvc.perform(get("/api/public/g/" + g.token()))
                .andExpect(jsonPath("$.products[0].soldOut").value(true))
                .andExpect(jsonPath("$.products[0].blockWhenSoldOut").value(true))
                .andExpect(jsonPath("$.products[0].remaining").doesNotExist());
        mvc.perform(get("/api/seller/group-buys/" + g.id()).cookie(g.seller()))
                .andExpect(jsonPath("$.products[0].remaining").value(0))
                .andExpect(jsonPath("$.products[0].soldOut").value(true));
    }

    @Test
    void blockOnAlsoLimitsSellerEditAndCanBeTurnedOff() throws Exception {
        Opened g = open(3, true);
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), order(g, "010-3300-0001", 2))).andExpect(status().isOk());
        // 남은 1 → 2개 주문은 막힘
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), order(g, "010-3300-0002", 2)))
                .andExpect(status().isConflict());
        // 판매자 주문 변경도 준비 수량 안에서만 (2 → 4 막힘)
        JsonNode first = read(mvc.perform(get("/api/seller/group-buys/" + g.id() + "/orders").cookie(g.seller())).andReturn()).get(0);
        Map<String, Object> edit = new HashMap<>();
        edit.put("items", List.of(Map.of("productId", g.productId(), "quantity", 4)));
        edit.put("depositorName", "고객0001");
        mvc.perform(withJson(put("/api/seller/group-buys/" + g.id() + "/orders/" + first.get("id").asLong()).cookie(g.seller()), edit))
                .andExpect(status().isConflict());

        // 판매자가 끄면 다시 막지 않는다
        Map<String, Object> body = groupBuyBody("선착순", List.of(Map.of("id", g.productId(), "name", "홍게", "unitPrice", 21900,
                "stockLimit", 3, "blockWhenSoldOut", false)));
        mvc.perform(withJson(put("/api/seller/group-buys/" + g.id()).cookie(g.seller()), body)).andExpect(status().isOk());
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), order(g, "010-3300-0003", 2))).andExpect(status().isOk());
        mvc.perform(get("/api/seller/group-buys/" + g.id()).cookie(g.seller()))
                .andExpect(jsonPath("$.products[0].remaining").value(-1))
                .andExpect(jsonPath("$.products[0].soldOut").value(false));
    }
}
