package com.depositcock.commentorder;

/**
 * @param quantity     해석한 수량 (REVIEW 면 추정 수량, 없으면 null)
 * @param memo         원문에서 수량 부분을 뺀 나머지
 * @param reviewReason REVIEW 인 이유
 */
public record ParseResult(ParseType type, Integer quantity, String memo, String reviewReason) {
}
