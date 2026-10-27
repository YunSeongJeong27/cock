package com.depositcock.deposit;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.depositcock.common.Texts;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

/**
 * 입금 대조 규칙 (정리본 6-2장). 결과는 저장하지 않고, 판매자가 "확정"을 눌러야 저장한다.
 *
 * <pre>
 * 근거 판정 순서 (이름 비교는 띄어쓰기·영문 대소문자 무시)
 *   0) 입금자명이 주문서의 입금자명과 정확히 일치 (최우선)
 *   a) 저장된 별칭 (입금자명 → 고객 키)
 *   b) 입금자명의 한글/영문(2자 이상)이 닉네임에 포함
 *   c) 입금자명의 숫자(3자리 이상, 이어붙인 값 포함)가 닉네임 숫자와 일치. 예: "217-1505" → {217, 1505, 2171505}
 * 결과
 *   MATCHED   : 금액 일치 + 근거 있음 + 후보 1명 (0)·a) 근거가 한 고객에게만 있으면 b)·c) 근거의 다른 고객보다 우선)
 *   SUGGESTED : 근거 있음 + 후보 1명 + 금액 다름 → "금액 차이 ±N원"
 *   SUGGESTED : 근거 없음 + 금액만 일치 + 그 금액의 미입금 주문이 1건뿐 (그 금액의 연결 안 된 입금도 1건뿐,
 *               그 주문에 이름 근거로 추천된 다른 입금이 없음, 이 입금이 이미 연결·확인·취소된 주문의 입금자와 같지 않음)
 *   AMBIGUOUS : 후보가 2명 이상 → 자동 확정 금지, 판매자가 고른다 (목록의 첫 번째에 임의로 붙이지 않는다)
 *   UNRELATED : 관련 없는 입금
 * 확인 필요 (2026-10-26): 자동 매칭·추천(이름 근거)이어도 같은 고객의 미입금 주문이나 그 고객으로 보이는 입금이
 *   여러 건이면 짝이 맞는지 판매자가 확인하게 표시한다 (Outcome.checks)
 * 입금 1건은 주문 1건에만 쓴다. "후보 몇 명"은 고객(customerId) 수로 센다 (한 고객의 주문 여러 건은 1명).
 * 1차(자동 매칭)는 입금마다 다른 입금과 상관없이 판단해서, 거래내역의 줄 순서에 따라 결과가 달라지지 않게 한다.
 * </pre>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class DepositMatcher {

    public enum Status {
        MATCHED,
        SUGGESTED,
        AMBIGUOUS,
        UNRELATED
    }

    @Getter
    @RequiredArgsConstructor
    public enum Evidence {
        DEPOSITOR_NAME("입금자명 일치"),
        ALIAS("저장된 별칭"),
        NICKNAME_TEXT("닉네임 포함"),
        NICKNAME_NUMBER("닉네임 숫자 일치"),
        AMOUNT_ONLY("금액만 일치");

        private final String label;

        boolean strong() {
            return this == DEPOSITOR_NAME || this == ALIAS;
        }
    }

    /** 관련 없는 입금에 붙이는 확인 안내: 이 입금자가 어떤 주문과 이미 관련 있는지 */
    public enum Relation {
        /** 이번 대조에서 같은 입금자의 다른 입금이 이 주문에 연결됨 → 중복 입금? */
        LINKED,
        /** 이미 입금 확인된 주문의 입금자 → 중복 입금? */
        PAID,
        /** 취소된 주문의 입금자 → 환불 필요? */
        CANCELLED,
        /** 이번 대조에서 같은 고객의 다른 입금이 이 주문에 추천됨 → 나눠 보냄·중복 입금? (2026-10-19) */
        SUGGESTED
    }

    /** 대조 대상 주문 (선입금, 취소 안 됨, 입금 확정 안 됨, 금액 > 0) */
    public record Payee(Long orderId, Long customerId, String depositorName, String nickname, int expectedAmount,
                        LocalDateTime orderedAt) {
    }

    /** 대조 대상은 아니지만 입금자 확인에 쓰는 주문 (이미 입금 확인됨 / 취소됨) */
    public record Known(Payee order, Relation relation) {
    }

    /** 입금 1건 */
    public record Deposit(Long rowId, String name, int amount) {
    }

    public record Candidate(Long orderId, Evidence evidence, int amountDiff) {
    }

    public record Related(Long orderId, Relation relation) {
    }

    public record Result(Deposit deposit, Status status, Long orderId, Evidence evidence, int amountDiff,
                         List<Candidate> candidates, Related related) {
    }

    /** 확인 필요: 같은 고객의 미입금 주문 수·그 고객으로 보이는 입금 수 */
    public record Check(int orders, int deposits) {
    }

    /** @param checks 입금 줄 id → 확인 필요 (자동 매칭·추천 중 같은 고객의 주문이나 입금이 여러 건인 것) */
    public record Outcome(List<Result> results, List<Payee> unpaid, Map<Long, Check> checks) {
    }

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern NOT_LETTER = Pattern.compile("[^가-힣a-zA-Z]");

    /** @param aliasToCustomerId 입금자명(별칭) → 고객 id */
    public static Outcome match(List<Payee> payees, List<Deposit> deposits, Map<String, Long> aliasToCustomerId) {
        return match(payees, List.of(), deposits, aliasToCustomerId);
    }

    public static Outcome match(List<Payee> payees, List<Known> known, List<Deposit> deposits,
                                Map<String, Long> aliasToCustomerId) {
        Map<String, Long> aliases = new HashMap<>();
        aliasToCustomerId.forEach((name, id) -> aliases.put(normalize(name), id));
        List<Payee> targets = payees.stream().filter(p -> p.expectedAmount() > 0)
                .sorted(Comparator.comparing(Payee::orderedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Payee::orderId))
                .toList();
        Map<Long, Payee> byId = new HashMap<>();
        targets.forEach(p -> byId.put(p.orderId(), p));
        Result[] results = new Result[deposits.size()];
        Set<Long> reserved = new HashSet<>();

        // 1차: 입금마다 "확실한 고객"을 다른 입금과 상관없이 정한다. 같은 고객으로 정해진 입금들은
        // 그 고객의 금액이 맞는 주문에 (주문 시각 순으로) 하나씩 붙인다. 주문보다 입금이 많으면 남는 입금은 2차로.
        for (int i = 0; i < deposits.size(); i++) {
            Deposit d = deposits.get(i);
            List<Candidate> certain = certainOrders(d, candidates(d, targets, aliases, Set.of()), byId);
            for (Candidate c : certain) {
                if (reserved.add(c.orderId())) {
                    results[i] = new Result(d, Status.MATCHED, c.orderId(), c.evidence(), 0, List.of(c), null);
                    break;
                }
            }
        }

        // 1차에서 연결 안 된 입금의 금액별 건수 (금액만 일치 추천은 그 금액의 입금이 1건일 때만)
        Map<Integer, Integer> unmatchedByAmount = new HashMap<>();
        for (int i = 0; i < deposits.size(); i++) {
            if (results[i] == null) {
                unmatchedByAmount.merge(deposits.get(i).amount(), 1, Integer::sum);
            }
        }

        // 2차: 근거 있는 입금 → 추천(후보 1명) / 후보 여러 명
        Set<Long> suggestedByEvidence = new HashSet<>();
        Candidate[] single = new Candidate[deposits.size()];
        Map<Long, Integer> bestForOrder = new HashMap<>();
        for (int i = 0; i < deposits.size(); i++) {
            if (results[i] != null) {
                continue;
            }
            Deposit d = deposits.get(i);
            List<Candidate> withEvidence = sorted(candidates(d, targets, aliases, reserved), byId);
            if (withEvidence.isEmpty()) {
                continue;
            }
            long customers = withEvidence.stream().map(c -> byId.get(c.orderId()).customerId()).distinct().count();
            if (customers == 1) {
                Candidate c = withEvidence.get(0); // 같은 고객의 주문 중 금액이 맞는(가까운) 것
                single[i] = c;
                suggestedByEvidence.add(c.orderId());
                Integer best = bestForOrder.get(c.orderId());
                if (best == null || Math.abs(c.amountDiff()) < Math.abs(single[best].amountDiff())) {
                    bestForOrder.put(c.orderId(), i);
                }
            } else {
                withEvidence.forEach(c -> suggestedByEvidence.add(c.orderId()));
                results[i] = new Result(d, Status.AMBIGUOUS, null, null, 0, withEvidence, null);
            }
        }
        // 한 주문에는 추천 1건만 (2026-10-19): 같은 고객의 입금이 여러 건이면 금액이 가장 가까운 입금에만 추천하고,
        // 나머지는 "나눠 보냈거나 중복 입금" 확인 안내로 둔다 (같은 주문에 두 번 확정하려다 오류가 나지 않게)
        for (int i = 0; i < deposits.size(); i++) {
            Candidate c = single[i];
            if (c == null) {
                continue;
            }
            Deposit d = deposits.get(i);
            results[i] = bestForOrder.get(c.orderId()) == i
                    ? new Result(d, Status.SUGGESTED, c.orderId(), c.evidence(), c.amountDiff(), List.of(c), null)
                    : new Result(d, Status.UNRELATED, null, null, 0, List.of(), new Related(c.orderId(), Relation.SUGGESTED));
        }

        // 3차: 근거 없는 입금 → 금액만 일치 추천 / 관련 없음
        for (int i = 0; i < deposits.size(); i++) {
            if (results[i] != null) {
                continue;
            }
            Deposit d = deposits.get(i);
            Related related = related(d, targets, reserved, known, aliases);
            List<Payee> sameAmount = targets.stream()
                    .filter(p -> !reserved.contains(p.orderId()) && p.expectedAmount() == d.amount()).toList();
            // 같은 금액의 연결 안 된 입금이 여러 건이면 어느 입금이 그 주문의 것인지 알 수 없으므로 추천하지 않는다.
            // 이 입금자가 이미 다른 주문(연결·입금 확인·취소)의 입금자이면 중복 입금·환불일 수 있으므로 남의 주문에 붙이지 않는다.
            if (related == null && sameAmount.size() == 1 && unmatchedByAmount.getOrDefault(d.amount(), 0) == 1
                    && !suggestedByEvidence.contains(sameAmount.get(0).orderId())) {
                Candidate c = new Candidate(sameAmount.get(0).orderId(), Evidence.AMOUNT_ONLY, 0);
                results[i] = new Result(d, Status.SUGGESTED, c.orderId(), c.evidence(), 0, List.of(c), null);
            } else {
                results[i] = new Result(d, Status.UNRELATED, null, null, 0, List.of(), related);
            }
        }

        // 입금이 안 보이는 주문: 자동 매칭·추천·후보 어디에도 안 나온 주문
        Set<Long> mentioned = new HashSet<>(reserved);
        for (Result r : results) {
            if (r.orderId() != null) {
                mentioned.add(r.orderId());
            }
            if (r.status() == Status.AMBIGUOUS) {
                r.candidates().forEach(c -> mentioned.add(c.orderId()));
            }
        }
        List<Payee> unpaid = targets.stream().filter(p -> !mentioned.contains(p.orderId())).toList();
        return new Outcome(List.of(results), unpaid, checks(results, deposits, targets, byId, aliases));
    }

    /**
     * 확인 필요 (2026-10-26 사용자 "중복되는 건 확인 필요 표시는 다 해 줘야"): 자동 매칭·추천(이름 근거)이어도
     * 같은 고객의 미입금 주문이 여러 건이거나 그 고객으로 보이는 입금이 여러 건이면 짝이 바뀌었을 수 있다
     */
    private static Map<Long, Check> checks(Result[] results, List<Deposit> deposits, List<Payee> targets,
                                           Map<Long, Payee> byId, Map<String, Long> aliases) {
        Map<Long, Integer> ordersByCustomer = new HashMap<>();
        targets.forEach(p -> ordersByCustomer.merge(p.customerId(), 1, Integer::sum));
        Map<Long, Integer> depositsByCustomer = new HashMap<>();
        for (Deposit d : deposits) {
            candidates(d, targets, aliases, Set.of()).stream().map(c -> byId.get(c.orderId()).customerId()).distinct()
                    .forEach(customer -> depositsByCustomer.merge(customer, 1, Integer::sum));
        }
        Map<Long, Check> checks = new HashMap<>();
        for (Result r : results) {
            boolean byName = r.status() == Status.MATCHED
                    || (r.status() == Status.SUGGESTED && r.evidence() != Evidence.AMOUNT_ONLY);
            if (!byName || r.orderId() == null) {
                continue;
            }
            Long customer = byId.get(r.orderId()).customerId();
            int orders = ordersByCustomer.getOrDefault(customer, 0);
            int sameDeposits = depositsByCustomer.getOrDefault(customer, 0);
            if (orders > 1 || sameDeposits > 1) {
                checks.put(r.deposit().rowId(), new Check(orders, sameDeposits));
            }
        }
        return checks;
    }

    /**
     * 이 입금 하나만 보고 확실히 고를 수 있는 주문들 (같은 고객의 금액 맞는 주문, 주문 시각 순). 없으면 빈 목록.
     * - 가장 강한 근거 등급의 후보가 한 고객뿐이어야 한다. 0)·a) 는 그 근거끼리, b)·c) 는 같은 등급으로 본다.
     * - b)·c) 근거만 있으면 근거 있는 고객 전체가 1명이어야 한다.
     */
    private static List<Candidate> certainOrders(Deposit d, List<Candidate> all, Map<Long, Payee> byId) {
        if (all.isEmpty()) {
            return List.of();
        }
        Evidence best = all.stream().map(Candidate::evidence).min(Comparator.naturalOrder()).orElseThrow();
        List<Candidate> top = best.strong() ? all.stream().filter(c -> c.evidence() == best).toList() : all;
        if (top.stream().map(c -> byId.get(c.orderId()).customerId()).distinct().count() != 1) {
            return List.of();
        }
        return sorted(top.stream().filter(c -> c.amountDiff() == 0).toList(), byId);
    }

    /** 근거가 강한 순 → 금액 차이가 작은 순 → 주문 시각 순 */
    private static List<Candidate> sorted(List<Candidate> candidates, Map<Long, Payee> byId) {
        return candidates.stream()
                .sorted(Comparator.comparing(Candidate::evidence)
                        .thenComparingInt(c -> Math.abs(c.amountDiff()))
                        .thenComparing(c -> byId.get(c.orderId()).orderedAt(), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Candidate::orderId))
                .toList();
    }

    /** 근거 없는 입금이 이미 다른 주문(이번에 연결됨 / 입금 확인됨 / 취소됨)의 입금자인지 */
    private static Related related(Deposit d, List<Payee> targets, Set<Long> reserved, List<Known> known,
                                   Map<String, Long> aliases) {
        for (Payee p : targets) {
            if (reserved.contains(p.orderId()) && evidence(d.name(), p, aliases) != null) {
                return new Related(p.orderId(), Relation.LINKED);
            }
        }
        for (Relation relation : List.of(Relation.PAID, Relation.CANCELLED)) {
            for (Known k : known) {
                if (k.relation() == relation && evidence(d.name(), k.order(), aliases) != null) {
                    return new Related(k.order().orderId(), relation);
                }
            }
        }
        return null;
    }

    private static List<Candidate> candidates(Deposit d, List<Payee> targets, Map<String, Long> aliases,
                                              Set<Long> reserved) {
        List<Candidate> list = new ArrayList<>();
        for (Payee p : targets) {
            if (reserved.contains(p.orderId())) {
                continue;
            }
            Evidence e = evidence(d.name(), p, aliases);
            if (e != null) {
                list.add(new Candidate(p.orderId(), e, d.amount() - p.expectedAmount()));
            }
        }
        return list;
    }

    /** aliases 의 키는 normalize 한 입금자명이어야 한다 */
    static Evidence evidence(String depositName, Payee p, Map<String, Long> aliases) {
        String name = normalize(depositName);
        if (p.depositorName() != null && !name.isEmpty() && name.equals(normalize(p.depositorName()))) {
            return Evidence.DEPOSITOR_NAME;
        }
        Long aliasCustomerId = aliases.get(name);
        if (aliasCustomerId != null && aliasCustomerId.equals(p.customerId())) {
            return Evidence.ALIAS;
        }
        if (p.nickname() != null && !p.nickname().isBlank()) {
            String letters = lettersOnly(depositName);
            if (letters.length() >= 2 && lettersOnly(p.nickname()).contains(letters)) {
                return Evidence.NICKNAME_TEXT;
            }
            Set<String> depositNumbers = numberTokens(depositName);
            if (!depositNumbers.isEmpty()) {
                Set<String> nickNumbers = numberTokens(p.nickname());
                for (String n : depositNumbers) {
                    if (nickNumbers.contains(n)) {
                        return Evidence.NICKNAME_NUMBER;
                    }
                }
            }
        }
        return null;
    }

    /** 비교용: 공백을 지우고 영문은 소문자로 ("Kim Jiyoung" = "KIMJIYOUNG") */
    static String normalize(String value) {
        return Texts.squash(value).toLowerCase(Locale.ROOT);
    }

    /** 한글·영문만 남긴다 (소문자) */
    static String lettersOnly(String value) {
        return value == null ? "" : NOT_LETTER.matcher(value).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /** 3자리 이상 숫자 묶음 + 전부 이어붙인 값. "217-1505" → {217, 1505, 2171505} */
    static Set<String> numberTokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        if (value == null) {
            return tokens;
        }
        Matcher m = DIGITS.matcher(value);
        StringBuilder joined = new StringBuilder();
        int groups = 0;
        while (m.find()) {
            String g = m.group();
            joined.append(g);
            groups++;
            if (g.length() >= 3) {
                tokens.add(g);
            }
        }
        if (groups > 1 && joined.length() >= 3) {
            tokens.add(joined.toString());
        }
        return tokens;
    }
}
