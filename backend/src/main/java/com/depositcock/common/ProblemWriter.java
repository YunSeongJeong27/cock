package com.depositcock.common;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.http.MediaType;

/**
 * 컨트롤러 밖(필터·인터셉터)에서 오류를 응답할 때 ApiExceptionHandler 와 같은 모양({status, detail, code})으로 쓴다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ProblemWriter {

    public static void write(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":" + code.getStatus().value() + ",\"detail\":\"" + json(code.message())
                + "\",\"code\":\"" + code.name() + "\"}");
    }

    private static String json(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
