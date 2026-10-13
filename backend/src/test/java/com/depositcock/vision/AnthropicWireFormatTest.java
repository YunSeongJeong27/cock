package com.depositcock.vision;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Spring AI AnthropicChatModel 이 보내는 HTTP 요청을 로컬 가짜 서버로 받아 확인한다.
 * 응답은 Claude Haiku 5.5 처럼 thinking 블록(빈 텍스트) 다음에 text 블록을 준다.
 * 진짜 Anthropic 서버가 이 요청을 받아들이는지는 API 키가 생기면 따로 확인해야 한다.
 */
class AnthropicWireFormatTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> beta = new AtomicReference<>();
    private final AtomicReference<String> apiKey = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            beta.set(exchange.getRequestHeaders().getFirst("anthropic-beta"));
            apiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            String response = """
                    {"id":"msg_test","type":"message","role":"assistant","model":"claude-haiku-5-5",
                     "content":[
                       {"type":"thinking","thinking":"","signature":"sig123"},
                       {"type":"text","text":"{\\"comments\\":[{\\"author\\":\\"꼭지맘 217-1505\\",\\"content\\":\\"1\\",\\"order\\":0}]}"}
                     ],
                     "stop_reason":"end_turn","stop_sequence":null,
                     "usage":{"input_tokens":100,"output_tokens":20}}
                    """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void requestBodyAndThinkingResponse() throws Exception {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        AnthropicVisionExtractor extractor = new AnthropicVisionExtractor(
                AnthropicVisionExtractor.createChatModel("test-key", "claude-haiku-5-5", baseUrl), "claude-haiku-5-5");

        List<ExtractedComment> comments = extractor.extractComments(new byte[]{(byte) 0x89, 'P', 'N', 'G'}, "image/png");

        // 응답: thinking 블록을 건너뛰고 텍스트 JSON 을 읽었다
        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).author()).isEqualTo("꼭지맘 217-1505");

        // 요청: 실제로 보내는 JSON
        JsonNode req = new ObjectMapper().readTree(body.get());
        System.out.println("[Spring AI → Anthropic 요청 본문 키] " + iteratorToList(req.fieldNames()));
        System.out.println("[anthropic-beta] " + beta.get());
        assertThat(apiKey.get()).isEqualTo("test-key");
        assertThat(req.get("model").asText()).isEqualTo("claude-haiku-5-5");
        assertThat(req.get("temperature").asDouble()).isEqualTo(1.0);
        assertThat(req.has("top_p")).isFalse();
        assertThat(req.has("thinking")).isFalse();
        assertThat(req.get("max_tokens").asInt()).isEqualTo(16000);
        // 네이티브 구조화 출력: Spring AI 1.1.8 은 output_format(json_schema) 로 보낸다
        assertThat(req.get("output_format").get("type").asText()).isEqualTo("json_schema");
        assertThat(req.get("output_format").get("schema").toString()).contains("comments");
        assertThat(beta.get()).contains("structured-outputs-2025-11-13");
        // 이미지 블록
        JsonNode content = req.get("messages").get(0).get("content");
        boolean hasImage = false;
        for (JsonNode block : content) {
            if ("image".equals(block.get("type").asText())) {
                hasImage = true;
                assertThat(block.get("source").get("type").asText()).isEqualTo("base64");
                assertThat(block.get("source").get("media_type").asText()).isEqualTo("image/png");
            }
        }
        assertThat(hasImage).isTrue();
    }

    private static List<String> iteratorToList(java.util.Iterator<String> it) {
        List<String> list = new java.util.ArrayList<>();
        it.forEachRemaining(list::add);
        return list;
    }
}
