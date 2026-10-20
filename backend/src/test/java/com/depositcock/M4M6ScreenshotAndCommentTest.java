package com.depositcock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.depositcock.support.IntegrationTest;
import com.depositcock.vision.ExtractedComment;
import com.depositcock.vision.ExtractedDeposit;
import com.depositcock.vision.VisionExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M4: 은행앱 스크린샷 → 추출 → 검수 → 저장 → 대조. M6: 댓글 → 집계 → 주문 반영 → 입금 대조.
 * 비전 AI 는 가짜(Mockito)로 바꿔서 테스트한다. 실제 AI 정확도는 실제 캡처로 따로 확인해야 한다 (미검증).
 */
class M4M6ScreenshotAndCommentTest extends IntegrationTest {

    @MockitoBean
    VisionExtractor visionExtractor;

    private long openGroupBuy(Cookie seller, Map<String, Object> body) throws Exception {
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body)).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        return id;
    }

    private static MockMultipartFile image(String name) {
        return new MockMultipartFile("files", name, "image/png", new byte[]{1, 2, 3});
    }

    @Test
    void bankScreenshotFlow() throws Exception {
        when(visionExtractor.available()).thenReturn(true);
        when(visionExtractor.provider()).thenReturn("test");
        when(visionExtractor.extractDeposits(any(), anyString())).thenReturn(
                List.of(new ExtractedDeposit("김지영", 3800, "10-08 10:01", "입금", 0),
                        new ExtractedDeposit("편의점", 5000, "10-08 11:00", "출금", 1)),
                List.of(new ExtractedDeposit("편의점", 5000, "10-08 11:00", "출금", 0),
                        new ExtractedDeposit("박[?]준", 3800, "10-08 12:00", "입금", 1)));

        Cookie seller = signup(uniqueEmail());
        long id = openGroupBuy(seller, groupBuyBody("파프리카", List.of(product("파프리카", 3800, null))));
        JsonNode g = read(mvc.perform(get("/api/seller/group-buys/" + id).cookie(seller)).andReturn());
        Map<String, Object> order = new HashMap<>();
        order.put("items", List.of(Map.of("productId", g.get("products").get(0).get("id").asLong(), "quantity", 1)));
        order.put("phone", "010-3000-0001");
        order.put("depositorName", "김지영");
        order.put("acknowledgeWarnings", true);
        mvc.perform(withJson(post("/api/public/g/" + g.get("publicToken").asText() + "/orders"), order)).andExpect(status().isOk());

        JsonNode extracted = read(mvc.perform(multipart("/api/seller/group-buys/" + id + "/deposits/screenshots/extract")
                        .file(image("a.png")).file(image("b.png")).cookie(seller))
                .andExpect(status().isOk()).andReturn());
        assertThat(extracted.get("deposits")).hasSize(2);
        assertThat(extracted.get("skippedWithdrawals").asInt()).isEqualTo(1); // 겹친 출금 1건은 중복 제거 후 1번만
        assertThat(extracted.get("deposits").get(1).get("name").asText()).isEqualTo("박[?]준");

        // [?] 가 남아 있으면 저장 불가
        mvc.perform(withJson(post("/api/seller/group-buys/" + id + "/deposits/screenshots/save").cookie(seller),
                Map.of("rows", List.of(Map.of("name", "박[?]준", "amount", 3800))))).andExpect(status().isBadRequest());
        mvc.perform(withJson(post("/api/seller/group-buys/" + id + "/deposits/screenshots/save").cookie(seller),
                        Map.of("rows", List.of(Map.of("name", "김지영", "amount", 3800, "datetimeText", "10-08 10:01"),
                                Map.of("name", "박서준", "amount", 3800)))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/seller/group-buys/" + id + "/deposits/match").cookie(seller))
                .andExpect(jsonPath("$.matchedCount").value(1));

        // 이미지가 아닌 파일은 거부
        mvc.perform(multipart("/api/seller/group-buys/" + id + "/deposits/screenshots/extract")
                .file(new MockMultipartFile("files", "a.txt", "text/plain", new byte[]{1})).cookie(seller))
                .andExpect(status().isBadRequest());
    }

    @Test
    void commentFlowToOrdersAndDeposit() throws Exception {
        when(visionExtractor.available()).thenReturn(true);
        when(visionExtractor.provider()).thenReturn("test");
        when(visionExtractor.extractComments(any(), anyString())).thenReturn(
                List.of(new ExtractedComment("꼭지맘 217-1505", "1", 0), new ExtractedComment("205-1205", "1 7시이후 픽업", 1)),
                List.of(new ExtractedComment("205-1205", "1 7시이후 픽업", 0), new ExtractedComment("망고맘", "1", 1)));

        Cookie seller = signup(uniqueEmail());
        Map<String, Object> body = groupBuyBody("파프리카 공구", List.of(product("파프리카 한봉지", 1900, null)));
        body.put("orderChannel", "LINK_AND_COMMENT");
        body.put("adminNames", "공구장");
        long id = openGroupBuy(seller, body);
        String base = "/api/seller/group-buys/" + id + "/comments";

        JsonNode extracted = read(mvc.perform(multipart(base + "/extract").file(image("1.png")).file(image("2.png")).cookie(seller))
                .andExpect(status().isOk()).andReturn());
        assertThat(extracted.get("comments")).hasSize(3);
        assertThat(extracted.get("comments").get(1).get("duplicateSuspected").asBoolean()).isTrue();

        mvc.perform(withJson(post(base).cookie(seller), Map.of("source", "SCREENSHOT", "comments", extracted.get("comments"))))
                .andExpect(jsonPath("$.savedCount").value(3));

        JsonNode text = read(mvc.perform(withJson(post(base + "/parse-text").cookie(seller), Map.of("text",
                "쏠 | 1\n신한화 | 1\n캐파101동 | 1\n신지 | 1\n공구장 | 🚫🚫1시부터 현장구매부탁드려요 댓글 ❌🚫🚫\n"))).andReturn());
        mvc.perform(withJson(post(base).cookie(seller), Map.of("source", "TEXT", "comments", text.get("comments"))))
                .andExpect(jsonPath("$.savedCount").value(5));

        JsonNode list = read(mvc.perform(get(base).cookie(seller)).andExpect(status().isOk()).andReturn());
        assertThat(list.get("customers")).hasSize(7);
        assertThat(list.get("totalQuantity").asInt()).isEqualTo(7);
        long productId = list.get("productId").asLong();

        mvc.perform(withJson(post(base + "/apply").cookie(seller), Map.of("productId", productId)))
                .andExpect(jsonPath("$.createdCount").value(7));
        // 다시 반영해도 중복되지 않는다 (이미 반영된 손님은 그대로)
        mvc.perform(withJson(post(base + "/apply").cookie(seller), Map.of("productId", productId)))
                .andExpect(jsonPath("$.createdCount").value(0))
                .andExpect(jsonPath("$.reviewCount").value(0));
        mvc.perform(get("/api/seller/group-buys/" + id + "/dashboard").cookie(seller))
                .andExpect(jsonPath("$.orderCount").value(7))
                .andExpect(jsonPath("$.totalAmount").value(13300));

        // 댓글 주문도 입금 대조: 닉네임 숫자 "2171505" → 꼭지맘 217-1505
        mvc.perform(multipart("/api/seller/group-buys/" + id + "/deposits/uploads")
                        .file(new MockMultipartFile("file", "x.csv", "text/csv", "입금자명,입금액\n2171505,1900\n".getBytes()))
                        .cookie(seller))
                .andExpect(status().isOk());
        mvc.perform(get("/api/seller/group-buys/" + id + "/deposits/match").cookie(seller))
                .andExpect(jsonPath("$.rows[0].status").value("MATCHED"))
                .andExpect(jsonPath("$.rows[0].order.nickname").value("꼭지맘 217-1505"));
    }

    @Test
    void notConfiguredVisionReturnsNotice() throws Exception {
        when(visionExtractor.available()).thenReturn(false);
        when(visionExtractor.provider()).thenReturn("none");
        Cookie seller = signup(uniqueEmail());
        long id = openGroupBuy(seller, groupBuyBody("x", List.of(product("x", 1000, null))));
        mvc.perform(multipart("/api/seller/group-buys/" + id + "/deposits/screenshots/extract").file(image("a.png")).cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deposits").isEmpty())
                .andExpect(jsonPath("$.notice").exists());
    }

    @Test
    void dailyCaptureLimitAndAdminControl() throws Exception {
        when(visionExtractor.available()).thenReturn(true);
        when(visionExtractor.provider()).thenReturn("test");
        when(visionExtractor.extractDeposits(any(), anyString())).thenReturn(List.of());

        String email = uniqueEmail();
        Cookie seller = signup(email);
        long id = openGroupBuy(seller, groupBuyBody("한도", List.of(product("x", 1000, null))));
        String url = "/api/seller/group-buys/" + id + "/deposits/screenshots/extract";

        mvc.perform(get("/api/seller/capture-usage").cookie(seller))
                .andExpect(jsonPath("$.visionAvailable").value(true))
                .andExpect(jsonPath("$.usage.dailyLimit").value(30))
                .andExpect(jsonPath("$.usage.remainingToday").value(30));

        // 관리자가 이 판매자의 하루 제한을 3장으로
        Cookie admin = login("admin@test.local", "admin1234");
        JsonNode sellers = read(mvc.perform(get("/api/admin/sellers").cookie(admin)).andReturn());
        long sellerId = 0;
        for (JsonNode s : sellers) {
            if (s.get("email").asText().equals(email)) {
                sellerId = s.get("id").asLong();
                assertThat(s.get("dailyCaptureLimit").asInt()).isEqualTo(30);
                assertThat(s.get("customCaptureLimit").asBoolean()).isFalse();
            }
        }
        Map<String, Object> update = new HashMap<>();
        update.put("plan", "TRIAL");
        update.put("suspended", false);
        update.put("dailyCaptureLimit", 3);
        mvc.perform(withJson(put("/api/admin/sellers/" + sellerId).cookie(admin), update)).andExpect(status().isOk());

        mvc.perform(multipart(url).file(image("1.png")).file(image("2.png")).cookie(seller))
                .andExpect(jsonPath("$.limitExceeded").value(false))
                .andExpect(jsonPath("$.remainingToday").value(1));
        // 남은 1장인데 2장 → 한도 초과 안내, 차감 안 함
        mvc.perform(multipart(url).file(image("1.png")).file(image("2.png")).cookie(seller))
                .andExpect(jsonPath("$.limitExceeded").value(true))
                .andExpect(jsonPath("$.notice").value(org.hamcrest.Matchers.containsString("한도 초과")));
        mvc.perform(multipart(url).file(image("1.png")).cookie(seller))
                .andExpect(jsonPath("$.limitExceeded").value(false));
        mvc.perform(multipart(url).file(image("1.png")).cookie(seller))
                .andExpect(jsonPath("$.limitExceeded").value(true))
                .andExpect(jsonPath("$.notice").value(org.hamcrest.Matchers.containsString("직접 입력")));

        // 관리자 화면: 오늘/이번 달 사용 장수
        JsonNode after = read(mvc.perform(get("/api/admin/sellers").cookie(admin)).andReturn());
        for (JsonNode s : after) {
            if (s.get("id").asLong() == sellerId) {
                assertThat(s.get("captureToday").asInt()).isEqualTo(3);
                assertThat(s.get("captureThisMonth").asInt()).isEqualTo(3);
                assertThat(s.get("customCaptureLimit").asBoolean()).isTrue();
            }
        }
        // AI 가 꺼져 있으면 사용량을 세지 않는다 (올려도 차감 안 됨)
        when(visionExtractor.available()).thenReturn(false);
        mvc.perform(multipart(url).file(image("1.png")).cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limitExceeded").value(false));
        mvc.perform(get("/api/seller/capture-usage").cookie(seller))
                .andExpect(jsonPath("$.usage.usedToday").value(3));
    }

    /** 여러 장 중 한 장이 실패해도 나머지 결과는 살리고, 실패한 장 번호를 알려준다. 전부 실패면 502. */
    @Test
    void partialFailureKeepsOtherImages() throws Exception {
        when(visionExtractor.available()).thenReturn(true);
        when(visionExtractor.provider()).thenReturn("test");
        when(visionExtractor.extractDeposits(any(), anyString()))
                .thenReturn(List.of(new ExtractedDeposit("김지영", 3800, "", "입금", 0)))
                .thenThrow(new com.depositcock.common.ExternalApiException(com.depositcock.common.ErrorCode.VISION_REFUSED))
                .thenReturn(List.of(new ExtractedDeposit("박서준", 1900, "", "입금", 0)));
        Cookie seller = signup(uniqueEmail());
        long id = openGroupBuy(seller, groupBuyBody("부분 실패", List.of(product("x", 1000, null))));
        String url = "/api/seller/group-buys/" + id + "/deposits/screenshots/extract";
        mvc.perform(multipart(url).file(image("1.png")).file(image("2.png")).file(image("3.png")).cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deposits.length()").value(2))
                .andExpect(jsonPath("$.notice").value(org.hamcrest.Matchers.containsString("2번째 캡처는 읽지 못했습니다")));

        when(visionExtractor.extractDeposits(any(), anyString()))
                .thenThrow(new com.depositcock.common.ExternalApiException(com.depositcock.common.ErrorCode.VISION_CALL_FAILED));
        mvc.perform(multipart(url).file(image("1.png")).cookie(seller)).andExpect(status().isBadGateway());
    }

    /** 서버에서 줄일 수 없는 큰 이미지(webp 7MB 초과)는 사용량을 쓰기 전에 400 으로 막는다 */
    @Test
    void oversizedUndecodableImageIsRejectedBeforeUsage() throws Exception {
        when(visionExtractor.available()).thenReturn(true);
        when(visionExtractor.provider()).thenReturn("test");
        Cookie seller = signup(uniqueEmail());
        long id = openGroupBuy(seller, groupBuyBody("큰 이미지", List.of(product("x", 1000, null))));
        String url = "/api/seller/group-buys/" + id + "/deposits/screenshots/extract";
        MockMultipartFile big = new MockMultipartFile("files", "big.webp", "image/webp",
                new byte[7_000_001]);
        mvc.perform(multipart(url).file(image("1.png")).file(big).cookie(seller))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("2번째 이미지")));
        mvc.perform(get("/api/seller/capture-usage").cookie(seller))
                .andExpect(jsonPath("$.usage.usedToday").value(0));
    }

    /** 긴 캡처를 자른 조각 경계에서 같은 줄을 못 찾으면, 경계 앞뒤 줄이 "중복 의심"으로 화면까지 간다 */
    @Test
    void pieceBoundaryFlagsReachDrafts() throws Exception {
        when(visionExtractor.available()).thenReturn(true);
        when(visionExtractor.provider()).thenReturn("test");
        // 100x500 → 조각 3개 (높이 200, 겹침 40)
        when(visionExtractor.extractDepositsFromPiece(any(), anyString()))
                .thenReturn(List.of(new ExtractedDeposit("가", 1000, "", "입금", 0)))
                .thenReturn(List.of(new ExtractedDeposit("가 ", 1000, "", "입금", 0), new ExtractedDeposit("나", 2000, "", "입금", 1)))
                .thenReturn(List.of(new ExtractedDeposit("나", 2000, "", "입금", 0), new ExtractedDeposit("다", 3000, "", "입금", 1)));
        Cookie seller = signup(uniqueEmail());
        long id = openGroupBuy(seller, groupBuyBody("긴 캡처", List.of(product("x", 1000, null))));
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(100, 500, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        MockMultipartFile tall = new MockMultipartFile("files", "tall.png", "image/png", out.toByteArray());
        JsonNode res = read(mvc.perform(multipart("/api/seller/group-buys/" + id + "/deposits/screenshots/extract")
                .file(tall).cookie(seller)).andExpect(status().isOk()).andReturn());
        JsonNode deposits = res.get("deposits");
        assertThat(deposits.size()).isEqualTo(4); // 가, 가(다르게 읽힘), 나, 다
        // 조각 1·2 경계: 같은 구간 없음 → 가 / 가·나 표시. 조각 2·3 경계: 나 가 정확히 겹침 → 합침
        assertThat(deposits.get(0).get("duplicateSuspected").asBoolean()).isTrue();
        assertThat(deposits.get(1).get("duplicateSuspected").asBoolean()).isTrue();
        assertThat(deposits.get(2).get("duplicateSuspected").asBoolean()).isTrue();
        assertThat(deposits.get(3).get("duplicateSuspected").asBoolean()).isFalse();
        // 사용량은 조각 수가 아니라 이미지 장수로 센다
        assertThat(res.get("remainingToday").asInt()).isEqualTo(29);
    }
}
