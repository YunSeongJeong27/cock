package com.depositcock.comment;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.depositcock.comment.OrderAggregator.CustomerTotal;
import com.depositcock.common.ApiException;
import com.depositcock.common.Tokens;
import com.depositcock.customer.Customer;
import com.depositcock.customer.Customers;
import com.depositcock.groupbuy.GroupBuy;
import com.depositcock.groupbuy.GroupBuyRepository;
import com.depositcock.groupbuy.GroupBuyService;
import com.depositcock.groupbuy.Product;
import com.depositcock.groupbuy.ProductRepository;
import com.depositcock.order.CustomerOrder;
import com.depositcock.order.CustomerOrderRepository;
import com.depositcock.order.Nicknames;
import com.depositcock.order.OrderItem;
import com.depositcock.order.OrderSource;
import com.depositcock.order.PaymentMethod;
import com.depositcock.order.ReceiveMethod;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 2단계: 댓글 저장 → 해석·집계 → 주문으로 반영 (정리본 8장, M6).
 */
@Service
@Transactional(readOnly = true)
public class CommentService {

    private final GroupBuyService groupBuyService;
    private final GroupBuyRepository groupBuyRepository;
    private final ImportedCommentRepository commentRepository;
    private final CustomerOrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final Customers customers;
    private final EntityManager entityManager;
    private final Clock clock;

