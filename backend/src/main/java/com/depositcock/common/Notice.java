package com.depositcock.common;

/**
 * 서버가 만들어 화면에 보내는 안내 문구 (2026-10-20). 오류는 ErrorCode, 메일은 MailText.
 * 서버와 화면이 같이 쓰는 문장은 여기 하나만 두고 응답으로 보낸다 (프론트에 같은 문장을 또 적지 않는다).
 * 값이 들어가는 문장은 %s·%d 자리에 순서대로 넣는다.
 */
public enum Notice {

    // 공구·주문
    PAUSE_DEFAULT("현재 주문 집계 중입니다. 판매자에게 문의해 주시면 감사하겠습니다."),
    ORDER_CLOSED("주문이 마감되었습니다."),
    CHANGED_ON_OTHER_DEVICE("(주문한 기기와 다른 기기에서 바꿈)"),

    // 재고 알림 (화면 알림 칸과 메일이 같은 문장)
    STOCK_LEFT("'%s' 재고가 %d개 남았습니다."),
    STOCK_ALL_ORDERED("'%s' 준비 수량이 모두 주문됐습니다."),
    STOCK_OVER("'%s' 재고보다 %d개 더 주문됐습니다."),
    STOCK_ABUSE_WARNING("악용하는 고객이 있을 수 있으니 계좌 입금내역 대조를 통해 확인하십시오."),

    // 입금 대조 결과 (상태 이름)
    MATCH_AUTO("자동 매칭"),
    MATCH_SUGGESTED("추천"),
    MATCH_SUGGESTED_DIFF("추천 (금액 차이 %s원)"),
    MATCH_AMBIGUOUS("후보 %d명 - 직접 선택"),
    MATCH_UNRELATED("관련 없는 입금"),

    // 입금 대조: 관련 없는 입금의 확인 안내. 첫 자리는 "3번 " 처럼 주문번호 (없으면 빈칸)
    DEPOSIT_SAME_AS_LINKED("%s주문에 같은 입금자의 입금이 이미 연결됐습니다. 중복 입금인지 확인하세요."),
    DEPOSIT_SAME_AS_PAID("%s주문(입금 확인됨)의 입금자와 같습니다. 중복 입금인지 확인하세요."),
    DEPOSIT_SAME_AS_CANCELLED("취소된 %s주문의 입금자와 같습니다. 환불이 필요한지 확인하세요."),
    DEPOSIT_SAME_AS_SUGGESTED("%s주문에 같은 입금자의 다른 입금이 추천됐습니다. 나눠 보냈거나 중복 입금인지 확인하세요."),

    // 스크린샷 읽기
    SCREENSHOT_DEPOSITS_READ("AI가 읽은 결과입니다. 화면과 비교해 이름·금액을 꼭 확인하세요.%s (오늘 남은 캡처 %d장)"),
    SCREENSHOT_COMMENTS_READ("AI가 읽은 결과입니다. 작성자·내용을 확인하세요.%s (오늘 남은 캡처 %d장)"),
    SCREENSHOT_PAGES_FAILED(" %s번째 캡처는 읽지 못했습니다. 그 부분은 직접 입력해 주세요."),
    CAPTURE_LIMIT_REACHED("오늘 캡처 한도(%d장)를 초과했습니다. 아래 표에 직접 입력해 주세요. (내일 다시 쓸 수 있습니다)"),
    CAPTURE_LIMIT_OVER("오늘 캡처 한도 초과: 남은 장수는 %d장인데 %d장을 올렸습니다. 장수를 줄여서 다시 올리거나 아래 표에 직접 입력해 주세요."),
    VISION_NOT_CONFIGURED("스크린샷 글자 읽기(비전 AI)가 아직 설정되지 않았습니다. 아래 표에 직접 입력해 주세요. "
            + "(관리자: 환경변수 APP_VISION_PROVIDER=anthropic, ANTHROPIC_API_KEY 설정)");

    private final String format;

    Notice(String format) {
        this.format = format;
    }

    public String text(Object... args) {
        return args.length == 0 ? format : String.format(format, args);
    }
}
