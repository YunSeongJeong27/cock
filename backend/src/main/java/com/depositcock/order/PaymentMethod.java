package com.depositcock.order;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 결제 방식. label 은 판매자 쪽 이름 (엑셀·변경 기록). 고객 화면은 선입금을 "계좌이체"로 보여 준다 */
@Getter
@RequiredArgsConstructor
public enum PaymentMethod {
    /** 선입금 (입금 대조 대상) */
    PREPAID("선입금"),
    /** 현장결제 (입금 대조 제외) */
    ONSITE("현장결제");

    private final String label;
}
