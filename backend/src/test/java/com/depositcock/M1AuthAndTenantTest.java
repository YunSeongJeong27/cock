package com.depositcock;

import java.util.List;
import java.util.Map;

import com.depositcock.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M1: 가입/로그인, 멀티테넌트(다른 판매자 데이터 접근 차단), 관리자 기능.
 */
class M1AuthAndTenantTest extends IntegrationTest {

    @Test
    void signupLoginMe() throws Exception {
        String email = uniqueEmail();
        signup(email);
        Cookie token = login(email, "password123");
        mvc.perform(get("/api/me").cookie(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.plan").value("TRIAL"))
                .andExpect(jsonPath("$.expired").value(false));
    }

    @Test
    void wrongPasswordAndNoToken() throws Exception {
        String email = uniqueEmail();
        signup(email);
        mvc.perform(withJson(post("/api/auth/login"), Map.of("email", email, "password", "wrong-password")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/seller/group-buys")).andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateEmailRejected() throws Exception {
        String email = uniqueEmail();
        signup(email);
        mvc.perform(withJson(post("/api/auth/signup"),
                        Map.of("email", email.toUpperCase(), "password", "password123", "displayName", "x")))
                .andExpect(status().isConflict());
    }

    @Test
    void otherSellerCannotAccessGroupBuy() throws Exception {
        Cookie alice = signup(uniqueEmail());
        Cookie bob = signup(uniqueEmail());
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(alice),
                        groupBuyBody("앨리스 공구", List.of(product("사과", 10000, null)))))
                .andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();

        mvc.perform(get("/api/seller/group-buys/" + id).cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(withJson(put("/api/seller/group-buys/" + id).cookie(bob),
                groupBuyBody("탈취", List.of(product("사과", 1, null))))).andExpect(status().isNotFound());
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/seller/group-buys/" + id + "/orders").cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/seller/group-buys/" + id + "/export.xlsx").cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/seller/group-buys/" + id + "/deposits/match").cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/seller/group-buys/" + id).cookie(bob)).andExpect(status().isNotFound());

        JsonNode bobList = read(mvc.perform(get("/api/seller/group-buys").cookie(bob)).andReturn());
        assertThat(bobList).isEmpty();
        mvc.perform(get("/api/seller/group-buys/" + id).cookie(alice)).andExpect(status().isOk());
    }

    @Test
    void adminOnlyEndpointsAndSuspend() throws Exception {
        String email = uniqueEmail();
        Cookie seller = signup(email);
        mvc.perform(get("/api/admin/sellers").cookie(seller)).andExpect(status().isForbidden());

        Cookie admin = login("admin@test.local", "admin1234");
        JsonNode sellers = read(mvc.perform(get("/api/admin/sellers").cookie(admin)).andExpect(status().isOk()).andReturn());
        long sellerId = 0;
        for (JsonNode s : sellers) {
            if (s.get("email").asText().equals(email)) {
                sellerId = s.get("id").asLong();
            }
        }
        assertThat(sellerId).isPositive();

        // 사용 제한: 조회는 되고 쓰기는 막힌다
        Map<String, Object> suspend = new java.util.HashMap<>();
        suspend.put("plan", "TRIAL");
        suspend.put("paidUntil", null);
        suspend.put("trialEndsAt", null);
        suspend.put("suspended", true);
        suspend.put("adminMemo", "입금 안 됨");
        mvc.perform(withJson(put("/api/admin/sellers/" + sellerId).cookie(admin), suspend)).andExpect(status().isOk());
        mvc.perform(get("/api/seller/group-buys").cookie(seller)).andExpect(status().isOk());
        mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller),
                groupBuyBody("막힘", List.of(product("사과", 1000, null))))).andExpect(status().isForbidden());

        // 유료 전환 + 만료일
        Map<String, Object> paid = new java.util.HashMap<>(suspend);
        paid.put("plan", "PAID");
        paid.put("paidUntil", "2099-12-31");
        paid.put("suspended", false);
        mvc.perform(withJson(put("/api/admin/sellers/" + sellerId).cookie(admin), paid)).andExpect(status().isOk());
        mvc.perform(get("/api/me").cookie(seller))
                .andExpect(jsonPath("$.plan").value("PAID"))
                .andExpect(jsonPath("$.suspended").value(false));
        mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller),
                groupBuyBody("다시 됨", List.of(product("사과", 1000, null))))).andExpect(status().isOk());
    }
}
