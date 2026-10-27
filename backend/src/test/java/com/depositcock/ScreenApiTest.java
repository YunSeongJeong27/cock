package com.depositcock;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.depositcock.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 화면 하나를 API 한 번으로 (2026-10-21): 공구 상세, 입금 대조, 은행 열 설정.
 * 합친 응답이 따로 읽던 값과 같은지 본다. 기록: docs/점검기록-2026-10-20.md 11장
 */
class ScreenApiTest extends IntegrationTest {

    record Opened(Cookie seller, long id, String token, long productId) {
        String base() {
            return "/api/seller/group-buys/" + id;
        }
    }

    private Opened open() throws Exception {
        Cookie seller = signup(uniqueEmail());
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller),
                groupBuyBody("화면", List.of(product("사과", 1000, 10))))).andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        return new Opened(seller, id, created.get("publicToken").asText(), created.get("products").get(0).get("id").asLong());
    }

    private void order(Opened g, String phone, String depositor, int quantity) throws Exception {
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", g.productId(), "quantity", quantity)));
        o.put("phone", phone);
        o.put("depositorName", depositor);
        o.put("acknowledgeWarnings", true);
        mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), o)).andExpect(status().isOk());
    }

    private JsonNode getJson(Opened g, String path) throws Exception {
        return read(mvc.perform(get(path).cookie(g.seller())).andExpect(status().isOk()).andReturn());
    }

    private void upload(Opened g, String content) throws Exception {
        mvc.perform(multipart(g.base() + "/deposits/uploads")
                        .file(new MockMultipartFile("file", "내역.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8)))
                        .cookie(g.seller()))
                .andExpect(status().isOk());
    }

    /** 공구 상세: 공구·대시보드·주문 목록이 한 응답에. 주문 목록·공구는 따로 읽은 것과 같다 */
    @Test
    void groupBuyDetailHasGroupBuyDashboardAndOrders() throws Exception {
        Opened g = open();
        order(g, "010-3000-0001", "김하나", 2);
        order(g, "010-3000-0002", "이두리", 1);

        JsonNode detail = getJson(g, g.base() + "/detail");
        assertThat(detail.get("groupBuy")).isEqualTo(getJson(g, g.base()));
        assertThat(detail.get("orders")).isEqualTo(getJson(g, g.base() + "/orders"));
        assertThat(detail.get("orders")).hasSize(2);
        JsonNode d = detail.get("dashboard");
        assertThat(d.get("orderCount").asInt()).isEqualTo(2);
        assertThat(d.get("totalQuantity").asInt()).isEqualTo(3);
        assertThat(d.get("totalAmount").asLong()).isEqualTo(3000);
        assertThat(d.get("unpaidCount").asInt()).isEqualTo(2);
        assertThat(detail.get("groupBuy").get("products").get(0).get("sold").asInt()).isEqualTo(3);
    }

    /** 입금 대조: 업로드 목록이 대조 응답에 함께 오고, 따로 읽은 업로드 목록과 같다 (확정에 쓰인 줄 수 포함) */
    @Test
    void matchIncludesUploads() throws Exception {
        Opened g = open();
        order(g, "010-3000-0011", "박세나", 1);
        assertThat(getJson(g, g.base() + "/deposits/match").get("uploads")).isEmpty();

        upload(g, "거래내용,거래금액,거래일자\n박세나,1000\n관련없음,500\n");
        upload(g, "거래내용,거래금액,거래일자\n또다른,700\n");
        JsonNode match = getJson(g, g.base() + "/deposits/match");
        long row = match.get("rows").get(0).get("rowId").asLong();
        long orderId = match.get("rows").get(0).get("order").get("orderId").asLong();
        assertThat(match.get("rows").get(0).get("depositorName").asText()).isEqualTo("박세나");
        mvc.perform(withJson(post(g.base() + "/deposits/confirm").cookie(g.seller()),
                Map.of("items", List.of(Map.of("rowId", row, "orderId", orderId))))).andExpect(status().isOk());

        JsonNode uploads = getJson(g, g.base() + "/deposits/match").get("uploads");
        assertThat(uploads).isEqualTo(getJson(g, g.base() + "/deposits/uploads"));
        assertThat(uploads).hasSize(2);
        // 최근 올린 것이 먼저
        assertThat(uploads.get(0).get("rowCount").asInt()).isEqualTo(1);
        assertThat(uploads.get(0).get("usedCount").asInt()).isZero();
        assertThat(uploads.get(1).get("rowCount").asInt()).isEqualTo(2);
        assertThat(uploads.get(1).get("usedCount").asInt()).isEqualTo(1);
    }

    /** 은행 헤더(컬럼) 설정: 은행별 헤더(컬럼)명·입금자 별칭이 한 응답에. 별칭은 따로 읽은 것과 같다 */
    @Test
    void bankSettingsHasBankHeadersAndAliases() throws Exception {
        Opened g = open();
        mvc.perform(withJson(put("/api/seller/bank-headers").cookie(g.seller()), Map.of("bankName", "우리은행",
                "nameColumn", "기재내용", "amountColumn", "맡기신금액", "dateColumn", "거래일시"))).andExpect(status().isOk());
        order(g, "010-3000-0021", "최다솜", 1);
        upload(g, "거래내용,거래금액,거래일자\n다솜맘,1000\n");
        JsonNode match = getJson(g, g.base() + "/deposits/match");
        long row = match.get("rows").get(0).get("rowId").asLong();
        long orderId = match.get("openOrders").get(0).get("orderId").asLong();
        mvc.perform(withJson(post(g.base() + "/deposits/confirm").cookie(g.seller()),
                Map.of("items", List.of(Map.of("rowId", row, "orderId", orderId))))).andExpect(status().isOk());

        JsonNode settings = getJson(g, "/api/seller/bank-settings");
        JsonNode banks = settings.get("banks");
        // 운영자 은행(토스뱅크 기본·KB국민은행) 다음에 판매자가 추가한 은행
        assertThat(banks.get(0).get("bankName").asText()).isEqualTo("토스뱅크");
        assertThat(banks.get(0).get("defaultBank").asBoolean()).isTrue();
        assertThat(banks.get(0).get("source").asText()).isEqualTo("SYSTEM");
        assertThat(banks.get(0).get("values").get("nameColumn").asText()).isEqualTo("거래내용");
        assertThat(banks.get(1).get("bankName").asText()).isEqualTo("KB국민은행");
        JsonNode mine = banks.get(banks.size() - 1);
        assertThat(mine.get("bankName").asText()).isEqualTo("우리은행");
        assertThat(mine.get("source").asText()).isEqualTo("CUSTOM");
        assertThat(mine.get("original").isNull()).isTrue();
        assertThat(settings.get("aliases")).isEqualTo(getJson(g, "/api/seller/aliases"));
        assertThat(settings.get("aliases").get(0).get("depositorName").asText()).isEqualTo("다솜맘");
    }
}
