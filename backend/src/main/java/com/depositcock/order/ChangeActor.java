package com.depositcock.order;

/** 주문 내용을 바꾼 사람: 판매자("주문 변경") 또는 고객(같은 휴대폰 번호로 다시 주문, 2026-10-18) */
public enum ChangeActor {
    SELLER,
    CUSTOMER
}
