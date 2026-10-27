package com.depositcock;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.depositcock.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 같은 요청이 동시에 들어올 때 (2026-10-14 점검): 공구 행 잠금으로 한 줄로 세워서
 * 재고가 두 번 복구되거나 입금 1건이 두 주문에 확정되지 않아야 한다.
 */
class ConcurrencyTest extends IntegrationTest {

    record Opened(Cookie seller, long id, String token, long productId) {
    }

    private Opened open(int stock) throws Exception {
        Cookie seller = signup(uniqueEmail());
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller),
                groupBuyBody("동시성", List.of(product("한정", 1000, stock))))).andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        return new Opened(seller, id, created.get("publicToken").asText(), created.get("products").get(0).get("id").asLong());
    }

    private void order(Opened g, String phone, String depositor, int qty) throws Exception {
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", g.productId(), "quantity", qty)));
        o.put("phone", phone);
        o.put("depositorName", depositor);
        o.put("acknowledgeWarnings", true);
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), o)).andExpect(status().isOk());
    }

    private List<Integer> runTogether(List<Callable<Integer>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : futures) {
            codes.add(f.get());
        }
        pool.shutdown();
        return codes;
    }

    private JsonNode orders(Opened g) throws Exception {
        return read(mvc.perform(get("/api/seller/group-buys/" + g.id() + "/orders").cookie(g.seller())).andReturn());
    }

    @Test
    void cancellingTheSameOrderManyTimesAtOnceRestoresStockOnce() throws Exception {
        Opened g = open(10);
        order(g, "010-8100-0001", "가", 2);
        order(g, "010-8100-0002", "나", 8); // 재고 다 팔림
        long orderId = orders(g).get(0).get("id").asLong();
        String sellerCancel = "/api/seller/group-buys/" + g.id() + "/orders/" + orderId + "/cancel";
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> mvc.perform(post(sellerCancel).cookie(g.seller())).andReturn().getResponse().getStatus());
            tasks.add(() -> mvc.perform(withJson(post("/api/public/g/" + g.token() + "/cancel"),
                    Map.of("phone", "010-8100-0001", "depositorName", "가"))).andReturn().getResponse().getStatus());
        }
        List<Integer> codes = runTogether(tasks);
        assertThat(codes.stream().filter(c -> c == 200).count()).isEqualTo(1);
        // 2개만 돌아와야 한다 (두 번 복구되면 4 이상)
        JsonNode view = read(mvc.perform(get("/api/seller/group-buys/" + g.id()).cookie(g.seller())).andReturn());
        assertThat(view.get("products").get(0).get("remaining").asInt()).isEqualTo(2);
    }

    @Test
    void oneDepositCannotBeConfirmedToTwoOrdersAtOnce() throws Exception {
        Opened g = open(100);
        order(g, "010-8200-0001", "가", 1);
        order(g, "010-8200-0002", "나", 1);
        mvc.perform(multipart("/api/seller/group-buys/" + g.id() + "/deposits/uploads")
                        .file(new MockMultipartFile("file", "a.csv", "text/csv",
                                "거래내용,거래금액,거래일자\n다른이름,1000\n또다른이름,1000\n".getBytes(StandardCharsets.UTF_8)))
                        .cookie(g.seller()))
                .andExpect(status().isOk());
        JsonNode match = read(mvc.perform(get("/api/seller/group-buys/" + g.id() + "/deposits/match").cookie(g.seller())).andReturn());
        long row1 = match.get("rows").get(0).get("rowId").asLong();
        long row2 = match.get("rows").get(1).get("rowId").asLong();
        JsonNode orders = orders(g);
        long order1 = orders.get(0).get("id").asLong();
        long order2 = orders.get(1).get("id").asLong();
        String confirm = "/api/seller/group-buys/" + g.id() + "/deposits/confirm";

        // 같은 입금(row1)을 두 주문에 동시에 + 다른 입금(row2)을 같은 주문(order1)에 동시에
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (long[] pair : new long[][]{{row1, order1}, {row1, order2}, {row2, order1}}) {
            tasks.add(() -> {
                var res = mvc.perform(withJson(post(confirm).cookie(g.seller()),
                        Map.of("items", List.of(Map.of("rowId", pair[0], "orderId", pair[1]))))).andReturn().getResponse();
                // 409(동시에 같은 별칭을 만들려다 충돌 등)는 아무것도 확정하지 않은 것으로 센다
                assertThat(res.getStatus()).as(res.getContentAsString()).isIn(200, 409);
                return res.getStatus() == 200 ? objectMapper.readTree(res.getContentAsString()).get("confirmedCount").asInt() : 0;
            });
        }
        List<Integer> confirmed = runTogether(tasks);
        JsonNode after = orders(g);
        long paidOrders = 0;
        for (JsonNode o : after) {
            if (o.get("paid").asBoolean()) {
                paidOrders++;
            }
        }
        // 입금 1건은 주문 1건에만, 주문 1건에는 입금 1건만
        JsonNode used = read(mvc.perform(get("/api/seller/group-buys/" + g.id() + "/deposits/match").cookie(g.seller())).andReturn());
        assertThat(confirmed.stream().mapToInt(Integer::intValue).sum()).isEqualTo((int) paidOrders);
        assertThat(paidOrders).isBetween(1L, 2L);
        Map<Long, Long> ordersByRow = new HashMap<>();
        for (JsonNode u : used.get("used")) {
            assertThat(ordersByRow.put(u.get("rowId").asLong(), u.get("orderId").asLong())).isNull();
        }
        assertThat(ordersByRow.values()).doesNotHaveDuplicates();
        assertThat(ordersByRow).hasSize((int) paidOrders);
    }
}
