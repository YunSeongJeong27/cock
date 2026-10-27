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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    /**
     * 수동 확정한 주문에 나중에 올린 입금 줄 연결 + "이미 처리 완료된 입금" (2026-10-26).
     * 처리 완료로 옮긴 입금은 같은 파일을 다시 올려도 다시 나오지 않고, 되돌리면 대조에 다시 나온다
     */
    @Test
    void linkManualConfirmationAndHandledDeposits() throws Exception {
        Cookie seller = signup(uniqueEmail());
        long id = openGroupBuy(seller, "연결 공구", 5000);
        String base = "/api/seller/group-buys/" + id;
        placeOrder(seller, id, "010-1300-0001", "김수동");
        long orderId = read(mvc.perform(get(base + "/orders").cookie(seller)).andReturn()).get(0).get("id").asLong();
        mvc.perform(withJson(post(base + "/orders/" + orderId + "/manual-paid").cookie(seller),
                Map.of("depositorName", "김수동", "amount", 5000))).andExpect(status().isOk());

        String file = "거래 일시,적요,거래 금액\n2026.10.26 10:00:00,김수동,5000\n2026.10.26 11:00:00,홍길동,3000\n";
        mvc.perform(multipart(base + "/deposits/uploads").file(csv(file)).cookie(seller)).andExpect(status().isOk());
        JsonNode match = read(mvc.perform(get(base + "/deposits/match").cookie(seller)).andReturn());
        assertThat(match.get("manualOrders")).hasSize(1);
        JsonNode manualRow = match.get("rows").get(0);
        assertThat(manualRow.get("depositorName").asText()).isEqualTo("김수동");
        assertThat(manualRow.get("linkOrderId").asLong()).isEqualTo(orderId);
        assertThat(manualRow.get("note").asText()).contains("거래내역 없이 입금 확정한");
        long otherRow = match.get("rows").get(1).get("rowId").asLong();

        // 연결: 주문은 그대로 입금 확정, 입금 줄은 "확정한 입금"으로
        mvc.perform(withJson(post(base + "/deposits/confirm").cookie(seller), Map.of("items",
                List.of(Map.of("rowId", manualRow.get("rowId").asLong(), "orderId", orderId)))))
                .andExpect(jsonPath("$.confirmedCount").value(1));
        match = read(mvc.perform(get(base + "/deposits/match").cookie(seller)).andReturn());
        assertThat(match.get("manualOrders")).isEmpty();
        assertThat(match.get("rows")).hasSize(1);
        assertThat(match.get("used").get(0).get("rowId").asLong()).isEqualTo(manualRow.get("rowId").asLong());
        assertThat(match.get("used").get(0).get("manual").asBoolean()).isTrue();

        // 확정한 입금은 처리 완료로 못 옮김
        mvc.perform(post(base + "/deposits/rows/" + manualRow.get("rowId").asLong() + "/handled").cookie(seller))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DEPOSIT_CONFIRMED_CANNOT_HANDLE"));

        // 처리 완료로 옮기면 대조에서 빠지고, 같은 파일을 다시 올려도 다시 안 나옴
        mvc.perform(post(base + "/deposits/rows/" + otherRow + "/handled").cookie(seller)).andExpect(status().isOk());
        mvc.perform(multipart(base + "/deposits/uploads").file(csv(file)).cookie(seller))
                .andExpect(jsonPath("$.savedCount").value(0)).andExpect(jsonPath("$.duplicateCount").value(2));
        match = read(mvc.perform(get(base + "/deposits/match").cookie(seller)).andReturn());
        assertThat(match.get("rows")).isEmpty();
        assertThat(match.get("handled")).hasSize(1);
        assertThat(match.get("handled").get(0).get("depositorName").asText()).isEqualTo("홍길동");
        mvc.perform(withJson(post(base + "/deposits/confirm").cookie(seller), Map.of("items",
                List.of(Map.of("rowId", otherRow, "orderId", orderId)))))
                .andExpect(jsonPath("$.confirmedCount").value(0))
                .andExpect(jsonPath("$.errors[0]").value("'홍길동 3000원' 입금은 이미 처리 완료된 입금입니다. 되돌린 뒤 확정해 주세요."));

        // 되돌리기
        mvc.perform(delete(base + "/deposits/rows/" + otherRow + "/handled").cookie(seller)).andExpect(status().isOk());
        match = read(mvc.perform(get(base + "/deposits/match").cookie(seller)).andReturn());
        assertThat(match.get("rows")).hasSize(1);
        assertThat(match.get("handled")).isEmpty();

        // 연결한 확정을 취소하면 입금 줄이 다시 대조에 나온다
        mvc.perform(post(base + "/orders/" + orderId + "/unconfirm-paid").cookie(seller)).andExpect(status().isOk());
        match = read(mvc.perform(get(base + "/deposits/match").cookie(seller)).andReturn());
        assertThat(match.get("rows")).hasSize(2);
        assertThat(match.get("matchedCount").asInt()).isEqualTo(1);
    }

    @Test
    void fullDepositFlow() throws Exception {
        Cookie seller = signup(uniqueEmail());
        long id = openGroupBuy(seller, "떡 공구", 13900);
        placeOrder(seller, id, "010-1000-0001", "김지영");
        placeOrder(seller, id, "010-1000-0002", "박서준");
        placeOrder(seller, id, "010-1000-0003", "이수진");

        String content = "거래 일시,적요,거래 금액\n"
                + "2025.11.09 10:00:00,김지영,13900\n"
                + "2025.11.09 10:05:00,박서준맘,13900\n"
                + "2025.11.09 10:06:00,이수진,14000\n"
                + "2025.11.09 10:07:00,관련없음,500\n"
                + "2025.11.09 10:08:00,카드결제,-3000\n";

        // 미리보기: 공구 입금 계좌의 은행(토스뱅크) 헤더(컬럼)명으로 읽는다
        mvc.perform(multipart("/api/seller/group-buys/" + id + "/deposits/preview").file(csv(content)).cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankName").value("토스뱅크"))
                .andExpect(jsonPath("$.fileType").value("EXCEL"))
                .andExpect(jsonPath("$.parsed.mapping.nameColumn").value("적요"))
                .andExpect(jsonPath("$.parsed.mapping.amountColumn").value("거래 금액"))
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
        mvc.perform(get("/api/seller/group-buys/" + id + "/detail").cookie(seller))
                .andExpect(jsonPath("$.dashboard.paidCount").value(2))
                .andExpect(jsonPath("$.dashboard.unpaidCount").value(1));

        // 다음 공구: 같은 고객(같은 휴대폰)이 "박서준맘"으로 입금 → 별칭으로 자동 매칭
        long next = openGroupBuy(seller, "다음 공구", 8900);
        placeOrder(seller, next, "010-1000-0002", "박서준");
        mvc.perform(multipart("/api/seller/group-buys/" + next + "/deposits/uploads")
                        .file(csv("적요,거래 금액,거래 일시\n박서준맘,8900\n")).cookie(seller))
                .andExpect(status().isOk());
        JsonNode nextMatch = read(mvc.perform(get("/api/seller/group-buys/" + next + "/deposits/match").cookie(seller)).andReturn());
        Map<String, JsonNode> nextByName = new HashMap<>();
        nextMatch.get("rows").forEach(r -> nextByName.put(r.get("depositorName").asText(), r));
        assertThat(nextByName.get("박서준맘").get("status").asText()).isEqualTo("MATCHED");
        assertThat(nextByName.get("박서준맘").get("evidence").asText()).isEqualTo("저장된 별칭");
        // 두 공구가 같은 입금 계좌: 앞 공구 주문(이수진)으로 보이는 입금은 "다른 공구" 목록에, 관련 없는 입금은 양쪽에 (2026-10-27)
        assertThat(nextMatch.get("sharedGroupBuys").get(0).asText()).isEqualTo("떡 공구");
        assertThat(nextMatch.get("otherRows").get(0).get("depositorName").asText()).isEqualTo("이수진");
        assertThat(nextMatch.get("otherRows").get(0).get("order").get("groupBuyTitle").asText()).isEqualTo("떡 공구");
        assertThat(nextByName).containsKey("관련없음");

        // 확정 취소 → 입금 줄이 다시 대조 대상으로
        mvc.perform(post("/api/seller/group-buys/" + id + "/orders/" + orderPark + "/unconfirm-paid").cookie(seller))
                .andExpect(status().isOk());
        JsonNode after = read(mvc.perform(get("/api/seller/group-buys/" + id + "/deposits/match").cookie(seller)).andReturn());
        assertThat(after.get("used").size()).isEqualTo(1);

        // 입금 확정된 주문은 고객이 바로 취소하지 못하고 환불 신청 → 판매자가 취소하고 환불 → 환불 필요 (2026-10-18)
        JsonNode g = groupBuy(seller, id);
        String pub = "/api/public/g/" + g.get("publicToken").asText();
        mvc.perform(withJson(post(pub + "/cancel"), Map.of("orderNo", 1, "phone", "01010000001", "depositorName", "김지영")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.refundRequired").value(true));
        mvc.perform(withJson(post(pub + "/refund-request"), Map.of("orderNo", 1, "phone", "01010000001",
                "depositorName", "김지영", "bankName", "국민", "accountNumber", "123-456-7890")))
                .andExpect(status().isOk());
        long refundOrderId = read(mvc.perform(get("/api/seller/group-buys/" + id + "/orders").cookie(seller)).andReturn())
                .get(0).get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/orders/" + refundOrderId + "/refund-request/approve").cookie(seller))
                .andExpect(status().isOk());
        JsonNode orders = read(mvc.perform(get("/api/seller/group-buys/" + id + "/orders").cookie(seller)).andReturn());
        assertThat(orders.get(0).get("refundRequired").asBoolean()).isTrue();
        assertThat(orders.get(0).get("refundAccount").asText()).isEqualTo("1234567890");
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

    /**
     * 은행 헤더(컬럼) 설정 (2026-10-27): 고른 은행의 헤더명이 파일에 없으면 멈추고 알린다.
     * 판매자가 은행을 추가하거나 운영자 은행 값을 바꿔 쓸 수 있고, 지우면 원래 값으로 돌아간다
     */
    @Test
    void bankHeadersReadStatementAndStopOnMismatch() throws Exception {
        Cookie seller = signup(uniqueEmail());
        long id = openGroupBuy(seller, "은행 헤더", 5000);
        String base = "/api/seller/group-buys/" + id;
        placeOrder(seller, id, "010-2000-0001", "홍길동");
        String content = "입금 날짜,보낸 사람 이름,들어온 돈\n2026-10-27,홍길동,5000\n";
        mvc.perform(multipart(base + "/deposits/preview").file(csv(content)).cookie(seller))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STATEMENT_HEADER_MISMATCH"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("'토스뱅크 엑셀'")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("적요, 거래 금액, 거래 일시")));
        mvc.perform(multipart(base + "/deposits/uploads").file(csv(content)).cookie(seller))
                .andExpect(status().isBadRequest());
        assertThat(read(mvc.perform(get(base + "/deposits/uploads").cookie(seller)).andReturn())).isEmpty();

        // 은행 추가 → 그 은행을 골라 읽기
        mvc.perform(withJson(put("/api/seller/bank-headers").cookie(seller), Map.of("bankName", "우리동네은행", "fileType", "EXCEL",
                        "nameColumn", "보낸 사람 이름", "amountColumn", "들어온 돈", "dateColumn", "입금 날짜")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("CUSTOM"));
        // 추가한 은행에 PDF 값은 없다 → 파일 종류를 PDF 로 고르면 "아직 없습니다"
        mvc.perform(multipart(base + "/deposits/preview").file(csv(content)).param("bankName", "우리동네은행")
                        .param("fileType", "PDF").cookie(seller))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STATEMENT_BANK_TYPE_MISSING"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("'우리동네은행' PDF")));
        mvc.perform(multipart(base + "/deposits/preview").file(csv(content)).param("bankName", "우리동네은행").cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankName").value("우리동네은행"))
                .andExpect(jsonPath("$.parsed.mapping.nameColumn").value("보낸 사람 이름"))
                .andExpect(jsonPath("$.parsed.deposits.length()").value(1));
        mvc.perform(multipart(base + "/deposits/uploads").file(csv(content)).param("bankName", "우리동네은행").cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedCount").value(1));
        mvc.perform(get(base + "/deposits/match").cookie(seller))
                .andExpect(jsonPath("$.rows[0].status").value("MATCHED"));

        // 운영자 은행(토스뱅크) 값을 판매자가 바꾸면 은행을 안 골라도 바꾼 값으로 읽고, 지우면 원래 값으로
        mvc.perform(withJson(put("/api/seller/bank-headers").cookie(seller), Map.of("bankName", "토스뱅크", "fileType", "EXCEL",
                        "nameColumn", "보낸 사람 이름", "amountColumn", "들어온 돈", "dateColumn", "입금 날짜")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("CHANGED"))
                .andExpect(jsonPath("$.original.nameColumn").value("적요"));
        mvc.perform(multipart(base + "/deposits/preview").file(csv(content)).cookie(seller))
                .andExpect(status().isOk()).andExpect(jsonPath("$.duplicateCount").value(1));
        mvc.perform(delete("/api/seller/bank-headers").param("bankName", "토스뱅크").param("fileType", "EXCEL").cookie(seller))
                .andExpect(status().isOk());
        mvc.perform(multipart(base + "/deposits/preview").file(csv(content)).cookie(seller))
                .andExpect(jsonPath("$.code").value("STATEMENT_HEADER_MISMATCH"));
        mvc.perform(delete("/api/seller/bank-headers").param("bankName", "토스뱅크").param("fileType", "EXCEL").cookie(seller))
                .andExpect(status().isNotFound());
        mvc.perform(withJson(put("/api/seller/bank-headers").cookie(seller), Map.of("bankName", "새은행", "fileType", "PDF",
                        "nameColumn", "a", "amountColumn", "b", "dateColumn", " ")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DATE_COLUMN_REQUIRED"));
        mvc.perform(withJson(put("/api/seller/bank-headers").cookie(seller), Map.of("bankName", "새은행",
                        "nameColumn", "a", "amountColumn", "b", "dateColumn", "c")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_TYPE_REQUIRED"));

        // 다른 판매자는 이 판매자가 추가한 은행을 쓸 수 없다
        Cookie other = signup(uniqueEmail());
        long otherId = openGroupBuy(other, "남의 공구", 1000);
        mvc.perform(multipart("/api/seller/group-buys/" + otherId + "/deposits/uploads").file(csv(content))
                .param("bankName", "우리동네은행").cookie(other)).andExpect(status().isNotFound());
    }

    /**
     * 암호 걸린 엑셀 올리기 (2026-10-27 사용자 요청): 비밀번호 없으면 "입력하세요", 틀리면 "맞지 않습니다", 맞으면 저장.
     * 토스 이메일 엑셀은 운영자 기본값 "토스뱅크 엑셀" 헤더(컬럼)명으로 읽는다 (은행과 파일 종류를 안 골라도 공구 계좌 은행과 올린 파일로)
     */
    @Test
    void encryptedExcelUploadWithPassword() throws Exception {
        Cookie seller = signup(uniqueEmail());
        long id = openGroupBuy(seller, "암호 엑셀", 5000);
        String base = "/api/seller/group-buys/" + id;
        placeOrder(seller, id, "010-2100-0001", "홍길동");

        byte[] locked;
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             org.apache.poi.poifs.filesystem.POIFSFileSystem fs = new org.apache.poi.poifs.filesystem.POIFSFileSystem();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet();
            String[][] rows = {{"거래 일시", "적요", "거래 유형", "거래 금액"}, {"2026.10.27 10:00:00", "홍길동", "입금", "5000"}};
            for (int r = 0; r < rows.length; r++) {
                var row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            var encryptor = new org.apache.poi.poifs.crypt.EncryptionInfo(org.apache.poi.poifs.crypt.EncryptionMode.agile).getEncryptor();
            encryptor.confirmPassword("900101");
            try (var os = encryptor.getDataStream(fs)) {
                wb.write(os);
            }
            fs.writeFilesystem(out);
            locked = out.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile("file", "토스뱅크_거래내역.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", locked);

        mvc.perform(multipart(base + "/deposits/preview").file(file).cookie(seller))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STATEMENT_FILE_PASSWORD_REQUIRED"));
        mvc.perform(multipart(base + "/deposits/uploads").file(file).param("password", "000000")
                        .cookie(seller))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STATEMENT_FILE_PASSWORD_WRONG"));
        assertThat(read(mvc.perform(get(base + "/deposits/uploads").cookie(seller)).andReturn())).isEmpty();
        mvc.perform(multipart(base + "/deposits/uploads").file(file).param("password", "900101")
                        .cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankName").value("토스뱅크"))
                .andExpect(jsonPath("$.fileType").value("EXCEL"))
                .andExpect(jsonPath("$.savedCount").value(1));
        mvc.perform(get(base + "/deposits/match").cookie(seller))
                .andExpect(jsonPath("$.rows[0].status").value("MATCHED"));
    }

    /** 날짜만 있는 파일: 같은 사람이 같은 날 같은 금액을 두 번 입금(주문 2건) → 같은 파일 안의 두 줄은 둘 다 저장 */
    @Test
    void sameDaySameAmountTwiceIsKept() throws Exception {
        Cookie seller = signup(uniqueEmail());
        long id = openGroupBuy(seller, "두 번 입금", 5000);
        // 같은 번호로 다시 주문하면 기존 주문이 바뀌므로(2026-10-18) 다른 번호, 같은 입금자명 두 사람
        placeOrder(seller, id, "010-7000-0001", "김지영");
        placeOrder(seller, id, "010-7000-0002", "김지영");
        String content = "거래 일시,적요,거래 금액\n2025-11-01,김지영,5000\n2025-11-01,김지영,5000\n";
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
