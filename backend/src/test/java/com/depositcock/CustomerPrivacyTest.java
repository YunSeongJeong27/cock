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

    record Opened(Cookie seller, long id, String token, long productId) {
    }

    private Opened open() throws Exception {
        Cookie seller = signup(uniqueEmail());
        Map<String, Object> body = groupBuyBody("개인정보 공구", List.of(product("상품", 1000, null)));
        body.put("deliveryEnabled", true);
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body))
                .andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        return new Opened(seller, id, created.get("publicToken").asText(), created.get("products").get(0).get("id").asLong());
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

    /**
     * 같은 번호로 다시 주문하면 기존 주문이 바뀐다 (2026-10-18). 단 휴대폰 번호 + 입금자명이 기존 주문과 같아야 한다 (2026-10-19).
     * 입금자명이 다르면 막히고 기존 주문 내용도 보여주지 않는다. 둘 다 아는 남은 바꿀 수 있지만(받아들인 위험),
     * 피해자의 휴대폰·주소는 다른 기기로 나가지 않으며, 판매자 기록에 "다른 기기에서 바꿈"이 남는다.
     */
    @Test
    void reorderWithSomeoneElsesPhoneNeedsTheirDepositorNameAndDoesNotRevealPersonalInfo() throws Exception {
        Opened g = open();
        String url = "/api/public/g/" + g.token();
        String cookieName = "dcg_" + g.token();

        // 피해자 주문 (자기 기기)
        MvcResult victim = mvc.perform(withJson(post(url + "/orders"),
                delivery(g.productId(), "010-6100-0001", "피해자이름", "서울시 피해자로 1"))).andExpect(status().isOk()).andReturn();
        Cookie victimDevice = victim.getResponse().getCookie(cookieName);
        int victimOrderNo = read(victim).get("orderNo").asInt();

        // 다른 기기에서 피해자 번호 + 다른 입금자명: 막힘, 기존 주문 내용은 응답에 없다
        Map<String, Object> attack = delivery(g.productId(), "010-6100-0001", "아무개", "공격자 주소");
        MvcResult blocked = mvc.perform(withJson(post(url + "/orders"), attack))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(blocked.getResponse().getContentAsString()).doesNotContain("피해자").doesNotContain("replaceOrder");
        attack.put("replaceOrderNo", victimOrderNo);
        mvc.perform(withJson(post(url + "/orders"), attack)).andExpect(status().isBadRequest());

        // 입금자명까지 맞히면 경고(409) 후 수정됨. 경고·응답에 피해자 주소·휴대폰은 없다
        attack.put("depositorName", "피해자 이름"); // 띄어쓰기는 무시
        attack.remove("replaceOrderNo");
        MvcResult warned = mvc.perform(withJson(post(url + "/orders"), attack))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.replaceOrderNo").value(victimOrderNo))
                .andReturn();
        assertThat(warned.getResponse().getContentAsString()).doesNotContain("피해자로").doesNotContain("010-6100-0001")
                .doesNotContain("01061000001");
        attack.put("replaceOrderNo", victimOrderNo);
        MvcResult replaced = mvc.perform(withJson(post(url + "/orders"), attack))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(victimOrderNo))
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.finalOrder.address").value("공격자 주소"))
                .andReturn();
        Cookie attacker = replaced.getResponse().getCookie(cookieName);
        assertThat(replaced.getResponse().getContentAsString()).doesNotContain("피해자로");

        // 바꾼 기기는 주문 상태는 보지만(휴대폰 번호 전체로 "내 주문 찾기"와 같은 기준), 휴대폰·주소 자동 채움은 없다
        JsonNode attackerView = read(mvc.perform(get(url).cookie(attacker)).andReturn());
        assertThat(attackerView.get("myOrders").size()).isEqualTo(1);
        assertThat(attackerView.get("prefill").get("limited").asBoolean()).isTrue();
        assertThat(attackerView.get("prefill").get("phone").isNull()).isTrue();
        assertThat(attackerView.toString()).doesNotContain("피해자로");

        // 피해자 기기: 주문이 바뀐 것이 보인다 (고객 변경 시각)
        mvc.perform(get(url).cookie(victimDevice))
                .andExpect(jsonPath("$.myOrders.length()").value(1))
                .andExpect(jsonPath("$.myOrders[0].customerChangedAt").isNotEmpty())
                .andExpect(jsonPath("$.myOrders[0].changedAt").isEmpty());

        // 판매자: 고객 변경 기록 + 다른 기기 표시
        JsonNode orders = read(mvc.perform(get("/api/seller/group-buys/" + g.id() + "/orders").cookie(g.seller())).andReturn());
        JsonNode change = orders.get(0).get("changes").get(0);
        assertThat(change.get("changedBy").asText()).isEqualTo("CUSTOMER");
        assertThat(change.get("summary").asText()).contains("배송지 변경").contains("다른 기기");
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
