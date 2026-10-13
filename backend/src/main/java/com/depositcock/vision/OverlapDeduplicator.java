package com.depositcock.vision;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 스크롤 캡처 겹침 제거 (정리본 7장): 앞 이미지의 끝과 다음 이미지의 시작에서 연속으로 같은 구간을 찾아 한 번만 남긴다.
 * 일부러 같은 내용을 두 번 쓴 경우와 구분할 수 없으므로, 남긴 쪽에 "중복 의심" 표시를 한다.
 */
public final class OverlapDeduplicator {

    public record Item<T>(T value, int imageIndex, boolean duplicateSuspected) {
    }

    private OverlapDeduplicator() {
    }

    public static <T> List<Item<T>> dedupe(List<List<T>> perImage, Function<T, String> key) {
        List<Item<T>> result = new ArrayList<>();
        for (int img = 0; img < perImage.size(); img++) {
            List<T> current = perImage.get(img);
            int overlap = 0;
            if (img > 0 && !result.isEmpty()) {
                List<T> previous = perImage.get(img - 1);
                int max = Math.min(previous.size(), current.size());
                for (int k = max; k >= 1; k--) {
                    if (sameSegment(previous.subList(previous.size() - k, previous.size()), current.subList(0, k), key)) {
                        overlap = k;
                        break;
                    }
                }
                // 겹친 구간은 앞 이미지 쪽 항목을 남기고 "중복 의심" 표시
                for (int k = 0; k < overlap; k++) {
                    int idx = result.size() - overlap + k;
                    Item<T> kept = result.get(idx);
                    result.set(idx, new Item<>(kept.value(), kept.imageIndex(), true));
                }
            }
            for (int i = overlap; i < current.size(); i++) {
                result.add(new Item<>(current.get(i), img, false));
            }
        }
        return result;
    }

    private static <T> boolean sameSegment(List<T> a, List<T> b, Function<T, String> key) {
        for (int i = 0; i < a.size(); i++) {
            if (!key.apply(a.get(i)).equals(key.apply(b.get(i)))) {
                return false;
            }
        }
        return true;
    }
}
