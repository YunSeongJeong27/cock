package com.depositcock;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.depositcock.common.BaseTimeEntity;
import com.depositcock.groupbuy.AutoCloseScheduler;
import com.depositcock.seller.FeatureAccess;
import com.depositcock.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 모든 테이블의 행 등록·수정 일시 (2026-10-21 사용자 요청): row_created_at·row_updated_at.
 * 등록하면 둘 다 그 시각, 바뀌면 row_updated_at 만 그 시각으로 바뀐다. 주문 시각·최종 변경 같은 업무 시각과는 별개.
 * JPA 저장·수정(Auditing)뿐 아니라 JPA 를 거치지 않는 쓰기(일괄 UPDATE, JdbcTemplate)와
 * 지우기 전에 앱이 비우는 연결(DB 의 ON DELETE SET NULL 대신)도 확인한다.
 */
class RowTimestampsTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    @Autowired
    FeatureAccess featureAccess;

    @Autowired
    AutoCloseScheduler autoClose;

    record Stamps(LocalDateTime created, LocalDateTime updated) {
    }

    record Opened(Cookie seller, long id, String token, long productId) {
        String base() {
            return "/api/seller/group-buys/" + id;
        }
    }

    /** @param tableAndWhere 예: "seller where id = ?" */
    private Stamps stamps(String tableAndWhere, Object... args) {
        return jdbc.queryForObject("select row_created_at, row_updated_at from " + tableAndWhere,
                (rs, n) -> new Stamps(rs.getTimestamp(1).toLocalDateTime(), rs.getTimestamp(2).toLocalDateTime()), args);
    }

    /** 앞 단계와 시각이 겹치지 않게 잠깐 쉰다 (수정 일시가 바뀌었는지 비교하려고) */
    private static void tick() throws InterruptedException {
        Thread.sleep(20);
    }

    private Opened open(Cookie seller, String title) throws Exception {
        JsonNode created = read(mvc.perform(withJson(post("/api/seller/group-buys").cookie(seller),
                groupBuyBody(title, List.of(product("상품", 1000, null))))).andExpect(status().isOk()).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(post("/api/seller/group-buys/" + id + "/open").cookie(seller)).andExpect(status().isOk());
        return new Opened(seller, id, created.get("publicToken").asText(), created.get("products").get(0).get("id").asLong());
    }

    /** @return 주문 id */
    private long place(Opened g, String phone, String depositor) throws Exception {
        Map<String, Object> o = new HashMap<>();
        o.put("items", List.of(Map.of("productId", g.productId(), "quantity", 1)));
        o.put("phone", phone);
        o.put("depositorName", depositor);
        o.put("acknowledgeWarnings", true);
        int orderNo = read(mvc.perform(withJson(post("/api/public/g/" + g.token() + "/orders"), o))
                .andExpect(status().isOk()).andReturn()).get("orderNo").asInt();
        return jdbc.queryForObject("select id from customer_order where group_buy_id = ? and order_no = ?", Long.class,
                g.id(), orderNo);
    }

    private void sellerPost(Opened g, String path, Object body) throws Exception {
        mvc.perform(withJson(post(g.base() + path).cookie(g.seller()), body)).andExpect(status().isOk());
    }

    /** 거래내역 한 줄 올리기 → 그 입금 줄 id */
    private long[] upload(Opened g, String depositor) throws Exception {
        long uploadId = read(mvc.perform(multipart(g.base() + "/deposits/uploads")
                .file(new MockMultipartFile("file", "a.csv", "text/csv",
                        ("거래내용,거래금액,거래일자\n" + depositor + ",1000\n").getBytes(StandardCharsets.UTF_8)))
                .cookie(g.seller())).andExpect(status().isOk()).andReturn()).get("uploadId").asLong();
        long rowId = jdbc.queryForObject("select id from deposit_row where upload_id = ?", Long.class, uploadId);
        return new long[] {uploadId, rowId};
    }

    @Test
    void everyTableHasNotNullRowTimestamps() {
        List<String> tables = jdbc.queryForList("select lower(table_name) from information_schema.tables"
                + " where lower(table_schema) = 'public' and table_type = 'BASE TABLE'"
                + " and lower(table_name) <> 'flyway_schema_history'", String.class);
        assertThat(tables).contains("seller", "seller_notification", "app_setting", "customer", "group_buy", "pickup_point",
                "product", "customer_order", "order_item", "order_change", "customer_device", "deposit_upload", "deposit_row",
                "deposit_confirmation", "depositor_alias", "bank_header", "seller_bank_header", "imported_comment", "capture_usage");
        for (String table : tables) {
            List<String> columns = jdbc.queryForList("select lower(column_name) from information_schema.columns"
                    + " where lower(table_schema) = 'public' and lower(table_name) = ?"
                    + " and lower(column_name) in ('row_created_at', 'row_updated_at') and is_nullable = 'NO'", String.class, table);
            assertThat(columns).as(table).containsExactlyInAnyOrder("row_created_at", "row_updated_at");
        }
    }

    /** 새 엔티티를 만들 때 BaseTimeEntity 를 빠뜨리면 여기서 걸린다 */
    @Test
    void everyEntityExtendsBaseTimeEntity() {
        assertThat(entityManager.getMetamodel().getEntities()).isNotEmpty().allSatisfy(e ->
                assertThat(BaseTimeEntity.class).as(e.getName()).isAssignableFrom(e.getJavaType()));
    }

    @Test
    void insertSetsBothAndUpdateMovesOnlyRowUpdatedAt() throws Exception {
        LocalDateTime start = LocalDateTime.now().minusSeconds(1);
        String email = uniqueEmail();
        Cookie seller = signup(email);
        long sellerId = jdbc.queryForObject("select id from seller where email = ?", Long.class, email);
        Stamps signedUp = stamps("seller where id = ?", sellerId);
        assertThat(signedUp.created()).isAfter(start).isBefore(LocalDateTime.now().plusSeconds(1)); // 한국 시각 그대로
        assertThat(signedUp.updated()).isAfterOrEqualTo(signedUp.created());

        // 판매자 정보 수정 → 수정 일시만 바뀜
        tick();
        mvc.perform(withJson(put("/api/seller/profile").cookie(seller), Map.of("displayName", "이름 바꿈")))
                .andExpect(status().isOk());
        Stamps edited = stamps("seller where id = ?", sellerId);
        assertThat(edited.created()).isEqualTo(signedUp.created());
        assertThat(edited.updated()).isAfter(signedUp.updated());

        // 알림 설정 (seller_notification): 처음 켜면 줄이 생기고, 끄면 그 줄의 수정 일시만 바뀜
        String notification = "seller_notification where seller_id = ? and notification_type = 'MAIL_NEW_ORDER'";
        mvc.perform(withJson(put("/api/seller/notifications").cookie(seller),
                Map.of("notifications", Map.of("MAIL_NEW_ORDER", true)))).andExpect(status().isOk());
        Stamps turnedOn = stamps(notification, sellerId);
        assertThat(turnedOn.updated()).isEqualTo(turnedOn.created());
        tick();
        mvc.perform(withJson(put("/api/seller/notifications").cookie(seller),
                Map.of("notifications", Map.of("MAIL_NEW_ORDER", false)))).andExpect(status().isOk());
        Stamps turnedOff = stamps(notification, sellerId);
        assertThat(turnedOff.created()).isEqualTo(turnedOn.created());
        assertThat(turnedOff.updated()).isAfter(turnedOn.updated());
        // 같은 값을 다시 보내면 바뀐 게 없으므로 그대로. 알림 줄만 바뀌었으므로 판매자 행도 그대로
        tick();
        mvc.perform(withJson(put("/api/seller/notifications").cookie(seller),
                Map.of("notifications", Map.of("MAIL_NEW_ORDER", false)))).andExpect(status().isOk());
        assertThat(stamps(notification, sellerId)).isEqualTo(turnedOff);
        assertThat(stamps("seller where id = ?", sellerId)).isEqualTo(edited);
    }

    @Test
    void bulkUpdatesAndJdbcWritesAlsoMoveRowUpdatedAt() throws Exception {
        Opened g = open(signup(uniqueEmail()), "일괄 UPDATE");
        Stamps productCreated = stamps("product where id = ?", g.productId());

        // 주문 → 판매 수량 일괄 UPDATE (ProductRepository.increaseSold)
        tick();
        long orderId = place(g, "010-9300-0001", "김수정");
        Stamps productOrdered = stamps("product where id = ?", g.productId());
        assertThat(productOrdered.created()).isEqualTo(productCreated.created());
        assertThat(productOrdered.updated()).isAfter(productCreated.updated());
        Stamps order = stamps("customer_order where id = ?", orderId);
        Stamps item = stamps("order_item where order_id = ?", orderId);
        assertThat(item.updated()).isEqualTo(item.created());

        // 판매자 취소 → 주문은 JPA 수정, 판매 수량은 일괄 UPDATE (decreaseSold). 주문 상품 줄은 안 바뀌었으니 그대로
        tick();
        sellerPost(g, "/orders/" + orderId + "/cancel", Map.of());
        Stamps cancelled = stamps("customer_order where id = ?", orderId);
        assertThat(cancelled.created()).isEqualTo(order.created());
        assertThat(cancelled.updated()).isAfter(order.updated());
        assertThat(stamps("product where id = ?", g.productId()).updated()).isAfter(productOrdered.updated());
        assertThat(stamps("order_item where order_id = ?", orderId)).isEqualTo(item);

        // 마감 시각 자동 마감 (GroupBuyRepository.closeIfExpired 일괄 UPDATE)
        jdbc.update("update group_buy set close_at = ? where id = ?", LocalDateTime.now().minusMinutes(5), g.id());
        Stamps groupBuy = stamps("group_buy where id = ?", g.id());
        tick();
        autoClose.closeExpired();
        Stamps closed = stamps("group_buy where id = ?", g.id());
        assertThat(closed.created()).isEqualTo(groupBuy.created());
        assertThat(closed.updated()).isAfter(groupBuy.updated());

        // 서비스 설정 (app_setting, JdbcTemplate)
        String setting = "app_setting where setting_key = 'comment_feature_open'";
        Stamps settingBefore = stamps(setting);
        tick();
        featureAccess.setCommentFeatureOpen(true, LocalDateTime.now());
        Stamps settingAfter = stamps(setting);
        assertThat(settingAfter.created()).isEqualTo(settingBefore.created());
        assertThat(settingAfter.updated()).isAfter(settingBefore.updated());
    }

    /** 거래내역 업로드를 지우면 취소된 확정 이력의 입금 줄 연결이 비워지고, 그 이력 줄의 수정 일시도 바뀐다 */
    @Test
    void deletingUploadUnlinksConfirmationHistoryWithRowUpdatedAt() throws Exception {
        Opened g = open(signup(uniqueEmail()), "업로드 삭제");
        long orderId = place(g, "010-9300-0101", "김이력");
        long[] uploaded = upload(g, "김이력");
        sellerPost(g, "/deposits/confirm", Map.of("items", List.of(Map.of("rowId", uploaded[1], "orderId", orderId))));
        sellerPost(g, "/orders/" + orderId + "/unconfirm-paid", Map.of());
        long confirmationId = jdbc.queryForObject("select id from deposit_confirmation where deposit_row_id = ?", Long.class,
                uploaded[1]);
        Stamps history = stamps("deposit_confirmation where id = ?", confirmationId);

        tick();
        mvc.perform(delete(g.base() + "/deposits/uploads/" + uploaded[0]).cookie(g.seller())).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select deposit_row_id from deposit_confirmation where id = ?", Long.class,
                confirmationId)).isNull();
        Stamps unlinked = stamps("deposit_confirmation where id = ?", confirmationId);
        assertThat(unlinked.created()).isEqualTo(history.created());
        assertThat(unlinked.updated()).isAfter(history.updated());
    }

    /** 완료된 주문을 지우면 그 주문에 확정된 입금 줄도 지운다. 같은 입금 줄을 가리키던 다른 주문의 확정 이력도 같이 */
    @Test
    void deletingOrderUnlinksOtherConfirmationHistoryWithRowUpdatedAt() throws Exception {
        Opened g = open(signup(uniqueEmail()), "주문 삭제");
        long first = place(g, "010-9300-0201", "김처음");
        long second = place(g, "010-9300-0202", "이다음");
        long rowId = upload(g, "이다음")[1];
        // 같은 입금을 처음 주문에 확정했다가 취소하고, 다음 주문에 확정 → 수령해서 "완료"
        sellerPost(g, "/deposits/confirm", Map.of("items", List.of(Map.of("rowId", rowId, "orderId", first))));
        sellerPost(g, "/orders/" + first + "/unconfirm-paid", Map.of());
        sellerPost(g, "/deposits/confirm", Map.of("items", List.of(Map.of("rowId", rowId, "orderId", second))));
        sellerPost(g, "/orders/" + second + "/picked-up", Map.of("pickedUp", true));
        long history = jdbc.queryForObject("select id from deposit_confirmation where order_id = ?", Long.class, first);
        Stamps before = stamps("deposit_confirmation where id = ?", history);

        tick();
        sellerPost(g, "/orders/delete", Map.of("orderIds", List.of(second)));
        assertThat(jdbc.queryForObject("select count(*) from deposit_row where id = ?", Integer.class, rowId)).isZero();
        assertThat(jdbc.queryForObject("select deposit_row_id from deposit_confirmation where id = ?", Long.class,
                history)).isNull();
        assertThat(stamps("deposit_confirmation where id = ?", history).updated()).isAfter(before.updated());
    }

    /**
     * 공구를 지워 주문이 없어진 고객은 파기한다. 그 고객을 "별칭 바꾸기 전 고객"으로 가리키던 다른 공구의 확정 기록은
     * 연결이 비워지고 수정 일시도 바뀐다
     */
    @Test
    void deletingCustomerUnlinksAliasPreviousCustomerWithRowUpdatedAt() throws Exception {
        Cookie seller = signup(uniqueEmail());
        Opened first = open(seller, "1차");
        Opened second = open(seller, "2차");
        long firstOrder = place(first, "010-9300-0301", "같은이름");
        long secondOrder = place(second, "010-9300-0302", "같은이름");
        // 같은 입금자명을 두 고객에게 차례로 확정 → 별칭이 두 번째 고객으로 바뀜 (바꾸기 전 고객 = 첫 고객)
        sellerPost(first, "/orders/" + firstOrder + "/manual-paid", Map.of("depositorName", "같은이름", "amount", 1000));
        sellerPost(second, "/orders/" + secondOrder + "/manual-paid", Map.of("depositorName", "같은이름", "amount", 1000));
        long firstCustomer = jdbc.queryForObject("select customer_id from customer_order where id = ?", Long.class, firstOrder);
        String confirmation = "deposit_confirmation where order_id = ?";
        assertThat(jdbc.queryForObject("select alias_previous_customer_id from " + confirmation, Long.class, secondOrder))
                .isEqualTo(firstCustomer);
        Stamps before = stamps(confirmation, secondOrder);

        tick();
        mvc.perform(delete(first.base()).cookie(seller)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from customer where id = ?", Integer.class, firstCustomer)).isZero();
        assertThat(jdbc.queryForObject("select alias_previous_customer_id from " + confirmation, Long.class, secondOrder))
                .isNull();
        Stamps after = stamps(confirmation, secondOrder);
        assertThat(after.created()).isEqualTo(before.created());
        assertThat(after.updated()).isAfter(before.updated());
    }
}
