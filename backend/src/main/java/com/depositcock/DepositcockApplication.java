package com.depositcock;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableAsync
public class DepositcockApplication {

    static {
        // 서비스는 한국 시각 기준 (app.zone 기본값과 같게). 서버(도커 등) 기본 시간대가 UTC 여도
        // DB 가 만드는 시각(CURRENT_TIMESTAMP)·PostgreSQL 세션 시간대·로그가 한국 시각이 되게 맞춘다.
        // 테스트도 이 클래스를 읽으므로 같은 시간대로 돈다.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    }

    public static void main(String[] args) {
        SpringApplication.run(DepositcockApplication.class, args);
    }
}
