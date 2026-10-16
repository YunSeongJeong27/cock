package com.depositcock.common;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 공구 공개 토큰, 주문 내부 토큰, 쿠키 토큰 같은 추측 불가능한 랜덤 문자열.
 */
public final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {
    }

    public static String random(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }
}
