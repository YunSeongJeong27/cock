package com.depositcock.deposit;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.depositcock.deposit.DepositMatcher.Deposit;
import com.depositcock.deposit.DepositMatcher.Evidence;
import com.depositcock.deposit.DepositMatcher.Outcome;
import com.depositcock.deposit.DepositMatcher.Payee;
import com.depositcock.deposit.DepositMatcher.Status;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DepositMatcherTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 1, 10, 0);

    /** 테스트에서는 고객을 이름("k1" 등)으로 적고, 이름마다 고객 id 를 하나씩 붙인다 */
    private static final Map<String, Long> CUSTOMER_IDS = new java.util.HashMap<>();

    private static Long cid(String name) {
        return CUSTOMER_IDS.computeIfAbsent(name, n -> (long) CUSTOMER_IDS.size() + 1);
    }

    private static Payee payee(long id, String customer, String depositor, String nickname, int amount) {
        return new Payee(id, cid(customer), depositor, nickname, amount, T.plusMinutes(id));
    }

    @Test
    void depositorNameExactMatchIsMatched() {
        Outcome o = DepositMatcher.match(
                List.of(payee(1, "01011112222", "김지영", null, 3800), payee(2, "01033334444", "박서준", null, 3800)),
                List.of(new Deposit(10L, "김지영", 3800)), Map.of());
        assertThat(o.results().get(0).status()).isEqualTo(Status.MATCHED);
        assertThat(o.results().get(0).orderId()).isEqualTo(1L);
        assertThat(o.results().get(0).evidence()).isEqualTo(Evidence.DEPOSITOR_NAME);
        assertThat(o.unpaid()).extracting(Payee::orderId).containsExactly(2L);
    }

    @Test
    void nicknameNumberEvidence() {
        assertThat(DepositMatcher.numberTokens("217-1505")).containsExactlyInAnyOrder("217", "1505", "2171505");
        Outcome o = DepositMatcher.match(
                List.of(payee(1, "nick:꼭지맘 217-1505", null, "꼭지맘 217-1505", 1900)),
                List.of(new Deposit(10L, "2171505", 1900)), Map.of());
        assertThat(o.results().get(0).status()).isEqualTo(Status.MATCHED);
        assertThat(o.results().get(0).evidence()).isEqualTo(Evidence.NICKNAME_NUMBER);
    }

    @Test
    void aliasEvidence() {
        Outcome o = DepositMatcher.match(
                List.of(payee(1, "01011112222", "김지영", null, 5000)),
                List.of(new Deposit(10L, "김 지 영 엄마", 5000)), Map.of("김지영엄마", cid("01011112222")));
        assertThat(o.results().get(0).status()).isEqualTo(Status.MATCHED);
        assertThat(o.results().get(0).evidence()).isEqualTo(Evidence.ALIAS);
    }

    @Test
    void amountDifferenceIsSuggestedWithDiff() {
        Outcome o = DepositMatcher.match(List.of(payee(1, "k1", "김지영", null, 19900)),
                List.of(new Deposit(10L, "김지영", 20000)), Map.of());
        assertThat(o.results().get(0).status()).isEqualTo(Status.SUGGESTED);
        assertThat(o.results().get(0).amountDiff()).isEqualTo(100);
    }

    @Test
    void amountOnlySuggestedWhenSingleCandidate() {
        Outcome single = DepositMatcher.match(List.of(payee(1, "k1", "김지영", null, 8900), payee(2, "k2", "박", null, 1000)),
                List.of(new Deposit(10L, "모르는이름", 8900)), Map.of());
        assertThat(single.results().get(0).status()).isEqualTo(Status.SUGGESTED);
        assertThat(single.results().get(0).evidence()).isEqualTo(Evidence.AMOUNT_ONLY);

        Outcome two = DepositMatcher.match(List.of(payee(1, "k1", "김지영", null, 8900), payee(2, "k2", "박", null, 8900)),
                List.of(new Deposit(10L, "모르는이름", 8900)), Map.of());
        assertThat(two.results().get(0).status()).isEqualTo(Status.UNRELATED);
    }

    /** 같은 이름·같은 금액 2명 → 자동 확정 금지, 첫 번째에게 임의 추천 금지 (구 코드 버그) */
    @Test
    void twoCandidatesAreAmbiguousNotFirstPicked() {
        Outcome o = DepositMatcher.match(
                List.of(payee(1, "k1", "김민지", null, 19900), payee(2, "k2", "김민지", null, 19900)),
                List.of(new Deposit(10L, "김민지", 19900)), Map.of());
        assertThat(o.results().get(0).status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(o.results().get(0).orderId()).isNull();
        assertThat(o.results().get(0).candidates()).hasSize(2);

        // 이름 근거 + 금액 다름 + 후보 2명 → 역시 AMBIGUOUS
        Outcome diff = DepositMatcher.match(
                List.of(payee(1, "k1", "김민지", null, 19900), payee(2, "k2", "김민지", null, 18000)),
                List.of(new Deposit(10L, "김민지", 20000)), Map.of());
        assertThat(diff.results().get(0).status()).isEqualTo(Status.AMBIGUOUS);
    }

    /** 입금 1건은 주문 1건에만: 같은 주문에 맞는 입금이 2건이면 두 번째는 매칭되지 않는다. */
    @Test
    void oneDepositPerOrder() {
        Outcome o = DepositMatcher.match(
                List.of(payee(1, "k1", "김지영", null, 3800)),
                List.of(new Deposit(10L, "김지영", 3800), new Deposit(11L, "김지영", 3800)), Map.of());
        assertThat(o.results().get(0).status()).isEqualTo(Status.MATCHED);
        assertThat(o.results().get(1).status()).isNotEqualTo(Status.MATCHED);
        assertThat(o.results().get(1).orderId()).isNull();
    }

    @Test
    void lettersInNickname() {
        Outcome o = DepositMatcher.match(List.of(payee(1, "nick:망고맘", null, "망고맘", 1900)),
                List.of(new Deposit(10L, "망고맘", 1900)), Map.of());
        assertThat(o.results().get(0).evidence()).isEqualTo(Evidence.NICKNAME_TEXT);
        // 한 글자는 근거로 쓰지 않는다
        Outcome single = DepositMatcher.match(List.of(payee(1, "nick:쏠", null, "쏠", 1900), payee(2, "nick:신지", null, "신지", 1900)),
                List.of(new Deposit(10L, "쏠", 1900)), Map.of());
        assertThat(single.results().get(0).status()).isEqualTo(Status.UNRELATED);
    }

    /** 같은 금액의 연결 안 된 입금이 2건이면 금액만으로는 추천하지 않는다 (어느 입금인지 모름) */
    @Test
    void amountOnlyNeedsUniqueDeposit() {
        Outcome o = DepositMatcher.match(List.of(payee(1, "k1", "김지영", null, 8900)),
                List.of(new Deposit(10L, "모르는1", 8900), new Deposit(11L, "모르는2", 8900)), Map.of());
        assertThat(o.results()).extracting(DepositMatcher.Result::status)
                .containsExactly(Status.UNRELATED, Status.UNRELATED);
        assertThat(o.unpaid()).hasSize(1);

        // 같은 금액이 2건이어도 하나가 1차에서 다른 주문에 확정되면, 남은 1건은 금액만으로 추천된다
        Outcome o2 = DepositMatcher.match(
                List.of(payee(1, "k1", "김지영", null, 8900), payee(2, "k2", "박서준", null, 8900)),
                List.of(new Deposit(10L, "김지영", 8900), new Deposit(11L, "모르는", 8900)), Map.of());
        assertThat(o2.results().get(0).status()).isEqualTo(Status.MATCHED);
        assertThat(o2.results().get(1).status()).isEqualTo(Status.SUGGESTED);
        assertThat(o2.results().get(1).orderId()).isEqualTo(2L);
    }

    // ---- 2026-10-14 점검에서 찾은 경우들 ----

    private static Outcome both(List<Payee> payees, List<Deposit> deposits, Map<String, Long> aliases) {
        Outcome forward = DepositMatcher.match(payees, deposits, aliases);
        List<Deposit> reversed = new java.util.ArrayList<>(deposits);
        java.util.Collections.reverse(reversed);
        Outcome backward = DepositMatcher.match(payees, reversed, aliases);
        // 줄 순서를 뒤집어도 결과가 같아야 한다. 이름·금액이 똑같은 입금끼리는 어느 쪽이 어느 주문에 붙든 같으므로
        // (입금자명, 금액, 결과, 주문) 묶음으로 비교한다
        assertThat(summary(backward)).isEqualTo(summary(forward));
        return forward;
    }

    private static List<String> summary(Outcome o) {
        return o.results().stream()
                .map(r -> r.deposit().name() + "|" + r.deposit().amount() + "|" + r.status() + "|" + r.orderId())
                .sorted().toList();
    }

    /** A-2: 금액만 맞는 닉네임 근거가, 입금자명이 정확히 같은 다른 고객을 이기지 않는다 → 후보 2명 */
    @Test
    void weakEvidenceWithExactAmountDoesNotBeatExactNameOfAnotherCustomer() {
        Outcome o = both(List.of(payee(1, "k1", "김민지", null, 19900), payee(2, "k2", "다른이름", "김민지네", 20000)),
                List.of(new Deposit(10L, "김민지", 20000)), Map.of());
        assertThat(o.results().get(0).status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(o.results().get(0).candidates()).extracting(DepositMatcher.Candidate::orderId).containsExactly(1L, 2L);
        assertThat(o.unpaid()).isEmpty(); // 후보로 나온 주문은 "입금이 안 보이는 주문"에 다시 넣지 않는다
    }

    /** 0) 입금자명 + 금액 일치는 다른 고객의 닉네임 근거보다 우선 (정리본 6-2 "최우선") */
    @Test
    void exactNameAndAmountBeatsNicknameOfAnotherCustomer() {
        Outcome o = both(List.of(payee(1, "k1", "김민지", null, 20000), payee(2, "k2", "다른이름", "김민지네", 20000)),
                List.of(new Deposit(10L, "김민지", 20000)), Map.of());
        assertThat(o.results().get(0).status()).isEqualTo(Status.MATCHED);
        assertThat(o.results().get(0).orderId()).isEqualTo(1L);
    }

    /** 같은 입금자명의 고객이 2명이면 금액이 하나만 맞아도 자동 확정하지 않는다 (후보 2명 → 자동 확정 금지) */
    @Test
    void twoCustomersWithSameNameAreAmbiguousEvenIfOnlyOneAmountMatches() {
        Outcome o = both(List.of(payee(1, "k1", "김민지", null, 20000), payee(2, "k2", "김민지", null, 19900)),
                List.of(new Deposit(10L, "김민지", 20000)), Map.of());
        assertThat(o.results().get(0).status()).isEqualTo(Status.AMBIGUOUS);
    }

    /** 한 고객의 주문 2건 + 입금 2건: 같은 사람이므로 1명으로 보고 각각 자동 매칭 */
    @Test
    void oneCustomerWithTwoOrdersAndTwoDeposits() {
        Outcome o = both(List.of(payee(1, "k1", "김지영", null, 3800), payee(2, "k1", "김지영", null, 3800)),
                List.of(new Deposit(10L, "김지영", 3800), new Deposit(11L, "김지영", 3800)), Map.of());
        assertThat(o.results()).extracting(DepositMatcher.Result::status).containsOnly(Status.MATCHED);
        assertThat(o.results()).extracting(DepositMatcher.Result::orderId).containsExactlyInAnyOrder(1L, 2L);
    }

    /** A-4: 결과가 입금 줄 순서에 따라 달라지지 않는다 */
    @Test
    void resultDoesNotDependOnRowOrder() {
        Outcome o = both(List.of(payee(1, "k1", "김민지", null, 19900), payee(2, "k2", "김민지", null, 19900)),
                List.of(new Deposit(10L, "김민지", 19900), new Deposit(11L, "민지아빠", 19900)), Map.of("민지아빠", cid("k2")));
        var byRow = new java.util.HashMap<Long, DepositMatcher.Result>();
        o.results().forEach(r -> byRow.put(r.deposit().rowId(), r));
        assertThat(byRow.get(11L).status()).isEqualTo(Status.MATCHED);
        assertThat(byRow.get(11L).orderId()).isEqualTo(2L);
        // "김민지"는 1차에서 2명이라 확실하지 않음 → 남은 1건에 추천만
        assertThat(byRow.get(10L).status()).isEqualTo(Status.SUGGESTED);
        assertThat(byRow.get(10L).orderId()).isEqualTo(1L);
    }

    /** A-5: 같은 이름의 두 번째 입금(중복 입금?)을 다른 고객에게 "금액만 일치"로 붙이지 않는다 */
    @Test
    void secondDepositOfSameNameIsNotGivenToAnotherCustomerByAmount() {
        Outcome o = both(List.of(payee(1, "k1", "김지영", null, 3800), payee(2, "k2", "박서준", null, 3800)),
                List.of(new Deposit(10L, "김지영", 3800), new Deposit(11L, "김지영", 3800)), Map.of());
        var statuses = o.results().stream().map(DepositMatcher.Result::status).toList();
        assertThat(statuses).containsExactlyInAnyOrder(Status.MATCHED, Status.UNRELATED);
        var unrelated = o.results().stream().filter(r -> r.status() == Status.UNRELATED).findFirst().orElseThrow();
        assertThat(unrelated.related().relation()).isEqualTo(DepositMatcher.Relation.LINKED);
        assertThat(o.unpaid()).extracting(Payee::orderId).containsExactly(2L);
    }

    /** A-6: 이름 근거로 추천된 주문에는 다른 입금을 "금액만 일치"로 또 추천하지 않는다 */
    @Test
    void amountOnlyIsNotSuggestedForOrderAlreadySuggestedByName() {
        Outcome o = both(List.of(payee(1, "k1", "김지영", null, 8900)),
                List.of(new Deposit(10L, "김지영", 9000), new Deposit(11L, "모르는", 8900)), Map.of());
        var byRow = new java.util.HashMap<Long, DepositMatcher.Result>();
        o.results().forEach(r -> byRow.put(r.deposit().rowId(), r));
        assertThat(byRow.get(10L).status()).isEqualTo(Status.SUGGESTED);
        assertThat(byRow.get(11L).status()).isEqualTo(Status.UNRELATED);
    }

    /** A-7: 취소된 주문의 입금자 입금은 다른 고객에게 붙이지 않고 "환불 확인" 안내 */
    @Test
    void depositOfCancelledOrderIsFlaggedNotGivenToOthers() {
        Outcome o = DepositMatcher.match(List.of(payee(2, "k2", "박서준", null, 8900)),
                List.of(new DepositMatcher.Known(payee(1, "k1", "김지영", null, 8900), DepositMatcher.Relation.CANCELLED)),
                List.of(new Deposit(10L, "김지영", 8900)), Map.of());
        assertThat(o.results().get(0).status()).isEqualTo(Status.UNRELATED);
        assertThat(o.results().get(0).related().relation()).isEqualTo(DepositMatcher.Relation.CANCELLED);
        assertThat(o.results().get(0).related().orderId()).isEqualTo(1L);
    }

    /** A-9: 영문 이름은 대소문자·띄어쓰기를 무시한다 */
    @Test
    void englishNamesIgnoreCase() {
        Outcome o = both(List.of(payee(1, "k1", "Kim Jiyoung", null, 5000)),
                List.of(new Deposit(10L, "KIMJIYOUNG", 5000)), Map.of());
        assertThat(o.results().get(0).status()).isEqualTo(Status.MATCHED);
        assertThat(o.results().get(0).evidence()).isEqualTo(Evidence.DEPOSITOR_NAME);
    }

    /** 같은 고객의 입금 2건이 주문 1건을 가리키면 금액이 가까운 입금에만 추천하고, 나머지는 확인 안내 (2026-10-19) */
    @Test
    void oneSuggestionPerOrder() {
        Outcome o = DepositMatcher.match(
                List.of(payee(1, "01077778888", "오나눔", null, 10000)),
                List.of(new Deposit(10L, "오나눔", 5000), new Deposit(11L, "오나눔", 9000)), Map.of());
        assertThat(o.results().get(1).status()).isEqualTo(Status.SUGGESTED);
        assertThat(o.results().get(1).orderId()).isEqualTo(1L);
        assertThat(o.results().get(0).status()).isEqualTo(Status.UNRELATED);
        assertThat(o.results().get(0).related().relation()).isEqualTo(DepositMatcher.Relation.SUGGESTED);
        assertThat(o.results().get(0).related().orderId()).isEqualTo(1L);
    }

    /** 같은 고객의 주문·입금이 여러 건이면 자동 매칭이어도 확인 필요 (2026-10-26) */
    @Test
    void sameCustomerManyOrdersOrDepositsNeedsCheck() {
        // 같은 고객 주문 2건 + 입금 2건 → 둘 다 자동 매칭, 둘 다 확인 필요
        Outcome two = DepositMatcher.match(
                List.of(payee(1, "c-dup", "김중복", null, 3800), payee(2, "c-dup", "김중복", null, 3800)),
                List.of(new Deposit(10L, "김중복", 3800), new Deposit(11L, "김중복", 3800)), Map.of());
        assertThat(two.results()).extracting(r -> r.status()).containsOnly(Status.MATCHED);
        assertThat(two.checks()).containsOnlyKeys(10L, 11L);
        assertThat(two.checks().get(10L)).isEqualTo(new DepositMatcher.Check(2, 2));

        // 주문 1건 + 입금 2건 → 매칭된 1건도 확인 필요
        Outcome extra = DepositMatcher.match(List.of(payee(3, "c-one", "박한번", null, 5000)),
                List.of(new Deposit(20L, "박한번", 5000), new Deposit(21L, "박한번", 5000)), Map.of());
        Long matchedRow = extra.results().stream().filter(r -> r.status() == Status.MATCHED).findFirst().orElseThrow()
                .deposit().rowId();
        assertThat(extra.checks()).containsOnlyKeys(matchedRow);

        // 주문 1건 + 입금 1건 → 확인 필요 없음
        Outcome single = DepositMatcher.match(List.of(payee(4, "c-solo", "이혼자", null, 7000)),
                List.of(new Deposit(30L, "이혼자", 7000)), Map.of());
        assertThat(single.checks()).isEmpty();
    }
}
