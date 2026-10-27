package com.depositcock;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.depositcock.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 입금 확정 기록 (2026-10-14 구조 정리): 확정 1건 = 기록 1줄, 확정을 취소해도 기록은 남는다.
 * "주문 1건에 살아 있는 확정 1건", "입금 1건은 주문 1건에만"은 DB 유일 제약으로도 지킨다.
 */
class PaymentHistoryTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void confirmationsAreKeptAsHistory() throws Exception {
        Cookie seller = signup(uniqueEmail());
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller),
                groupBuyBody("이력", List.of(product("상품", 1000, null))))).andExpect(status().isOk()).andReturn());
        long gb = created.get("id").asLong();
        String base = "/api/seller/group-buys/" + gb;
        mvc.perform(post(base + "/open").cookie(seller)).andExpect(status().isOk());
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", created.get("products").get(0).get("id").asLong(), "quantity", 1)));
        o.put("phone", "010-9800-0001");
        o.put("depositorName", "김기록");
        o.put("acknowledgeWarnings", true);
        mvc.perform(withJson(post("/api/public/g/" + created.get("publicToken").asText() + "/orders"), o)).andExpect(status().isOk());
        long orderId = read(mvc.perform(get(base + "/orders").cookie(seller)).andReturn()).get(0).get("id").asLong();

        // 1) 수동 확정 → 취소
        mvc.perform(withJson(post(base + "/orders/" + orderId + "/manual-paid").cookie(seller),
                Map.of("depositorName", "김기록", "amount", 1000))).andExpect(status().isOk());
        mvc.perform(post(base + "/orders/" + orderId + "/unconfirm-paid").cookie(seller)).andExpect(status().isOk());
        // 2) 거래내역으로 다시 확정 → 취소 → 업로드 삭제 (입금 줄이 없어져도 기록은 남음)
        long uploadId = read(mvc.perform(multipart(base + "/deposits/uploads")
                .file(new MockMultipartFile("file", "a.csv", "text/csv", "거래내용,거래금액,거래일자\n김기록,1000\n".getBytes(StandardCharsets.UTF_8)))
                .cookie(seller)).andExpect(status().isOk()).andReturn()).get("uploadId").asLong();
        long rowId = read(mvc.perform(get(base + "/deposits/match").cookie(seller)).andReturn()).get("rows").get(0).get("rowId").asLong();
        mvc.perform(withJson(post(base + "/deposits/confirm").cookie(seller),
                Map.of("items", List.of(Map.of("rowId", rowId, "orderId", orderId))))).andExpect(status().isOk());

        // 살아 있는 확정이 있는 동안 같은 주문·같은 입금에 확정을 하나 더 넣으면 DB 가 막는다
        assertThatThrownBy(() -> jdbc.update("INSERT INTO deposit_confirmation (order_id, deposit_row_id, manual, depositor_name, "
                + "amount, confirmed_at, active_order_id, active_deposit_row_id) VALUES (?, NULL, TRUE, 'x', 1, CURRENT_TIMESTAMP, ?, NULL)",
                orderId, orderId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO deposit_confirmation (order_id, deposit_row_id, manual, depositor_name, "
                + "amount, confirmed_at, active_order_id, active_deposit_row_id) VALUES (?, ?, FALSE, 'x', 1, CURRENT_TIMESTAMP, NULL, ?)",
                orderId, rowId, rowId)).isInstanceOf(DataIntegrityViolationException.class);

        mvc.perform(post(base + "/orders/" + orderId + "/unconfirm-paid").cookie(seller)).andExpect(status().isOk());
        mvc.perform(delete(base + "/deposits/uploads/" + uploadId).cookie(seller)).andExpect(status().isOk());

        JsonNode order = read(mvc.perform(get(base + "/orders").cookie(seller)).andReturn()).get(0);
        assertThat(order.get("paid").asBoolean()).isFalse();
        JsonNode payments = order.get("payments");
        assertThat(payments.size()).isEqualTo(2);
        assertThat(payments.get(0).get("manual").asBoolean()).isTrue();
        assertThat(payments.get(1).get("manual").asBoolean()).isFalse(); // 입금 줄이 지워져도 "거래내역 확정"으로 남음
        assertThat(payments.get(0).get("cancelledAt").isNull()).isFalse();
        assertThat(payments.get(1).get("cancelledAt").isNull()).isFalse();
        assertThat(payments.get(1).get("depositorName").asText()).isEqualTo("김기록");
    }
}
