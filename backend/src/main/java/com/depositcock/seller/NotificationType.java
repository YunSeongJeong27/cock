package com.depositcock.seller;

/**
 * 판매자 알림 종류 (2026-10-19 사용자 요청: 알림마다 판매자가 켜고 끈다). 판매자가 정하지 않은 알림은 defaultOn 을 따른다.
 * 기본으로 켜 두는 것: 판매자가 바로 처리해야 하는 메일(재고, 환불 신청)과 화면 알림(비용 없음) [제안].
 * 주문마다 오는 메일은 꺼 둔다. 메일은 처음에 Gmail 한 계정(하루 약 500명 한도, 공식 수치 미확인)을 판매자 모두가 나눠 쓰므로,
 * 주문마다 보내면 한도가 차서 재고·환불 메일까지 못 보낼 수 있다.
 */
public enum NotificationType {

    /** 남은 수량이 알림 기준 이하가 됨 (2026-10-16) */
    MAIL_STOCK(true),
    /** 고객 환불 신청 */
    MAIL_REFUND_REQUEST(true),
    /** 새 주문 (주문 링크) */
    MAIL_NEW_ORDER(false),
    /** 고객이 주문을 수정함 (같은 번호로 다시 주문, [주문 수정]) */
    MAIL_CUSTOMER_CHANGE(false),
    /** 고객이 주문 확정 전 주문을 바로 취소함 */
    MAIL_CUSTOMER_CANCEL(false),
    /** 마감 시각이 돼 공구가 자동으로 마감됨 */
    MAIL_AUTO_CLOSE(false),
    /** 공구 화면 맨 위 재고 알림 칸 */
    SCREEN_STOCK(true),
    /** 주문 목록 위 "고객 환불 신청 N건" 칸 */
    SCREEN_REFUND_REQUEST(true),
    /** 주문 목록 위 "링크·댓글 닉네임 같은 주문 N건" 칸 */
    SCREEN_SAME_NICKNAME(true);

    private final boolean defaultOn;

    NotificationType(boolean defaultOn) {
        this.defaultOn = defaultOn;
    }

    public boolean isDefaultOn() {
        return defaultOn;
    }
}
