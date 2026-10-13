package com.depositcock.deposit;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import com.depositcock.common.ApiException;
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

    @Test
    void pdfIsRejectedWithGuide() {
        assertThatThrownBy(() -> reader.read("문서.pdf", new byte[]{'%', 'P', 'D', 'F'}, null))
                .isInstanceOf(ApiException.class).hasMessageContaining("PDF");
    }
}
