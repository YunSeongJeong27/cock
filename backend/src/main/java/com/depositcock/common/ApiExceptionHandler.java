package com.depositcock.common;

import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 오류 응답은 전부 ProblemDetail 형식 (404 / 400 / 409 / 외부 API 502 / 그 외 500).
 * detail = 화면에 보여줄 문구, code = ErrorCode 이름 (2026-10-20). 프론트는 detail 을 그대로 보여준다.
 * Spring MVC 기본 예외(잘못된 JSON, 지원 안 하는 메서드, 없는 경로 등)는 ResponseEntityExceptionHandler 가 처리하고,
 * 예상 못 한 오류는 내부 메시지를 숨기고 로그에만 남긴다.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** ErrorCode 로 응답 본문을 만든다. 409 처럼 추가 정보가 필요한 곳은 받은 값에 setProperty 로 더한다 */
    public static ProblemDetail problem(ErrorCode code, String message) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), message);
        problem.setProperty("code", code.name());
        return problem;
    }

    public static ProblemDetail problem(ErrorCode code) {
        return problem(code, code.message());
    }

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException e) {
        return problem(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(ExternalApiException.class)
    public ProblemDetail handleExternal(ExternalApiException e) {
        log.warn("외부 API 오류: {}", e.getMessage(), e);
        return problem(e.getCode(), e.getMessage());
    }

    /** 동시에 같은 데이터를 고친 경우 (상품 version 충돌 등) */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        return problem(ErrorCode.CONCURRENT_UPDATE);
    }

    /** 유일 제약 위반 등 (동시에 같은 값을 저장한 경우) */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("데이터 제약 위반: {}", e.getMostSpecificCause().getMessage());
        return problem(ErrorCode.DATA_CONFLICT);
    }

    /** 예상 못 한 오류: 내부 메시지는 응답에 넣지 않는다 */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("처리하지 못한 오류", e);
        return problem(ErrorCode.INTERNAL_ERROR);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        // 문구는 요청 DTO 의 어노테이션에 적은 한국어 (2026-10-20). 영어 칸 이름은 보여주지 않고, 여러 칸이 틀리면 이어 붙인다
        String detail = ex.getBindingResult().getAllErrors().stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining(" "));
        return ResponseEntity.badRequest()
                .body(problem(ErrorCode.INVALID_INPUT, detail.isBlank() ? ErrorCode.INVALID_INPUT.message() : detail));
    }

    /** 잘못된 JSON, 없는 선택지 값 등 (기본 응답은 영어라 바꾼다) */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest().body(problem(ErrorCode.INVALID_REQUEST_BODY));
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        return ResponseEntity.status(ErrorCode.UPLOAD_TOO_LARGE.status()).body(problem(ErrorCode.UPLOAD_TOO_LARGE));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.is5xxServerError()) {
            log.error("서버 오류", ex);
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }
}
