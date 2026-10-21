package com.depositcock.common;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.http.MediaType;

/**
 * 컨트롤러 밖(필터·인터셉터)에서 오류를 응답할 때 ApiExceptionHandler 와 같은 모양({status, detail, code})으로 쓴다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ProblemWriter {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void write(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", code.getStatus().value());
        body.put("detail", code.message());
        body.put("code", code.name());
        // 전에는 문자열을 이어 붙이고 \\·"·줄바꿈만 직접 이스케이프했음. Jackson 이 모든 특수 문자를 맞게 바꾼다 (2026-10-21)
        response.getWriter().write(JSON.writeValueAsString(body));
    }
}
