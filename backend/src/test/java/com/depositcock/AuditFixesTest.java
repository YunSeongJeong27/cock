package com.depositcock;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.depositcock.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2026-10-14 설계 문서 대조 점검에서 고친 것들.
 */
class AuditFixesTest extends IntegrationTest {

    record Opened(Cookie seller, long id, String token, JsonNode body) {
        long productId() {
            return body.get("products").get(0).get("id").asLong();
        }
    }

    private Opened open(Map<String, Object> body) throws Exception {
        Cookie seller = signup(uniqueEmail());
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body))
                .andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        return new Opened(seller, id, created.get("publicToken").asText(), created);
    }

    private Map<String, Object> order(long productId, String phone, String depositor) {
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", productId, "quantity", 1)));
        o.put("phone", phone);
        o.put("depositorName", depositor);
        o.put("acknowledgeWarnings", true);
        return o;
    }

    /** 댓글 주문은 픽업 결제 설정을 따른다: 배송을 켰어도 픽업이 현장결제만이면 현장결제 */
    @Test
    void commentOrdersFollowPickupPaymentSetting() throws Exception {
        Map<String, Object> body = groupBuyBody("현장결제 공구", List.of(product("상품", 1000, null)));
        body.put("orderChannel", "LINK_AND_COMMENT");
        body.put("pickupPrepayEnabled", false);
        body.put("pickupOnsiteEnabled", true);
        body.put("deliveryEnabled", true);
        Opened g = open(body);
        String base = "/api/seller/group-buys/" + g.id() + "/comments";
        mvc.perform(withJson(post(base).cookie(g.seller()),
                Map.of("source", "TEXT", "comments", List.of(Map.of("author", "꼭지맘", "content", "2"))))).andExpect(status().isOk());
        mvc.perform(withJson(post(base + "/apply").cookie(g.seller()), Map.of("productId", g.productId()))).andExpect(status().isOk());
        JsonNode orders = read(mvc.perform(get("/api/seller/group-buys/" + g.id() + "/orders").cookie(g.seller())).andReturn());
        assertThat(orders.get(0).get("paymentMethod").asText()).isEqualTo("ONSITE");
        assertThat(orders.get(0).get("receiveMethod").asText()).isEqualTo("PICKUP");
    }

    /** 읽지 못한 글자([?])가 남은 댓글은 저장할 수 없다 (그 닉네임이 고객 키가 되어 주문으로 만들어지므로) */
    @Test
    void commentsWithUnreadMarkCannotBeSaved() throws Exception {
        Opened g = open(groupBuyBody("댓글", List.of(product("상품", 1000, null))));
        mvc.perform(withJson(post("/api/seller/group-buys/" + g.id() + "/comments").cookie(g.seller()),
                        Map.of("source", "SCREENSHOT", "comments", List.of(Map.of("author", "꼭[?]맘", "content", "1")))))
                .andExpect(status().isBadRequest());
    }

    /** 주문이 고른 픽업 지점은 지울 수 없다 (지우면 그 주문의 픽업 지점이 조용히 비워짐) */
    @Test
    void pickupPointWithOrdersCannotBeDeleted() throws Exception {
        Map<String, Object> body = groupBuyBody("지점", List.of(product("상품", 1000, null)));
        body.put("pickupPoints", List.of(Map.of("name", "1지점"), Map.of("name", "2지점")));
        Opened g = open(body);
        long point1 = g.body().get("pickupPoints").get(0).get("id").asLong();
        long point2 = g.body().get("pickupPoints").get(1).get("id").asLong();
        Map<String, Object> o = order(g.productId(), "010-9400-0001", "가");
        o.put("pickupPointId", point1);
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), o)).andExpect(status().isOk());

        Map<String, Object> edit = groupBuyBody("지점", List.of(Map.of("id", g.productId(), "name", "상품", "unitPrice", 1000)));
        edit.put("pickupPoints", List.of(Map.of("id", point2, "name", "2지점")));
        mvc.perform(withJson(put("/api/seller/group-buys/" + g.id()).cookie(g.seller()), edit)).andExpect(status().isBadRequest());
        // 주문 없는 지점은 지울 수 있다
        edit.put("pickupPoints", List.of(Map.of("id", point1, "name", "1지점")));
        mvc.perform(withJson(put("/api/seller/group-buys/" + g.id()).cookie(g.seller()), edit)).andExpect(status().isOk());
    }

    /** 수령한 주문은 고객 화면에 취소 버튼이 나오지 않는다 (서버도 거절) */
    @Test
    void pickedUpOrderIsNotCancellable() throws Exception {
        Opened g = open(groupBuyBody("수령", List.of(product("상품", 1000, null))));
        MvcResult placed = mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"),
                order(g.productId(), "010-9500-0001", "가"))).andReturn();
        Cookie device = placed.getResponse().getCookie("dcg_" + g.token());
        long orderId = read(mvc.perform(get("/api/seller/group-buys/" + g.id() + "/orders").cookie(g.seller())).andReturn())
                .get(0).get("id").asLong();
        mvc.perform(withJson(post("/api/seller/group-buys/" + g.id() + "/orders/" + orderId + "/picked-up").cookie(g.seller()),
                Map.of("pickedUp", true))).andExpect(status().isOk());
        mvc.perform(get("/api/public/g/" + g.token()).cookie(device))
                .andExpect(jsonPath("$.myOrders[0].cancellable").value(false));
    }

    /** 같은 입금자명을 다른 번호가 써도 경고하지 않는다 (2026-10-18 사용자 결정: 경고는 같은 번호인지만 봄) */
    @Test
    void sameDepositorNameFromAnotherPhoneIsNotWarned() throws Exception {
        Opened g = open(groupBuyBody("경고", List.of(product("상품", 1000, null))));
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), order(g.productId(), "010-9600-0001", "김민지")))
                .andExpect(status().isOk());
        Map<String, Object> second = order(g.productId(), "010-9600-0002", "김민지");
        second.put("acknowledgeWarnings", false);
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(2));
    }

    /** 기본 주문 정리표에도 수령 방법·픽업 지점 열이 있다 (연락처는 없음) */
    @Test
    void basicExcelHasReceiveColumnsButNoContact() throws Exception {
        Opened g = open(groupBuyBody("엑셀", List.of(product("상품", 1000, null))));
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), order(g.productId(), "010-9700-0001", "가")))
                .andExpect(status().isOk());
        byte[] xlsx = mvc.perform(get("/api/seller/group-buys/" + g.id() + "/export.xlsx").cookie(g.seller()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Row header = wb.getSheetAt(0).getRow(0);
            List<String> names = new ArrayList<>();
            header.forEach(c -> names.add(c.getStringCellValue()));
            assertThat(names).contains("수령 방법", "픽업 지점").doesNotContain("휴대폰", "주소");
        }
    }

    /** 열 너비는 칸 내용 길이에 맞춘다 (한글 2칸, 영문·숫자 1칸, 여백 2칸, 최소 6칸·최대 60칸) */
    @Test
    void excelColumnWidthFollowsContent() throws Exception {
        Map<String, Object> body = groupBuyBody("엑셀 너비", List.of(product("상품", 1000, null)));
        body.put("deliveryEnabled", true);
        Opened g = open(body);
        String address = "서울특별시 강남구 테헤란로 123 상가동 4층"; // 한글 16자(32칸) + 공백·숫자 9칸 = 41칸
        Map<String, Object> delivery = order(g.productId(), "010-9700-0002", "나");
        delivery.put("receiveMethod", "DELIVERY");
        delivery.put("paymentMethod", "PREPAID");
        delivery.put("recipientName", "나");
        delivery.put("recipientPhone", "010-9700-0003");
        delivery.put("address", address);
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), delivery)).andExpect(status().isOk());
        byte[] xlsx = mvc.perform(get("/api/seller/group-buys/" + g.id() + "/export.xlsx").param("includeContact", "true")
                        .cookie(g.seller()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            Map<String, Integer> width = new HashMap<>();
            sheet.getRow(0).forEach(c -> width.put(c.getStringCellValue(), sheet.getColumnWidth(c.getColumnIndex()) / 256));
            assertThat(width.get("주소")).isEqualTo(41 + 2);
            assertThat(width.get("주문번호")).isEqualTo(8 + 2);          // 머리줄 "주문번호" 한글 4자
            assertThat(width.get("금액")).isEqualTo(5 + 2);              // 금액 서식 적용 뒤 "1,000"
        }
    }
}
