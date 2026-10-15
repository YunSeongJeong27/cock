package com.depositcock.order;

import java.util.Locale;

/**
 * 링크 주문과 댓글 주문이 같은 사람인지 의심할 때 쓰는 닉네임 비교 키.
 * 댓글에는 휴대폰 번호가 없어서 두 주문을 비교할 수 있는 건 닉네임뿐이다. 다른 사람이 같은 닉네임을 쓸 수 있으므로
 * 경고에만 쓰고 자동으로 합치지 않는다 (2026-10-15 사용자 결정). 띄어쓰기·대소문자 차이는 같은 것으로 본다.
 */
public final class Nicknames {

    private Nicknames() {
    }

    /** 비교 키. 비어 있으면 null */
    public static String key(String nickname) {
        if (nickname == null) {
            return null;
        }
        String key = nickname.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }
}
