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
 * IP 당 요청 제한 (정리본 4-4장, M5). 메모리 기반이라 서버가 여러 대가 되면 공유 저장소(Redis 등)로 바꿔야 한다.
 * <ul>
 *   <li>공개 주문 API·로그인/가입의 쓰기 요청: 1분에 app.public-rate-limit-per-minute 회</li>
 *   <li>본인 확인(/identify)·주문 취소(/cancel)·환불 신청(/refund-request): (IP + 공구)마다 10분에
 *   app.sensitive-rate-limit-per-10-minutes 회
 *       — 휴대폰 번호를 하나씩 넣어보는 시도를 막는다 (내 주문 찾기는 휴대폰 전체 번호, 취소·환불 신청은 번호 + 입금자명).
 *       공구별로 따로 세는 이유: 휴대폰 망은 여러 사람이 IP 하나를 같이 쓰는 경우가 많아서, 다른 공구 고객끼리 한도를 나눠 쓰지 않게.</li>
 * </ul>
 * 프록시 뒤에서는 server.forward-headers-strategy 설정으로 실제 IP 가 getRemoteAddr 에 들어온다.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long MINUTE = 60_000;
    private static final long TEN_MINUTES = 10 * MINUTE;
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Map<String, Deque<Long>> general = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> sensitive = new ConcurrentHashMap<>();
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
        String ip = request.getRemoteAddr();
        String uri = request.getRequestURI();
        if (!allow(general, ip, now, MINUTE, properties.publicRateLimitPerMinute())) {
            reject(response, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
            return;
        }
        if ((uri.endsWith("/identify") || uri.endsWith("/cancel") || uri.endsWith("/refund-request"))
                && !allow(sensitive, ip + "|" + groupBuyPath(uri), now, TEN_MINUTES,
                properties.sensitiveRateLimitPer10Minutes())) {
            reject(response, "확인 시도가 너무 많습니다. 10분 뒤에 다시 시도해 주세요.");
            return;
        }
        chain.doFilter(request, response);
    }

    /** /api/public/g/{토큰}/identify → /api/public/g/{토큰} */
    static String groupBuyPath(String uri) {
        int last = uri.lastIndexOf('/');
        return last > 0 ? uri.substring(0, last) : uri;
    }

    private static boolean allow(Map<String, Deque<Long>> hits, String key, long now, long window, int limit) {
        Deque<Long> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        boolean allowed;
        synchronized (deque) {
            while (!deque.isEmpty() && now - deque.peekFirst() >= window) {
                deque.pollFirst();
            }
            allowed = deque.size() < limit;
            if (allowed) {
                deque.addLast(now);
            }
        }
        if (hits.size() > CLEANUP_THRESHOLD) {
            // 창이 지난 기록만 남은 IP 를 지워서 메모리가 계속 늘지 않게 한다
            hits.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    Long last = e.getValue().peekLast();
                    return last == null || now - last >= window;
                }
            });
        }
        return allowed;
    }

    private static void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":429,\"detail\":\"" + message + "\"}");
    }

    /** 테스트용 */
    int trackedKeys() {
        return general.size() + sensitive.size();
    }
}
