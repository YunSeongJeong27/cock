package com.depositcock.common;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private static AppProperties props(int limit) {
        return new AppProperties("Asia/Seoul", "x", 1, false, 30, null, null, limit, null);
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
}
