package com.depositcock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.depositcock.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2026-10-20 전체 점검 2차(다른 에이전트)를 검토하면서 더 고친 것.
 */
class AuditReviewTest extends IntegrationTest {

    /** 로그인 없음·깨진 쿠키(401), 관리자 아닌 판매자의 관리자 주소(403)도 {detail, code}. 전에는 본문 없음 */
    @Test
    void securityErrorsAnswerWithCode() throws Exception {
        mvc.perform(get("/api/seller/group-buys"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"))
                .andExpect(jsonPath("$.detail").value("로그인이 필요합니다."));
        mvc.perform(get("/api/seller/group-buys").cookie(new Cookie("DC_TOKEN", "broken")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        mvc.perform(get("/api/admin/sellers").cookie(signup(uniqueEmail())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_ONLY"));
    }

    /** 같은 픽업 지점 그대로 수량만 바꾸면 수령 변경 기록이 없고, 같은 내용 다시 저장은 "바뀐 내용 없음" (지점은 id 로 비교) */
    @Test
    void samePickupPointIsNotRecordedAsChange() throws Exception {
        Cookie seller = signup(uniqueEmail());
        Map<String, Object> body = groupBuyBody("지점 두 곳", List.of(product("사과", 1000, null)));
        body.put("pickupPoints", List.of(Map.of("name", "A점", "info", ""), Map.of("name", "B점", "info", "")));
        JsonNode g = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body)).andReturn());
        long id = g.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        long productId = g.get("products").get(0).get("id").asLong();
        long pointA = g.get("pickupPoints").get(0).get("id").asLong();

        Map<String, Object> order = new HashMap<>();
        order.put("items", List.of(Map.of("productId", productId, "quantity", 1)));
        order.put("phone", "010-7300-0001");
        order.put("depositorName", "김지점");
        order.put("receiveMethod", "PICKUP");
        order.put("pickupPointId", pointA);
        order.put("paymentMethod", "PREPAID");
        order.put("acknowledgeWarnings", true);
        mvc.perform(withJson(post("/api/public/g/" + g.get("publicToken").asText() + "/orders"), order))
                .andExpect(status().isOk());
        long orderId = read(mvc.perform(get("/api/seller/group-buys/" + id + "/orders").cookie(seller)).andReturn())
                .get(0).get("id").asLong();

        Map<String, Object> edit = new HashMap<>();
        edit.put("items", List.of(Map.of("productId", productId, "quantity", 2)));
        edit.put("receiveMethod", "PICKUP");
        edit.put("pickupPointId", pointA);
        edit.put("paymentMethod", "PREPAID");
        edit.put("depositorName", "김지점");
        String path = "/api/seller/group-buys/" + id + "/orders/" + orderId;
        mvc.perform(withJson(put(path).cookie(seller), edit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].summary").value(not(containsString("수령"))));
        mvc.perform(withJson(put(path).cookie(seller), edit))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NO_CHANGE"));
    }
}
