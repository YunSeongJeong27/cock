package com.depositcock.vision;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OverlapDeduplicatorTest {

    @Test
    void removesOverlapBetweenAdjacentImages() {
        List<List<String>> images = List.of(List.of("a", "b", "c", "d"), List.of("c", "d", "e"), List.of("e", "f"));
        var result = OverlapDeduplicator.dedupe(images, Function.identity());
        assertThat(result).extracting(OverlapDeduplicator.Item::value).containsExactly("a", "b", "c", "d", "e", "f");
        assertThat(result).extracting(OverlapDeduplicator.Item::duplicateSuspected)
                .containsExactly(false, false, true, true, true, false);
    }

    @Test
    void noOverlap() {
        var result = OverlapDeduplicator.dedupe(List.of(List.of("a", "b"), List.of("c")), Function.identity());
        assertThat(result).extracting(OverlapDeduplicator.Item::value).containsExactly("a", "b", "c");
        assertThat(result).noneMatch(OverlapDeduplicator.Item::duplicateSuspected);
    }

    private static List<Boolean> flags(List<OverlapDeduplicator.Item<String>> items) {
        return items.stream().map(OverlapDeduplicator.Item::duplicateSuspected).toList();
    }

    @Test
    void piecesWithExactOverlapAreMergedWithoutFlag() {
        var result = OverlapDeduplicator.mergePieces(List.of(List.of("a", "b", "c"), List.of("c", "d")), Function.identity());
        assertThat(result).extracting(OverlapDeduplicator.Item::value).containsExactly("a", "b", "c", "d");
        assertThat(result).noneMatch(OverlapDeduplicator.Item::duplicateSuspected);
    }

    /** 우리가 겹치게 자른 조각인데 같은 구간이 없다 = 경계의 같은 줄을 다르게 읽었을 수 있음 → 경계 앞뒤 2줄 표시 */
    @Test
    void piecesWithoutOverlapFlagBoundaryRows() {
        var result = OverlapDeduplicator.mergePieces(
                List.of(List.of("a", "b", "c", "d"), List.of("d'", "e", "f", "g")), Function.identity());
        assertThat(result).extracting(OverlapDeduplicator.Item::value)
                .containsExactly("a", "b", "c", "d", "d'", "e", "f", "g");
        assertThat(flags(result)).containsExactly(false, false, true, true, true, true, false, false);
    }

    /** 합친 구간 옆에 똑같은 줄이 또 있으면 겹침이 몇 줄인지 모름 → 표시 (진짜 두 줄이 하나로 합쳐졌을 수 있음) */
    @Test
    void piecesWithRepeatedRowsAroundOverlapAreFlagged() {
        var result = OverlapDeduplicator.mergePieces(List.of(List.of("x", "a", "a"), List.of("a", "a", "y")),
                Function.identity());
        assertThat(result).extracting(OverlapDeduplicator.Item::value).containsExactly("x", "a", "a", "y");
        assertThat(flags(result)).containsExactly(false, true, true, false);
    }

    /** 조각 단계에서 붙은 표시는 이미지 사이 겹침 제거에서도 유지된다 */
    @Test
    void pieceFlagsSurviveImageLevelDedupe() {
        var image1 = OverlapDeduplicator.mergePieces(List.of(List.of("a", "b"), List.of("b2", "c")), Function.identity());
        var image2 = OverlapDeduplicator.mergePieces(List.of(List.of("c", "d")), Function.identity());
        var result = OverlapDeduplicator.dedupeItems(List.of(image1, image2), Function.identity());
        assertThat(result).extracting(OverlapDeduplicator.Item::value).containsExactly("a", "b", "b2", "c", "d");
        // a,b / b2,c 는 조각 경계 표시, c 는 이미지 사이 겹침 표시
        assertThat(flags(result)).containsExactly(true, true, true, true, false);
        assertThat(result).extracting(OverlapDeduplicator.Item::imageIndex).containsExactly(0, 0, 0, 0, 1);
    }
}
