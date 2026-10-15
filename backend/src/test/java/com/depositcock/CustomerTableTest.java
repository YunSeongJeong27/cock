package com.depositcock;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 판매자별 고객 테이블 (2026-10-14): 휴대폰 번호는 customer 에만 있고, 주문·기기·별칭은 customer_id 로 가리킨다.
 */
class CustomerTableTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    record Opened(long id, String token, long productId) {
    }

    private Opened open(Cookie seller, String title) throws Exception {
        return open(seller, title, "LINK_ONLY");
    }

    private Opened open(Cookie seller, String title, String orderChannel) throws Exception {
        Map<String, Object> body = groupBuyBody(title, List.of(product("상품", 1000, null)));
        body.put("orderChannel", orderChannel);
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body))
                .andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        return new Opened(id, created.get("publicToken").asText(), created.get("products").get(0).get("id").asLong());
    }

    private int order(Opened g, String phone, String depositor) throws Exception {
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", g.productId(), "quantity", 1)));
        o.put("phone", phone);
        o.put("depositorName", depositor);
        o.put("acknowledgeWarnings", true);
        return mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), o)).andReturn().getResponse().getStatus();
    }

    private long customerIdOfFirstOrder(Cookie seller, Opened g) throws Exception {
        return read(mvc.perform(get("/api/seller/group-buys/" + g.id() + "/orders").cookie(seller)).andReturn())
                .get(0).get("customerId").asLong();
    }

    private int customersWithPhone(String phone) {
        return jdbc.queryForObject("select count(*) from customer where phone = ?", Integer.class, phone);
    }

    @Test
    void samePhoneIsOneCustomerPerSellerAndSeparateBetweenSellers() throws Exception {
        Cookie alice = signup(uniqueEmail());
        Cookie bob = signup(uniqueEmail());
        Opened a1 = open(alice, "앨리스 1차");
        Opened a2 = open(alice, "앨리스 2차");
        Opened b1 = open(bob, "밥 1차");
        assertThat(order(a1, "010-9900-0001", "가")).isEqualTo(200);
        assertThat(order(a2, "010-9900-0001", "가")).isEqualTo(200);
        assertThat(order(b1, "010-9900-0001", "가")).isEqualTo(200);
        // 같은 판매자의 두 공구 → 같은 고객, 다른 판매자 → 다른 고객 (판매자 사이에 공유하지 않음)
        assertThat(customerIdOfFirstOrder(alice, a1)).isEqualTo(customerIdOfFirstOrder(alice, a2));
        assertThat(customerIdOfFirstOrder(bob, b1)).isNotEqualTo(customerIdOfFirstOrder(alice, a1));
        assertThat(customersWithPhone("01099000001")).isEqualTo(2);
        // 휴대폰 번호는 고객 테이블에만 있다 (주문 테이블에 휴대폰 칸이 없음)
        Integer phoneColumns = jdbc.queryForObject("select count(*) from information_schema.columns "
                + "where lower(table_name) = 'customer_order' and lower(column_name) in ('phone', 'customer_key')", Integer.class);
        assertThat(phoneColumns).isZero();
    }

    @Test
    void deletingGroupBuyRemovesCustomersThatHaveNoOtherOrders() throws Exception {
        Cookie seller = signup(uniqueEmail());
        Opened g1 = open(seller, "1차");
        Opened g2 = open(seller, "2차");
        order(g1, "010-9910-0001", "남는고객");
        order(g2, "010-9910-0001", "남는고객");
        order(g2, "010-9910-0002", "지워질고객");
        mvc.perform(delete("/api/seller/group-buys/" + g2.id()).cookie(seller)).andExpect(status().isOk());
        assertThat(customersWithPhone("01099100001")).isEqualTo(1); // 1차에 주문이 남아 있음
        assertThat(customersWithPhone("01099100002")).isZero();     // 이 공구에서만 주문 → 파기
    }

    @Test
    void firstOrdersWithSamePhoneAtTheSameTimeMakeOneCustomer() throws Exception {
        Cookie seller = signup(uniqueEmail());
        List<Opened> groups = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            groups.add(open(seller, "동시 " + i));
        }
        ExecutorService pool = Executors.newFixedThreadPool(groups.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (Opened g : groups) {
            Callable<Integer> task = () -> {
                start.await();
                return order(g, "010-9920-0001", "동시고객");
            };
            futures.add(pool.submit(task));
        }
        start.countDown();
        for (Future<Integer> f : futures) {
            assertThat(f.get()).isEqualTo(200);
        }
        pool.shutdown();
        assertThat(customersWithPhone("01099200001")).isEqualTo(1);
    }

    @Test
    void customerHasEitherPhoneOrCommentNickname() throws Exception {
        Cookie seller = signup(uniqueEmail());
        long sellerId = read(mvc.perform(get("/api/me").cookie(seller)).andReturn()).get("id").asLong();
        assertThatThrownBy(() -> jdbc.update("insert into customer (seller_id, phone, comment_nickname, created_at) "
                + "values (?, '01099300001', '닉', CURRENT_TIMESTAMP)", sellerId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into customer (seller_id, phone, comment_nickname, created_at) "
                + "values (?, NULL, NULL, CURRENT_TIMESTAMP)", sellerId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 댓글 고객은 닉네임으로 하나만 생긴다. 다시 반영해도 이미 반영된 손님은 건드리지 않는다 (2026-10-15 사용자 결정) */
    @Test
    void commentCustomersAreKeptByNicknameAcrossReapply() throws Exception {
        Cookie seller = signup(uniqueEmail());
        Opened g = open(seller, "댓글", "LINK_AND_COMMENT");
        String base = "/api/seller/group-buys/" + g.id() + "/comments";
        mvc.perform(withJson(post(base).cookie(seller), Map.of("source", "TEXT", "comments",
                List.of(Map.of("author", "꼭지맘", "content", "1"), Map.of("author", "망고맘", "content", "2")))))
                .andExpect(status().isOk());
        mvc.perform(withJson(post(base + "/apply").cookie(seller), Map.of("productId", g.productId()))).andExpect(status().isOk());
        // 망고맘 댓글을 지우고 다시 반영 → 망고맘 주문은 자동으로 지우지 않고 "집계에 없는 주문"으로 알려 준다
        JsonNode comments = read(mvc.perform(get(base).cookie(seller)).andReturn()).get("comments");
        for (JsonNode c : comments) {
            if (c.get("authorName").asText().equals("망고맘")) {
                mvc.perform(delete(base + "/" + c.get("id").asLong()).cookie(seller)).andExpect(status().isOk());
            }
        }
        mvc.perform(withJson(post(base + "/apply").cookie(seller), Map.of("productId", g.productId())))
                .andExpect(jsonPath("$.createdCount").value(0))
                .andExpect(jsonPath("$.reviewCount").value(1));
        JsonNode stale = read(mvc.perform(get(base).cookie(seller)).andReturn()).get("staleOrders");
        assertThat(stale).hasSize(1);
        assertThat(stale.get(0).get("nickname").asText()).isEqualTo("망고맘");
        long sellerId = read(mvc.perform(get("/api/me").cookie(seller)).andReturn()).get("id").asLong();
        for (String nick : List.of("꼭지맘", "망고맘")) {
            assertThat(jdbc.queryForObject("select count(*) from customer where seller_id = ? and comment_nickname = ?",
                    Integer.class, sellerId, nick)).isEqualTo(1);
        }
    }
}
