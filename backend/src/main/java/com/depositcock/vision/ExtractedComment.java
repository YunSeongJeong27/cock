package com.depositcock.vision;

/**
 * 댓글 캡처에서 읽은 댓글 1개. {"comments":[{"author":"꼭지맘 217-1505","content":"1","order":0}]}
 * 공지·광고·시스템 메시지도 그대로 읽고, 제외는 우리 코드에서 한다.
 */
public record ExtractedComment(String author, String content, int order) {
}
