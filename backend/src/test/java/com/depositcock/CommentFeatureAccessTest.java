package com.depositcock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.depositcock.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 댓글 주문 기능은 관리자가 연다 (2026-10-15 사용자 결정: 처음엔 주문 링크로만 운영하고 댓글 기능은 나중에 연다).
 * 쓸 수 있음 = 전체 공개 또는 판매자별 허용. 주문 링크·입금 대조는 상관없이 쓴다.
 */
class CommentFeatureAccessTest extends IntegrationTest {

    private Map<String, Object> body(String channel) {
        Map<String, Object> body = groupBuyBody("공구", List.of(product("상품", 1000, null)));
        body.put("orderChannel", channel);
        return body;
    }

    private long sellerId(Cookie seller) throws Exception {
        return read(mvc.perform(get("/api/me").cookie(seller)).andReturn()).get("id").asLong();
    }

    private void allowSeller(Cookie admin, long sellerId, boolean allow) throws Exception {
        Map<String, Object> update = new HashMap<>();
        update.put("plan", "TRIAL");
        update.put("suspended", false);
        update.put("commentFeatureEnabled", allow);
        mvc.perform(withJson(put("/api/admin/sellers/" + sellerId).cookie(admin), update)).andExpect(status().isOk());
    }

    @Test
    void closedByDefaultAndOpenedPerSellerByAdmin() throws Exception {
        setCommentFeatureOpen(false);
        Cookie seller = signup(uniqueEmail());
        mvc.perform(get("/api/me").cookie(seller)).andExpect(jsonPath("$.commentFeatureEnabled").value(false));

        // 주문 링크만 공구는 그대로 만든다
        JsonNode linkOnly = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body("LINK_ONLY")))
                .andExpect(status().isOk()).andReturn());
        // "주문 링크 + 댓글"은 못 고르고, 댓글 화면 API 도 막힌다
        mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body("LINK_AND_COMMENT")))
                .andExpect(status().isBadRequest());
        String comments = "/api/seller/group-buys/" + linkOnly.get("id").asLong() + "/comments";
        mvc.perform(get(comments).cookie(seller)).andExpect(status().isForbidden());
        mvc.perform(withJson(post(comments).cookie(seller),
                Map.of("source", "TEXT", "comments", List.of(Map.of("author", "꼭지맘", "content", "1")))))
                .andExpect(status().isForbidden());

        // 관리자가 이 판매자에게만 연다
        Cookie admin = login("admin@test.local", "admin1234");
        allowSeller(admin, sellerId(seller), true);
        mvc.perform(get("/api/me").cookie(seller)).andExpect(jsonPath("$.commentFeatureEnabled").value(true));
        JsonNode both = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body("LINK_AND_COMMENT")))
                .andExpect(status().isOk()).andReturn());
        String bothComments = "/api/seller/group-buys/" + both.get("id").asLong() + "/comments";
        mvc.perform(withJson(post(bothComments).cookie(seller),
                Map.of("source", "TEXT", "comments", List.of(Map.of("author", "꼭지맘", "content", "1")))))
                .andExpect(status().isOk());
        // 다른 판매자는 여전히 닫혀 있다
        Cookie other = signup(uniqueEmail());
        mvc.perform(get("/api/me").cookie(other)).andExpect(jsonPath("$.commentFeatureEnabled").value(false));
        mvc.perform(get("/api/admin/sellers").cookie(admin))
                .andExpect(status().isOk());

        // 다시 닫아도 이미 "링크 + 댓글"인 공구는 수정할 수 있다 (댓글 기능만 막힘)
        allowSeller(admin, sellerId(seller), false);
        Map<String, Object> edit = body("LINK_AND_COMMENT");
        edit.put("title", "제목만 바꿈");
        edit.put("products", List.of(Map.of("id", both.get("products").get(0).get("id").asLong(), "name", "상품", "unitPrice", 1000)));
        mvc.perform(withJson(put("/api/seller/group-buys/" + both.get("id").asLong()).cookie(seller), edit)).andExpect(status().isOk());
        mvc.perform(get(bothComments).cookie(seller)).andExpect(status().isForbidden());
    }

    @Test
    void adminCanOpenToEveryoneAndSellersCannotChangeIt() throws Exception {
        setCommentFeatureOpen(false);
        Cookie seller = signup(uniqueEmail());
        mvc.perform(get("/api/admin/settings").cookie(seller)).andExpect(status().isForbidden());
        mvc.perform(withJson(put("/api/admin/settings").cookie(seller), Map.of("commentFeatureOpen", true)))
                .andExpect(status().isForbidden());

        Cookie admin = login("admin@test.local", "admin1234");
        mvc.perform(get("/api/admin/settings").cookie(admin)).andExpect(jsonPath("$.commentFeatureOpen").value(false));
        mvc.perform(withJson(put("/api/admin/settings").cookie(admin), Map.of("commentFeatureOpen", true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.commentFeatureOpen").value(true));
        mvc.perform(get("/api/me").cookie(seller)).andExpect(jsonPath("$.commentFeatureEnabled").value(true));
        mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body("LINK_AND_COMMENT"))).andExpect(status().isOk());
    }
}
