package com.depositcock.support;

import com.depositcock.security.CsrfHeaderFilter;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 테스트의 모든 MockMvc 요청에 프론트처럼 X-Requested-With 헤더를 붙인다 (CsrfHeaderFilter 통과용).
 * 헤더가 없을 때 막히는지는 CsrfHeaderTest 에서 따로 확인한다.
 */
@TestConfiguration
public class TestMockMvcConfig {

    @Bean
    MockMvcBuilderCustomizer csrfHeaderByDefault() {
        return builder -> builder.defaultRequest(get("/").header(CsrfHeaderFilter.HEADER, "depositcock"));
    }
}
