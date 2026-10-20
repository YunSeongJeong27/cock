package com.depositcock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.depositcock.security.CsrfHeaderFilter;
import com.depositcock.seller.SellerService;
import com.depositcock.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 보안 점검: CSRF 헤더, 다른 판매자 데이터 접근 차단(전 기능), 사용 제한 중 탈퇴, 관리자 계정 부트스트랩.
 */
class SecurityReviewTest extends IntegrationTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    SellerService sellerService;

    @Test
    void writeRequestsWithoutHeaderAreRejected() throws Exception {
        // 직접 만든 MockMvc 는 서블릿 필터가 자동으로 붙지 않아서 CsrfHeaderFilter 를 명시적으로 붙인다
        MockMvc raw = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(CsrfHeaderFilter.class))
                .apply(springSecurity()).build();
        raw.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", uniqueEmail(), "password", "password123", "displayName", "x"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        raw.perform(post("/api/auth/signup").header(CsrfHeaderFilter.HEADER, "depositcock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", uniqueEmail(), "password", "password123", "displayName", "x"))))
                .andExpect(status().isOk());
        // 읽기 요청은 헤더 없이도 된다 (다운로드 링크 등)
        raw.perform(get("/api/public/g/없는토큰")).andExpect(status().isNotFound());
    }

    /** 다른 판매자의 공구·주문·입금·댓글·프리셋·별칭에 접근할 수 없다 */
    @Test
    void tenantIsolationAcrossFeatures() throws Exception {
        Cookie alice = signup(uniqueEmail());
        Cookie bob = signup(uniqueEmail());
        JsonNode ag = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(alice),
                groupBuyBody("앨리스", List.of(product("상품", 1000, null))))).andReturn());
        long aliceGb = ag.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + aliceGb + "/open").cookie(alice));
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", ag.get("products").get(0).get("id").asLong(), "quantity", 1)));
        o.put("phone", "010-6000-0001");
        o.put("depositorName", "가");
        o.put("acknowledgeWarnings", true);
        mvc.perform(withJson(post("/api/public/g/" + ag.get("publicToken").asText() + "/orders"), o));
        mvc.perform(multipart("/api/seller/group-buys/" + aliceGb + "/deposits/uploads")
                .file(new MockMultipartFile("file", "a.csv", "text/csv", "입금자명,입금액\n가,1000\n".getBytes()))
                .cookie(alice)).andExpect(status().isOk());
        JsonNode match = read(mvc.perform(get("/api/seller/group-buys/" + aliceGb + "/deposits/match").cookie(alice)).andReturn());
        long aliceRow = match.get("rows").get(0).get("rowId").asLong();
        long aliceOrder = match.get("rows").get(0).get("order").get("orderId").asLong();
        mvc.perform(withJson(post("/api/seller/group-buys/" + aliceGb + "/deposits/confirm").cookie(alice),
                Map.of("items", List.of(Map.of("rowId", aliceRow, "orderId", aliceOrder))))).andExpect(status().isOk());
        JsonNode alicePreset = read(mvc.perform(withJson(post("/api/seller/bank-columns/presets").cookie(alice),
                Map.of("name", "앨리스은행", "nameColumn", "a", "amountColumn", "b"))).andReturn());
        JsonNode aliceAliases = read(mvc.perform(get("/api/seller/aliases").cookie(alice)).andReturn());
        assertThat(aliceAliases).isNotEmpty();

        String a = "/api/seller/group-buys/" + aliceGb;
        mvc.perform(get(a + "/orders").cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(post(a + "/orders/" + aliceOrder + "/picked-up").cookie(bob)
                .contentType(MediaType.APPLICATION_JSON).content("{\"pickedUp\":true}")).andExpect(status().isNotFound());
        mvc.perform(post(a + "/orders/" + aliceOrder + "/cancel").cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(post(a + "/orders/" + aliceOrder + "/unconfirm-paid").cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(get(a + "/deposits/match").cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(get(a + "/deposits/uploads").cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(get(a + "/comments").cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(get(a + "/announcement").cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(get(a + "/export.xlsx").cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(get(a + "/dashboard").cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(multipart(a + "/deposits/screenshots/extract")
                .file(new MockMultipartFile("files", "x.png", "image/png", new byte[]{1})).cookie(bob))
                .andExpect(status().isNotFound());

        // 밥이 자기 공구 경로에 앨리스의 주문·입금 id 를 넣어도 안 된다
        JsonNode bg = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(bob),
                groupBuyBody("밥", List.of(product("상품", 1000, null))))).andReturn());
        String b = "/api/seller/group-buys/" + bg.get("id").asLong();
        mvc.perform(post(b + "/orders/" + aliceOrder + "/cancel").cookie(bob)).andExpect(status().isNotFound());
        JsonNode confirm = read(mvc.perform(withJson(post(b + "/deposits/confirm").cookie(bob),
                Map.of("items", List.of(Map.of("rowId", aliceRow, "orderId", aliceOrder))))).andReturn());
        assertThat(confirm.get("confirmedCount").asInt()).isZero();

        mvc.perform(delete("/api/seller/bank-columns/presets/" + alicePreset.get("id").asLong()).cookie(bob))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/seller/aliases/" + aliceAliases.get(0).get("id").asLong()).cookie(bob))
                .andExpect(status().isNotFound());
        assertThat(read(mvc.perform(get("/api/seller/bank-columns/presets").cookie(bob)).andReturn())).isEmpty();
        assertThat(read(mvc.perform(get("/api/seller/aliases").cookie(bob)).andReturn())).isEmpty();
    }

    @Test
    void suspendedSellerCanStillWithdraw() throws Exception {
        String email = uniqueEmail();
        Cookie seller = signup(email);
        Cookie admin = login("admin@test.local", "admin1234");
        long sellerId = 0;
        for (JsonNode s : read(mvc.perform(get("/api/admin/sellers").cookie(admin)).andReturn())) {
            if (s.get("email").asText().equals(email)) {
                sellerId = s.get("id").asLong();
            }
        }
        Map<String, Object> suspend = new HashMap<>();
        suspend.put("plan", "TRIAL");
        suspend.put("suspended", true);
        mvc.perform(withJson(put("/api/admin/sellers/" + sellerId).cookie(admin), suspend)).andExpect(status().isOk());
        mvc.perform(withJson(put("/api/seller/profile").cookie(seller), Map.of("displayName", "x")))
                .andExpect(status().isForbidden());
        mvc.perform(withJson(post("/api/seller/withdraw").cookie(seller), Map.of("password", "password123")))
                .andExpect(status().isOk());
    }

    /** 관리자 이메일로 일반 판매자가 먼저 가입해 있으면 관리자로 바꾸지 않는다 */
    @Test
    void adminBootstrapDoesNotPromoteExistingSeller() throws Exception {
        String email = uniqueEmail();
        signup(email);
        assertThat(sellerService.ensureAdmin(email, "whatever123")).isFalse();
        Cookie cookie = login(email, "password123");
        mvc.perform(get("/api/admin/sellers").cookie(cookie)).andExpect(status().isForbidden());
        assertThat(sellerService.ensureAdmin("admin@test.local", "admin1234")).isTrue();
    }
}
