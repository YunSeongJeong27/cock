package com.depositcock.common;

import java.security.SecureRandom;
import java.util.Base64;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 공구 공개 토큰, 주문 내부 토큰, 쿠키 토큰 같은 추측 불가능한 랜덤 문자열.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    public static String random(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }
}
