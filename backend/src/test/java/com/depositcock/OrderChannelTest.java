package com.depositcock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.depositcock.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주문 받는 방법 (2026-10-15 사용자 결정): 공구를 만들 때 고른다.
 * 주문 링크만 = 닉네임 선택 입력, 주문 링크 + 댓글 = 닉네임 필수 (댓글엔 휴대폰 번호가 없어 닉네임으로만 같은 사람인지 비교).
 */
class OrderChannelTest extends IntegrationTest {

    record Opened(Cookie seller, long id, String token, long productId, Map<String, Object> body) {
    }

    private Opened open(String channel) throws Exception {
        Cookie seller = signup(uniqueEmail());
        Map<String, Object> body = groupBuyBody("공구", List.of(product("상품", 1000, null)));
        body.put("orderChannel", channel);
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body))
                .andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        return new Opened(seller, id, created.get("publicToken").asText(), created.get("products").get(0).get("id").asLong(), body);
    }

    private Map<String, Object> order(Opened g, String nickname) {
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", g.productId(), "quantity", 1)));
        o.put("phone", "010-9600-" + (1000 + (int) (Math.random() * 8999)));
        o.put("depositorName", "김채널");
        o.put("nickname", nickname);
        o.put("acknowledgeWarnings", true);
        return o;
    }

    @Test
    void orderChannelMustBeChosen() throws Exception {
        Cookie seller = signup(uniqueEmail());
        Map<String, Object> body = groupBuyBody("공구", List.of(product("상품", 1000, null)));
        body.remove("orderChannel");
        mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body)).andExpect(status().isBadRequest());
    }

    @Test
    void linkOnlyMakesNicknameOptionalAndBlocksCommentOrders() throws Exception {
        Opened g = open("LINK_ONLY");
        mvc.perform(get("/api/public/g/" + g.token())).andExpect(jsonPath("$.nicknameRequired").value(false));
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), order(g, null))).andExpect(status().isOk());
        // 적으면 저장된다 (칸은 열려 있음)
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), order(g, "꼭지맘"))).andExpect(status().isOk());
        mvc.perform(get("/api/seller/group-buys/" + g.id() + "/orders").cookie(g.seller()))
                .andExpect(jsonPath("$[1].nickname").value("꼭지맘"));
        // 댓글 주문은 만들지 않는다 (캡처 읽기도 장수를 쓰기 전에 막음)
        String comments = "/api/seller/group-buys/" + g.id() + "/comments";
        mvc.perform(withJson(post(comments + "/apply").cookie(g.seller()), Map.of("productId", g.productId())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("주문 링크만")));
        mvc.perform(multipart(comments + "/extract")
                        .file(new MockMultipartFile("files", "a.png", "image/png", new byte[]{1})).cookie(g.seller()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("주문 링크만")));
    }

    @Test
    void linkAndCommentRequiresNickname() throws Exception {
        Opened g = open("LINK_AND_COMMENT");
        mvc.perform(get("/api/public/g/" + g.token())).andExpect(jsonPath("$.nicknameRequired").value(true));
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), order(g, null))).andExpect(status().isBadRequest());
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), order(g, "꼭지맘"))).andExpect(status().isOk());
    }

    /** 댓글 주문이 있으면 '주문 링크만'으로 못 바꾼다 (댓글 집계가 숨겨져 관리할 수 없게 되므로) */
    @Test
    void cannotSwitchToLinkOnlyWhileCommentOrdersExist() throws Exception {
        Opened g = open("LINK_AND_COMMENT");
        String comments = "/api/seller/group-buys/" + g.id() + "/comments";
        mvc.perform(withJson(post(comments).cookie(g.seller()),
                Map.of("source", "TEXT", "comments", List.of(Map.of("author", "꼭지맘", "content", "1"))))).andExpect(status().isOk());
        mvc.perform(withJson(post(comments + "/apply").cookie(g.seller()), Map.of("productId", g.productId())))
                .andExpect(status().isOk());
        Map<String, Object> edit = new HashMap<>(g.body());
        edit.put("orderChannel", "LINK_ONLY");
        edit.put("products", List.of(Map.of("id", g.productId(), "name", "상품", "unitPrice", 1000)));
        mvc.perform(withJson(put("/api/seller/group-buys/" + g.id()).cookie(g.seller()), edit)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("댓글 주문이 있어")));
    }
}
