package com.depositcock.deposit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import com.depositcock.common.ApiException;
import com.depositcock.common.ErrorCode;
import com.depositcock.common.Texts;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * 거래내역 PDF → 문자열 표 (2026-10-27 사용자 요청: KB국민처럼 엑셀 없이 PDF 로만 받는 은행).
 * <pre>
 * 1) PDF 에 들어 있는 글자와 위치를 읽는다 (은행이 만든 PDF 는 보통 글자 정보가 있다. 글자가 그림이면 읽을 수 없다)
 * 2) 같은 높이의 글자를 한 줄로, 한 줄 안에서 글자 사이가 넓게 벌어진 곳을 칸 경계로 본다
 * 3) 머리줄(지정한 입금자명·금액 헤더(컬럼) 이름이 있는 줄)을 찾고, 그 아래 줄들의 칸을
 *    가로 위치가 겹치는(없으면 가장 가까운) 머리줄 칸에 넣는다. 페이지마다 다시 나오는 머리줄은 뺀다
 * 4) 결과는 엑셀·CSV 와 같은 문자열 표 → 같은 방식으로 입금 줄을 읽는다 (BankStatementReader.parse)
 * </pre>
 * 머리줄을 못 찾으면 줄마다 칸을 나눈 표를 그대로 돌려줘서, 판매자가 "헤더(컬럼)명 직접 지정"으로 고를 수 있게 한다.
 * 알려진 한계: 한 칸의 글자가 두 줄로 나뉘면 두 번째 줄은 금액이 없어 건너뛴다. 실제 은행 PDF 로는 아직 확인 못 함
 */
final class PdfTableReader {

    /** 이보다 많은 페이지는 읽지 않는다 (거래내역 1년치도 수십 장) */
    static final int MAX_PAGES = 200;

    /** 같은 줄로 보는 높이 차이 (글자 크기 대비) */
    private static final double SAME_LINE = 0.5;
    /** 한 칸 안의 띄어쓰기보다 넓은 간격이면 칸 경계 (글자 크기 대비) */
    private static final double CELL_GAP = 0.9;

    private PdfTableReader() {
    }

    /** 글자 하나 */
    private record Glyph(int page, double x, double y, double width, double size, String text) {
    }

    /** 한 줄 안의 칸: 글자와 가로 범위 */
    record Cell(double x0, double x1, String text) {
    }

    /** 한 줄 */
    record Line(int page, double y, List<Cell> cells) {
        String key() {
            return String.join("|", cells.stream().map(c -> Texts.squash(c.text())).toList());
        }
    }

    /**
     * @param isHeader 머리줄인지 (칸 글자 목록을 받아 판단). 자동 인식이면 헤더(컬럼) 후보, 직접 지정이면 지정한 이름
     */
    static List<List<String>> read(byte[] content, Predicate<List<String>> isHeader) {
        List<Line> lines = lines(glyphs(content));
        if (lines.isEmpty()) {
            throw new ApiException(ErrorCode.STATEMENT_PDF_NO_TEXT);
        }
        Line header = lines.stream().filter(l -> isHeader.test(texts(l))).findFirst().orElse(null);
        if (header == null) {
            // 머리줄을 못 찾음: 줄마다 칸을 나눈 그대로 (미리보기·직접 지정용)
            return lines.stream().map(PdfTableReader::texts).toList();
        }
        // 머리줄 위의 줄(계좌번호·조회기간 등)은 칸만 나눠 그대로 둔다 (거래내역서의 계좌번호를 찾는 데 씀, 2026-10-27)
        List<List<String>> rows = new ArrayList<>();
        int headerIndex = lines.indexOf(header);
        lines.subList(0, headerIndex).forEach(l -> rows.add(texts(l)));
        rows.add(texts(header));
        int start = headerIndex + 1;
        for (Line line : lines.subList(start, lines.size())) {
            if (line.key().equals(header.key())) {
                continue; // 다음 페이지에 다시 나온 머리줄
            }
            rows.add(alignTo(header, line));
        }
        return rows;
    }

    private static List<String> texts(Line line) {
        return line.cells().stream().map(Cell::text).toList();
    }

