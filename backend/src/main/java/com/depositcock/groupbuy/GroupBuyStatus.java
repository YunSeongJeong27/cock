package com.depositcock.groupbuy;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 공구 상태. 화면에 보일 이름은 서버가 정해서 statusLabel 로 보낸다 (2026-10-20) */
@Getter
@RequiredArgsConstructor
public enum GroupBuyStatus {
    DRAFT("작성 중"),
    OPEN("주문 받는 중"),
    CLOSED("마감");

    /** OPEN 인데 마감 시각이 지나 자동 마감을 기다리는 동안 (1분 이내) */
    static final String CLOSE_AT_PASSED_LABEL = "마감 시각 지남";

    private final String label;
}
