package com.depositcock.vision;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 스크롤 캡처 겹침 제거 (정리본 7장): 앞 이미지의 끝과 다음 이미지의 시작에서 연속으로 같은 구간을 찾아 한 번만 남긴다.
 * <ul>
 *   <li>사용자가 올린 이미지 사이: 일부러 같은 내용을 두 번 쓴 경우와 구분할 수 없으므로, 남긴 쪽에 "중복 의심" 표시를 한다.</li>
 *   <li>한 장을 우리가 겹치게 자른 조각 사이: 겹침이 있다는 걸 알고 있으므로 합친 줄은 표시하지 않는다. 대신
 *       (1) 같은 구간을 못 찾으면(같은 줄을 조각마다 다르게 읽은 경우 등) 경계 앞뒤 줄을 표시하고,
 *       (2) 합친 구간 근처에 똑같은 줄이 연달아 있으면(진짜 두 줄인지 겹침인지 모름) 그 줄들을 표시한다.</li>
 * </ul>
 * 조각 단계에서 붙은 표시는 이미지 사이 단계에서도 그대로 남는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OverlapDeduplicator {

    /** 조각 경계에서 같은 구간을 못 찾았을 때 경계 앞뒤로 표시할 줄 수 */
    static final int BOUNDARY_ROWS = 2;

    public record Item<T>(T value, int imageIndex, boolean duplicateSuspected) {

        Item<T> flagged() {
            return duplicateSuspected ? this : new Item<>(value, imageIndex, true);
        }
    }

    /** 사용자가 올린 이미지들 사이의 겹침을 합친다 */
    public static <T> List<Item<T>> dedupe(List<List<T>> perImage, Function<T, String> key) {
        return merge(wrap(perImage), key, true);
    }

    /** 조각을 합친 결과(표시 포함)를 이미지 사이에서 합친다. 조각 단계의 표시는 유지된다 */
    public static <T> List<Item<T>> dedupeItems(List<List<Item<T>>> perImage, Function<T, String> key) {
        return merge(perImage, key, true);
    }

    /** 한 장을 우리가 겹치게 자른 조각들을 합친다 */
    public static <T> List<Item<T>> mergePieces(List<List<T>> pieces, Function<T, String> key) {
        return merge(wrap(pieces), key, false);
    }

    private static <T> List<List<Item<T>>> wrap(List<List<T>> groups) {
        List<List<Item<T>>> result = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
            int index = g;
            result.add(groups.get(g).stream().map(v -> new Item<>(v, index, false)).toList());
        }
        return result;
    }

    /**
     * @param userOverlap true 면 사용자가 찍은 이미지 사이(합친 줄 표시), false 면 우리가 자른 조각 사이
     */
    private static <T> List<Item<T>> merge(List<List<Item<T>>> groups, Function<T, String> key, boolean userOverlap) {
        List<Item<T>> result = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
            List<Item<T>> current = groups.get(g);
            List<Item<T>> previous = g > 0 ? groups.get(g - 1) : List.of();
            int overlap = 0;
            Set<Integer> flagCurrent = new HashSet<>();
            if (!result.isEmpty() && !previous.isEmpty() && !current.isEmpty()) {
                overlap = longestOverlap(previous, current, key);
                int start = result.size() - overlap;
                if (overlap > 0) {
                    boolean ambiguous = !userOverlap && repeatsAround(result, start, current, overlap, key);
                    // 겹친 구간은 앞 이미지 쪽 항목을 남긴다. 버리는 쪽에 붙어 있던 표시도 옮긴다
                    for (int k = 0; k < overlap; k++) {
                        Item<T> kept = result.get(start + k);
                        if (userOverlap || ambiguous || current.get(k).duplicateSuspected()) {
                            result.set(start + k, kept.flagged());
                        }
                    }
                    if (ambiguous) {
                        // 합친 줄과 똑같은 바로 앞·뒤 줄도 함께 확인하게 한다
                        if (start > 0 && sameKey(result.get(start - 1), current.get(0), key)) {
                            result.set(start - 1, result.get(start - 1).flagged());
                        }
                        if (overlap < current.size() && sameKey(current.get(overlap), current.get(overlap - 1), key)) {
                            flagCurrent.add(overlap);
                        }
                    }
                } else if (!userOverlap) {
                    // 겹치게 잘랐는데 같은 구간이 없다 → 같은 줄이 두 번 들어갔을 수 있으니 경계 앞뒤를 확인하게 한다
                    int count = Math.min(BOUNDARY_ROWS, Math.min(previous.size(), result.size()));
                    for (int k = result.size() - count; k < result.size(); k++) {
                        result.set(k, result.get(k).flagged());
                    }
                    for (int k = 0; k < Math.min(BOUNDARY_ROWS, current.size()); k++) {
                        flagCurrent.add(k);
                    }
                }
            }
            for (int i = overlap; i < current.size(); i++) {
                Item<T> item = current.get(i);
                result.add(new Item<>(item.value(), g, item.duplicateSuspected() || flagCurrent.contains(i)));
            }
        }
        return result;
    }

    private static <T> int longestOverlap(List<Item<T>> previous, List<Item<T>> current, Function<T, String> key) {
        for (int k = Math.min(previous.size(), current.size()); k >= 1; k--) {
            if (sameSegment(previous.subList(previous.size() - k, previous.size()), current.subList(0, k), key)) {
                return k;
            }
        }
        return 0;
    }

    /** 합친 구간과 그 바로 앞·뒤 줄 중에 똑같은 줄이 연달아 있는지 (겹침이 몇 줄인지 확실하지 않음) */
    private static <T> boolean repeatsAround(List<Item<T>> result, int start, List<Item<T>> current, int overlap,
                                             Function<T, String> key) {
        List<String> keys = new ArrayList<>();
        if (start > 0) {
            keys.add(key.apply(result.get(start - 1).value()));
        }
        for (int k = 0; k < overlap; k++) {
            keys.add(key.apply(current.get(k).value()));
        }
        if (overlap < current.size()) {
            keys.add(key.apply(current.get(overlap).value()));
        }
        for (int i = 1; i < keys.size(); i++) {
            if (keys.get(i).equals(keys.get(i - 1))) {
                return true;
            }
        }
        return false;
    }

    private static <T> boolean sameKey(Item<T> a, Item<T> b, Function<T, String> key) {
        return key.apply(a.value()).equals(key.apply(b.value()));
    }

    private static <T> boolean sameSegment(List<Item<T>> a, List<Item<T>> b, Function<T, String> key) {
        for (int i = 0; i < a.size(); i++) {
            if (!key.apply(a.get(i).value()).equals(key.apply(b.get(i).value()))) {
                return false;
            }
        }
        return true;
    }
}
