package com.depositcock.vision;

import java.util.List;

import com.depositcock.common.ExternalApiException;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.MimeTypeUtils;

/**
 * Spring AI(ChatClient)로 Claude 에 스크린샷 글자 읽기를 요청한다.
 * - 구조화 출력: AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT (모델 API 가 JSON 스키마를 강제하는 방식)
 * - Haiku 5.5 는 thinking 블록을 먼저 돌려줄 수 있어 ThinkingFilterAdvisor 로 걸러낸 뒤 JSON 을 읽는다.
 * - Haiku 5.5 는 temperature 가 1 이 아니면 400 이라 1.0 으로 고정한다 (Spring AI 기본값 0.8).
 * API 키는 환경변수 ANTHROPIC_API_KEY. 이미지는 메모리에서만 쓰고 저장하지 않는다.
 * 실제 API 로는 아직 확인 못 함 (API 키 필요) — docs/design.md 참고.
 */
public class AnthropicVisionExtractor implements VisionExtractor {

    record DepositList(List<ExtractedDeposit> deposits) {
    }

    record CommentList(List<ExtractedComment> comments) {
    }

    static final String DEPOSIT_PROMPT = """
            이 이미지는 한국 은행 앱의 계좌 거래내역 화면 캡처입니다.
            화면에 보이는 거래를 위에서 아래 순서대로 모두 읽어 deposits 배열에 넣으세요.
            - name: 거래 상대 이름(입금자명/적요/내용)을 화면에 보이는 글자 그대로
            - amount: 금액 숫자만 (쉼표·원·부호 없이 양수)
            - datetime: 화면에 보이는 날짜·시각 글자 그대로 (없으면 빈 문자열)
            - type: 입금이면 "입금", 출금·결제·이체 보냄이면 "출금", 이자면 "이자"
            - order: 화면 위에서부터 0, 1, 2 ...
            읽을 수 없는 글자는 추측하지 말고 그 자리에 [?] 를 쓰세요.
            잔액, 광고, 메뉴 버튼은 거래가 아니므로 넣지 마세요.
            """;

    static final String COMMENT_PROMPT = """
            이 이미지는 네이버 밴드(또는 카카오톡) 게시글의 댓글 화면 캡처입니다.
            화면에 보이는 댓글을 위에서 아래 순서대로 모두 읽어 comments 배열에 넣으세요.
            - author: 작성자 닉네임을 글자 그대로 (숫자·동호수 포함)
            - content: 댓글 내용을 글자 그대로 (이모지는 생략 가능)
            - order: 화면 위에서부터 0, 1, 2 ...
            공지, 광고, 시스템 메시지도 빼지 말고 그대로 넣으세요 (제외는 프로그램이 합니다).
            스티커만 있는 댓글은 content 를 "[스티커]" 로 쓰세요.
            읽을 수 없는 글자는 추측하지 말고 그 자리에 [?] 를 쓰세요.
            """;

    private final ChatClient chatClient;
    private final String model;

    public AnthropicVisionExtractor(String apiKey, String model) {
        this(createChatModel(apiKey, model), model);
    }

    /** 테스트에서 가짜 ChatModel 을 넣을 수 있게 열어 둔 생성자 */
    AnthropicVisionExtractor(ChatModel chatModel, String model) {
        this.chatClient = ChatClient.builder(chatModel).defaultAdvisors(new ThinkingFilterAdvisor()).build();
        this.model = model;
    }

    static ChatModel createChatModel(String apiKey, String model) {
        return createChatModel(apiKey, model, null);
    }

    /** baseUrl 은 테스트용 (null 이면 Anthropic 기본 주소) */
    static ChatModel createChatModel(String apiKey, String model, String baseUrl) {
        AnthropicApi.Builder apiBuilder = AnthropicApi.builder().apiKey(apiKey);
        if (baseUrl != null) {
            apiBuilder.baseUrl(baseUrl);
        }
        AnthropicApi api = apiBuilder.build();
        return AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(defaultOptions(model))
                // Spring AI 기본 재시도(최대 10회, 긴 대기)는 웹 요청에 너무 길어서 2회로 줄인다
                .retryTemplate(RetryTemplate.builder().maxAttempts(2).fixedBackoff(1000).build())
                .build();
    }

    static AnthropicChatOptions defaultOptions(String model) {
        return AnthropicChatOptions.builder()
                .model(model)
                .maxTokens(16000)
                .temperature(1.0)
                .build();
    }

    @Override
    public String provider() {
        return "anthropic:" + model;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public List<ExtractedDeposit> extractDeposits(byte[] image, String mediaType) {
        DepositList result = call(image, mediaType, DEPOSIT_PROMPT, DepositList.class);
        return result == null || result.deposits() == null ? List.of() : result.deposits();
    }

    @Override
    public List<ExtractedComment> extractComments(byte[] image, String mediaType) {
        CommentList result = call(image, mediaType, COMMENT_PROMPT, CommentList.class);
        return result == null || result.comments() == null ? List.of() : result.comments();
    }

    private <T> T call(byte[] image, String mediaType, String prompt, Class<T> type) {
        try {
            return chatClient.prompt()
                    .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                    .user(u -> u.text(prompt).media(MimeTypeUtils.parseMimeType(mediaType), new ByteArrayResource(image)))
                    .call()
                    .entity(type);
        } catch (ExternalApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ExternalApiException("비전 AI 호출에 실패했습니다. 직접 입력해 주세요. (" + e.getMessage() + ")", e);
        }
    }
}
