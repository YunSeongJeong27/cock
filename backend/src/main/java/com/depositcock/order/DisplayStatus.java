package com.depositcock.order;

/**
 * 판매자·고객 화면에 보여주는 주문 상태 (개발 전 인터뷰 결과 1번).
 * 선입금: 입금 대기 → 입금 신고됨(고객이 "입금했어요") → 입금 확인됨(판매자 확정) → 수령 완료
 * 현장결제: 예약됨 → 수령 완료
 */
public enum DisplayStatus {
    AWAITING_DEPOSIT("입금 대기 중"),
    DEPOSIT_REPORTED("입금 신고됨 (확인 대기)"), // "입금 확인됨"과 헷갈리지 않게 (2026-10-14)
    PAID("입금 확인됨"),
    RESERVED("예약됨 (현장결제)"),
    PICKED_UP("수령 완료"),
    CANCELLED("취소됨");

    private final String label;

    DisplayStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
