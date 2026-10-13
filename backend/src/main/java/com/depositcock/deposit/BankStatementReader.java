package com.depositcock.deposit;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.depositcock.common.ApiException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/**
 * 은행 거래내역 파일(xlsx/xls/csv)을 읽는다 (정리본 6-1장).
 * - CSV 는 UTF-8 로 읽고, 실패하면 MS949 로 다시 읽는다. BOM 제거, 따옴표 안 쉼표 처리.
 * - 위쪽 30줄 안에서 헤더 줄을 찾는다. 공백을 지운 뒤 후보 이름과 정확히 같아야 한다.
 * - 은행마다 열 이름이 달라서 못 찾으면 판매자가 열 이름을 직접 지정한다 (ColumnMapping).
 */
@Component
public class BankStatementReader {

    static final int HEADER_SEARCH_ROWS = 30;

    static final List<String> DATE_CANDIDATES = List.of("거래일시", "거래일자", "거래일", "일시", "날짜", "일자");
    static final List<String> NAME_CANDIDATES = List.of("입금자명", "입금자", "보낸분", "기재내용", "내용", "적요",
            "받는분/보낸분", "거래내용");
    /** 입금 전용 열을 먼저 찾고, 없으면 부호 있는 단일 열 "거래금액" (양수만 입금) */
    static final List<String> AMOUNT_CANDIDATES = List.of("입금액", "입금금액", "맡기신금액", "입금(원)", "입금", "거래금액");
    static final List<String> TYPE_CANDIDATES = List.of("거래유형", "구분", "거래구분");

    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyyMMdd HHmmss"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy.MM.dd"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyyMMdd"));

    private static final Charset MS949 = Charset.forName("MS949");

    public ParsedStatement read(String fileName, byte[] content, ColumnMapping requested) {
        List<List<String>> rows = readRows(fileName, content);
        return parse(rows, requested);
    }

    // ---------- 파일 → 문자열 표 ----------

