package com.depositcock.common;

import lombok.Getter;

/**
 * 외부 API(비전 AI 등) 호출 실패. 502 로 응답한다. 문구는 ErrorCode 에서 꺼낸다.
 */
public class ExternalApiException extends RuntimeException {

    @Getter
    private final ErrorCode code;

    public ExternalApiException(ErrorCode code) {
        super(code.message());
        this.code = code;
    }

    public ExternalApiException(ErrorCode code, Throwable cause) {
        super(code.message(), cause);
        this.code = code;
    }
}
