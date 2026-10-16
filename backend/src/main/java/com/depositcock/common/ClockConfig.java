package com.depositcock.common;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 서버 시각은 전부 이 Clock 으로 만든다 (Asia/Seoul). 테스트에서는 고정 Clock 으로 바꿀 수 있다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock(AppProperties properties) {
        return Clock.system(ZoneId.of(properties.zone()));
    }
}
