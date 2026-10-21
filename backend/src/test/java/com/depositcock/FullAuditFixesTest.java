package com.depositcock;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.depositcock.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2026-10-20 전체 점검(이상한 판매자·고객 입력, 디버깅)에서 고친 것들. 기록: docs/점검기록-2026-10-20.md
 */
class FullAuditFixesTest extends IntegrationTest {

    record Opened(Cookie seller, long id, String token, long productId) {
        String base() {
            return "/api/seller/group-buys/" + id;
        }

        String pub() {
            return "/api/public/g/" + token;
        }
    }

    private Opened open(Map<String, Object> body) throws Exception {
        Cookie seller = signup(uniqueEmail());
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), body))
                .andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        return new Opened(seller, id, created.get("publicToken").asText(), created.get("products").get(0).get("id").asLong());
    }

    private Opened open() throws Exception {
        return open(groupBuyBody("점검", List.of(product("사과", 1000, null))));
    }

    private Map<String, Object> order(Opened g, String phone, String depositor, boolean acknowledge) {
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", g.productId(), "quantity", 1)));
        o.put("phone", phone);
        o.put("depositorName", depositor);
        o.put("acknowledgeWarnings", acknowledge);
        return o;
    }

    private long orderId(Opened g, int orderNo) throws Exception {
        for (JsonNode o : read(mvc.perform(get(g.base() + "/orders").cookie(g.seller())).andReturn())) {
            if (o.get("orderNo").asInt() == orderNo) {
                return o.get("id").asLong();
            }
        }
        throw new AssertionError("주문 없음: " + orderNo);
    }

    private static List<Object> listWithNull() {
        List<Object> list = new ArrayList<>();
        list.add(null);
        return list;
    }

    /** 목록 안에 null 이 든 요청은 400 (전에는 NullPointerException 으로 500, 고객 주문 API 포함) */
    @Test
    void nullInsideListsIsRejectedNot500() throws Exception {
        Opened g = open();
        Map<String, Object> o = order(g, "010-8100-0001", "널고객", false);
        o.put("items", listWithNull());
        mvc.perform(withJson(post(g.pub() + "/orders"), o))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_CHOSEN"));

        Map<String, Object> gb = groupBuyBody("널 상품", List.of());
        gb.put("products", listWithNull());
        mvc.perform(withJson(post("/api/seller/group-buys").cookie(g.seller()), gb))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PRODUCT_NAME_REQUIRED"));
        Map<String, Object> gb2 = groupBuyBody("널 지점", List.of(product("상품", 1000, null)));
        gb2.put("pickupPoints", listWithNull());
        mvc.perform(withJson(post("/api/seller/group-buys").cookie(g.seller()), gb2))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PICKUP_POINT_NAME_REQUIRED"));

        mvc.perform(withJson(post(g.base() + "/deposits/confirm").cookie(g.seller()), Map.of("items", listWithNull())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DEPOSIT_NOT_CHOSEN"));
        mvc.perform(withJson(post(g.base() + "/comments").cookie(g.seller()), Map.of("source", "TEXT", "comments", listWithNull())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(withJson(post(g.base() + "/deposits/screenshots/save").cookie(g.seller()), Map.of("rows", listWithNull())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        // 스크린샷 입금 저장은 @Valid 가 빠져 있어서 rows 를 빼면 500 이었음
        mvc.perform(withJson(post(g.base() + "/deposits/screenshots/save").cookie(g.seller()), Map.of()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SCREENSHOT_NOTHING_TO_SAVE"));
    }

    /**
     * DB 칸보다 긴 거래내역 값(메모 열을 이름 열로 고른 경우 등)과 파일 이름은 잘라서 저장한다.
     * 전에는 "이미 같은 데이터가 있거나 동시에 저장" 409 로 업로드 전체가 실패했음.
     * 같은 파일을 다시 올리면 잘린 값끼리 중복으로 잡히고, 새 입금이 없으면 빈 업로드 기록을 만들지 않는다.
     */
    @Test
    void longBankValuesAreCutAndDuplicateReuploadLeavesNoEmptyUpload() throws Exception {
        Opened g = open();
        String longName = "가".repeat(250);
        String longDate = "2026-10-20 10:00:00 " + "메".repeat(150);
        String csv = "거래일시,입금자명,입금액\n" + longDate + "," + longName + ",1000\n2026-10-20 11:00:00,김보통,2000\n";
        MockMultipartFile file = new MockMultipartFile("file", "b".repeat(320) + ".csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart(g.base() + "/deposits/uploads").file(file).cookie(g.seller()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.savedCount").value(2));
        JsonNode uploads = read(mvc.perform(get(g.base() + "/deposits/uploads").cookie(g.seller())).andReturn());
        assertThat(uploads).hasSize(1);
        assertThat(uploads.get(0).get("fileName").asText()).hasSize(300);
        JsonNode rows = read(mvc.perform(get(g.base() + "/deposits/match").cookie(g.seller())).andReturn()).get("rows");
        assertThat(rows.get(0).get("depositorName").asText()).hasSize(200);
        assertThat(rows.get(0).get("datetimeText").asText()).hasSize(100);

        // 같은 파일 다시: 둘 다 중복, 업로드 기록은 늘지 않음
        mvc.perform(multipart(g.base() + "/deposits/uploads").file(file).cookie(g.seller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedCount").value(0))
                .andExpect(jsonPath("$.duplicateCount").value(2));
        assertThat(read(mvc.perform(get(g.base() + "/deposits/uploads").cookie(g.seller())).andReturn())).hasSize(1);
    }

    /** 저장한 댓글을 고칠 때도 칸 길이에 맞춰 자른다 (저장할 때와 같게. 전에는 409) */
    @Test
    void editedCommentIsCutToColumnLength() throws Exception {
        Map<String, Object> body = groupBuyBody("댓글", List.of(product("떡", 1000, null)));
        body.put("orderChannel", "LINK_AND_COMMENT");
        Opened g = open(body);
        mvc.perform(withJson(post(g.base() + "/comments").cookie(g.seller()),
                Map.of("source", "TEXT", "comments", List.of(Map.of("author", "꼭지맘", "content", "1"))))).andExpect(status().isOk());
        long commentId = read(mvc.perform(get(g.base() + "/comments").cookie(g.seller())).andReturn())
                .get("comments").get(0).get("id").asLong();
        mvc.perform(withJson(put(g.base() + "/comments/" + commentId).cookie(g.seller()),
                Map.of("authorName", "나".repeat(300), "content", "2" + " ".repeat(10) + "메".repeat(2500)))).andExpect(status().isOk());
        JsonNode comment = read(mvc.perform(get(g.base() + "/comments").cookie(g.seller())).andReturn()).get("comments").get(0);
        assertThat(comment.get("authorName").asText()).hasSize(200);
        assertThat(comment.get("content").asText()).hasSize(2000);
    }

    /** 댓글 반영도 주문 1건 1억 원 상한을 지킨다. 전에는 int 로 곱해서 넘치면 금액이 음수로 저장됐음 */
    @Test
    void commentOrderOverMaxAmountIsRejectedInsteadOfOverflowing() throws Exception {
        Map<String, Object> body = groupBuyBody("비싼 댓글", List.of(product("금괴", 100_000_000, null)));
        body.put("orderChannel", "LINK_AND_COMMENT");
        Opened g = open(body);
        mvc.perform(withJson(post(g.base() + "/comments").cookie(g.seller()),
                Map.of("source", "TEXT", "comments", List.of(Map.of("author", "큰손", "content", "22"))))).andExpect(status().isOk());
        mvc.perform(withJson(post(g.base() + "/comments/apply").cookie(g.seller()), Map.of("productId", g.productId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMENT_ORDER_AMOUNT_TOO_LARGE"))
                .andExpect(jsonPath("$.detail").value(containsString("큰손")));
        assertThat(read(mvc.perform(get(g.base() + "/orders").cookie(g.seller())).andReturn())).isEmpty();
    }

    /** 수동 입금 확정에 금액이 없거나 숫자가 아니면 400 (전에는 0원으로 확정됐음) */
    @Test
    void manualPaidNeedsAnAmount() throws Exception {
        Opened g = open();
        mvc.perform(withJson(post(g.pub() + "/orders"), order(g, "010-8100-0101", "김수동", false))).andExpect(status().isOk());
        String path = g.base() + "/orders/" + orderId(g, 1) + "/manual-paid";
        Map<String, Object> noAmount = new HashMap<>();
        noAmount.put("depositorName", "김수동");
        noAmount.put("amount", null);
        mvc.perform(withJson(post(path).cookie(g.seller()), noAmount))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AMOUNT_REQUIRED"));
        mvc.perform(post(path).cookie(g.seller()).contentType(MediaType.APPLICATION_JSON).content("{\"depositorName\":\"김수동\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AMOUNT_REQUIRED"));
        mvc.perform(withJson(post(path).cookie(g.seller()), Map.of("depositorName", "김수동", "amount", 1000))).andExpect(status().isOk());
    }

    /** Spring 기본 예외도 {detail: 한국어 문구, code} 모양 (전에는 영어 문구, code 없음) */
    @Test
    void springDefaultErrorsAnswerInKoreanWithCode() throws Exception {
        Opened g = open();
        mvc.perform(get("/api/seller/group-buys/abc/orders").cookie(g.seller()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andExpect(jsonPath("$.detail").value(not(containsString("Failed"))));
        mvc.perform(multipart(g.base() + "/deposits/uploads").cookie(g.seller()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
        mvc.perform(put(g.base() + "/open").cookie(g.seller()))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("REQUEST_NOT_SUPPORTED"));
        mvc.perform(get("/api/no-such-api").cookie(g.seller()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
        mvc.perform(get(g.base() + "/announcement").param("since", "어제").cookie(g.seller()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
    }

    /** 탈퇴한 판매자의 남은 로그인 쿠키로 쓰기 요청: 다른 오류와 같은 모양의 401 (전에는 본문 없음) */
    @Test
    void leftoverCookieOfWithdrawnSellerGetsLoginRequired() throws Exception {
        Cookie seller = signup(uniqueEmail());
        mvc.perform(withJson(post("/api/seller/withdraw").cookie(seller), Map.of("password", "password123"))).andExpect(status().isOk());
        mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller), groupBuyBody("탈퇴 뒤", List.of(product("a", 1, null)))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
    }

    /**
     * 위 처리를 넣다가 우리가 던지는 409(경고·환불 안내·주문 고르기)의 본문까지 덮어쓰는 회귀를 브라우저 점검에서 잡았다.
     * 화면이 쓰는 code 와 추가 값(warnings, replaceOrderNo, refundRequired, orders)이 그대로 오는지 지킨다.
     */
    @Test
    void ourConflictResponsesKeepTheirCodeAndExtraFields() throws Exception {
        Opened g = open();
        mvc.perform(withJson(post(g.pub() + "/orders"), order(g, "010-8100-0201", "김경고", false))).andExpect(status().isOk());
        // 같은 번호로 다시 주문 → 수정 경고
        mvc.perform(withJson(post(g.pub() + "/orders"), order(g, "010-8100-0201", "김경고", false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REORDER_WARNING"))
                .andExpect(jsonPath("$.warnings.length()").value(1))
                .andExpect(jsonPath("$.replaceOrderNo").value(1))
                .andExpect(jsonPath("$.replaceOrder.orderNo").value(1));

        // 확정된 주문을 바로 취소 → 환불 신청 안내
        long first = orderId(g, 1);
        mvc.perform(withJson(post(g.base() + "/orders/" + first + "/manual-paid").cookie(g.seller()),
                Map.of("depositorName", "김경고", "amount", 1000))).andExpect(status().isOk());
        Map<String, Object> cancel = Map.of("phone", "010-8100-0201", "depositorName", "김경고");
        mvc.perform(withJson(post(g.pub() + "/cancel"), cancel))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_INSTEAD"))
                .andExpect(jsonPath("$.refundRequired").value(true));

        // 확정 뒤 같은 번호로 새 주문 → 확정 취소 → 바로 취소할 수 있는 주문이 2건 → 고르기
        mvc.perform(withJson(post(g.pub() + "/orders"), order(g, "010-8100-0201", "김경고", true))).andExpect(status().isOk());
        mvc.perform(post(g.base() + "/orders/" + first + "/unconfirm-paid").cookie(g.seller())).andExpect(status().isOk());
        mvc.perform(withJson(post(g.pub() + "/cancel"), cancel))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHOOSE_ORDER_TO_CANCEL"))
                .andExpect(jsonPath("$.orders.length()").value(2));
    }
}
