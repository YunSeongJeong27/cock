package com.depositcock.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 서비스 계층에서 던지는 예외. 문구는 ErrorCode 에서 꺼낸다 (2026-10-20).
 * ApiExceptionHandler 가 ProblemDetail(detail = 문구, code = ErrorCode 이름) 로 바꾼다.
 */
public class ApiException extends RuntimeException {

    @Getter
    private final ErrorCode code;

    /** @param args 문구의 %s·%d 자리에 넣을 값 (없으면 생략) */
    public ApiException(ErrorCode code, Object... args) {
        super(code.message(args));
        this.code = code;
    }

    private ApiException(String message, ErrorCode code) {
        super(message);
        this.code = code;
    }

    /** 문구가 enum 밖에서 정해지는 경우 (판매자가 적은 멈춤 문구, 여러 줄을 이어 붙인 확인 문구 등) */
    public static ApiException withMessage(ErrorCode code, String message) {
        return new ApiException(message, code);
    }

    public HttpStatus getStatus() {
        return code.status();
    }
}
