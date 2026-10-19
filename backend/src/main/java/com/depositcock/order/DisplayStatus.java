package com.depositcock.order;

/**
 * 판매자·고객 화면에 보여주는 주문 상태 (개발 전 인터뷰 결과 1번).
 * 선입금: 주문 확정 전 → (고객이 "입금했어요"로 알림) → 주문 확정(판매자가 대조해서 입금 확정) → 수령 완료
 * 현장결제: 예약됨 → 수령 완료
 * 2026-10-19 사용자 결정: "입금 대기 중"은 시스템이 입금 여부를 모르는데 아는 것처럼 보여서, 시스템이 확실히 아는
 * "주문 확정"(판매자가 대조해서 완료 처리) 기준으로 이름을 바꿈
 */
public enum DisplayStatus {
    AWAITING_DEPOSIT("주문 확정 전"),
    DEPOSIT_REPORTED("주문 확정 전 (입금 알림 보냄)"),
    PAID("주문 확정"),
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
