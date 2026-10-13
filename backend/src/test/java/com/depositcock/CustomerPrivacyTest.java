package com.depositcock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * 고객 화면 개인정보 (2026-10-14 보안 점검):
 * 남의 휴대폰 번호로 주문만 넣어서는 그 사람의 주문 목록·개인정보를 볼 수 없어야 한다.
 */
class CustomerPrivacyTest extends IntegrationTest {

    record Opened(String token, long productId) {
    }

    private Opened open() throws Exception {
        Cookie seller = signup(uniqueEmail());
        Map<String, Object> body = groupBuyBody("개인정보 공구", List.of(product("상품", 1000, null)));
        body.put("deliveryEnabled", true);
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body))
                .andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        return new Opened(created.get("publicToken").asText(), created.get("products").get(0).get("id").asLong());
    }

    private Map<String, Object> delivery(long productId, String phone, String depositor, String address) {
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", productId, "quantity", 1)));
        o.put("phone", phone);
        o.put("depositorName", depositor);
        o.put("acknowledgeWarnings", true);
        o.put("receiveMethod", "DELIVERY");
        o.put("recipientName", depositor);
        o.put("recipientPhone", phone);
        o.put("address", address);
        return o;
    }

    @Test
    void orderingWithSomeoneElsesPhoneDoesNotRevealTheirOrders() throws Exception {
        Opened g = open();
        String url = "/api/public/g/" + g.token();
        String cookieName = "dcg_" + g.token();

        // 피해자 주문 (자기 기기)
        MvcResult victim = mvc.perform(withJson(post(url + "/orders"),
                delivery(g.productId(), "010-6100-0001", "피해자이름", "서울시 피해자로 1"))).andExpect(status().isOk()).andReturn();
        Cookie victimDevice = victim.getResponse().getCookie(cookieName);
        int victimOrderNo = read(victim).get("orderNo").asInt();

        // 공격자: 피해자 번호로 주문 → 응답의 내 주문에는 자기 주문만
        MvcResult attack = mvc.perform(withJson(post(url + "/orders"),
                        delivery(g.productId(), "010-6100-0001", "아무개", "공격자 주소")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myOrders.length()").value(1))
                .andExpect(jsonPath("$.myOrders[0].depositorName").value("아무개"))
                .andReturn();
        Cookie attacker = attack.getResponse().getCookie(cookieName);

        // 피해자 주문에 "입금했어요"를 대신 누를 수 없다
        mvc.perform(post(url + "/orders/" + victimOrderNo + "/report-deposit").cookie(attacker))
                .andExpect(status().isNotFound());

        // 피해자가 자기 기기에서 다시 주문해도, 공격자 기기에는 피해자 주소가 채워지지 않는다
        mvc.perform(withJson(post(url + "/orders").cookie(victimDevice),
                delivery(g.productId(), "010-6100-0001", "피해자이름", "서울시 피해자로 2"))).andExpect(status().isOk());
        JsonNode attackerView = read(mvc.perform(get(url).cookie(attacker)).andReturn());
        assertThat(attackerView.get("myOrders").size()).isEqualTo(1);
        assertThat(attackerView.get("prefill").get("address").asText()).isEqualTo("공격자 주소");
        assertThat(attackerView.toString()).doesNotContain("피해자로").doesNotContain("피해자이름");

        // 피해자 기기는 자기 주문 2건과 최근 주소
        mvc.perform(get(url).cookie(victimDevice))
                .andExpect(jsonPath("$.myOrders.length()").value(2))
                .andExpect(jsonPath("$.prefill.address").value("서울시 피해자로 2"))
                .andExpect(jsonPath("$.prefill.limited").value(false));
    }

    @Test
    void cookieFromAnotherGroupBuyIsIgnored() throws Exception {
        Opened a = open();
        Opened b = open();
        MvcResult placed = mvc.perform(withJson(post("/api/public/g/" + a.token() + "/orders"),
                delivery(a.productId(), "010-6200-0001", "김가나", "주소 A"))).andReturn();
        Cookie cookieA = placed.getResponse().getCookie("dcg_" + a.token());
        // 같은 값을 B 공구 쿠키 이름으로 보내도 B 에서는 모르는 기기
        mvc.perform(get("/api/public/g/" + b.token()).cookie(new Cookie("dcg_" + b.token(), cookieA.getValue())))
                .andExpect(jsonPath("$.myOrders").isEmpty())
                .andExpect(jsonPath("$.prefill").doesNotExist());
    }

    @Test
    void cancelResponseShowsOnlyThisDevicesOrders() throws Exception {
        Opened g = open();
        String url = "/api/public/g/" + g.token();
        mvc.perform(withJson(post(url + "/orders"), delivery(g.productId(), "010-6300-0001", "이다라", "주소 1")))
                .andExpect(status().isOk());
        mvc.perform(withJson(post(url + "/orders"), delivery(g.productId(), "010-6300-0001", "이다라", "주소 2")))
                .andExpect(status().isOk());
        // 쿠키 없이 휴대폰 + 입금자명 + 주문번호로 취소 → 취소는 되지만 남은 주문 목록은 보여주지 않는다
        mvc.perform(withJson(post(url + "/cancel"), Map.of("phone", "010-6300-0001", "depositorName", "이다라", "orderNo", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myOrders").isEmpty());
    }

    @Test
    void hugeQuantityIsRejectedWithoutOverflow() throws Exception {
        Opened g = open();
        Map<String, Object> o = delivery(g.productId(), "010-6400-0001", "큰수", "주소");
        o.put("items", List.of(Map.of("productId", g.productId(), "quantity", 10_000)));
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), o)).andExpect(status().isBadRequest());
    }
}
