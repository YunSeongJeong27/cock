package com.depositcock.order;

/**
 * 입금 확정 때 입금자 별칭(입금자명 → 고객)을 어떻게 건드렸는지. 확정을 취소할 때 이것만 되돌린다.
 * 별칭을 건드리지 않았으면(이미 같은 고객으로 저장돼 있었음) 주문에 null 로 남는다.
 */
public enum PaidAliasChange {
    /** 별칭을 새로 만들었다 → 취소 때 지운다 */
    CREATED,
    /** 다른 고객을 가리키던 별칭을 이 고객으로 바꿨다 → 취소 때 원래 고객으로 되돌린다 */
    CHANGED
}
