package com.depositcock.support;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 통합 테스트 공통: H2 메모리 DB + MockMvc. 테스트마다 DB 를 비우지 않으므로 이메일·데이터는 테스트마다 다르게 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestMockMvcConfig.class)
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper objectMapper;

    private static int sequence = 0;

    protected static synchronized String uniqueEmail() {
        sequence++;
        return "seller" + System.nanoTime() + "_" + sequence + "@test.local";
    }

    protected String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    protected JsonNode read(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return body.isEmpty() ? objectMapper.nullNode() : objectMapper.readTree(body);
    }

    /** 가입 후 로그인 쿠키를 돌려준다. */
    protected Cookie signup(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", "password123", "displayName", "공구장"))))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getCookie("DC_TOKEN");
    }

    protected Cookie login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getCookie("DC_TOKEN");
    }

    protected MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        return builder.contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    /** 기본 공구 설정 요청 본문. 필요한 값만 바꿔 쓴다. */
    protected static Map<String, Object> groupBuyBody(String title, List<Map<String, Object>> products) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("title", title);
        body.put("description", "설명");
        body.put("closeAt", null);
        body.put("bankName", "토스뱅크");
        body.put("accountNumber", "1000-0000-0000");
        body.put("accountHolder", "홍길동");
        body.put("pickupEnabled", true);
        body.put("pickupPrepayEnabled", true);
        body.put("pickupOnsiteEnabled", false);
        body.put("deliveryEnabled", false);
        body.put("shippingFeeType", "NONE");
        body.put("shippingFee", 0);
        body.put("nicknameFieldEnabled", false);
        body.put("unitAddressFieldEnabled", false);
        body.put("kakaoInquiryUrl", null);
        body.put("adminNames", null);
        body.put("products", products);
        body.put("pickupPoints", List.of());
        return body;
    }

    protected static Map<String, Object> product(String name, int price, Integer stockLimit) {
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("name", name);
        p.put("unitPrice", price);
        p.put("stockLimit", stockLimit);
        p.put("perOrderLimit", null);
        return p;
    }
}
