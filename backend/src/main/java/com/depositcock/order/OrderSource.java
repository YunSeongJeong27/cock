package com.depositcock.order;

public enum OrderSource {
    /** 주문서 링크로 들어온 주문 */
    FORM,
    /** 댓글(스크린샷·텍스트) 집계로 만든 주문 */
    COMMENT
}
