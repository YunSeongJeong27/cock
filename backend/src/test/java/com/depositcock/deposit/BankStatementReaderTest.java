package com.depositcock.deposit;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import com.depositcock.common.ApiException;
import com.depositcock.common.ErrorCode;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BankStatementReaderTest {

    private final BankStatementReader reader = new BankStatementReader();

    /**
     * 토스뱅크 엑셀 구조 재현 (docs/design.md 원문 E-2장): A열 비어 있음, 위쪽 안내문, 10행 헤더, 부호 있는 금액 한 열.
     * 이름·금액은 가짜 값이다. (실제 토스 파일로는 아직 테스트 못 함)
     */
    static byte[] tossBankXlsx() throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("토스뱅크 거래내역");
            s.createRow(0).createCell(1).setCellValue("토스뱅크 거래내역");
            s.createRow(1).createCell(1).setCellValue("성명 홍길동");
            s.createRow(2).createCell(1).setCellValue("계좌번호 ****-****-1234");
            s.createRow(3).createCell(1).setCellValue("조회기간 2025.10.12 - 2025.11.12");
            s.createRow(5).createCell(1).setCellValue("※ 본 거래내역은 법적효력이 없는 참고용 문서입니다.");
            String[] header = {"거래 일시", "적요", "거래 유형", "거래 기관", "계좌번호", "거래 금액", "거래 후 잔액", "메모"};
            Row h = s.createRow(9);
            for (int i = 0; i < header.length; i++) {
                h.createCell(i + 1).setCellValue(header[i]);
            }
            Object[][] rows = {
                    {"2025.11.09 00:12:19", "김지영", "입금", "KB국민은행", "", 3800, 100000},
                    {"2025.11.09 01:00:00", "통장 이자", "이자입금", "토스뱅크", "", 12, 100012},
                    {"2025.11.09 02:00:00", "편의점", "체크카드결제", "", "", -69120, 30892},
                    {"2025.11.10 10:53:21", "꼭지맘217-1505", "모임원송금", "신한은행", "", 1900, 32792},
            };
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(10 + r);
                for (int c = 0; c < rows[r].length; c++) {
                    Object v = rows[r][c];
                    if (v instanceof Integer i) {
                        row.createCell(c + 1).setCellValue(i);
                    } else {
                        row.createCell(c + 1).setCellValue((String) v);
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void tossBankLayout() throws Exception {
        ParsedStatement parsed = reader.read("토스뱅크_거래내역.xlsx", tossBankXlsx(), null);
        assertThat(parsed.ok()).isTrue();
        assertThat(parsed.autoDetected()).isTrue();
        assertThat(parsed.headerRowNo()).isEqualTo(10);
        assertThat(parsed.mapping().nameColumn()).isEqualTo("적요");
        assertThat(parsed.mapping().amountColumn()).isEqualTo("거래 금액");
        assertThat(parsed.mapping().typeColumn()).isEqualTo("거래 유형");
        assertThat(parsed.deposits()).extracting(DepositLine::name).containsExactly("김지영", "꼭지맘217-1505");
        assertThat(parsed.deposits()).extracting(DepositLine::amount).containsExactly(3800, 1900);
        assertThat(parsed.deposits().get(0).depositedAt()).isEqualTo(LocalDateTime.of(2025, 11, 9, 0, 12, 19));
        assertThat(parsed.skippedRows()).isEqualTo(2);
    }

    /**
     * 사용자가 보내 준 실제 거래내역 PDF(2026-10-27, KB국민·토스뱅크)의 머리줄을 CSV 로 옮긴 것. 값은 가짜.
     * KB 는 "적요"에 거래 종류(전자금융·FBS입금)가 들어 있어 입금자명은 "보낸분/받는분"에서 읽어야 한다
     */
    @Test
    void kbAndTossPdfHeaders() {
        String kb = "거래일시,적요,보낸분/받는분,출금액,입금액,잔액,송금메모,거래점\n"
                + "2026.10.26 10:30:00,전자금융,홍길동,0,\"10,000\",\"110,000\",-,예시은행\n"
                + "2026.10.26 10:20:00,오픈뱅킹출금,예시가게,\"5,000\",0,\"100,000\",-,예시은행\n";
        ParsedStatement kbParsed = reader.read("kb.csv", kb.getBytes(StandardCharsets.UTF_8), null);
        assertThat(kbParsed.mapping().nameColumn()).isEqualTo("보낸분/받는분");
        assertThat(kbParsed.mapping().dateColumn()).isEqualTo("거래일시");
        assertThat(kbParsed.deposits()).extracting(DepositLine::name).containsExactly("홍길동");
        assertThat(kbParsed.deposits().get(0).dateText()).isEqualTo("2026.10.26 10:30:00");

        String toss = "거래일자,구분,거래금액,거래 후 잔액,거래내용\n"
                + "2026-10-22 22:50:00,출금,\"-20,000\",\"50,500\",김출금\n"
                + "2026-10-22 22:40:00,입금,\"30,000\",\"70,500\",홍길동\n";
        ParsedStatement tossParsed = reader.read("toss.csv", toss.getBytes(StandardCharsets.UTF_8), null);
        assertThat(tossParsed.mapping().nameColumn()).isEqualTo("거래내용");
        assertThat(tossParsed.mapping().dateColumn()).isEqualTo("거래일자");
        assertThat(tossParsed.deposits()).extracting(DepositLine::name).containsExactly("홍길동");
        assertThat(tossParsed.deposits().get(0).dateText()).isEqualTo("2026-10-22 22:40:00");
    }

    /**
     * 거래내역 PDF (2026-10-27). 테스트 PDF 는 사용자가 보낸 KB국민·토스뱅크 PDF 캡처의 배치(머리줄·칸 위치·정렬·페이지 나눔)를
     * 흉내 내 만든 것이고 이름·금액·계좌번호·시각은 모두 지어낸 값이다 (src/test/resources/statements). 실제 은행 PDF 로는 아직 확인 못 함
     */
    @Test
    void kbPdf() throws Exception {
        ParsedStatement parsed = reader.read("KB거래내역조회.pdf", resource("statements/kb-statement.pdf"), null);
        assertThat(parsed.ok()).isTrue();
        assertThat(parsed.mapping().nameColumn()).isEqualTo("보낸분/받는분");
        assertThat(parsed.mapping().amountColumn()).isEqualTo("입금액");
        assertThat(parsed.mapping().dateColumn()).isEqualTo("거래일시");
        // 2쪽에 다시 나온 머리줄·쪽 번호·안내문은 건너뛰고, 출금(입금액 0)도 건너뜀
        assertThat(parsed.deposits()).extracting(DepositLine::name)
                .containsExactly("홍길동", "김예시", "예시페이", "박샘플", "이테스트");
        assertThat(parsed.deposits()).extracting(DepositLine::amount).containsExactly(10000, 20000, 30000, 40000, 50000);
        assertThat(parsed.deposits().get(0).dateText()).isEqualTo("2026.10.26 10:30:00");
        assertThat(parsed.deposits().get(0).depositedAt()).isEqualTo(LocalDateTime.of(2026, 10, 26, 10, 30, 0));
    }

    @Test
    void tossPdf() throws Exception {
        ParsedStatement parsed = reader.read("toss.pdf", resource("statements/toss-statement.pdf"), null);
        assertThat(parsed.ok()).isTrue();
        assertThat(parsed.mapping().nameColumn()).isEqualTo("거래내용");
        assertThat(parsed.mapping().amountColumn()).isEqualTo("거래금액");
        assertThat(parsed.mapping().typeColumn()).isEqualTo("구분");
        // 머리줄 없는 2쪽도 같은 칸 위치로 읽음. 캐시백(+500)도 입금으로 읽는다 (거래 이름으로 빼지 않음, 2026-10-27 사용자 결정)
        assertThat(parsed.deposits()).extracting(DepositLine::name).containsExactly("홍길동", "카드 캐시백", "김샘플");
        assertThat(parsed.deposits()).extracting(DepositLine::amount).containsExactly(30000, 500, 20000);
        assertThat(parsed.deposits().get(0).dateText()).isEqualTo("2026-10-22 22:40:00");
    }

    /**
     * 은행별 헤더(컬럼)명으로 읽기 + 머리줄 위쪽에서 계좌번호 찾기 (2026-10-27). 값은 V1 운영자 값(bank_header)과 같다
     */
    @Test
    void bankHeaderValuesAndAccountNumber() throws Exception {
        ColumnMapping kb = new ColumnMapping("거래일시", "보낸분/받는분", "입금액", null);
        ParsedStatement kbParsed = reader.read("kb.pdf", resource("statements/kb-statement.pdf"), kb, "계좌번호");
        assertThat(kbParsed.ok()).isTrue();
        assertThat(kbParsed.deposits()).hasSize(5);
        assertThat(kbParsed.accountNumber()).isEqualTo("123456-**-***789");

        ColumnMapping toss = new ColumnMapping("거래일자", "거래내용", "거래금액", null);
        ParsedStatement tossParsed = reader.read("toss.pdf", resource("statements/toss-statement.pdf"), toss, "계좌번호");
        assertThat(tossParsed.ok()).isTrue();
        assertThat(tossParsed.deposits()).extracting(DepositLine::name).containsExactly("홍길동", "카드 캐시백", "김샘플");
        assertThat(tossParsed.accountNumber()).isEqualTo("1001-0000-0000");

        // 칸 이름 뒤에 붙은 계좌번호 ("계좌번호 ****-****-1234"), 계좌번호 칸이 머리줄에도 있지만 머리줄 위쪽만 본다
        ColumnMapping xlsx = new ColumnMapping("거래 일시", "적요", "거래 금액", null);
        ParsedStatement xlsxParsed = reader.read("toss.xlsx", tossBankXlsx(), xlsx, "계좌번호");
        assertThat(xlsxParsed.accountNumber()).isEqualTo("****-****-1234");
        assertThat(reader.read("toss.xlsx", tossBankXlsx(), xlsx, "계좌 번호").accountNumber()).isEqualTo("****-****-1234");
        assertThat(reader.read("toss.xlsx", tossBankXlsx(), xlsx, "출금계좌").accountNumber()).isNull();
    }

    /** 은행 헤더(컬럼)명이 파일에 없으면 못 찾은 이름을 알려 준다. 날짜 칸도 있어야 한다 (2026-10-27) */
    @Test
    void missingBankHeaderColumnsAreReported() {
        String csv = "거래일시,보낸분/받는분,입금액\n2026.10.26 10:00:00,홍길동,5000\n";
        ParsedStatement wrongBank = reader.read("x.csv", csv.getBytes(StandardCharsets.UTF_8),
                new ColumnMapping("거래일자", "거래내용", "거래금액", null));
        assertThat(wrongBank.ok()).isFalse();
        assertThat(wrongBank.missingColumns()).containsExactly("거래내용", "거래금액", "거래일자");
        ParsedStatement noDate = reader.read("x.csv", csv.getBytes(StandardCharsets.UTF_8),
                new ColumnMapping("입금일", "보낸분/받는분", "입금액", null));
        assertThat(noDate.ok()).isFalse();
        assertThat(noDate.missingColumns()).containsExactly("입금일");
    }

    /** 파일 이름이 .pdf 가 아니어도 내용이 PDF 면 PDF 로 읽는다 */
    @Test
    void pdfDetectedByContent() throws Exception {
        assertThat(reader.read("거래내역", resource("statements/kb-statement.pdf"), null).deposits()).hasSize(5);
    }

    @Test
    void pdfWithoutTextOrWithPassword() throws Exception {
        byte[] blank;
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            doc.save(out);
            blank = out.toByteArray();
        }
        assertThatThrownBy(() -> reader.read("scan.pdf", blank, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.STATEMENT_PDF_NO_TEXT));

        byte[] locked;
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            doc.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner", "900101",
                    new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            doc.save(out);
            locked = out.toByteArray();
        }
        assertThatThrownBy(() -> reader.read("locked.pdf", locked, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.STATEMENT_PDF_PASSWORD));
    }

    private static byte[] resource(String path) throws Exception {
        try (var in = BankStatementReaderTest.class.getClassLoader().getResourceAsStream(path)) {
            return in.readAllBytes();
        }
    }

    @Test
    void csvMs949WithQuotesAndBom() {
        String csv = "거래일자,기재내용,출금액,입금액,잔액\r\n"
                + "2025-11-01 10:00:00,\"김민지1505\",,\"19,900\",\"100,000\"\r\n"
                + "2025-11-01 11:00:00,\"쿠팡, 결제\",\"5,000\",,95000\r\n"
                + "2025-11-02,박서준,,8900,103900\r\n";
        byte[] ms949 = csv.getBytes(Charset.forName("MS949"));
        ParsedStatement parsed = reader.read("bank.csv", ms949, null);
        assertThat(parsed.deposits()).extracting(DepositLine::name).containsExactly("김민지1505", "박서준");
        assertThat(parsed.deposits()).extracting(DepositLine::amount).containsExactly(19900, 8900);
        assertThat(parsed.mapping().amountColumn()).isEqualTo("입금액");

        byte[] utf8Bom = ("﻿" + csv).getBytes(StandardCharsets.UTF_8);
        assertThat(reader.read("bank.csv", utf8Bom, null).deposits()).hasSize(2);
    }

    @Test
    void unknownHeaderNeedsManualMapping() throws Exception {
        String csv = "날 짜,보낸 사람 이름,들어온 돈\n2025-11-01,홍길동,5000\n";
        ParsedStatement auto = reader.read("x.csv", csv.getBytes(StandardCharsets.UTF_8), null);
        assertThat(auto.ok()).isFalse();
        assertThat(auto.message()).contains("직접 골라주세요");
        assertThat(auto.columnOptions()).contains("보낸 사람 이름", "들어온 돈");

        ParsedStatement manual = reader.read("x.csv", csv.getBytes(StandardCharsets.UTF_8),
                new ColumnMapping("날 짜", "보낸사람 이름", "들어온 돈", null));
        assertThat(manual.ok()).isTrue();
        assertThat(manual.autoDetected()).isFalse();
        assertThat(manual.deposits()).extracting(DepositLine::name).containsExactly("홍길동");
    }

    @Test
    void xlsLegacyFormat() throws Exception {
        try (Workbook wb = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("a");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("거래일시");
            h.createCell(1).setCellValue("입금자명");
            h.createCell(2).setCellValue("입금액");
            Row r = s.createRow(1);
            r.createCell(0).setCellValue("2025/11/03 09:00");
            r.createCell(1).setCellValue("이수진");
            r.createCell(2).setCellValue(13900);
            wb.write(out);
            ParsedStatement parsed = reader.read("old.xls", out.toByteArray(), null);
            assertThat(parsed.deposits()).hasSize(1);
            assertThat(parsed.deposits().get(0).depositedAt()).isEqualTo(LocalDateTime.of(2025, 11, 3, 9, 0));
        }
    }

    @Test
    void yearlessDateIsNotGuessed() {
        assertThat(BankStatementReader.parseDateTime("10-08 10:01")).isNull();
        assertThat(BankStatementReader.parseAmount("-69,120")).isEqualTo(-69120);
        assertThat(BankStatementReader.parseAmount("1,900원")).isEqualTo(1900);
        assertThat(BankStatementReader.parseAmount("입금")).isNull();
    }

    /** 손상된 PDF (2026-10-27 전에는 PDF 를 모두 거절했음) */
    @Test
    void brokenPdfIsRejectedWithGuide() {
        assertThatThrownBy(() -> reader.read("문서.pdf", new byte[]{'%', 'P', 'D', 'F'}, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.STATEMENT_PDF_UNREADABLE));
    }

    /** 금액이 항상 양수이고 '구분'으로 입금/출금을 나누는 형식: 출금·지급 줄은 빠진다 */
    @Test
    void unsignedAmountWithTypeColumn() {
        String csv = "거래일시,구분,내용,거래금액\n"
                + "2025-11-01 10:00:00,입금,김지영,3800\n"
                + "2025-11-01 11:00:00,출금,편의점,5000\n"
                + "2025-11-01 12:00:00,지급,카드대금,7000\n"
                + "2025-11-01 13:00:00,입금,박서준,1900\n";
        ParsedStatement parsed = reader.read("x.csv", csv.getBytes(StandardCharsets.UTF_8), null);
        assertThat(parsed.deposits()).extracting(DepositLine::name).containsExactly("김지영", "박서준");
        assertThat(parsed.skippedRows()).isEqualTo(2);
    }
}
