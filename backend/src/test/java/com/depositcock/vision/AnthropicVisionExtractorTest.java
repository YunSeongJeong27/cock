package com.depositcock.vision;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.depositcock.common.ExternalApiException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spring AI 경로를 실제 API 없이 확인한다 (가짜 ChatModel).
 * 실제 Claude(haiku-5-5) 응답으로는 아직 확인하지 못했다 — API 키가 생기면 확인할 것.
 */
class AnthropicVisionExtractorTest {

    /** Anthropic 응답을 흉내: thinking Generation(signature 속성) 다음에 텍스트(JSON) Generation */
    static class FakeChatModel implements ChatModel {
        final AtomicReference<Prompt> lastPrompt = new AtomicReference<>();
        final String json;
        final String stopReason;

        FakeChatModel(String json, String stopReason) {
            this.json = json;
            this.stopReason = stopReason;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            lastPrompt.set(prompt);
            Generation thinking = new Generation(AssistantMessage.builder().content("")
                    .properties(Map.of("signature", "sig")).build(),
                    ChatGenerationMetadata.builder().finishReason(stopReason).build());
            Generation text = new Generation(new AssistantMessage(json),
                    ChatGenerationMetadata.builder().finishReason(stopReason).build());
            return new ChatResponse(List.of(thinking, text));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return AnthropicVisionExtractor.defaultOptions("claude-haiku-5-5");
        }
    }

    @Test
    void nativeStructuredOutputAndThinkingFilter() {
        FakeChatModel model = new FakeChatModel("""
                {"deposits":[{"name":"김지영","amount":3800,"datetime":"10-08 10:01","type":"입금","order":0}]}
                """, "end_turn");
        AnthropicVisionExtractor extractor = new AnthropicVisionExtractor(model, "claude-haiku-5-5");

        List<ExtractedDeposit> result = extractor.extractDeposits(new byte[]{1, 2, 3}, "image/png");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("김지영");
        assertThat(result.get(0).amount()).isEqualTo(3800);

        Prompt prompt = model.lastPrompt.get();
        assertThat(prompt.getOptions()).isInstanceOf(AnthropicChatOptions.class);
        AnthropicChatOptions options = (AnthropicChatOptions) prompt.getOptions();
        // ENABLE_NATIVE_STRUCTURED_OUTPUT → JSON 스키마가 Anthropic 옵션(output_format)에 들어간다
        assertThat(options.getOutputSchema()).contains("deposits").contains("amount");
        assertThat(options.getModel()).isEqualTo("claude-haiku-5-5");
        assertThat(options.getTemperature()).isEqualTo(1.0);
        // 이미지가 사용자 메시지에 붙는다
        UserMessage user = (UserMessage) prompt.getInstructions().stream()
                .filter(m -> m instanceof UserMessage).findFirst().orElseThrow();
        assertThat(user.getMedia()).hasSize(1);
        assertThat(user.getMedia().get(0).getMimeType().toString()).isEqualTo("image/png");
    }

    /** thinking 블록만 오고 텍스트가 없는 응답 (thinking 도중 max_tokens 등) */
    static ChatModel thinkingOnly(String stopReason) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                Generation thinking = new Generation(AssistantMessage.builder().content("")
                        .properties(Map.of("signature", "sig")).build(),
                        ChatGenerationMetadata.builder().finishReason(stopReason).build());
                return new ChatResponse(List.of(thinking));
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return AnthropicVisionExtractor.defaultOptions("claude-haiku-5-5");
            }
        };
    }

    @Test
    void thinkingOnlyResponseIsAnErrorNotAnEmptyResult() {
        AnthropicVisionExtractor maxTokens = new AnthropicVisionExtractor(thinkingOnly("max_tokens"), "claude-haiku-5-5");
        assertThatThrownBy(() -> maxTokens.extractDeposits(new byte[]{1}, "image/png"))
                .isInstanceOf(ExternalApiException.class).hasMessageContaining("글자가 너무 많습니다");

        AnthropicVisionExtractor refusal = new AnthropicVisionExtractor(thinkingOnly("refusal"), "claude-haiku-5-5");
        assertThatThrownBy(() -> refusal.extractDeposits(new byte[]{1}, "image/png"))
                .isInstanceOf(ExternalApiException.class).hasMessageContaining("거절");

        AnthropicVisionExtractor noText = new AnthropicVisionExtractor(thinkingOnly("end_turn"), "claude-haiku-5-5");
        assertThatThrownBy(() -> noText.extractDeposits(new byte[]{1}, "image/png"))
                .isInstanceOf(ExternalApiException.class);
    }

    @Test
    void refusalBecomesFriendlyError() {
        FakeChatModel model = new FakeChatModel("", "refusal");
        AnthropicVisionExtractor extractor = new AnthropicVisionExtractor(model, "claude-haiku-5-5");
        assertThatThrownBy(() -> extractor.extractComments(new byte[]{1}, "image/jpeg"))
                .isInstanceOf(ExternalApiException.class).hasMessageContaining("거절");
    }
}
