package com.depositcock.order;

import java.util.regex.Pattern;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 휴대폰 번호 정규화. 고객 구분 키로 쓴다 (정리본 4-2장).
 * "010-1234-5678", "010 1234 5678", "01012345678" → "01012345678"
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PhoneNumbers {

    private static final Pattern MOBILE = Pattern.compile("^01[016789]\\d{7,8}$");
    private static final Pattern NON_DIGIT = Pattern.compile("\\D");

    /** 숫자만 남긴다. 휴대폰 번호 형식이 아니면 null. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = NON_DIGIT.matcher(raw).replaceAll("");
        if (digits.startsWith("82") && digits.length() >= 11) {
            digits = "0" + digits.substring(2);
        }
        return MOBILE.matcher(digits).matches() ? digits : null;
    }

    public static String last4(String normalized) {
        return normalized == null || normalized.length() < 4 ? "" : normalized.substring(normalized.length() - 4);
    }

    /** 화면 표시용 010-1234-5678 */
    public static String format(String normalized) {
        if (normalized == null) {
            return null;
        }
        if (normalized.length() == 11) {
            return normalized.substring(0, 3) + "-" + normalized.substring(3, 7) + "-" + normalized.substring(7);
        }
        if (normalized.length() == 10) {
            return normalized.substring(0, 3) + "-" + normalized.substring(3, 6) + "-" + normalized.substring(6);
        }
        return normalized;
    }
}
