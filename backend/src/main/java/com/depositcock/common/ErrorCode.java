package com.depositcock.common;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import org.springframework.http.HttpStatus;

/**
 * 오류 응답 문구 전체 (2026-10-20 사용자 결정: 오류 문구는 이 enum 한 곳에서 관리).
 * 응답은 ProblemDetail 의 detail(문구) + code(이 enum 이름). 화면은 detail 을 그대로 보여 주고,
 * 따로 처리할 오류만 code 로 구분한다.
 * 문구 안의 %s(글자)·%d(숫자)는 ApiException 을 만들 때 넘긴 값으로 채운다 (String.format).
 * 입력값 검사 문구는 요청 DTO 의 어노테이션에 바로 적고, 응답 code 는 INVALID_INPUT.
 */
public enum ErrorCode {

    // ---- 공통 ----
    INVALID_INPUT(BAD_REQUEST, "입력값을 확인해 주세요."),
    INVALID_REQUEST_BODY(BAD_REQUEST, "요청 내용을 읽을 수 없습니다. 새로고침한 뒤 다시 시도해 주세요."),
    UPLOAD_TOO_LARGE(PAYLOAD_TOO_LARGE, "파일은 1개당 10MB, 한 번에 50MB 까지 올릴 수 있습니다."),
    CONCURRENT_UPDATE(CONFLICT, "다른 곳에서 같은 내용을 먼저 바꿨습니다. 새로고침한 뒤 다시 시도해 주세요."),
    DATA_CONFLICT(CONFLICT, "저장하지 못했습니다. 이미 같은 데이터가 있거나 동시에 저장되었습니다. 새로고침한 뒤 다시 시도해 주세요."),
    INTERNAL_ERROR(INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
    TOO_MANY_REQUESTS_ERROR(TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    TOO_MANY_CHECKS(TOO_MANY_REQUESTS, "확인 시도가 너무 많습니다. 10분 뒤에 다시 시도해 주세요."),
    CSRF_REJECTED(FORBIDDEN, "잘못된 요청입니다. 페이지를 새로고침한 뒤 다시 시도해 주세요."),

    // ---- 판매자·로그인 ----
    LOGIN_REQUIRED(UNAUTHORIZED, "로그인이 필요합니다."),
    LOGIN_FAILED(UNAUTHORIZED, "이메일 또는 비밀번호가 맞지 않습니다."),
    EMAIL_TAKEN(CONFLICT, "이미 가입된 이메일입니다."),
    WRONG_PASSWORD(BAD_REQUEST, "비밀번호가 맞지 않습니다."),
    SELLER_NOT_FOUND(NOT_FOUND, "판매자를 찾을 수 없습니다."),
    SELLER_SUSPENDED(FORBIDDEN, "관리자가 사용을 제한한 계정입니다. 조회만 할 수 있습니다."),
    ADMIN_CANNOT_SUSPEND(BAD_REQUEST, "관리자 계정은 사용 제한할 수 없습니다."),
    ADMIN_CANNOT_DELETE(BAD_REQUEST, "관리자 계정은 삭제할 수 없습니다."),
    ADMIN_CANNOT_WITHDRAW(BAD_REQUEST, "관리자 계정은 탈퇴할 수 없습니다."),
    COMMENT_FEATURE_CLOSED(FORBIDDEN, "댓글 주문 기능은 아직 열려 있지 않습니다. 주문 링크로 주문을 받아 주세요."),

    // ---- 공구 설정 ----
    GROUP_BUY_NOT_FOUND(NOT_FOUND, "공구를 찾을 수 없습니다."),
    GROUP_BUY_NOT_OPEN(NOT_FOUND, "아직 열리지 않은 공구입니다."),
    CLOSE_AT_PASSED(BAD_REQUEST, "마감 일시가 지났습니다. 마감 일시를 바꾼 뒤 다시 열어주세요."),
    PAUSE_LINK_INVALID(BAD_REQUEST, "링크는 http:// 또는 https:// 로 시작하는 주소만 넣을 수 있습니다."),
    KAKAO_LINK_INVALID(BAD_REQUEST, "카톡 문의 링크는 http:// 또는 https:// 로 시작하는 주소만 넣을 수 있습니다."),
    RECEIVE_SETTING_REQUIRED(BAD_REQUEST, "픽업과 배송 중 하나 이상은 켜야 합니다."),
    PICKUP_PAYMENT_SETTING_REQUIRED(BAD_REQUEST, "픽업 결제 방식(선입금, 현장결제) 중 하나 이상은 켜야 합니다."),
    SHIPPING_FEE_REQUIRED(BAD_REQUEST, "고정 배송비 금액을 입력해 주세요."),
    COMMENT_CHANNEL_CLOSED(BAD_REQUEST, "댓글 주문 기능이 아직 열려 있지 않아 '주문 링크 + 댓글'을 고를 수 없습니다."),
    COMMENT_ORDERS_EXIST(BAD_REQUEST, "댓글 주문이 있어 '주문 링크만'으로 바꿀 수 없습니다. 댓글 주문을 먼저 취소해 주세요."),
    PRODUCT_ID_NOT_FOUND(BAD_REQUEST, "상품을 찾을 수 없습니다: %s"),
    PRODUCT_HAS_ORDERS(BAD_REQUEST, "주문이 있는 상품('%s')은 삭제할 수 없습니다."),
    PICKUP_POINT_HAS_ORDERS(BAD_REQUEST, "주문이 있는 픽업 지점('%s')은 삭제할 수 없습니다."),
    PRODUCT_REQUIRED_TO_OPEN(BAD_REQUEST, "상품을 1개 이상 등록해야 공구를 열 수 있습니다."),
    ACCOUNT_REQUIRED_FOR_PREPAY(BAD_REQUEST, "선입금(배송 또는 픽업 선입금)을 받으려면 입금 계좌(은행·계좌번호·예금주)를 입력해야 합니다."),

    // ---- 고객 주문 ----
    ORDER_CLOSED(BAD_REQUEST, "주문이 마감되었습니다."),
    ORDER_PAUSED(CONFLICT, "판매자가 주문을 잠시 멈췄습니다."),
    PHONE_INVALID(BAD_REQUEST, "휴대폰 번호를 확인해 주세요. (예: 010-1234-5678)"),
    PHONE_FULL_REQUIRED(BAD_REQUEST, "휴대폰 번호 전체를 입력해 주세요. (예: 010-1234-5678)"),
    NICKNAME_REQUIRED(BAD_REQUEST, "닉네임을 입력해 주세요."),
    UNIT_ADDRESS_REQUIRED(BAD_REQUEST, "동·호수를 입력해 주세요."),
    DEPOSITOR_NAME_REQUIRED(BAD_REQUEST, "입금자명을 입력해 주세요."),
    PRODUCT_NOT_FOUND(BAD_REQUEST, "상품을 찾을 수 없습니다."),
    QUANTITY_REQUIRED(BAD_REQUEST, "상품 수량을 1개 이상 선택해 주세요."),
    DELIVERY_INFO_REQUIRED(BAD_REQUEST, "배송을 고르면 받는 분, 받는 분 연락처, 주소를 모두 입력해야 합니다."),
    REORDER_NAME_MISMATCH(BAD_REQUEST, "이 번호로 주문 확정 전인 주문이 있습니다. 수정하려면 그 주문의 입금자명을 똑같이 적어 주세요. "
            + "(입금자명을 바꾸려면 판매자에게 문의해 주세요)"),
    SOLD_OUT(CONFLICT, "'%s'이(가) 품절되었습니다."),
    OVER_REMAINING(CONFLICT, "'%s'이(가) 남은 수량보다 많습니다. (남은 수량 %d개)"),
    ORDER_AMOUNT_TOO_LARGE(BAD_REQUEST, "주문 금액이 너무 큽니다. 수량을 확인해 주세요."),
    PER_ORDER_LIMIT(BAD_REQUEST, "'%s'은(는) 1인 최대 %d개까지 주문할 수 있습니다."),
    PER_ORDER_LIMIT_WITH_PREVIOUS(BAD_REQUEST, "'%s'은(는) 1인 최대 %d개까지 주문할 수 있습니다. (이미 주문한 %d개 포함)"),
    PER_ORDER_LIMIT_REPLACING(BAD_REQUEST, "'%s'은(는) 1인 최대 %d개까지 주문할 수 있습니다. (이미 주문한 %d개 포함, 바뀔 %d번 주문 제외)"),
    // 같은 번호로 다시 주문할 때 확인 (409). 응답 detail 은 아래 줄들을 이어 붙인 것
    REORDER_WARNING(CONFLICT, "주문하기 전에 확인해 주세요."),
    REORDER_CANNOT_REPLACE(CONFLICT, "이 주문은 수정할 수 없습니다. 주문이 확정됐거나 휴대폰 번호가 다릅니다."),
    REORDER_WILL_UPDATE(CONFLICT, "이 번호로 주문 확정 전인 주문이 있습니다. 계속하면 지금 내용으로 수정됩니다."),
    REORDER_WILL_UPDATE_ONSITE(CONFLICT, "이 번호로 주문한 내역이 있습니다. 계속하면 지금 내용으로 수정됩니다."),
    REORDER_NEW_AFTER_REFUND_REQUEST(CONFLICT, "이 번호로 환불 신청한 주문이 있어, 이번 주문은 새 주문으로 추가됩니다."),
    REORDER_NEW_AFTER_CONFIRMED(CONFLICT, "이 번호로 확정된 주문이 있어, 이번 주문은 새 주문으로 추가됩니다."),
    REORDER_NEW_AFTER_PICKUP(CONFLICT, "이 번호로 수령한 주문이 있어, 이번 주문은 새 주문으로 추가됩니다."),
    ORDER_NOT_FOUND_BY_PHONE(NOT_FOUND, "일치하는 주문이 없습니다. 휴대폰 번호를 확인해 주세요."),
    ORDER_NOT_FOUND_BY_PHONE_NAME(NOT_FOUND, "휴대폰 번호와 입금자명이 일치하는 주문이 없습니다."),
    ORDER_NO_INVALID(NOT_FOUND, "주문번호를 확인해 주세요."),
    CHOOSE_ORDER_TO_CANCEL(CONFLICT, "주문이 여러 건 있습니다. 취소할 주문을 골라주세요."),
    CHOOSE_ORDER_TO_REFUND(CONFLICT, "주문이 여러 건 있습니다. 환불 신청할 주문을 골라주세요."),
    NOTHING_TO_CANCEL(BAD_REQUEST, "취소할 수 있는 주문이 없습니다. (이미 취소·수령했거나 환불 신청한 주문)"),
    NOTHING_TO_REFUND(BAD_REQUEST, "환불 신청할 수 있는 주문이 없습니다. (이미 신청했거나 취소·수령한 주문)"),
    CANCEL_AFTER_CLOSE(BAD_REQUEST, "마감된 공구는 여기서 취소할 수 없습니다. 판매자에게 문의해 주세요. (입금한 주문은 환불 신청을 할 수 있습니다)"),
    REFUND_INSTEAD(CONFLICT, "확정된 주문입니다. 바로 취소 대신 환불 신청을 해 주세요."),
    ORDER_ALREADY_CANCELLED(BAD_REQUEST, "이미 취소된 주문입니다."),
    PICKED_UP_CANNOT_CANCEL(BAD_REQUEST, "이미 수령한 주문은 취소할 수 없습니다."),
    REFUND_PENDING(BAD_REQUEST, "환불 신청한 주문입니다. 판매자가 확인 중입니다."),
    ACCOUNT_NUMBER_INVALID(BAD_REQUEST, "계좌번호를 숫자로 입력해 주세요. (8~20자리)"),
    PICKED_UP_CANNOT_REFUND(BAD_REQUEST, "이미 수령한 주문은 환불 신청을 할 수 없습니다. 판매자에게 문의해 주세요."),
    ONSITE_NO_REFUND(BAD_REQUEST, "현장결제 주문은 입금이 없어 환불 신청이 필요 없습니다. 주문 취소를 눌러 주세요."),
    REFUND_ALREADY_REQUESTED(BAD_REQUEST, "이미 환불 신청한 주문입니다. 판매자가 확인 중입니다."),
    REFUND_REJECTED(BAD_REQUEST, "환불 신청이 반려된 주문입니다. 판매자에게 문의해 주세요."),

    // ---- 수령·결제 규칙 ----
    RECEIVE_METHOD_REQUIRED(BAD_REQUEST, "수령 방법(픽업/배송)을 선택해 주세요."),
    RECEIVE_METHOD_NOT_ALLOWED(BAD_REQUEST, "선택할 수 없는 수령 방법입니다."),
    PAYMENT_METHOD_REQUIRED(BAD_REQUEST, "결제 방식(선입금/현장결제)을 선택해 주세요."),
    PAYMENT_METHOD_NOT_ALLOWED(BAD_REQUEST, "선택할 수 없는 결제 방식입니다."),
    PICKUP_POINT_REQUIRED(BAD_REQUEST, "픽업 지점을 선택해 주세요."),
    PICKUP_POINT_NOT_FOUND(BAD_REQUEST, "픽업 지점을 찾을 수 없습니다."),

    // ---- 주문 바꾸기 (판매자·고객 공통) ----
    NOT_ENOUGH_STOCK_SELLER(CONFLICT, "'%s'의 남은 수량이 부족합니다. (남은 수량 %d개, 품절로 막는 상품)"),
    NOT_ENOUGH_STOCK(CONFLICT, "'%s'은(는) 남은 수량이 부족해 최대 %d개까지 주문할 수 있습니다."),
    NOT_ENOUGH_STOCK_WITH_CURRENT(CONFLICT, "'%s'은(는) 남은 수량이 부족해 최대 %d개까지 주문할 수 있습니다. (지금 주문한 %d개 포함)"),
    NO_CHANGE(BAD_REQUEST, "바뀐 내용이 없습니다."),
    NO_CHANGE_CUSTOMER(BAD_REQUEST, "%d번 주문과 같은 내용입니다. 바뀐 내용이 없습니다."),

    // ---- 판매자 주문 관리 ----
    ORDER_NOT_FOUND(NOT_FOUND, "주문을 찾을 수 없습니다."),
    ORDER_CANCELLED(BAD_REQUEST, "취소된 주문입니다."),
    REFUND_PENDING_DECIDE_FIRST(BAD_REQUEST, "고객이 환불 신청한 주문입니다. 먼저 [취소하고 환불] 또는 [취소 안 함]을 눌러 주세요."),
    PICKED_UP_UNCHECK_FIRST(BAD_REQUEST, "수령 완료된 주문입니다. 취소하려면 수령 체크를 먼저 해제해 주세요."),
    NO_PENDING_REFUND(BAD_REQUEST, "확인을 기다리는 환불 신청이 없습니다."),
    REFUND_NOT_REQUIRED(BAD_REQUEST, "환불이 필요한 주문이 아닙니다."),
    ALREADY_REFUNDED(BAD_REQUEST, "이미 환불 완료된 주문입니다."),
    CANCELLED_CANNOT_EDIT(BAD_REQUEST, "취소된 주문은 바꿀 수 없습니다."),
    PICKED_UP_CANNOT_EDIT(BAD_REQUEST, "수령 완료된 주문은 바꿀 수 없습니다."),
    PAID_CANNOT_EDIT(BAD_REQUEST, "입금 확정된 주문입니다. 바꾸려면 먼저 입금 확정을 취소해 주세요."),
    DELIVERY_INFO_REQUIRED_FOR_CHANGE(BAD_REQUEST, "배송으로 바꾸려면 받는 분, 받는 분 연락처, 주소를 모두 입력해야 합니다."),
    QUANTITY_REQUIRED_FOR_CHANGE(BAD_REQUEST, "상품 수량을 1개 이상 넣어 주세요. 주문을 없애려면 주문 취소를 눌러 주세요."),
    DELETE_NOTHING_SELECTED(BAD_REQUEST, "지울 주문을 골라 주세요."),
    DELETE_ONLY_FINISHED(BAD_REQUEST, "완료된 주문만 지울 수 있습니다. 아직 처리 중인 주문: %s"),

    // ---- 입금 대조 ----
    STATEMENT_PDF_NOT_SUPPORTED(BAD_REQUEST, "PDF 거래내역은 아직 읽을 수 없습니다. 은행 앱에서 엑셀(xlsx) 또는 CSV 로 받아 올려주세요."),
    STATEMENT_EXCEL_UNREADABLE(BAD_REQUEST, "엑셀 파일을 읽을 수 없습니다. 파일이 손상되었거나 암호가 걸려 있는지 확인해 주세요."),
    STATEMENT_GIVEN_COLUMNS_NOT_FOUND(BAD_REQUEST, "지정한 열 이름(이름: %s, 금액: %s)을 위쪽 %d줄 안에서 찾지 못했습니다. 열 이름을 다시 확인해 주세요."),
    STATEMENT_COLUMNS_NOT_FOUND(BAD_REQUEST, "입금자명·금액 열을 자동으로 찾지 못했습니다. 은행마다 열 이름이 달라서 그렇습니다. "
            + "아래에서 입금자명 열과 금액 열을 직접 골라주세요."),
    FILE_REQUIRED(BAD_REQUEST, "파일을 선택해 주세요."),
    FILE_UNREADABLE(BAD_REQUEST, "파일을 읽을 수 없습니다."),
    UPLOAD_NOT_FOUND(NOT_FOUND, "업로드를 찾을 수 없습니다."),
    UPLOAD_IN_USE(BAD_REQUEST, "확정에 쓴 입금이 들어 있는 업로드는 지울 수 없습니다. 먼저 해당 입금 확정을 취소해 주세요."),
    DEPOSIT_OR_ORDER_NOT_FOUND(BAD_REQUEST, "입금 또는 주문을 찾을 수 없습니다."),
    DEPOSIT_ALREADY_USED(BAD_REQUEST, "'%s %d원' 입금은 이미 다른 주문에 확정되었습니다."),
    ORDER_CANNOT_CONFIRM(BAD_REQUEST, "%d번 주문은 입금 확정할 수 없는 상태입니다."),
    CANNOT_CONFIRM(BAD_REQUEST, "입금 확정할 수 없는 상태입니다."),
    NOT_PAID(BAD_REQUEST, "입금 확정된 주문이 아닙니다."),
    REFUNDED_CANNOT_UNCONFIRM(BAD_REQUEST, "환불 완료된 주문은 입금 확정을 취소할 수 없습니다."),
    ALIAS_NOT_FOUND(NOT_FOUND, "별칭을 찾을 수 없습니다."),
    PRESET_NOT_FOUND(NOT_FOUND, "저장된 열 설정을 찾을 수 없습니다."),

    // ---- 댓글 집계 ----
    COMMENT_AUTHOR_EMPTY(BAD_REQUEST, "작성자가 비어 있는 댓글이 있습니다: %s"),
    COMMENT_UNREADABLE_CHARS(BAD_REQUEST, "읽지 못한 글자([?])가 남아 있습니다: %s %s"),
    COMMENT_FIX_UNREADABLE(BAD_REQUEST, "읽지 못한 글자([?])를 고친 뒤 저장해 주세요."),
    COMMENT_NOT_FOUND(NOT_FOUND, "댓글을 찾을 수 없습니다."),
    COMMENT_QUANTITY_INVALID(BAD_REQUEST, "수량을 확인해 주세요."),
    COMMENT_CHANNEL_LINK_ONLY(BAD_REQUEST, "'주문 링크만' 쓰는 공구입니다. 댓글 주문도 받으려면 공구 수정에서 주문 받는 방법을 "
            + "'주문 링크 + 댓글'로 바꿔 주세요."),
    COMMENT_MULTI_PRODUCT(BAD_REQUEST, "상품이 여러 개인 공구는 아직 댓글 주문을 받을 수 없습니다 "
            + "(댓글을 상품별로 나눠 세는 기능 준비 중). 주문 링크로 받아 주세요."),
    PRODUCT_REQUIRED(BAD_REQUEST, "공구에 상품을 먼저 등록해 주세요."),

    // ---- 스크린샷 (비전 AI) ----
    SCREENSHOT_UNREADABLE_CHARS(BAD_REQUEST, "읽지 못한 글자([?])가 남아 있습니다: %d번째 줄"),
    SCREENSHOT_NAME_TOO_LONG(BAD_REQUEST, "입금자명이 너무 깁니다 (200자까지): %d번째 줄"),
    SCREENSHOT_DATETIME_TOO_LONG(BAD_REQUEST, "일시가 너무 깁니다 (100자까지): %d번째 줄"),
    SCREENSHOT_NOTHING_TO_SAVE(BAD_REQUEST, "저장할 입금이 없습니다."),
    IMAGE_REQUIRED(BAD_REQUEST, "이미지를 선택해 주세요."),
    TOO_MANY_IMAGES(BAD_REQUEST, "한 번에 %d장까지 올릴 수 있습니다."),
    IMAGE_TYPE_NOT_ALLOWED(BAD_REQUEST, "이미지(png, jpg, gif, webp)만 올릴 수 있습니다: %s"),
    IMAGE_UNREADABLE(BAD_REQUEST, "이미지를 읽을 수 없습니다."),
    IMAGE_REJECTED(BAD_REQUEST, "%d번째 이미지: %s"),
    IMAGE_TOO_HEAVY(BAD_REQUEST, "용량이 너무 큽니다. 7MB 이하로 줄이거나 png·jpg로 저장해서 올려 주세요."),
    IMAGE_SIZE_UNREADABLE(BAD_REQUEST, "이미지 크기를 읽을 수 없습니다."),
    IMAGE_TOO_LARGE(BAD_REQUEST, "이미지가 너무 큽니다 (%d×%d). 나눠서 캡처해 올려 주세요."),
    IMAGE_SHRINK_FAILED(BAD_REQUEST, "이미지를 줄이지 못했습니다. 줄여서 다시 올려 주세요."),
    VISION_CALL_FAILED(BAD_GATEWAY, "비전 AI 호출에 실패했습니다. 직접 입력해 주세요."),
    VISION_REFUSED(BAD_GATEWAY, "비전 AI가 이 이미지 읽기를 거절했습니다. 직접 입력해 주세요."),
    VISION_TOO_MUCH_TEXT(BAD_GATEWAY, "캡처 한 장에 글자가 너무 많습니다. 더 작게 나눠서 올려주세요."),
    VISION_EMPTY_RESULT(BAD_GATEWAY, "비전 AI 응답에 읽은 결과가 없습니다. 직접 입력해 주세요.");

    private final HttpStatus status;
    private final String format;

    ErrorCode(HttpStatus status, String format) {
        this.status = status;
        this.format = format;
    }

    public HttpStatus status() {
        return status;
    }

    /** 문구. 값이 있으면 %s·%d 자리를 채운다 */
    public String message(Object... args) {
        return args.length == 0 ? format : String.format(format, args);
    }
}
