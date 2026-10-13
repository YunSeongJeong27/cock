package com.depositcock.vision;

import java.util.Base64;
import java.util.List;
import java.util.Optional;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.depositcock.common.ExternalApiException;

/**
 * Claude API(공식 Java SDK)로 스크린샷 글자를 읽는다. 결과는 구조화 출력(JSON 스키마)으로 강제한다.
 * API 키는 환경변수 ANTHROPIC_API_KEY. 이미지는 메모리에서만 쓰고 저장하지 않는다.
 */
public class AnthropicVisionExtractor implements VisionExtractor {

    record DepositList(List<ExtractedDeposit> deposits) {
    }

    record CommentList(List<ExtractedComment> comments) {
    }

    private static final String DEPOSIT_PROMPT = """
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

    private static final String COMMENT_PROMPT = """
            이 이미지는 네이버 밴드(또는 카카오톡) 게시글의 댓글 화면 캡처입니다.
            화면에 보이는 댓글을 위에서 아래 순서대로 모두 읽어 comments 배열에 넣으세요.
            - author: 작성자 닉네임을 글자 그대로 (숫자·동호수 포함)
            - content: 댓글 내용을 글자 그대로 (이모지는 생략 가능)
            - order: 화면 위에서부터 0, 1, 2 ...
            공지, 광고, 시스템 메시지도 빼지 말고 그대로 넣으세요 (제외는 프로그램이 합니다).
            스티커만 있는 댓글은 content 를 "[스티커]" 로 쓰세요.
            읽을 수 없는 글자는 추측하지 말고 그 자리에 [?] 를 쓰세요.
            """;

    private final AnthropicClient client;
    private final String model;

    public AnthropicVisionExtractor(String apiKey, String model) {
        this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        this.model = model;
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
        StructuredMessageCreateParams<T> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .outputConfig(type)
                .addUserMessageOfBlockParams(List.of(
                        ContentBlockParam.ofImage(ImageBlockParam.builder()
                                .source(Base64ImageSource.builder()
                                        .mediaType(Base64ImageSource.MediaType.of(mediaType))
                                        .data(Base64.getEncoder().encodeToString(image))
                                        .build())
                                .build()),
                        ContentBlockParam.ofText(TextBlockParam.builder().text(prompt).build())))
                .build();
        try {
            StructuredMessage<T> response = client.messages().create(params);
            Optional<StopReason> stop = response.stopReason();
            if (stop.isPresent() && stop.get().equals(StopReason.REFUSAL)) {
                throw new ExternalApiException("비전 AI가 이 이미지 읽기를 거절했습니다. 직접 입력해 주세요.");
            }
            if (stop.isPresent() && stop.get().equals(StopReason.MAX_TOKENS)) {
                throw new ExternalApiException("캡처 한 장에 글자가 너무 많습니다. 더 작게 나눠서 올려주세요.");
            }
            return response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(typed -> typed.text())
                    .findFirst()
                    .orElse(null);
        } catch (AnthropicException e) {
            throw new ExternalApiException("비전 AI 호출에 실패했습니다: " + e.getMessage(), e);
        }
    }
}
