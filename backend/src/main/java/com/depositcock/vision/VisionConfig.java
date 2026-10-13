package com.depositcock.vision;

import com.depositcock.common.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * app.vision.provider = anthropic 이고 ANTHROPIC_API_KEY 가 있으면 Claude(Spring AI, 기본 모델 claude-haiku-5-5), 아니면 꺼짐(Fake).
 * 기본은 꺼짐.
 */
@Configuration
public class VisionConfig {

    private static final Logger log = LoggerFactory.getLogger(VisionConfig.class);

    @Bean
    public VisionExtractor visionExtractor(AppProperties properties) {
        AppProperties.Vision vision = properties.vision();
        if (vision != null && "anthropic".equalsIgnoreCase(vision.provider())) {
            if (vision.anthropicApiKey() == null || vision.anthropicApiKey().isBlank()) {
                log.warn("APP_VISION_PROVIDER=anthropic 인데 ANTHROPIC_API_KEY 가 없어 스크린샷 읽기를 끕니다.");
                return new FakeVisionExtractor();
            }
            return new AnthropicVisionExtractor(vision.anthropicApiKey(), vision.anthropicModel());
        }
        return new FakeVisionExtractor();
    }
}