    public CommentService(GroupBuyService groupBuyService, GroupBuyRepository groupBuyRepository,
                          ImportedCommentRepository commentRepository, CustomerOrderRepository orderRepository,
                          ProductRepository productRepository, Customers customers, EntityManager entityManager,
                          Clock clock) {
        this.customers = customers;
        this.groupBuyService = groupBuyService;
        this.groupBuyRepository = groupBuyRepository;
        this.commentRepository = commentRepository;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    public record CommentInput(String author, String content, boolean duplicateSuspected) {
    }

    public record CommentView(Long id, int seq, String authorName, String content, boolean duplicateSuspected,
                              Integer manualQuantity, ParseType type, Integer quantity, String memo,
                              String reviewReason, boolean excludedAsAdmin) {
    }

    /**
     * 댓글 손님이 주문으로 반영됐는지 (2026-10-15 사용자 결정: 다시 반영해도 이미 반영된 손님은 건드리지 않는다).
     * NEW = 아직 주문 없음(반영하면 만듦), SAME = 주문 있음·수량 같음, DIFFERENT = 주문 있음·수량 다름(판매자 확인),
     * CANCELLED = 반영했던 주문을 취소함(다시 만들지 않음)
     */
    public enum ApplyState { NEW, SAME, DIFFERENT, CANCELLED }

    /**
     * linkOrderNos: 닉네임이 같은 링크(주문서) 주문 번호. 같은 사람이면 반영 시 주문이 두 번 잡힌다 (경고만)
     * orderNo·orderQuantity: 이미 반영된 댓글 주문 (없으면 null)
     */
    public record CustomerView(String customerKey, String authorName, int quantity, long amount, String memo,
                               boolean needsReview, List<Integer> linkOrderNos, ApplyState applyState, Integer orderNo,
                               Integer orderQuantity) {
    }

    /** 반영된 댓글 주문 중 지금 집계에 없는 것 (댓글로 취소했거나 댓글을 지움). 자동으로 취소하지 않는다 */
    public record StaleOrder(int orderNo, String nickname, int quantity) {
    }

    public record CommentsResponse(List<CommentView> comments, List<CustomerView> customers, int totalQuantity,
                                   int reviewCount, int appliedOrderCount, Long productId, List<StaleOrder> staleOrders) {
    }

    /** createdCount: 새로 만든 주문, reviewCount: 이미 반영된 주문과 댓글이 달라 판매자가 확인할 것 */
    public record ApplyResult(int createdCount, int reviewCount) {
    }

    private record Applied(ApplyState state, Integer orderNo, Integer orderQuantity) {
    }

    public void checkOwned(Long sellerId, Long groupBuyId) {
        groupBuyService.getOwned(sellerId, groupBuyId);
    }

    public CommentsResponse list(Long sellerId, Long groupBuyId, Long productId) {
        GroupBuy groupBuy = groupBuyService.getOwned(sellerId, groupBuyId);
        Product product = resolveProduct(groupBuy, productId, false);
        List<String> admins = groupBuy.adminNameList();
        List<ImportedComment> comments = commentRepository.findByGroupBuyIdOrderBySeqAsc(groupBuy.getId());
        List<CommentView> views = new ArrayList<>();
        int reviewCount = 0;
        for (ImportedComment c : comments) {
            ParseResult r = CommentOrderParser.parse(c.getContent());
            boolean admin = OrderAggregator.isAdmin(c.getAuthorName(), admins);
            if (!admin && r.type() == ParseType.REVIEW && c.getManualQuantity() == null) {
                reviewCount++;
            }
            views.add(new CommentView(c.getId(), c.getSeq(), c.getAuthorName(), c.getContent(), c.isDuplicateSuspected(),
                    c.getManualQuantity(), r.type(), r.quantity(), r.memo(), r.reviewReason(), admin));
        }
        int unitPrice = product == null ? 0 : product.getUnitPrice();
        // 댓글에는 휴대폰 번호가 없어 링크 주문과는 닉네임으로만 비교할 수 있다
        Map<String, List<Integer>> linkOrders = orderRepository.findByGroupBuyIdAndSource(groupBuy.getId(), OrderSource.FORM)
                .stream().filter(CustomerOrder::isActive).filter(o -> Nicknames.key(o.getNickname()) != null)
                .collect(Collectors.groupingBy(o -> Nicknames.key(o.getNickname()),
                        Collectors.mapping(CustomerOrder::getOrderNo, Collectors.toList())));
        Map<String, List<CustomerOrder>> appliedOrders = appliedByNickname(groupBuy.getId());
        List<CustomerTotal> totals = aggregate(groupBuy, comments);
        List<CustomerView> customers = totals.stream()
                .map(t -> {
                    Applied a = applied(appliedOrders.get(t.authorName().trim()), t.quantity());
                    return new CustomerView(t.customerKey(), t.authorName(), t.quantity(), (long) t.quantity() * unitPrice,
                            t.memo(), t.needsReview(), linkOrders.getOrDefault(Nicknames.key(t.authorName()), List.of()),
                            a.state(), a.orderNo(), a.orderQuantity());
                })
                .toList();
        int total = customers.stream().mapToInt(CustomerView::quantity).sum();
        int applied = (int) appliedOrders.values().stream().flatMap(List::stream).filter(CustomerOrder::isActive).count();
        return new CommentsResponse(views, customers, total, reviewCount, applied, product == null ? null : product.getId(),
                staleOrders(appliedOrders, totals));
    }

    /** 이미 반영한 댓글 주문(취소 포함)을 댓글 닉네임별로. 판매자가 주문의 닉네임 칸을 바꿔도 고객은 그대로라 고객 기준으로 찾는다 */
    private Map<String, List<CustomerOrder>> appliedByNickname(Long groupBuyId) {
        return orderRepository.findByGroupBuyIdAndSource(groupBuyId, OrderSource.COMMENT).stream()
                .collect(Collectors.groupingBy(o -> o.getCustomer().getCommentNickname(), LinkedHashMap::new,
                        Collectors.toList()));
    }

    private static Applied applied(List<CustomerOrder> orders, int quantity) {
        if (orders == null || orders.isEmpty()) {
            return new Applied(ApplyState.NEW, null, null);
        }
        List<CustomerOrder> active = orders.stream().filter(CustomerOrder::isActive).toList();
        if (active.isEmpty()) {
            CustomerOrder last = orders.get(orders.size() - 1);
            return new Applied(ApplyState.CANCELLED, last.getOrderNo(), last.totalQuantity());
        }
        int ordered = active.stream().mapToInt(CustomerOrder::totalQuantity).sum();
        return new Applied(ordered == quantity ? ApplyState.SAME : ApplyState.DIFFERENT, active.get(0).getOrderNo(), ordered);
    }

    private static List<StaleOrder> staleOrders(Map<String, List<CustomerOrder>> appliedOrders, List<CustomerTotal> totals) {
        Set<String> inTotals = totals.stream().map(t -> t.authorName().trim()).collect(Collectors.toSet());
        return appliedOrders.entrySet().stream()
                .filter(e -> !inTotals.contains(e.getKey()))
                .flatMap(e -> e.getValue().stream())
                .filter(CustomerOrder::isActive)
                .map(o -> new StaleOrder(o.getOrderNo(), o.getCustomer().getCommentNickname(), o.totalQuantity()))
                .toList();
    }

    @Transactional
    public int save(Long sellerId, Long groupBuyId, CommentSource source, List<CommentInput> inputs) {
        GroupBuy groupBuy = groupBuyService.getOwned(sellerId, groupBuyId);
        int seq = commentRepository.maxSeq(groupBuy.getId());
        LocalDateTime now = LocalDateTime.now(clock);
        int saved = 0;
        for (CommentInput in : inputs) {
            String author = in.author() == null ? "" : in.author().trim();
            String content = in.content() == null ? "" : in.content().trim();
            if (author.isEmpty() && content.isEmpty()) {
                continue;
            }
            if (author.isEmpty()) {
                throw ApiException.badRequest("작성자가 비어 있는 댓글이 있습니다: " + content);
            }
            if (author.contains("[?]") || content.contains("[?]")) {
                // 읽지 못한 글자가 남은 채로 저장하면 그 닉네임이 고객 키가 되어 주문으로 만들어진다
                throw ApiException.badRequest("읽지 못한 글자([?])가 남아 있습니다: " + author + " " + content);
            }
            commentRepository.save(new ImportedComment(groupBuy.getId(), source, ++seq, truncate(author, 200),
                    truncate(content, 2000), in.duplicateSuspected(), now));
            saved++;
        }
        return saved;
    }

    @Transactional
    public void update(Long sellerId, Long groupBuyId, Long commentId, String authorName, String content,
                       Integer manualQuantity) {
        GroupBuy groupBuy = groupBuyService.getOwned(sellerId, groupBuyId);
        ImportedComment comment = commentRepository.findByIdAndGroupBuyId(commentId, groupBuy.getId())
                .orElseThrow(() -> ApiException.notFound("댓글을 찾을 수 없습니다."));
        if (manualQuantity != null && (manualQuantity < 0 || manualQuantity > 999)) {
            throw ApiException.badRequest("수량을 확인해 주세요.");
        }
        if (authorName.contains("[?]") || content.contains("[?]")) {
            throw ApiException.badRequest("읽지 못한 글자([?])를 고친 뒤 저장해 주세요.");
        }
        comment.update(authorName.trim(), content.trim(), manualQuantity);
    }

    @Transactional
    public void delete(Long sellerId, Long groupBuyId, Long commentId) {
        GroupBuy groupBuy = groupBuyService.getOwned(sellerId, groupBuyId);
        commentRepository.delete(commentRepository.findByIdAndGroupBuyId(commentId, groupBuy.getId())
                .orElseThrow(() -> ApiException.notFound("댓글을 찾을 수 없습니다.")));
    }

    @Transactional
    public void deleteAll(Long sellerId, Long groupBuyId) {
        GroupBuy groupBuy = groupBuyService.getOwned(sellerId, groupBuyId);
        commentRepository.deleteByGroupBuyId(groupBuy.getId());
    }

    /**
     * 집계 결과를 주문(source=COMMENT)으로 만든다 (2026-10-15 사용자 결정).
     * 이미 반영된 손님(취소한 주문 포함)은 건드리지 않고, 처음 보는 닉네임만 주문으로 만든다.
     * 이미 반영된 손님의 댓글 합계가 주문과 다르면 자동으로 바꾸지 않고 판매자가 확인하게 한다 (reviewCount).
     * 그래서 입금 확정·수령·판매자 변경이 된 댓글 주문이 있어도 다시 반영할 수 있다.
     */
    @Transactional
    public ApplyResult apply(Long sellerId, Long groupBuyId, Long productId) {
        GroupBuy groupBuy = groupBuyService.lockOwned(sellerId, groupBuyId);
        Product product = resolveProduct(groupBuy, productId, true);
        Map<String, List<CustomerOrder>> appliedOrders = appliedByNickname(groupBuy.getId());

        List<CustomerTotal> totals = aggregate(groupBuy, commentRepository.findByGroupBuyIdOrderBySeqAsc(groupBuy.getId()));
        LocalDateTime now = LocalDateTime.now(clock);
        int orderNo = orderRepository.maxOrderNo(groupBuy.getId());
        // 댓글 주문은 픽업 예약으로 만든다 (정리본 5장). 결제 방식은 이 공구의 픽업 결제 설정을 따른다:
        // 픽업 선입금을 받으면 선입금(입금 대조 대상), 현장결제만 받으면 현장결제.
        // 픽업을 끈 공구(배송만)는 배송·선입금으로 만들고, 주소는 판매자가 따로 받는다 [제안].
        ReceiveMethod receive = groupBuy.isPickupEnabled() ? ReceiveMethod.PICKUP : ReceiveMethod.DELIVERY;
        PaymentMethod payment = receive == ReceiveMethod.DELIVERY || groupBuy.isPickupPrepayEnabled()
                ? PaymentMethod.PREPAID : PaymentMethod.ONSITE;
        int created = 0;
        int review = 0;
        for (CustomerTotal t : totals) {
            List<CustomerOrder> existing = appliedOrders.get(t.authorName().trim());
            if (existing != null) {
                if (applied(existing, t.quantity()).state() == ApplyState.DIFFERENT) {
                    review++;
                }
                continue; // 이미 반영된 손님은 그대로 둔다
            }
            if (t.quantity() <= 0) {
                continue; // 확인 필요만 있고 수량 미정인 고객은 판매자가 수량을 정한 뒤 다시 반영
            }
            // 댓글 고객은 휴대폰을 모르므로 댓글 닉네임으로 알아본다
            Customer customer = customers.byCommentNickname(groupBuy.getSeller().getId(), t.authorName().trim(), now);
            CustomerOrder order = new CustomerOrder(groupBuy, ++orderNo, OrderSource.COMMENT, customer,
                    Tokens.random(16), now);
            order.fillCustomer(null, t.authorName(), null);
            order.fillReceive(receive, null, payment);
            order.addItem(new OrderItem(order, product, t.quantity()));
            order.fillAmounts(product.getUnitPrice() * t.quantity(), 0);
            orderRepository.save(order);
            productRepository.addSoldUnchecked(product.getId(), t.quantity());
            created++;
        }
        entityManager.refresh(product);
        return new ApplyResult(created, review + staleOrders(appliedOrders, totals).size());
    }

    private List<CustomerTotal> aggregate(GroupBuy groupBuy, List<ImportedComment> comments) {
        return OrderAggregator.aggregate(comments.stream()
                .map(c -> new OrderAggregator.Input(c.getAuthorName(), c.getContent(), c.getManualQuantity()))
                .toList(), groupBuy.adminNameList());
    }

    private static Product resolveProduct(GroupBuy groupBuy, Long productId, boolean required) {
        List<Product> products = groupBuy.sortedProducts();
        if (productId != null) {
            return products.stream().filter(p -> p.getId().equals(productId)).findFirst()
                    .orElseThrow(() -> ApiException.badRequest("상품을 찾을 수 없습니다."));
        }
        if (products.isEmpty()) {
            if (required) {
                throw ApiException.badRequest("공구에 상품을 먼저 등록해 주세요.");
            }
            return null;
        }
        return products.get(0);
    }

    // ---------- 텍스트 붙여넣기 ----------

    private static final Pattern LINE = Pattern.compile("^(.+?)\\s*(?:\\||\\t|:)\\s*(.*)$");

    /**
     * 텍스트 한 줄 = 댓글 1개. 구분자: " | ", 탭, ":" (첫 번째 것). 우리가 정한 입력 형식이다.
     * 밴드에서 복사한 텍스트의 실제 형식은 확인하지 못했다 (샘플 필요).
     */
    public static List<CommentInput> parseText(String text) {
        List<CommentInput> list = new ArrayList<>();
        if (text == null) {
            return list;
        }
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = LINE.matcher(line);
            if (m.matches()) {
                list.add(new CommentInput(m.group(1).trim(), m.group(2).trim(), false));
            } else {
                list.add(new CommentInput("", line, false));
            }
        }
        return list;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
