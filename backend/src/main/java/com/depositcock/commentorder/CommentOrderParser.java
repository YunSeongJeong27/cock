package com.depositcock.commentorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 댓글 해석 규칙 (docs/design.md 정리본 8장). 단일 상품 공구 기준.
 * <pre>
 * 1) 수량이 아닌 숫자(전화번호, 동-호, N동/N호, 시각, 날짜, 금액, N번째/층/차 등)는 같은 길이의 공백으로 가린 뒤 해석한다
 *    (원문 위치를 유지해야 메모가 정확히 분리된다).
 * 2) 숫자 수량: (\d{1,3}(?:\s*[/+]\s*\d{1,3})*)(?:\s*단위)?  "1/1/1" → 3, "2+1" → 3 (합산)
 *    단위 앞 \s* 는 그룹 안쪽에 둔다 (밖에 두면 "1 7시이후 픽업" 의 메모가 잘리는 버그).
 * 3) 한글 수량 + 단위 ("한박스"), 또는 단독 "하나/둘/셋/넷" → 추정 수량과 함께 REVIEW
 * 4) "취소|캔슬": 수량 없으면 CANCEL, 수량 있으면 REVIEW
 * 5) 수량 후보 0개 → NOT_ORDER, 2개 이상 → REVIEW
 * 6) "수정|변경|바꿔|말고" → REVIEW
 * 7) 수량 0 또는 30 초과 → REVIEW
 * 8) 메모 = 원문에서 수량 부분을 뺀 나머지. 주문 말끝("1박스요"의 "요", "주세요", "부탁드려요" 등)과
 *    단어 앞뒤 기호·이모티콘은 뺀다 (2026-10-26, 전에는 "1박스요" → 메모 "요")
 * 알려진 한계: "10/8"(날짜)을 18로 오인할 수 있다.
 * </pre>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CommentOrderParser {

    private static final String UNIT = "(?:봉지|박스|세트|묶음|포기|마리|키로|kg|개|봉|팩|통|단|판|병)";

    private static final List<Pattern> NOT_QUANTITY = List.of(
            Pattern.compile("01[016789]-?\\d{3,4}-?\\d{4}"),
            Pattern.compile("\\d{1,4}\\s*-\\s*\\d{3,4}"),
            Pattern.compile("\\d+\\s*(?:동|호)"),
            Pattern.compile("\\d{1,2}\\s*시(?:\\s*\\d{1,2}\\s*분)?"),
            Pattern.compile("\\d{1,2}\\s*분"),
            Pattern.compile("\\d{1,2}\\s*(?:일|월)"),
            Pattern.compile("\\d[\\d,]*\\s*(?:원|만원)"),
            Pattern.compile("\\d+\\s*(?:번째|등|층|차)"));

    private static final Pattern NUMERIC_QTY = Pattern.compile(
            "(?<!\\d)(\\d{1,3}(?:\\s*[/+]\\s*\\d{1,3})*)(?!\\d)(\\s*" + UNIT + ")?");

    private static final Map<String, Integer> KOREAN_NUMBERS = Map.ofEntries(
            Map.entry("한", 1), Map.entry("하나", 1), Map.entry("두", 2), Map.entry("둘", 2),
            Map.entry("세", 3), Map.entry("셋", 3), Map.entry("석", 3), Map.entry("네", 4), Map.entry("넷", 4),
            Map.entry("넉", 4), Map.entry("다섯", 5), Map.entry("여섯", 6), Map.entry("일곱", 7), Map.entry("여덟", 8),
            Map.entry("아홉", 9), Map.entry("열", 10));

    /** 한글 수량 + 단위 (단위 필수: "주세요"의 '세' 오인 방지) */
    private static final Pattern KOREAN_QTY = Pattern.compile(
            "(다섯|여섯|일곱|여덟|아홉|하나|한|둘|두|셋|세|석|넷|네|넉|열)(\\s*" + UNIT + ")");

    /** 단독 "하나/둘/셋/넷" (앞뒤가 한글이 아님) */
    private static final Pattern KOREAN_ALONE = Pattern.compile("(?<![가-힣])(하나|둘|셋|넷)(?![가-힣])");

    /** 수량 바로 뒤에 붙는 말끝 ("1박스요", "2개만", "1개씩이요") */
    private static final Pattern ATTACHED_ENDING = Pattern.compile("^(?:씩|만)?(?:이요|요)?(?![가-힣])");

    /** 메모에서 뺄 주문 말 (띄어 쓴 한 단어 전체가 이것일 때만) */
    private static final Pattern FILLER_WORD = Pattern.compile(
            "(?:주문|신청|구매|예약|부탁)(?:이요|요|해요|합니다|할게요|할께요|드려요|드립니다)?"
                    + "|드려요|드립니다|요|이요|주세요|주셔요|줘요|할게요|할께요|감사합니다|감사해요|고맙습니다");

    /** 단어 앞뒤의 기호·이모티콘 (한글·영문·숫자가 아닌 것) */
    private static final Pattern EDGE_SYMBOLS = Pattern.compile("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$");

    private static final Pattern CANCEL = Pattern.compile("취소|캔슬");
    private static final Pattern MODIFY = Pattern.compile("수정|변경|바꿔|말고");

    private record Candidate(int start, int end, int quantity, boolean korean) {
    }

    public static ParseResult parse(String content) {
        String original = content == null ? "" : content;
        String masked = mask(original);

        List<Candidate> candidates = new ArrayList<>();
        Matcher m = NUMERIC_QTY.matcher(masked);
        while (m.find()) {
            int sum = 0;
            for (String part : m.group(1).split("[/+]")) {
                sum += Integer.parseInt(part.trim());
            }
            candidates.add(new Candidate(m.start(), m.end(), sum, false));
        }
        Matcher k = KOREAN_QTY.matcher(masked);
        while (k.find()) {
            candidates.add(new Candidate(k.start(), k.end(), KOREAN_NUMBERS.get(k.group(1)), true));
        }
        Matcher alone = KOREAN_ALONE.matcher(masked);
        while (alone.find()) {
            int s = alone.start();
            boolean overlaps = candidates.stream().anyMatch(c -> s >= c.start() && s < c.end());
            if (!overlaps) {
                candidates.add(new Candidate(alone.start(), alone.end(), KOREAN_NUMBERS.get(alone.group(1)), true));
            }
        }

        boolean cancel = CANCEL.matcher(original).find();
        if (cancel) {
            if (candidates.isEmpty()) {
                return new ParseResult(ParseType.CANCEL, null, clean(original), null);
            }
            Candidate c = candidates.get(0);
            return new ParseResult(ParseType.REVIEW, c.quantity(), memo(original, c), "취소와 수량이 함께 있음");
        }
        if (candidates.isEmpty()) {
            return new ParseResult(ParseType.NOT_ORDER, null, clean(original), null);
        }
        if (candidates.size() >= 2) {
            return new ParseResult(ParseType.REVIEW, null, clean(original), "수량 후보가 여러 개");
        }
        Candidate c = candidates.get(0);
        String memo = memo(original, c);
        if (c.korean()) {
            return new ParseResult(ParseType.REVIEW, c.quantity(), memo, "한글 수량 추정");
        }
        if (MODIFY.matcher(original).find()) {
            return new ParseResult(ParseType.REVIEW, c.quantity(), memo, "수정·변경 요청");
        }
        if (c.quantity() == 0 || c.quantity() > 30) {
            return new ParseResult(ParseType.REVIEW, c.quantity(), memo, "수량이 0 이거나 30 초과");
        }
        return new ParseResult(ParseType.ORDER, c.quantity(), memo, null);
    }

    static String mask(String text) {
        String result = text;
        for (Pattern p : NOT_QUANTITY) {
            Matcher m = p.matcher(result);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, " ".repeat(m.group().length()));
            }
            m.appendTail(sb);
            result = sb.toString();
        }
        return result;
    }

    private static String memo(String original, Candidate c) {
        String after = original.substring(c.end());
        Matcher ending = ATTACHED_ENDING.matcher(after);
        if (ending.find()) {
            after = after.substring(ending.end());
        }
        return Arrays.stream(clean(original.substring(0, c.start()) + " " + after).split(" "))
                .map(word -> EDGE_SYMBOLS.matcher(word).replaceAll(""))
                .filter(word -> !word.isEmpty() && !FILLER_WORD.matcher(word).matches())
                .collect(Collectors.joining(" "));
    }

    private static String clean(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