    List<List<String>> readRows(String fileName, byte[] content) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv") || lower.endsWith(".txt")) {
            return readCsv(content);
        }
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls") || looksLikeExcel(content)) {
            return readExcel(content);
        }
        if (lower.endsWith(".pdf")) {
            throw ApiException.badRequest("PDF 거래내역은 아직 읽을 수 없습니다. 은행 앱에서 엑셀(xlsx) 또는 CSV 로 받아 올려주세요.");
        }
        return readCsv(content);
    }

    private static boolean looksLikeExcel(byte[] content) {
        if (content.length < 4) {
            return false;
        }
        boolean zip = content[0] == 'P' && content[1] == 'K';
        boolean ole = (content[0] & 0xFF) == 0xD0 && (content[1] & 0xFF) == 0xCF;
        return zip || ole;
    }

    private List<List<String>> readExcel(byte[] content) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.KOREA);
            List<List<String>> rows = new ArrayList<>();
            int last = sheet.getLastRowNum();
            for (int r = 0; r <= last; r++) {
                Row row = sheet.getRow(r);
                List<String> values = new ArrayList<>();
                if (row != null) {
                    short lastCell = row.getLastCellNum();
                    for (int c = 0; c < Math.max(lastCell, 0); c++) {
                        values.add(cellText(row.getCell(c), formatter));
                    }
                }
                rows.add(values);
            }
            return rows;
        } catch (IOException | RuntimeException e) {
            throw ApiException.badRequest("엑셀 파일을 읽을 수 없습니다. 파일이 손상되었거나 암호가 걸려 있는지 확인해 주세요.");
        }
    }

    private static String cellText(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                LocalDateTime dt = cell.getLocalDateTimeCellValue();
                return dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
            double value = cell.getNumericCellValue();
            if (value == Math.rint(value) && Math.abs(value) < 1e15) {
                return String.valueOf((long) value);
            }
            return String.valueOf(value);
        }
        return formatter.formatCellValue(cell).trim();
    }

    List<List<String>> readCsv(byte[] content) {
        String text = decode(content);
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(field.toString().trim());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString().trim());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString().trim());
            rows.add(row);
        }
        return rows;
    }

    private static String decode(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            return new String(content, MS949);
        }
    }

    // ---------- 표 → 입금 목록 ----------

    ParsedStatement parse(List<List<String>> rows, ColumnMapping requested) {
        List<String> options = columnOptions(rows);
        List<List<String>> preview = rows.stream().limit(15).toList();
        boolean manual = requested != null && requested.isComplete();

        for (int r = 0; r < Math.min(rows.size(), HEADER_SEARCH_ROWS); r++) {
            List<String> header = rows.get(r).stream().map(BankStatementReader::squash).toList();
            int nameIdx;
            int amountIdx;
            int dateIdx;
            int typeIdx;
            ColumnMapping used;
            if (manual) {
                nameIdx = header.indexOf(squash(requested.nameColumn()));
                amountIdx = header.indexOf(squash(requested.amountColumn()));
                dateIdx = isBlank(requested.dateColumn()) ? -1 : header.indexOf(squash(requested.dateColumn()));
                typeIdx = isBlank(requested.typeColumn()) ? -1 : header.indexOf(squash(requested.typeColumn()));
                used = requested;
            } else {
                nameIdx = firstIndex(header, NAME_CANDIDATES);
                amountIdx = firstIndex(header, AMOUNT_CANDIDATES);
                dateIdx = firstIndex(header, DATE_CANDIDATES);
                typeIdx = firstIndex(header, TYPE_CANDIDATES);
                used = new ColumnMapping(original(rows.get(r), dateIdx), original(rows.get(r), nameIdx),
                        original(rows.get(r), amountIdx), original(rows.get(r), typeIdx));
            }
            if (nameIdx < 0 || amountIdx < 0 || nameIdx == amountIdx) {
                continue;
            }
            List<DepositLine> deposits = new ArrayList<>();
            int skipped = 0;
            for (int i = r + 1; i < rows.size(); i++) {
                List<String> row = rows.get(i);
                if (row.stream().allMatch(String::isBlank)) {
                    continue;
                }
                String name = get(row, nameIdx).trim();
                Integer amount = parseAmount(get(row, amountIdx));
                String type = typeIdx >= 0 ? get(row, typeIdx) : "";
                if (name.isEmpty() || amount == null || amount <= 0 || type.contains("이자")) {
                    skipped++;
                    continue;
                }
                String dateText = dateIdx >= 0 ? get(row, dateIdx).trim() : null;
                deposits.add(new DepositLine(i + 1, name, amount, blankToNull(dateText), parseDateTime(dateText)));
            }
            return new ParsedStatement(used, !manual, r + 1, rows.get(r), options, preview, deposits, skipped, null);
        }
        String message = manual
                ? "지정한 열 이름(이름: " + requested.nameColumn() + ", 금액: " + requested.amountColumn()
                        + ")을 위쪽 " + HEADER_SEARCH_ROWS + "줄 안에서 찾지 못했습니다. 열 이름을 다시 확인해 주세요."
                : "입금자명·금액 열을 자동으로 찾지 못했습니다. 은행마다 열 이름이 달라서 그렇습니다. "
                        + "아래에서 입금자명 열과 금액 열을 직접 골라주세요.";
        return new ParsedStatement(manual ? requested : new ColumnMapping(null, null, null, null), false, 0,
                List.of(), options, preview, List.of(), 0, message);
    }

    private static List<String> columnOptions(List<List<String>> rows) {
        Set<String> options = new LinkedHashSet<>();
        for (int r = 0; r < Math.min(rows.size(), HEADER_SEARCH_ROWS); r++) {
            for (String value : rows.get(r)) {
                String v = value.trim();
                if (!v.isEmpty() && v.length() <= 30 && parseAmount(v) == null) {
                    options.add(v);
                }
            }
        }
        return List.copyOf(options);
    }

    private static int firstIndex(List<String> header, List<String> candidates) {
        for (String candidate : candidates) {
            int idx = header.indexOf(candidate);
            if (idx >= 0) {
                return idx;
            }
        }
        return -1;
    }

    private static String original(List<String> row, int idx) {
        return idx >= 0 && idx < row.size() ? row.get(idx) : null;
    }

    private static String get(List<String> row, int idx) {
        return idx >= 0 && idx < row.size() ? row.get(idx) : "";
    }

    static String squash(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    /** "1,900", "1900원", "+250,000", "-69,120" → 정수. 숫자가 아니면 null */
    static Integer parseAmount(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replace(",", "").replace("원", "").replace("₩", "").replaceAll("\\s+", "");
        if (cleaned.isEmpty() || !cleaned.matches("[+-]?\\d+(\\.\\d+)?")) {
            return null;
        }
        try {
            double value = Double.parseDouble(cleaned);
            if (Math.abs(value) > Integer.MAX_VALUE) {
                return null;
            }
            return (int) Math.round(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 입금 시각 변환. 은행마다 형식이 달라서 아는 형식만 바꾸고 모르면 null (미결 39번).
     * 연도가 없는 "10-08 10:01" 같은 값은 연도를 추측하지 않고 null 로 둔다.
     */
    static LocalDateTime parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim().replaceAll("\\s+", " ");
        for (DateTimeFormatter f : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(text, f);
            } catch (DateTimeParseException ignored) {
                // 다음 형식 시도
            }
        }
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(text, f).atTime(LocalTime.MIDNIGHT);
            } catch (DateTimeParseException ignored) {
                // 다음 형식 시도
            }
        }
        return null;
    }

    /** 다른 경로(스크린샷 등)에서 입금 시각 원문을 변환할 때 쓴다. */
    public static LocalDateTime parseDateTimeText(String raw) {
        return parseDateTime(raw);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
