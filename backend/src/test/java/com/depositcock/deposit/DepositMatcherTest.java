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

    private static Payee payee(long id, String key, String depositor, String nickname, int amount) {
        return new Payee(id, key, depositor, nickname, amount, T.plusMinutes(id));
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
                List.of(new Deposit(10L, "김 지 영 엄마", 5000)), Map.of("김지영엄마", "01011112222"));
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
}
