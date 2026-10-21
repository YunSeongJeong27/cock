package com.depositcock;

import com.depositcock.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** React 화면 경로는 index.html 로, 정적 파일·API 는 그대로 (실제 실행에서 /assets 를 가로채던 버그 재발 방지) */
class SpaForwardTest extends IntegrationTest {

    @Test
    void screenRoutesForwardToIndex() throws Exception {
        mvc.perform(get("/g/abc")).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/seller/group-buys/1")).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/login")).andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void assetsAreNotForwarded() throws Exception {
        mvc.perform(get("/assets/index-abc.js")).andExpect(forwardedUrl(null)).andExpect(status().isNotFound());
    }
}
