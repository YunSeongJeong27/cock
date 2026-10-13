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
}
