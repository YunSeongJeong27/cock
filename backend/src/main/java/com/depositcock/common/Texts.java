package com.depositcock.common;

import java.util.regex.Pattern;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 여러 곳에서 쓰는 문자열 도우미. 전에는 서비스마다 같은 메서드를 따로 두었다 (2026-10-21 정리).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Texts {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** 앞뒤 공백을 지운 값. 비었거나 공백뿐이면 null */
    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 이름 비교용: 공백을 모두 지운다 ("김 지영" = "김지영"). null 이면 "" */
    public static String squash(String value) {
        return value == null ? "" : WHITESPACE.matcher(value).replaceAll("");
    }

    /** DB 칸 길이(max)에 맞춰 뒤를 자른다 */
    public static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
