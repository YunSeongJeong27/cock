package com.depositcock.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml 의 app.* 설정.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String zone,
        String jwtSecret,
        int jwtTtlHours,
        boolean cookieSecure,
        int trialDays,
        String adminEmail,
        String adminPassword,
        int publicRateLimitPerMinute,
        Vision vision
) {
    public record Vision(String provider, String anthropicApiKey, String anthropicModel) {
    }
}
