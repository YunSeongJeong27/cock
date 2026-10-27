package com.depositcock;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.depositcock.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 같은 입금 계좌를 쓰는 공구끼리 입금 공유 (2026-10-27 사용자 결정 ①), 거래내역서 계좌번호 확인, 은행 자동 선택, 1시간 규칙.
 */
class DepositPoolTest extends IntegrationTest {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private Clock clock;

    private long openGroupBuy(Cookie seller, String title, int price, String bankName, String account) throws Exception {
        Map<String, Object> body = groupBuyBody(title, List.of(product("상품", price, null)));
        body.put("bankName", bankName);
        body.put("accountNumber", account);
        long id = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body)).andReturn()).get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        return id;
    }

    private void placeOrder(Cookie seller, long id, String phone, String depositor) throws Exception {
        JsonNode g = read(mvc.perform(get("/api/seller/group-buys/" + id).cookie(seller)).andReturn());
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", g.get("products").get(0).get("id").asLong(), "quantity", 1)));
        o.put("phone", phone);
        o.put("depositorName", depositor);
        o.put("acknowledgeWarnings", true);
        mvc.perform(withJson(post("/api/public/g/" + g.get("publicToken").asText() + "/orders"), o)).andExpect(status().isOk());
    }

    private static MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "거래내역.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode upload(Cookie seller, long id, String content) throws Exception {
        return read(mvc.perform(multipart("/api/seller/group-buys/" + id + "/deposits/uploads").file(csv(content)).cookie(seller))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode match(Cookie seller, long id) throws Exception {
        return read(mvc.perform(get("/api/seller/group-buys/" + id + "/deposits/match").cookie(seller))
                .andExpect(status().isOk()).andReturn());
    }

    private static Map<String, JsonNode> byName(JsonNode rows) {
        Map<String, JsonNode> map = new HashMap<>();
        rows.forEach(r -> map.put(r.get("depositorName").asText(), r));
        return map;
    }

    @Test
    void groupBuysWithSameAccountShareDeposits() throws Exception {
        Cookie seller = signup(uniqueEmail());
        long a = openGroupBuy(seller, "A 공구", 3000, "토스뱅크", "1000-1111-2222");
        long b = openGroupBuy(seller, "B 공구", 5000, "토스뱅크", "1000 1111 2222");
        long c = openGroupBuy(seller, "C 공구", 5000, "토스뱅크", "9999-1111-2222");
        placeOrder(seller, a, "010-8100-0001", "김에이");
        placeOrder(seller, b, "010-8100-0002", "박비");
        String file = "거래 일시,적요,거래 금액\n2026-10-27,김에이,3000\n2026-10-27,박비,5000\n2026-10-27,무관,700\n";
        assertThat(upload(seller, a, file).get("savedCount").asInt()).isEqualTo(3);

        JsonNode ma = match(seller, a);
        assertThat(ma.get("sharedGroupBuys")).hasSize(1);
        assertThat(ma.get("sharedGroupBuys").get(0).asText()).isEqualTo("B 공구");
        assertThat(byName(ma.get("rows"))).containsOnlyKeys("김에이", "무관");
        JsonNode otherInA = ma.get("otherRows").get(0);
        assertThat(otherInA.get("depositorName").asText()).isEqualTo("박비");
        assertThat(otherInA.get("status").asText()).isEqualTo("MATCHED");
        assertThat(otherInA.get("order").get("groupBuyTitle").asText()).isEqualTo("B 공구");
        assertThat(ma.get("matchedCount").asInt()).isEqualTo(1);

        // B 화면: 같은 거래내역 (B 에서 다시 올리면 전부 중복)
        JsonNode mb = match(seller, b);
        assertThat(mb.get("uploads")).hasSize(1);
        assertThat(byName(mb.get("rows"))).containsOnlyKeys("박비", "무관");
        assertThat(mb.get("otherRows").get(0).get("depositorName").asText()).isEqualTo("김에이");
        JsonNode again = upload(seller, b, file);
        assertThat(again.get("savedCount").asInt()).isZero();
        assertThat(again.get("duplicateCount").asInt()).isEqualTo(3);

        // A 화면에서 B 주문을 확정해도 B 에 반영된다
        mvc.perform(withJson(post("/api/seller/group-buys/" + a + "/deposits/confirm").cookie(seller), Map.of("items",
                        List.of(Map.of("rowId", otherInA.get("rowId").asLong(), "orderId", otherInA.get("order").get("orderId").asLong())))))
                .andExpect(jsonPath("$.confirmedCount").value(1));
        mb = match(seller, b);
        assertThat(mb.get("used")).hasSize(1);
        assertThat(mb.get("used").get(0).get("depositorName").asText()).isEqualTo("박비");
        assertThat(match(seller, a).get("used")).isEmpty();
        mvc.perform(get("/api/seller/group-buys/" + b + "/detail").cookie(seller)).andExpect(jsonPath("$.dashboard.paidCount").value(1));

        // 처리 완료도 같이
        long unrelatedRow = byName(mb.get("rows")).get("무관").get("rowId").asLong();
        mvc.perform(post("/api/seller/group-buys/" + b + "/deposits/rows/" + unrelatedRow + "/handled").cookie(seller))
                .andExpect(status().isOk());
        assertThat(match(seller, a).get("handled").get(0).get("depositorName").asText()).isEqualTo("무관");

        // 다른 계좌 공구에는 안 보이고, 그 공구 화면에서 확정할 수도 없다
        JsonNode mc = match(seller, c);
        assertThat(mc.get("uploads")).isEmpty();
        assertThat(mc.get("rows")).isEmpty();
        assertThat(mc.get("sharedGroupBuys")).isEmpty();
        long kimRow = byName(ma.get("rows")).get("김에이").get("rowId").asLong();
        long kimOrder = byName(ma.get("rows")).get("김에이").get("order").get("orderId").asLong();
        mvc.perform(withJson(post("/api/seller/group-buys/" + c + "/deposits/confirm").cookie(seller), Map.of("items",
                List.of(Map.of("rowId", kimRow, "orderId", kimOrder))))).andExpect(jsonPath("$.confirmedCount").value(0));

        // B 를 지우면 B 주문에 확정된 입금 줄만 지우고 나머지는 A 에 남는다
        mvc.perform(delete("/api/seller/group-buys/" + b).cookie(seller)).andExpect(status().isOk());
        ma = match(seller, a);
        assertThat(ma.get("sharedGroupBuys")).isEmpty();
        assertThat(byName(ma.get("rows"))).containsOnlyKeys("김에이");
        assertThat(ma.get("otherRows")).isEmpty();
        assertThat(ma.get("handled")).hasSize(1);
        // A 까지 지우면 계좌의 거래내역 전체를 지운다: 같은 계좌로 새 공구를 만들어도 안 보임
        mvc.perform(delete("/api/seller/group-buys/" + a).cookie(seller)).andExpect(status().isOk());
        long d = openGroupBuy(seller, "D 공구", 3000, "토스뱅크", "1000-1111-2222");
        assertThat(match(seller, d).get("uploads")).isEmpty();
        assertThat(match(seller, d).get("handled")).isEmpty();
    }

    /** 공구 입금 계좌를 바꿔도 그 계좌를 이 공구만 썼으면 올린 거래내역이 따라온다 */
    @Test
    void uploadsFollowAccountChangeWhenNotShared() throws Exception {
        Cookie seller = signup(uniqueEmail());
        long a = openGroupBuy(seller, "혼자 공구", 3000, "토스뱅크", "1000-2222-3333");
        upload(seller, a, "거래 일시,적요,거래 금액\n2026-10-27,누구,3000\n");
        Map<String, Object> product = product("상품", 3000, null);
        product.put("id", read(mvc.perform(get("/api/seller/group-buys/" + a).cookie(seller)).andReturn())
                .get("products").get(0).get("id").asLong());
        Map<String, Object> body = groupBuyBody("혼자 공구", List.of(product));
        body.put("accountNumber", "1000-2222-4444");
        mvc.perform(withJson(put("/api/seller/group-buys/" + a).cookie(seller), body)).andExpect(status().isOk());
        assertThat(match(seller, a).get("uploads")).hasSize(1);
    }

    /** 거래내역서 위쪽의 계좌번호와 공구 입금 계좌 비교 (가린 자리는 빼고) */
    @Test
    void statementAccountIsComparedWithGroupBuyAccount() throws Exception {
        Cookie seller = signup(uniqueEmail());
        long a = openGroupBuy(seller, "계좌 확인", 3000, "토스뱅크", "1000-3333-4444");
        String header = "거래 일시,적요,거래 금액\n2026-10-27,누구,3000\n";
        JsonNode same = upload(seller, a, "계좌번호,1000-****-4444\n" + header);
        assertThat(same.get("account").get("check").asText()).isEqualTo("SAME");
        assertThat(same.get("account").get("notice").isNull()).isTrue();

        JsonNode different = read(mvc.perform(multipart("/api/seller/group-buys/" + a + "/deposits/preview")
                .file(csv("계좌번호: 2000-****-4444\n" + header)).cookie(seller)).andExpect(status().isOk()).andReturn());
        assertThat(different.get("account").get("check").asText()).isEqualTo("DIFFERENT");
        assertThat(different.get("account").get("statementAccount").asText()).isEqualTo("2000-****-4444");
        assertThat(different.get("account").get("notice").asText()).contains("2000-****-4444", "1000-3333-4444", "다릅니다");

        JsonNode unknown = read(mvc.perform(multipart("/api/seller/group-buys/" + a + "/deposits/preview")
                .file(csv(header)).cookie(seller)).andExpect(status().isOk()).andReturn());
        assertThat(unknown.get("account").get("check").asText()).isEqualTo("UNKNOWN");
        assertThat(unknown.get("account").get("notice").asText()).contains("\"계좌번호\"");
    }

    /** 공구 입금 계좌의 은행 이름으로 헤더(컬럼) 설정을 고른다 ("국민은행" → KB국민은행) */
    @Test
    void bankIsChosenFromGroupBuyAccountBank() throws Exception {
        Cookie seller = signup(uniqueEmail());
        long kb = openGroupBuy(seller, "국민 공구", 3000, "국민은행", "123456-00-123789");
        placeOrder(seller, kb, "010-8200-0001", "홍길동");
        assertThat(match(seller, kb).get("bankName").asText()).isEqualTo("KB국민은행");
        String kbCsv = "계좌번호,123456-**-***789\n거래일시,적요,보낸분/받는분,출금액,입금액\n"
                + "2026.10.26 10:30:00,전자금융,홍길동,0,3000\n2026.10.26 11:00:00,체크카드,편의점,5000,0\n";
        // KB국민은행은 PDF 기본값만 있다: CSV(엑셀·CSV)를 올리면 "아직 없습니다" → 판매자가 엑셀 값을 넣으면 읽힌다
        mvc.perform(multipart("/api/seller/group-buys/" + kb + "/deposits/uploads").file(csv(kbCsv)).cookie(seller))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STATEMENT_BANK_TYPE_MISSING"));
        mvc.perform(withJson(put("/api/seller/bank-headers").cookie(seller), Map.of("bankName", "KB국민은행", "fileType", "EXCEL",
                "nameColumn", "보낸분/받는분", "amountColumn", "입금액", "dateColumn", "거래일시", "accountLabel", "계좌번호",
                "typeColumn", "적요"))).andExpect(status().isOk()).andExpect(jsonPath("$.source").value("CUSTOM"));
        JsonNode uploaded = upload(seller, kb, kbCsv);
        assertThat(uploaded.get("fileType").asText()).isEqualTo("EXCEL");
        assertThat(uploaded.get("bankName").asText()).isEqualTo("KB국민은행");
        assertThat(uploaded.get("savedCount").asInt()).isEqualTo(1);
        assertThat(uploaded.get("account").get("check").asText()).isEqualTo("SAME");
        assertThat(match(seller, kb).get("rows").get(0).get("status").asText()).isEqualTo("MATCHED");

        // PDF 를 올리면 같은 은행의 PDF 헤더(컬럼)명으로 읽는다 (은행·파일 종류를 안 골라도)
        byte[] pdf;
        try (var in = getClass().getClassLoader().getResourceAsStream("statements/kb-statement.pdf")) {
            pdf = in.readAllBytes();
        }
        mvc.perform(multipart("/api/seller/group-buys/" + kb + "/deposits/preview")
                        .file(new MockMultipartFile("file", "KB거래내역.pdf", "application/pdf", pdf)).cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankName").value("KB국민은행"))
                .andExpect(jsonPath("$.fileType").value("PDF"))
                .andExpect(jsonPath("$.parsed.mapping.nameColumn").value("보낸분/받는분"))
                .andExpect(jsonPath("$.parsed.deposits.length()").value(5));

        // 목록에 없는 은행이면 기본(토스뱅크)
        long other = openGroupBuy(seller, "모르는 은행", 3000, "어느은행", "1234-5678-9012");
        assertThat(match(seller, other).get("bankName").asText()).isEqualTo("토스뱅크");
    }

    /** 1시간 규칙: 자동 매칭이어도 주문 시각과 1시간 넘게 차이 나면 확인 필요 (날짜만 있으면 안 봄) */
    @Test
    void depositLongAfterOrderNeedsCheck() throws Exception {
        Cookie seller = signup(uniqueEmail());
        long a = openGroupBuy(seller, "시각 확인", 3000, "토스뱅크", "1000-4444-5555");
        placeOrder(seller, a, "010-8300-0001", "김정시");
        placeOrder(seller, a, "010-8300-0002", "박늦음");
        placeOrder(seller, a, "010-8300-0003", "이날짜");
        LocalDateTime now = LocalDateTime.now(clock);
        upload(seller, a, "거래 일시,적요,거래 금액\n"
                + now.plusMinutes(20).format(TIME) + ",김정시,3000\n"
                + now.plusHours(3).format(TIME) + ",박늦음,3000\n"
                + now.toLocalDate() + ",이날짜,3000\n");
        Map<String, JsonNode> rows = byName(match(seller, a).get("rows"));
        assertThat(rows.values()).allMatch(r -> r.get("status").asText().equals("MATCHED"));
        assertThat(rows.get("김정시").get("checkNote").isNull()).isTrue();
        assertThat(rows.get("김정시").get("timeNote").asText()).matches("주문 (19|20)분 뒤 입금");
        assertThat(rows.get("박늦음").get("checkNote").asText()).contains("1시간이 넘게");
        assertThat(rows.get("박늦음").get("timeNote").asText()).matches("주문 (2시간 59분|3시간) 뒤 입금");
        assertThat(rows.get("이날짜").get("checkNote").isNull()).isTrue();
        assertThat(rows.get("이날짜").get("timeNote").isNull()).isTrue();
    }
}
