package com.depositcock.security;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF 방어 (쿠키 SameSite=Lax 에 더해 한 겹 더):
 * /api 로 가는 쓰기 요청(POST/PUT/PATCH/DELETE)은 헤더 X-Requested-With 가 있어야 받는다.
 * 다른 사이트의 폼·스크립트는 이 헤더를 붙일 수 없다 (붙이려면 CORS 사전 요청이 필요하고, 우리는 CORS 를 열지 않는다).
 * 우리 프론트(frontend/src/api.ts)는 항상 이 헤더를 보낸다.
 */
@Component
public class CsrfHeaderFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Requested-With";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/") || SAFE_METHODS.contains(request.getMethod().toUpperCase());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String value = request.getHeader(HEADER);
        if (value == null || value.isBlank()) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"status\":403,\"detail\":\"잘못된 요청입니다. 페이지를 새로고침한 뒤 다시 시도해 주세요.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
