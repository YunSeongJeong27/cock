package com.depositcock.comment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 댓글 집계 (정리본 8장 "집계"):
 * - 관리자 닉네임 제외
 * - 시간순: ORDER 는 합산, CANCEL 이면 그때까지 수량 0 (이후 새 주문은 다시 합산)
 * - REVIEW 는 판매자가 수량을 정하기 전까지 합계에서 제외. 수동 수량이 있으면 해석보다 우선
 * - 잡담만 한 사람은 제외
 */
public final class OrderAggregator {

    public record Input(String authorName, String content, Integer manualQuantity) {
    }

    public record CustomerTotal(String customerKey, String authorName, int quantity, String memo, boolean needsReview) {
    }

    private OrderAggregator() {
    }

    public static String customerKey(String authorName) {
        return "nick:" + authorName.trim();
    }

    public static boolean isAdmin(String authorName, List<String> adminNames) {
        String name = authorName == null ? "" : authorName.trim();
        return adminNames.stream().anyMatch(a -> a.equals(name));
    }

    public static List<CustomerTotal> aggregate(List<Input> comments, List<String> adminNames) {
        Map<String, int[]> quantity = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, List<String>> memos = new LinkedHashMap<>();
        Map<String, Boolean> review = new LinkedHashMap<>();
        Map<String, Boolean> ordered = new LinkedHashMap<>();

        for (Input c : comments) {
            if (c.authorName() == null || c.authorName().isBlank() || isAdmin(c.authorName(), adminNames)) {
                continue;
            }
            String key = customerKey(c.authorName());
            ParseResult r = CommentOrderParser.parse(c.content());
            int[] q = quantity.computeIfAbsent(key, x -> new int[1]);
            names.putIfAbsent(key, c.authorName().trim());
            if (c.manualQuantity() != null) {
                q[0] += c.manualQuantity();
                ordered.put(key, true);
                addMemo(memos, key, r.memo());
                continue;
            }
            switch (r.type()) {
                case ORDER -> {
                    q[0] += r.quantity();
                    ordered.put(key, true);
                    addMemo(memos, key, r.memo());
                }
                case CANCEL -> q[0] = 0;
                case REVIEW -> review.put(key, true);
                case NOT_ORDER -> {
                    // 잡담
                }
            }
        }
        List<CustomerTotal> result = new ArrayList<>();
        for (String key : quantity.keySet()) {
            boolean needsReview = review.getOrDefault(key, false);
            int total = quantity.get(key)[0];
            if (!ordered.getOrDefault(key, false) && !needsReview) {
                continue; // 잡담만 한 사람
            }
            if (total == 0 && !needsReview) {
                continue; // 주문 후 취소
            }
            result.add(new CustomerTotal(key, names.get(key), total,
                    String.join(" / ", memos.getOrDefault(key, List.of())), needsReview));
        }
        return result;
    }

    private static void addMemo(Map<String, List<String>> memos, String key, String memo) {
        if (memo != null && !memo.isBlank()) {
            memos.computeIfAbsent(key, x -> new ArrayList<>()).add(memo);
        }
    }
}
