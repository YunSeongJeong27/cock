package com.depositcock.common;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 공개 주문 폼·로그인에 IP 당 요청 제한 (정리본 4-4장, M5). 1분 창, 메모리 기반.
 * 서버가 여러 대가 되면 공유 저장소(Redis 등)로 바꿔야 한다.
 * 프록시 뒤에서는 server.forward-headers-strategy 설정으로 실제 IP 가 getRemoteAddr 에 들어온다.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MILLIS = 60_000;

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final AppProperties properties;
    private final Clock clock;

    public RateLimitFilter(AppProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        boolean write = !"GET".equalsIgnoreCase(request.getMethod());
        return !(write && (uri.startsWith("/api/public/") || uri.startsWith("/api/auth/")));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = clock.millis();
        String key = request.getRemoteAddr();
        Deque<Long> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        boolean allowed;
        synchronized (deque) {
            while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MILLIS) {
                deque.pollFirst();
            }
            allowed = deque.size() < properties.publicRateLimitPerMinute();
            if (allowed) {
                deque.addLast(now);
            }
        }
        if (hits.size() > 50_000) {
            hits.entrySet().removeIf(e -> e.getValue().isEmpty());
        }
        if (!allowed) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"status\":429,\"detail\":\"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
