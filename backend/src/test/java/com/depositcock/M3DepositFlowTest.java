package com.depositcock;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.depositcock.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M3: 거래내역 업로드 → 대조 결과 → 확정 → 별칭 저장 → 다음 공구 자동 매칭, 확정 취소, 수령 체크, 엑셀, 입금 확인 문구.
 */
class M3DepositFlowTest extends IntegrationTest {

    private long openGroupBuy(Cookie seller, String title, int price) throws Exception {
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller),
                groupBuyBody(title, List.of(product("상품", price, null))))).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        return id;
    }

    private JsonNode groupBuy(Cookie seller, long id) throws Exception {
        return read(mvc.perform(get("/api/seller/group-buys/" + id).cookie(seller)).andReturn());
    }

    private void placeOrder(Cookie seller, long id, String phone, String depositor) throws Exception {
        JsonNode g = groupBuy(seller, id);
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", g.get("products").get(0).get("id").asLong(), "quantity", 1)));
        o.put("phone", phone);
        o.put("depositorName", depositor);
        o.put("acknowledgeWarnings", true);
        mvc.perform(withJson(post("/api/public/g/" + g.get("publicToken").asText() + "/orders"), o))
                .andExpect(status().isOk());
    }

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "거래내역.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void fullDepositFlow() throws Exception {
        Cookie seller = signup(uniqueEmail());
        long id = openGroupBuy(seller, "떡 공구", 13900);
        placeOrder(seller, id, "010-1000-0001", "김지영");
        placeOrder(seller, id, "010-1000-0002", "박서준");
        placeOrder(seller, id, "010-1000-0003", "이수진");

        String content = "거래일시,적요,거래금액\n"
                + "2025.11.09 10:00:00,김지영,13900\n"
                + "2025.11.09 10:05:00,박서준맘,13900\n"
                + "2025.11.09 10:06:00,이수진,14000\n"
                + "2025.11.09 10:07:00,관련없음,500\n"
                + "2025.11.09 10:08:00,카드결제,-3000\n";

        // 미리보기: 자동으로 찾은 열을 보여준다
        mvc.perform(multipart("/api/seller/group-buys/" + id + "/deposits/preview").file(csv(content)).cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parsed.autoDetected").value(true))
                .andExpect(jsonPath("$.parsed.mapping.nameColumn").value("적요"))
                .andExpect(jsonPath("$.parsed.mapping.amountColumn").value("거래금액"))
                .andExpect(jsonPath("$.parsed.deposits.length()").value(4));

        mvc.perform(multipart("/api/seller/group-buys/" + id + "/deposits/uploads").file(csv(content)).cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedCount").value(4));
        // 같은 파일 다시 올리면 중복은 건너뛴다
        mvc.perform(multipart("/api/seller/group-buys/" + id + "/deposits/uploads").file(csv(content)).cookie(seller))
                .andExpect(jsonPath("$.savedCount").value(0))
                .andExpect(jsonPath("$.duplicateCount").value(4));

        JsonNode match = read(mvc.perform(get("/api/seller/group-buys/" + id + "/deposits/match").cookie(seller))
                .andExpect(status().isOk()).andReturn());
        assertThat(match.get("matchedCount").asInt()).isEqualTo(1);
        Map<String, JsonNode> byName = new HashMap<>();
        match.get("rows").forEach(r -> byName.put(r.get("depositorName").asText(), r));
        assertThat(byName.get("김지영").get("status").asText()).isEqualTo("MATCHED");
        assertThat(byName.get("이수진").get("status").asText()).isEqualTo("SUGGESTED");
        assertThat(byName.get("이수진").get("amountDiff").asInt()).isEqualTo(100);
        // "박서준맘" 13900: 이름 근거 없음(주문서 입금자명 "박서준"과 정확히 같지 않음) + 13900 미입금이 2건(박서준, 이수진)
        // → 금액만으로 추천하지 않는다 (정리본 6-2장). 판매자가 직접 주문을 골라 확정한다.
        assertThat(byName.get("박서준맘").get("status").asText()).isEqualTo("UNRELATED");
        assertThat(byName.get("관련없음").get("status").asText()).isEqualTo("UNRELATED");

        // 확정 (박서준맘 → 박서준 주문)
        long rowPark = byName.get("박서준맘").get("rowId").asLong();
        long orderPark = match.get("unpaid").get(0).get("orderId").asLong();
        assertThat(match.get("unpaid").get(0).get("depositorName").asText()).isEqualTo("박서준");
        long rowKim = byName.get("김지영").get("rowId").asLong();
        long orderKim = byName.get("김지영").get("order").get("orderId").asLong();
        mvc.perform(withJson(post("/api/seller/group-buys/" + id + "/deposits/confirm").cookie(seller),
                        Map.of("items", List.of(Map.of("rowId", rowKim, "orderId", orderKim),
                                Map.of("rowId", rowPark, "orderId", orderPark)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmedCount").value(2));
        // 같은 입금으로 다시 확정 불가
        mvc.perform(withJson(post("/api/seller/group-buys/" + id + "/deposits/confirm").cookie(seller),
                        Map.of("items", List.of(Map.of("rowId", rowKim, "orderId", orderPark)))))
                .andExpect(jsonPath("$.confirmedCount").value(0));

        mvc.perform(get("/api/seller/aliases").cookie(seller))
                .andExpect(jsonPath("$[?(@.depositorName == '박서준맘')].customerLabel").value("휴대폰 010-1000-0002"));

        // 고객 화면에도 입금 확인됨
        mvc.perform(get("/api/seller/group-buys/" + id + "/dashboard").cookie(seller))
                .andExpect(jsonPath("$.paidCount").value(2))
                .andExpect(jsonPath("$.unpaidCount").value(1));

        // 다음 공구: 같은 고객(같은 휴대폰)이 "박서준맘"으로 입금 → 별칭으로 자동 매칭
        long next = openGroupBuy(seller, "다음 공구", 8900);
        placeOrder(seller, next, "010-1000-0002", "박서준");
        mvc.perform(multipart("/api/seller/group-buys/" + next + "/deposits/uploads")
                        .file(csv("입금자명,입금액\n박서준맘,8900\n")).cookie(seller))
                .andExpect(status().isOk());
        mvc.perform(get("/api/seller/group-buys/" + next + "/deposits/match").cookie(seller))
                .andExpect(jsonPath("$.rows[0].status").value("MATCHED"))
                .andExpect(jsonPath("$.rows[0].evidence").value("저장된 별칭"));

        // 확정 취소 → 입금 줄이 다시 대조 대상으로
        mvc.perform(post("/api/seller/group-buys/" + id + "/orders/" + orderPark + "/unconfirm-paid").cookie(seller))
                .andExpect(status().isOk());
        JsonNode after = read(mvc.perform(get("/api/seller/group-buys/" + id + "/deposits/match").cookie(seller)).andReturn());
        assertThat(after.get("used").size()).isEqualTo(1);

        // 입금 확정된 주문을 고객이 취소 → 환불 필요
        JsonNode g = groupBuy(seller, id);
        mvc.perform(withJson(post("/api/public/g/" + g.get("publicToken").asText() + "/cancel"),
                Map.of("orderNo", 1, "phone", "01010000001", "depositorName", "김지영"))).andExpect(status().isOk());
        JsonNode orders = read(mvc.perform(get("/api/seller/group-buys/" + id + "/orders").cookie(seller)).andReturn());
        assertThat(orders.get(0).get("refundRequired").asBoolean()).isTrue();
        mvc.perform(post("/api/seller/group-buys/" + id + "/orders/" + orders.get(0).get("id").asLong() + "/refunded")
                .cookie(seller)).andExpect(status().isOk());

        // 수동 입금 확정
        long orderLee = orders.get(2).get("id").asLong();
        mvc.perform(withJson(post("/api/seller/group-buys/" + id + "/orders/" + orderLee + "/manual-paid").cookie(seller),
                Map.of("depositorName", "이수진", "amount", 13900))).andExpect(status().isOk());

        // 입금 확인 문구
        mvc.perform(get("/api/seller/group-buys/" + id + "/announcement").cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].displayName").value("이수진"));

        // 주문 정리표 엑셀
        byte[] xlsx = mvc.perform(get("/api/seller/group-buys/" + id + "/export.xlsx?includeContact=true").cookie(seller))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = WorkbookFactory.create(new java.io.ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("주문번호");
            assertThat(sheet.getLastRowNum()).isEqualTo(3); // 헤더 + 주문 2건 + 합계
            assertThat(wb.getSheet("취소된 주문")).isNotNull();
        }
    }

    @Test
    void manualColumnMappingAndPreset() throws Exception {
        Cookie seller = signup(uniqueEmail());
        long id = openGroupBuy(seller, "수동 열", 5000);
        placeOrder(seller, id, "010-2000-0001", "홍길동");
        String content = "보낸 사람 이름,들어온 돈\n홍길동,5000\n";
        mvc.perform(multipart("/api/seller/group-buys/" + id + "/deposits/preview").file(csv(content)).cookie(seller))
                .andExpect(jsonPath("$.parsed.headerRowNo").value(0))
                .andExpect(jsonPath("$.parsed.message").exists());
        mvc.perform(multipart("/api/seller/group-buys/" + id + "/deposits/uploads").file(csv(content)).cookie(seller))
                .andExpect(status().isBadRequest());

        JsonNode preset = read(mvc.perform(withJson(post("/api/seller/bank-columns/presets").cookie(seller),
                Map.of("name", "우리동네은행", "nameColumn", "보낸 사람 이름", "amountColumn", "들어온 돈"))).andReturn());
        mvc.perform(multipart("/api/seller/group-buys/" + id + "/deposits/uploads").file(csv(content))
                        .param("presetId", preset.get("id").asText()).cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedCount").value(1))
                .andExpect(jsonPath("$.parsed.autoDetected").value(false));
        mvc.perform(get("/api/seller/group-buys/" + id + "/deposits/match").cookie(seller))
                .andExpect(jsonPath("$.rows[0].status").value("MATCHED"));

        // 다른 판매자는 프리셋을 쓸 수 없다
        Cookie other = signup(uniqueEmail());
        long otherId = openGroupBuy(other, "남의 공구", 1000);
        mvc.perform(multipart("/api/seller/group-buys/" + otherId + "/deposits/uploads").file(csv(content))
                .param("presetId", preset.get("id").asText()).cookie(other)).andExpect(status().isNotFound());
    }

    /** 날짜만 있는 파일: 같은 사람이 같은 날 같은 금액을 두 번 입금(주문 2건) → 같은 파일 안의 두 줄은 둘 다 저장 */
    @Test
    void sameDaySameAmountTwiceIsKept() throws Exception {
        Cookie seller = signup(uniqueEmail());
        long id = openGroupBuy(seller, "두 번 입금", 5000);
        // 같은 번호로 다시 주문하면 기존 주문이 바뀌므로(2026-10-18) 다른 번호, 같은 입금자명 두 사람
        placeOrder(seller, id, "010-7000-0001", "김지영");
        placeOrder(seller, id, "010-7000-0002", "김지영");
        String content = "거래일자,입금자명,입금액\n2025-11-01,김지영,5000\n2025-11-01,김지영,5000\n";
        mvc.perform(multipart("/api/seller/group-buys/" + id + "/deposits/uploads").file(csv(content)).cookie(seller))
                .andExpect(jsonPath("$.savedCount").value(2))
                .andExpect(jsonPath("$.duplicateCount").value(0));
        // 같은 파일을 다시 올리면 2줄 다 건너뜀, 3줄짜리(새 1줄 포함)를 올리면 1줄만 저장
        mvc.perform(multipart("/api/seller/group-buys/" + id + "/deposits/uploads").file(csv(content)).cookie(seller))
                .andExpect(jsonPath("$.savedCount").value(0))
                .andExpect(jsonPath("$.duplicateCount").value(2));
        mvc.perform(multipart("/api/seller/group-buys/" + id + "/deposits/uploads")
                        .file(csv(content + "2025-11-01,김지영,5000\n")).cookie(seller))
                .andExpect(jsonPath("$.savedCount").value(1));

        // 판매자가 직접 고를 수 있는 주문 목록(openOrders)에는 다른 입금에 추천된 주문도 들어 있다
        JsonNode match = read(mvc.perform(get("/api/seller/group-buys/" + id + "/deposits/match").cookie(seller)).andReturn());
        assertThat(match.get("openOrders")).hasSize(2);
    }
}
