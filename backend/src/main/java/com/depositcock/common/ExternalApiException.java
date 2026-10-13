package com.depositcock.common;

/**
 * 외부 API(비전 AI 등) 호출 실패. 502 로 응답한다.
 */
public class ExternalApiException extends RuntimeException {

    public ExternalApiException(String message) {
        super(message);
    }

    public ExternalApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
