package com.depositcock.common;

import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml 의 app.* 설정.
 */
@ConfigurationProperties(prefix = "app")
@Builder
public record AppProperties(
        String zone,
        String jwtSecret,
        int jwtTtlHours,
        boolean cookieSecure,
        int trialDays,
        String adminEmail,
        String adminPassword,
        int publicRateLimitPerMinute,
        int sensitiveRateLimitPer10Minutes,
        Vision vision
) {
    public record Vision(String provider, String anthropicApiKey, String anthropicModel, int dailyCaptureLimit) {
    }
}
