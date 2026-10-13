package com.depositcock.comment;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CommentOrderParserTest {

    @ParameterizedTest(name = "{0} → {1} {2}")
    @CsvSource(delimiter = '|', value = {
            "1|ORDER|1",
            "2박스요|ORDER|2",
            "4팩 예약합니다|ORDER|4",
            "1/1/1|ORDER|3",
            "2+1|ORDER|3",
            "1 7시이후 픽업|ORDER|1",
            "간장닭갈비1세트요 입금했어요|ORDER|1",
            "7과요~|ORDER|7",
            "취소할께요|CANCEL|",
            "캔슬이요|CANCEL|",
            "1개 취소|REVIEW|1",
            "한박스요|REVIEW|1",
            "둘|REVIEW|2",
            "2개로 변경해주세요|REVIEW|2",
            "50개|REVIEW|50",
            "0|REVIEW|0",
            "입금했어요|NOT_ORDER|",
            "감사합니다 주세요|NOT_ORDER|",
            "010-1234-5678|NOT_ORDER|",
            "101동 1203호|NOT_ORDER|",
            "8일 3시에 갈게요|NOT_ORDER|",
            "13,900원 맞나요|NOT_ORDER|",
            "간장1 양념1 이요|REVIEW|",
    })
    void parse(String content, ParseType type, Integer quantity) {
        ParseResult r = CommentOrderParser.parse(content);
        assertThat(r.type()).as(content).isEqualTo(type);
        assertThat(r.quantity()).as(content).isEqualTo(quantity);
    }

    /** 실제로 났던 버그: "1 7시이후 픽업" 의 메모가 "이후 픽업"으로 잘림 */
    @Test
    void memoIsNotCut() {
        assertThat(CommentOrderParser.parse("1 7시이후 픽업").memo()).isEqualTo("7시이후 픽업");
        assertThat(CommentOrderParser.parse("2박스요").memo()).isEqualTo("요");
    }

    /** 알려진 한계 (정리본 8장): 날짜 "10/8" 을 합산 18 로 오인 → 30 초과 아니므로 ORDER 18 */
    @Test
    void knownLimitationSlashDate() {
        assertThat(CommentOrderParser.parse("10/8").quantity()).isEqualTo(18);
    }

    /** 기대 결과 픽스처 (파프리카 공구, 1,900원): 고객 7명, 7개, 13,300원. "205-1205" 메모 = "7시이후 픽업" */
    @Test
    void paprikaFixture() {
        List<OrderAggregator.Input> comments = List.of(
                new OrderAggregator.Input("꼭지맘 217-1505", "1", null),
                new OrderAggregator.Input("205-1205", "1 7시이후 픽업", null),
                new OrderAggregator.Input("망고맘", "1", null),
                new OrderAggregator.Input("쏠", "1", null),
                new OrderAggregator.Input("신한화", "1", null),
                new OrderAggregator.Input("캐파101동", "1", null),
                new OrderAggregator.Input("신지", "1", null),
                new OrderAggregator.Input("공구장", "🚫🚫1시부터 현장구매부탁드려요 댓글 ❌🚫🚫", null));
        List<OrderAggregator.CustomerTotal> totals = OrderAggregator.aggregate(comments, List.of("공구장"));
        assertThat(totals).hasSize(7);
        assertThat(totals.stream().mapToInt(OrderAggregator.CustomerTotal::quantity).sum()).isEqualTo(7);
        assertThat(totals.stream().mapToInt(OrderAggregator.CustomerTotal::quantity).sum() * 1900).isEqualTo(13300);
        assertThat(totals.stream().filter(t -> t.authorName().equals("205-1205")).findFirst().orElseThrow().memo())
                .isEqualTo("7시이후 픽업");
    }

    @Test
    void cancelThenReorderAndManualQuantity() {
        List<OrderAggregator.CustomerTotal> totals = OrderAggregator.aggregate(List.of(
                new OrderAggregator.Input("A", "2", null),
                new OrderAggregator.Input("A", "취소요", null),
                new OrderAggregator.Input("A", "1", null),
                new OrderAggregator.Input("B", "한박스요", null),
                new OrderAggregator.Input("C", "한박스요", 1),
                new OrderAggregator.Input("D", "안녕하세요", null),
                new OrderAggregator.Input("E", "3", null),
                new OrderAggregator.Input("E", "취소", null)), List.of());
        assertThat(totals).extracting(OrderAggregator.CustomerTotal::authorName).containsExactly("A", "B", "C");
        assertThat(totals.get(0).quantity()).isEqualTo(1);
        assertThat(totals.get(1).quantity()).isZero();
        assertThat(totals.get(1).needsReview()).isTrue();
        assertThat(totals.get(2).quantity()).isEqualTo(1);
    }
}
