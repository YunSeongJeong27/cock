package com.depositcock.deposit;

import com.depositcock.deposit.AccountNumbers.Check;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 거래내역서 계좌번호 vs 공구 입금 계좌 (2026-10-27). 가린 자리는 빼고 보이는 숫자만 비교한다 */
class AccountNumbersTest {

    @Test
    void unmaskedMustBeEqual() {
        assertThat(AccountNumbers.compare("1001-9964-9421", "1001 9964 9421")).isEqualTo(Check.SAME);
        assertThat(AccountNumbers.compare("1001-9964-9420", "1001-9964-9421")).isEqualTo(Check.DIFFERENT);
    }

    /** KB PDF 처럼 같은 길이로 가리면 보이는 자리마다 비교 */
    @Test
    void maskedSameLengthComparesVisibleDigits() {
        assertThat(AccountNumbers.compare("123456-**-***789", "123456-00-123789")).isEqualTo(Check.SAME);
        assertThat(AccountNumbers.compare("123456-**-***788", "123456-00-123789")).isEqualTo(Check.DIFFERENT);
        assertThat(AccountNumbers.compare("****-****-1234", "1000-0000-1234")).isEqualTo(Check.SAME);
        assertThat(AccountNumbers.compare("●●●●-●●●●-1234", "1000-0000-1234")).isEqualTo(Check.SAME);
    }

    /** 가린 길이가 다르면 앞뒤로 보이는 숫자(각 최대 3자리)만 */
    @Test
    void maskedDifferentLengthComparesEnds() {
        assertThat(AccountNumbers.compare("***1234", "1000-0000-1234")).isEqualTo(Check.SAME);
        assertThat(AccountNumbers.compare("100***34", "1000-0000-1234")).isEqualTo(Check.SAME);
        assertThat(AccountNumbers.compare("***9999", "1000-0000-1234")).isEqualTo(Check.DIFFERENT);
    }

    @Test
    void notEnoughToCompare() {
        assertThat(AccountNumbers.compare(null, "1000-0000-1234")).isEqualTo(Check.UNKNOWN);
        assertThat(AccountNumbers.compare("***-**-***", "1000-0000-1234")).isEqualTo(Check.UNKNOWN);
        assertThat(AccountNumbers.compare("4***", "1000-0000-1234")).isEqualTo(Check.UNKNOWN);
        assertThat(AccountNumbers.compare("1000-0000-1234", null)).isEqualTo(Check.UNKNOWN);
    }
}
