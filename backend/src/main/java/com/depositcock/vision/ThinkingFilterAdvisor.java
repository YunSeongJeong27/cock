package com.depositcock.vision;

import java.util.List;

import com.depositcock.common.ExternalApiException;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;

/**
 * Spring AI 1.1.8 의 Anthropic 모델은 thinking 블록을 별도 Generation 으로 만들고,
 * ChatClient.entity() 는 첫 번째 Generation 만 JSON 으로 읽는다.
 * Claude Haiku 5.5 처럼 thinking 이 기본으로 켜진 모델은 첫 번째가 (빈) thinking 이라 읽기에 실패하므로,
 * thinking·redacted thinking Generation(속성 signature / data 를 가진 것)을 빼고 텍스트 Generation 만 남긴다.
 * 또 모델이 거절(refusal)하면 안내 문구로 바꾼다.
 */
class ThinkingFilterAdvisor implements CallAdvisor {

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null) {
            return response;
        }
        List<Generation> textOnly = chatResponse.getResults().stream()
                .filter(g -> !isThinking(g))
                .toList();
        for (Generation g : textOnly) {
            String finishReason = g.getMetadata() == null ? null : g.getMetadata().getFinishReason();
            if ("refusal".equalsIgnoreCase(finishReason)) {
                throw new ExternalApiException("비전 AI가 이 이미지 읽기를 거절했습니다. 직접 입력해 주세요.");
            }
            if ("max_tokens".equalsIgnoreCase(finishReason)) {
                throw new ExternalApiException("캡처 한 장에 글자가 너무 많습니다. 더 작게 나눠서 올려주세요.");
            }
        }
        ChatResponse filtered = new ChatResponse(textOnly, chatResponse.getMetadata());
        return response.mutate().chatResponse(filtered).build();
    }

    static boolean isThinking(Generation g) {
        var props = g.getOutput().getMetadata();
        return props.containsKey("signature") || props.containsKey("data");
    }

    @Override
    public String getName() {
        return "thinkingFilter";
    }

    @Override
    public int getOrder() {
        // ChatModelCallAdvisor(가장 안쪽) 바로 바깥에서 응답을 고친다
        return Ordered.LOWEST_PRECEDENCE - 1;
    }
}
