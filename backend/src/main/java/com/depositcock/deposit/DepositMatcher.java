package com.depositcock.deposit;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 입금 대조 규칙 (정리본 6-2장). 결과는 저장하지 않고, 판매자가 "확정"을 눌러야 저장한다.
 *
 * <pre>
 * 근거 판정 순서
 *   0) 입금자명이 주문서의 입금자명과 정확히 일치 (최우선)
 *   a) 저장된 별칭 (입금자명 → 고객 키)
 *   b) 입금자명의 한글/영문(2자 이상)이 닉네임에 포함
 *   c) 입금자명의 숫자(3자리 이상, 이어붙인 값 포함)가 닉네임 숫자와 일치. 예: "217-1505" → {217, 1505, 2171505}
 * 결과
 *   MATCHED   : 금액 일치 + 근거 있음 + 후보 1명
 *   SUGGESTED : 근거 있음 + 금액 다름 (후보 1명) → "금액 차이 ±N원"
 *   SUGGESTED : 근거 없음 + 금액만 일치 + 그 금액의 미입금 주문이 1건뿐
 *   AMBIGUOUS : 후보가 2명 이상 → 자동 확정 금지, 판매자가 고른다 (목록의 첫 번째에 임의로 붙이지 않는다)
 *   UNRELATED : 관련 없는 입금
 * 입금 1건은 주문 1건에만 쓴다.
 * </pre>
 */
public final class DepositMatcher {

    public enum Status {
        MATCHED,
        SUGGESTED,
        AMBIGUOUS,
        UNRELATED
    }

    public enum Evidence {
        DEPOSITOR_NAME("입금자명 일치"),
        ALIAS("저장된 별칭"),
        NICKNAME_TEXT("닉네임 포함"),
        NICKNAME_NUMBER("닉네임 숫자 일치"),
        AMOUNT_ONLY("금액만 일치");

        private final String label;

        Evidence(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** 대조 대상 주문 (선입금, 취소 안 됨, 입금 확정 안 됨, 금액 > 0) */
    public record Payee(Long orderId, String customerKey, String depositorName, String nickname, int expectedAmount,
                        LocalDateTime orderedAt) {
    }

    /** 입금 1건 */
    public record Deposit(Long rowId, String name, int amount) {
    }

    public record Candidate(Long orderId, Evidence evidence, int amountDiff) {
    }

    public record Result(Deposit deposit, Status status, Long orderId, Evidence evidence, int amountDiff,
                         List<Candidate> candidates) {
    }

    public record Outcome(List<Result> results, List<Payee> unpaid) {
    }

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private DepositMatcher() {
    }

    public static Outcome match(List<Payee> payees, List<Deposit> deposits, Map<String, String> aliasToCustomerKey) {
        List<Payee> targets = payees.stream().filter(p -> p.expectedAmount() > 0)
                .sorted(Comparator.comparing(Payee::orderedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        Set<Long> reserved = new HashSet<>();
        Result[] results = new Result[deposits.size()];

        // 1차: 확실한 것(MATCHED)부터 주문을 잡는다
        for (int i = 0; i < deposits.size(); i++) {
            Deposit d = deposits.get(i);
            List<Candidate> exact = candidatesWithEvidence(d, targets, aliasToCustomerKey, reserved).stream()
                    .filter(c -> c.amountDiff() == 0).toList();
            List<Candidate> strongest = strongest(exact);
            if (strongest.size() == 1) {
                Candidate c = strongest.get(0);
                reserved.add(c.orderId());
                results[i] = new Result(d, Status.MATCHED, c.orderId(), c.evidence(), 0, List.of(c));
            }
        }

        // 2차: 나머지는 추천 / 후보 여러 명 / 관련 없음
        for (int i = 0; i < deposits.size(); i++) {
            if (results[i] != null) {
                continue;
            }
            Deposit d = deposits.get(i);
            List<Candidate> withEvidence = candidatesWithEvidence(d, targets, aliasToCustomerKey, reserved);
            if (!withEvidence.isEmpty()) {
                List<Candidate> exact = withEvidence.stream().filter(c -> c.amountDiff() == 0).toList();
                List<Candidate> pool = exact.isEmpty() ? withEvidence : exact;
                if (pool.size() == 1) {
                    Candidate c = pool.get(0);
                    results[i] = new Result(d, Status.SUGGESTED, c.orderId(), c.evidence(), c.amountDiff(), List.of(c));
                } else {
                    results[i] = new Result(d, Status.AMBIGUOUS, null, null, 0, pool);
                }
                continue;
            }
            List<Payee> sameAmount = targets.stream()
                    .filter(p -> !reserved.contains(p.orderId()) && p.expectedAmount() == d.amount()).toList();
            if (sameAmount.size() == 1) {
                Candidate c = new Candidate(sameAmount.get(0).orderId(), Evidence.AMOUNT_ONLY, 0);
                results[i] = new Result(d, Status.SUGGESTED, c.orderId(), c.evidence(), 0, List.of(c));
            } else {
                results[i] = new Result(d, Status.UNRELATED, null, null, 0, List.of());
            }
        }

        Set<Long> mentioned = new HashSet<>(reserved);
        for (Result r : results) {
            if (r.status() == Status.SUGGESTED) {
                mentioned.add(r.orderId());
            }
        }
        List<Payee> unpaid = targets.stream().filter(p -> !mentioned.contains(p.orderId())).toList();
        return new Outcome(List.of(results), unpaid);
    }

    /** 근거가 가장 강한 후보들만 남긴다 (입금자명 일치 > 별칭 > 닉네임). */
    private static List<Candidate> strongest(List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        Evidence best = candidates.stream().map(Candidate::evidence).min(Comparator.naturalOrder()).orElseThrow();
        // 입금자명 일치나 별칭이면 그 근거끼리만 비교한다. 닉네임 근거는 b·c 를 같은 등급으로 본다.
        if (best == Evidence.DEPOSITOR_NAME || best == Evidence.ALIAS) {
            return candidates.stream().filter(c -> c.evidence() == best).toList();
        }
        return candidates;
    }

    private static List<Candidate> candidatesWithEvidence(Deposit d, List<Payee> targets, Map<String, String> aliases,
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

    static Evidence evidence(String depositName, Payee p, Map<String, String> aliases) {
        String name = squash(depositName);
        if (p.depositorName() != null && !name.isEmpty() && name.equals(squash(p.depositorName()))) {
            return Evidence.DEPOSITOR_NAME;
        }
        String aliasKey = aliases.get(name);
        if (aliasKey != null && aliasKey.equals(p.customerKey())) {
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

    static String squash(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    /** 한글·영문만 남긴다 (소문자) */
    static String lettersOnly(String value) {
        return value == null ? "" : value.replaceAll("[^가-힣a-zA-Z]", "").toLowerCase();
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
