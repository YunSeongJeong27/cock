package com.depositcock.commentorder;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 댓글 닉네임에 붙은 동·호수를 떼어 낸다 ("꼭지맘 217-1505" → 닉네임 "꼭지맘" + 동·호수 "217-1505", 2026-10-26).
 * 밴드 닉네임에 동·호수를 붙이는 경우가 많다 (docs/design.md 정리본 8장 픽스처).
 * 닉네임이 동·호수뿐이면("205-1205") 닉네임은 그대로 두고 동·호수에도 넣는다 (닉네임이 비면 누군지 알 수 없어서).
 * 댓글 손님을 알아보는 키(Customer.commentNickname)는 떼기 전 원래 닉네임을 그대로 쓴다.
 */
public record CommentNickname(String nickname, String unitAddress) {

    /** 동-호 (217-1505), N동 (N호), 세·네 자리 N호. 앞뒤가 숫자로 이어지면 동·호수로 보지 않는다 */
    private static final Pattern UNIT = Pattern.compile(
            "(?<!\\d)(?:\\d{1,4}\\s*-\\s*\\d{3,4}|\\d{1,4}\\s*동(?:\\s*\\d{1,4}\\s*호)?|\\d{3,4}\\s*호)(?!\\d)");

    private static final Pattern EMPTY_BRACKETS = Pattern.compile("\\(\\s*\\)|\\[\\s*]");

    private static final Pattern EDGE_SYMBOLS = Pattern.compile("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$");

    public static CommentNickname split(String authorName) {
        String name = authorName == null ? "" : authorName.trim();
        Matcher m = UNIT.matcher(name);
        if (!m.find()) {
            return new CommentNickname(name, null);
        }
        String unit = m.group().replaceAll("\\s+", " ");
        String rest = name.substring(0, m.start()) + " " + name.substring(m.end());
        rest = EMPTY_BRACKETS.matcher(rest).replaceAll(" ").replaceAll("\\s+", " ").trim();
        rest = EDGE_SYMBOLS.matcher(rest).replaceAll("");
        return new CommentNickname(rest.isEmpty() ? name : rest, unit);
    }
}
