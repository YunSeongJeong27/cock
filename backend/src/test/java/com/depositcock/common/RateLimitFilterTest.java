package com.depositcock.common;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import lombok.AllArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private static AppProperties props(int limit) {
        return props(limit, 1000);
    }

    private static AppProperties props(int limit, int sensitiveLimit) {
        return AppProperties.builder()
                .zone("Asia/Seoul")
                .jwtSecret("x")
                .jwtTtlHours(1)
                .cookieSecure(false)
                .trialDays(30)
                .publicRateLimitPerMinute(limit)
                .sensitiveRateLimitPer10Minutes(sensitiveLimit)
                .build();
    }

    private static int call(RateLimitFilter filter, String method, String uri, String ip) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void limitsPublicPostsPerIp() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(props(3),
                Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneId.of("Asia/Seoul")));
        for (int i = 0; i < 3; i++) {
            assertThat(call(filter, "POST", "/api/public/g/abc/orders", "1.1.1.1")).isEqualTo(200);
        }
        assertThat(call(filter, "POST", "/api/public/g/abc/orders", "1.1.1.1")).isEqualTo(429);
        assertThat(call(filter, "POST", "/api/public/g/abc/orders", "2.2.2.2")).isEqualTo(200);
        assertThat(call(filter, "GET", "/api/public/g/abc", "1.1.1.1")).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/seller/group-buys", "1.1.1.1")).isEqualTo(200);
    }

    @Test
    void sensitiveEndpointsHaveStricterLimit() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(props(100, 3),
                Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneId.of("Asia/Seoul")));
        for (int i = 0; i < 3; i++) {
            assertThat(call(filter, "POST", "/api/public/g/abc/identify", "3.3.3.3")).isEqualTo(200);
        }
        assertThat(call(filter, "POST", "/api/public/g/abc/identify", "3.3.3.3")).isEqualTo(429);
        assertThat(call(filter, "POST", "/api/public/g/abc/cancel", "3.3.3.3")).isEqualTo(429);
        // 일반 주문은 영향 없음
        assertThat(call(filter, "POST", "/api/public/g/abc/orders", "3.3.3.3")).isEqualTo(200);
        // 같은 IP 라도 다른 공구는 따로 센다 (휴대폰 망 IP 공유)
        assertThat(call(filter, "POST", "/api/public/g/other/identify", "3.3.3.3")).isEqualTo(200);
    }

    /** 창이 지난 IP 기록은 정리된다 (메모리가 계속 늘지 않음) */
    @Test
    void oldEntriesAreCleanedUp() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-13T00:00:00Z"));
        RateLimitFilter filter = new RateLimitFilter(props(100, 100), clock);
        for (int i = 0; i < 10_001; i++) {
            call(filter, "POST", "/api/public/g/abc/orders", "10.0." + (i / 256) + "." + (i % 256));
        }
        clock.advance(java.time.Duration.ofMinutes(2));
        call(filter, "POST", "/api/public/g/abc/orders", "9.9.9.9");
        assertThat(filter.trackedKeys()).isLessThan(10);
    }

    @AllArgsConstructor
    static class MutableClock extends Clock {
        private Instant now;

        void advance(java.time.Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
