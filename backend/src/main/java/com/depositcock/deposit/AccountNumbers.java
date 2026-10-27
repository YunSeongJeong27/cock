package com.depositcock.deposit;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 거래내역서의 계좌번호가 공구 입금 계좌와 같은지 (2026-10-27 사용자 요청).
 * 은행마다 계좌번호 일부를 가려서 보여 줄 수 있어(예: KB PDF "123456-**-***789" 모양) 보이는 숫자만 비교한다.
 * 가리는 자리 수의 공식 기준은 찾지 못해(은행 계좌번호는 10~14자리) 사용자 결정대로 "조금만 같아도 통과"로 둔다.
 * <pre>
 * - 가린 글자가 없으면: 숫자 전체가 같아야 같음
 * - 가린 글자가 있고 길이가 같으면: 보이는 자리마다 같아야 같음
 * - 길이가 다르면: 앞쪽에 보이는 숫자(최대 3자리)와 뒤쪽에 보이는 숫자(최대 3자리)가 공구 계좌의 앞뒤와 같아야 같음.
 *   비교할 숫자가 2자리보다 적으면 판단하지 않음
 * </pre>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AccountNumbers {

    public enum Check {
        /** 같은 계좌로 보임 */
        SAME,
        /** 다른 계좌 */
        DIFFERENT,
        /** 거래내역서에서 계좌번호를 못 찾았거나 비교할 숫자가 모자람 */
        UNKNOWN
    }

    /** 숫자와 가린 글자(*)만 남긴다. 가린 글자는 *, x, X, ●, ○, #, ＊ 를 모두 * 로 */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char ch : value.toCharArray()) {
            if (Character.isDigit(ch)) {
                sb.append(ch);
            } else if ("*xX●○#＊".indexOf(ch) >= 0) {
                sb.append('*');
            }
        }
        return sb.toString();
    }

    public static Check compare(String statementAccount, String groupBuyAccount) {
        String seen = normalize(statementAccount);
        String expected = DepositPools.digits(groupBuyAccount);
        if (expected.isEmpty() || seen.chars().noneMatch(Character::isDigit)) {
            return Check.UNKNOWN;
        }
        if (seen.indexOf('*') < 0) {
            return seen.equals(expected) ? Check.SAME : Check.DIFFERENT;
        }
        if (seen.length() == expected.length()) {
            for (int i = 0; i < seen.length(); i++) {
                if (seen.charAt(i) != '*' && seen.charAt(i) != expected.charAt(i)) {
                    return Check.DIFFERENT;
                }
            }
            return Check.SAME;
        }
        String lead = leadingDigits(seen);
        String trail = trailingDigits(seen);
        lead = lead.substring(0, Math.min(3, lead.length()));
        trail = trail.substring(Math.max(0, trail.length() - 3));
        if (lead.length() + trail.length() < 2) {
            return Check.UNKNOWN;
        }
        return expected.startsWith(lead) && expected.endsWith(trail) ? Check.SAME : Check.DIFFERENT;
    }

    private static String leadingDigits(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) != '*') {
            i++;
        }
        return s.substring(0, i);
    }

    private static String trailingDigits(String s) {
        int i = s.length();
        while (i > 0 && s.charAt(i - 1) != '*') {
            i--;
        }
        return s.substring(i);
    }
}
