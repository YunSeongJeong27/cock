package com.depositcock;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.depositcock.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2026-10-19 다른 에이전트 점검에서 나온 문제 수정
 * - 환불 신청이 기다리는 주문은 판매자가 먼저 [취소하고 환불]·[취소 안 함]을 해야 함 (일반 취소·수령 체크·주문 변경 막음)
 * - 처리가 끝난 주문(취소 + 환불 필요 없음/환불 완료)은 "취소·환불 완료"로 모으고 더 바꿀 수 없음
 * - 입금하고 바로 취소한 고객: 입금 대조에서 [환불 필요로 표시]
 * - 엑셀 시트 이름 겹침, 픽업 끄기
 */
class OrderLifecycleFixesTest extends IntegrationTest {

    record Opened(Cookie seller, long id, String token, long productId, JsonNode body) {
        String pub() {
            return "/api/public/g/" + token;
        }

        String base() {
            return "/api/seller/group-buys/" + id;
        }
    }

    private Opened open(Map<String, Object> body) throws Exception {
        Cookie seller = signup(uniqueEmail());
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body))
                .andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        return new Opened(seller, id, created.get("publicToken").asText(), created.get("products").get(0).get("id").asLong(),
                created);
    }

    private Opened open() throws Exception {
        return open(groupBuyBody("주문 정리", List.of(product("상품", 1000, null))));
    }

    private Map<String, Object> order(Opened g, String phone, String depositor) {
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", g.productId(), "quantity", 1)));
        o.put("phone", phone);
        o.put("depositorName", depositor);
        o.put("acknowledgeWarnings", true);
        return o;
    }

    private void place(Opened g, String phone, String depositor) throws Exception {
        mvc.perform(withJson(post(g.pub() + "/orders"), order(g, phone, depositor))).andExpect(status().isOk());
    }

    private void requestRefund(Opened g, String phone, String depositor) throws Exception {
        mvc.perform(withJson(post(g.pub() + "/refund-request"), Map.of("phone", phone, "depositorName", depositor,
                "bankName", "국민", "accountNumber", "110123456789"))).andExpect(status().isOk());
    }

    private JsonNode sellerOrder(Opened g, int orderNo) throws Exception {
        for (JsonNode o : read(mvc.perform(get(g.base() + "/orders").cookie(g.seller())).andReturn())) {
            if (o.get("orderNo").asInt() == orderNo) {
                return o;
            }
        }
        throw new AssertionError("주문 없음: " + orderNo);
    }

    private String orderPath(Opened g, JsonNode o, String action) {
        return g.base() + "/orders/" + o.get("id").asLong() + "/" + action;
    }

    private void manualPaid(Opened g, JsonNode o) throws Exception {
        mvc.perform(withJson(post(orderPath(g, o, "manual-paid")).cookie(g.seller()),
                Map.of("depositorName", o.get("depositorName").asText(), "amount", 1000))).andExpect(status().isOk());
    }

    @Test
    void refundPendingOrderMustBeDecidedFirst() throws Exception {
        Opened g = open();
        place(g, "010-9100-0001", "김환불");
        requestRefund(g, "01091000001", "김환불");
        JsonNode o = sellerOrder(g, 1);

        // 일반 취소·수령 체크·주문 변경은 막힘 (전에는 일반 취소로 환불 기록이 사라지고, 수령 뒤 환불 취소가 됐음)
        mvc.perform(post(orderPath(g, o, "cancel")).cookie(g.seller())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("고객이 환불 신청한 주문입니다. 먼저 [취소하고 환불] 또는 [취소 안 함]을 눌러 주세요."));
        mvc.perform(withJson(post(orderPath(g, o, "picked-up")).cookie(g.seller()), Map.of("pickedUp", true)))
                .andExpect(status().isBadRequest());
        mvc.perform(withJson(put(g.base() + "/orders/" + o.get("id").asLong()).cookie(g.seller()), Map.of(
                "items", List.of(Map.of("productId", g.productId(), "quantity", 2)), "depositorName", "김환불")))
                .andExpect(status().isBadRequest());
        assertThat(sellerOrder(g, 1).get("refundBank").asText()).isEqualTo("국민");

        // [취소 안 함] 뒤에는 다시 할 수 있음
        mvc.perform(post(orderPath(g, o, "refund-request/reject")).cookie(g.seller())).andExpect(status().isOk());
        mvc.perform(withJson(post(orderPath(g, o, "picked-up")).cookie(g.seller()), Map.of("pickedUp", true)))
                .andExpect(status().isOk());
    }

    @Test
    void finishedOrdersAreLockedAndMarked() throws Exception {
        Opened g = open();
        place(g, "010-9100-0002", "이완료");
        place(g, "010-9100-0003", "박그냥");
        JsonNode paid = sellerOrder(g, 1);
        manualPaid(g, paid);
        mvc.perform(post(orderPath(g, paid, "cancel")).cookie(g.seller())).andExpect(status().isOk());
        // 취소 + 환불 필요: 아직 할 일이 남아 있어 완료 아님
        assertThat(sellerOrder(g, 1).get("finished").asBoolean()).isFalse();
        mvc.perform(post(orderPath(g, paid, "refunded")).cookie(g.seller())).andExpect(status().isOk());
        assertThat(sellerOrder(g, 1).get("finished").asBoolean()).isTrue();
        // 환불 완료 두 번, 환불 완료 뒤 입금 확정 취소는 막힘 (전에는 환불 기록이 지워졌음)
        mvc.perform(post(orderPath(g, paid, "refunded")).cookie(g.seller())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("이미 환불 완료된 주문입니다."));
        mvc.perform(post(orderPath(g, paid, "unconfirm-paid")).cookie(g.seller())).andExpect(status().isBadRequest());
        assertThat(sellerOrder(g, 1).get("refundedAt").isNull()).isFalse();

        // 입금 없이 취소 = 바로 완료, 살아 있는 주문은 완료 아님
        JsonNode plain = sellerOrder(g, 2);
        assertThat(plain.get("finished").asBoolean()).isFalse();
        mvc.perform(post(orderPath(g, plain, "cancel")).cookie(g.seller())).andExpect(status().isOk());
        assertThat(sellerOrder(g, 2).get("finished").asBoolean()).isTrue();
    }

    @Test
    void unconfirmKeepsRefundRequestedByCustomer() throws Exception {
        Opened g = open();
        place(g, "010-9100-0004", "최신청");
        place(g, "010-9100-0005", "정판매자");
        JsonNode requested = sellerOrder(g, 1);
        manualPaid(g, requested);
        requestRefund(g, "01091000004", "최신청");
        mvc.perform(post(orderPath(g, requested, "refund-request/approve")).cookie(g.seller())).andExpect(status().isOk());
        // 고객 환불 신청으로 취소된 주문: 입금 확정을 취소해도 환불 필요·계좌는 그대로 (환불은 고객 신청에서 온 것)
        mvc.perform(post(orderPath(g, requested, "unconfirm-paid")).cookie(g.seller())).andExpect(status().isOk());
        JsonNode after = sellerOrder(g, 1);
        assertThat(after.get("paid").asBoolean()).isFalse();
        assertThat(after.get("refundRequired").asBoolean()).isTrue();
        assertThat(after.get("refundAccount").asText()).isEqualTo("110123456789");
        assertThat(after.get("finished").asBoolean()).isFalse();

        // 판매자가 취소한 입금 확정 주문: 확정이 잘못이었다는 뜻이므로 환불 필요를 거둠 → 완료 (전과 같음)
        JsonNode bySeller = sellerOrder(g, 2);
        manualPaid(g, bySeller);
        mvc.perform(post(orderPath(g, bySeller, "cancel")).cookie(g.seller())).andExpect(status().isOk());
        mvc.perform(post(orderPath(g, bySeller, "unconfirm-paid")).cookie(g.seller())).andExpect(status().isOk());
        assertThat(sellerOrder(g, 2).get("refundRequired").asBoolean()).isFalse();
        assertThat(sellerOrder(g, 2).get("finished").asBoolean()).isTrue();
    }

    @Test
    void depositOfCancelledOrderCanBeMarkedRefundNeeded() throws Exception {
        Opened g = open();
        place(g, "010-9100-0006", "한바로");
        mvc.perform(withJson(post(g.pub() + "/cancel"), Map.of("phone", "01091000006", "depositorName", "한바로")))
                .andExpect(status().isOk());
        mvc.perform(multipart(g.base() + "/deposits/uploads").file(new MockMultipartFile("file", "거래내역.csv", "text/csv",
                "거래일시,적요,거래금액\n2026.10.19 10:00:00,한바로,1000\n".getBytes(StandardCharsets.UTF_8))).cookie(g.seller()))
                .andExpect(status().isOk());
        JsonNode row = read(mvc.perform(get(g.base() + "/deposits/match").cookie(g.seller())).andReturn()).get("rows").get(0);
        long orderId = sellerOrder(g, 1).get("id").asLong();
        assertThat(row.get("status").asText()).isEqualTo("UNRELATED");
        assertThat(row.get("note").asText()).contains("취소된 1번 주문의 입금자");
        assertThat(row.get("refundCheckOrderId").asLong()).isEqualTo(orderId);

        // [환불 필요로 표시] = 그 입금을 취소된 주문에 확정 → 입금 확정 + 환불 필요 (완료에서 빠짐)
        mvc.perform(withJson(post(g.base() + "/deposits/confirm").cookie(g.seller()),
                        Map.of("items", List.of(Map.of("rowId", row.get("rowId").asLong(), "orderId", orderId)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.confirmedCount").value(1));
        JsonNode o = sellerOrder(g, 1);
        assertThat(o.get("paid").asBoolean()).isTrue();
        assertThat(o.get("refundRequired").asBoolean()).isTrue();
        assertThat(o.get("finished").asBoolean()).isFalse();
        // 고객 화면에도 환불 예정으로 보임
        mvc.perform(withJson(post(g.pub() + "/identify"), Map.of("phone", "01091000006")))
                .andExpect(jsonPath("$.myOrders[0].refundRequired").value(true));
        mvc.perform(post(g.base() + "/orders/" + orderId + "/refunded").cookie(g.seller())).andExpect(status().isOk());
        assertThat(sellerOrder(g, 1).get("finished").asBoolean()).isTrue();
    }

    @Test
    void excelWorksWhenTitleIsCancelledSheetName() throws Exception {
        Opened g = open(groupBuyBody("취소된 주문", List.of(product("상품", 1000, null))));
        place(g, "010-9100-0007", "엑셀");
        place(g, "010-9100-0008", "취소");
        mvc.perform(post(orderPath(g, sellerOrder(g, 2), "cancel")).cookie(g.seller())).andExpect(status().isOk());
        byte[] xlsx = mvc.perform(get(g.base() + "/export.xlsx").cookie(g.seller())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(wb.getSheetName(0)).isEqualTo("취소된 주문");
            assertThat(wb.getSheetName(1)).isEqualTo("취소된 주문 (2)");
        }
    }

    /** 시트 이름에 쓸 수 없는 "/"는 빈칸이 아니라 "-"로 (2026-10-26) */
    @Test
    void excelSheetNameKeepsSlashAsDash() throws Exception {
        Opened g = open(groupBuyBody("10/30 목 픽업", List.of(product("상품", 1000, null))));
        place(g, "010-9100-0009", "엑셀");
        byte[] xlsx = mvc.perform(get(g.base() + "/export.xlsx").cookie(g.seller())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(wb.getSheetName(0)).isEqualTo("10-30 목 픽업");
        }
    }

    @Test
    void pickupCanBeTurnedOffWhenPickupOrdersExist() throws Exception {
        Map<String, Object> body = groupBuyBody("픽업 끄기", List.of(product("상품", 1000, null)));
        body.put("deliveryEnabled", true);
        body.put("pickupPoints", List.of(Map.of("name", "1지점")));
        Opened g = open(body);
        long point = g.body().get("pickupPoints").get(0).get("id").asLong();
        Map<String, Object> o = order(g, "010-9100-0009", "픽업");
        o.put("receiveMethod", "PICKUP");
        mvc.perform(withJson(post(g.pub() + "/orders"), o)).andExpect(status().isOk());

        // 픽업 끄고 배송만: 전에는 "주문이 있는 픽업 지점은 삭제할 수 없습니다"로 막혔음
        Map<String, Object> edit = groupBuyBody("픽업 끄기", List.of(Map.of("id", g.productId(), "name", "상품", "unitPrice", 1000)));
        edit.put("pickupEnabled", false);
        edit.put("deliveryEnabled", true);
        edit.put("pickupPoints", List.of());
        mvc.perform(withJson(put(g.base()).cookie(g.seller()), edit)).andExpect(status().isOk());
        JsonNode existing = sellerOrder(g, 1);
        assertThat(existing.get("receiveMethod").asText()).isEqualTo("PICKUP");
        assertThat(existing.get("pickupPointName").asText()).isEqualTo("1지점");
        // 이미 받은 픽업 주문은 판매자가 픽업 그대로 고칠 수 있다
        mvc.perform(withJson(put(g.base() + "/orders/" + existing.get("id").asLong()).cookie(g.seller()), Map.of(
                        "items", List.of(Map.of("productId", g.productId(), "quantity", 2)), "receiveMethod", "PICKUP",
                        "pickupPointId", point, "paymentMethod", "PREPAID", "depositorName", "픽업")))
                .andExpect(status().isOk());
        // 새 주문은 픽업을 못 고름
        o.put("phone", "010-9100-0010");
        mvc.perform(withJson(post(g.pub() + "/orders"), o)).andExpect(status().isBadRequest());
    }
}