    /** 한 줄의 칸을 머리줄 칸에 맞춰 넣는다: 가로로 가장 많이 겹치는 칸, 안 겹치면 가장 가까운 칸 */
    static List<String> alignTo(Line header, Line line) {
        List<Cell> columns = header.cells();
        List<StringBuilder> values = new ArrayList<>();
        columns.forEach(c -> values.add(new StringBuilder()));
        for (Cell cell : line.cells()) {
            int best = 0;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < columns.size(); i++) {
                Cell col = columns.get(i);
                double overlap = Math.min(cell.x1(), col.x1()) - Math.max(cell.x0(), col.x0());
                // 겹치면 겹친 폭(양수), 안 겹치면 떨어진 거리(음수)
                if (overlap > bestScore) {
                    bestScore = overlap;
                    best = i;
                }
            }
            StringBuilder value = values.get(best);
            if (!value.isEmpty()) {
                value.append(' ');
            }
            value.append(cell.text());
        }
        return values.stream().map(StringBuilder::toString).toList();
    }

    private static List<Glyph> glyphs(byte[] content) {
        try (PDDocument doc = Loader.loadPDF(content)) {
            if (doc.getNumberOfPages() > MAX_PAGES) {
                throw new ApiException(ErrorCode.STATEMENT_PDF_TOO_MANY_PAGES, MAX_PAGES);
            }
            List<Glyph> glyphs = new ArrayList<>();
            // writeString 으로 받는 글자는 PDFBox 가 겹쳐 그린 글자(굵게 보이려고 두 번 찍은 것 등)를 이미 뺀 것
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    for (TextPosition t : positions) {
                        String unicode = t.getUnicode();
                        if (unicode != null && !unicode.isBlank()) {
                            glyphs.add(new Glyph(getCurrentPageNo(), t.getXDirAdj(), t.getYDirAdj(), t.getWidthDirAdj(),
                                    Math.max(1, t.getFontSizeInPt()), unicode));
                        }
                    }
                }
            };
            stripper.setSortByPosition(true);
            stripper.getText(doc);
            return glyphs;
        } catch (InvalidPasswordException e) {
            throw new ApiException(ErrorCode.STATEMENT_PDF_PASSWORD);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.STATEMENT_PDF_UNREADABLE);
        }
    }

    /** 글자 → 줄 → 칸 */
    static List<Line> lines(List<Glyph> glyphs) {
        List<Glyph> sorted = new ArrayList<>(glyphs);
        sorted.sort(Comparator.comparingInt(Glyph::page).thenComparingDouble(Glyph::y).thenComparingDouble(Glyph::x));
        List<List<Glyph>> grouped = new ArrayList<>();
        List<Glyph> current = new ArrayList<>();
        for (Glyph g : sorted) {
            if (!current.isEmpty()) {
                Glyph first = current.get(0);
                if (first.page() != g.page() || Math.abs(first.y() - g.y()) > SAME_LINE * Math.max(first.size(), g.size())) {
                    grouped.add(current);
                    current = new ArrayList<>();
                }
            }
            current.add(g);
        }
        if (!current.isEmpty()) {
            grouped.add(current);
        }
        List<Line> lines = new ArrayList<>();
        for (List<Glyph> group : grouped) {
            group.sort(Comparator.comparingDouble(Glyph::x));
            lines.add(new Line(group.get(0).page(), group.get(0).y(), cells(group)));
        }
        return lines;
    }

    private static List<Cell> cells(List<Glyph> line) {
        List<Cell> cells = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        double x0 = line.get(0).x();
        double x1 = x0;
        double size = line.get(0).size();
        for (Glyph g : line) {
            double gap = g.x() - x1;
            if (!text.isEmpty() && gap > CELL_GAP * Math.max(size, g.size())) {
                cells.add(new Cell(x0, x1, normalize(text.toString())));
                text.setLength(0);
                x0 = g.x();
            } else if (!text.isEmpty() && gap > 0.2 * Math.max(size, g.size())) {
                text.append(' '); // 칸 안의 띄어쓰기
            }
            text.append(g.text());
            x1 = Math.max(x1, g.x() + g.width());
            size = g.size();
        }
        cells.add(new Cell(x0, x1, normalize(text.toString())));
        return cells;
    }

    /** PDF 에서 나오는 다른 모양의 빼기 기호(−)를 금액에서 읽을 수 있게 */
    private static String normalize(String text) {
        return text.replace('−', '-').trim();
    }
}
