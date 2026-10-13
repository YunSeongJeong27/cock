package com.depositcock.comment;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.depositcock.comment.OrderAggregator.CustomerTotal;
import com.depositcock.common.ApiException;
import com.depositcock.common.Tokens;
import com.depositcock.groupbuy.GroupBuy;
import com.depositcock.groupbuy.GroupBuyRepository;
import com.depositcock.groupbuy.GroupBuyService;
import com.depositcock.groupbuy.Product;
import com.depositcock.groupbuy.ProductRepository;
import com.depositcock.order.CustomerOrder;
import com.depositcock.order.CustomerOrderRepository;
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
    private final EntityManager entityManager;
    private final Clock clock;

    public CommentService(GroupBuyService groupBuyService, GroupBuyRepository groupBuyRepository,
                          ImportedCommentRepository commentRepository, CustomerOrderRepository orderRepository,
                          ProductRepository productRepository, EntityManager entityManager, Clock clock) {
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

    public record CustomerView(String customerKey, String authorName, int quantity, long amount, String memo,
                               boolean needsReview) {
    }

    public record CommentsResponse(List<CommentView> comments, List<CustomerView> customers, int totalQuantity,
                                   int reviewCount, int appliedOrderCount, Long productId) {
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
        List<CustomerView> customers = aggregate(groupBuy, comments).stream()
                .map(t -> new CustomerView(t.customerKey(), t.authorName(), t.quantity(), (long) t.quantity() * unitPrice,
                        t.memo(), t.needsReview()))
                .toList();
        int total = customers.stream().mapToInt(CustomerView::quantity).sum();
        int applied = orderRepository.findByGroupBuyIdAndSource(groupBuy.getId(), OrderSource.COMMENT).stream()
                .filter(CustomerOrder::isActive).toList().size();
        return new CommentsResponse(views, customers, total, reviewCount, applied, product == null ? null : product.getId());
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
     * 집계 결과를 주문(source=COMMENT)으로 만든다. 이전에 만든 댓글 주문은 지우고 새로 만든다.
     * 이미 입금 확정·수령 처리한 댓글 주문이 있으면 덮어쓰지 않는다 (판매자가 한 작업이 사라지므로).
     */
    @Transactional
    public int apply(Long sellerId, Long groupBuyId, Long productId) {
        GroupBuy groupBuy = groupBuyService.lockOwned(sellerId, groupBuyId);
        Product product = resolveProduct(groupBuy, productId, true);
        List<CustomerOrder> existing = orderRepository.findByGroupBuyIdAndSource(groupBuy.getId(), OrderSource.COMMENT);
        if (existing.stream().anyMatch(o -> o.isPaid() || o.isPickedUp() || o.isRefundRequired())) {
            throw ApiException.badRequest("입금 확정·수령 처리한 댓글 주문이 있어 다시 반영할 수 없습니다. "
                    + "바뀐 내용은 주문 목록에서 직접 고쳐 주세요.");
        }
        for (CustomerOrder o : existing) {
            if (o.isActive()) {
                for (OrderItem item : o.getItems()) {
                    productRepository.decreaseSold(item.getProduct().getId(), item.getQuantity());
                }
            }
            orderRepository.delete(o);
        }
        orderRepository.flush();

        List<CustomerTotal> totals = aggregate(groupBuy, commentRepository.findByGroupBuyIdOrderBySeqAsc(groupBuy.getId()));
        LocalDateTime now = LocalDateTime.now(clock);
        int orderNo = orderRepository.maxOrderNo(groupBuy.getId());
        PaymentMethod payment = groupBuy.usesPrepay() ? PaymentMethod.PREPAID : PaymentMethod.ONSITE;
        int created = 0;
        for (CustomerTotal t : totals) {
            if (t.quantity() <= 0) {
                continue; // 확인 필요만 있고 수량 미정인 고객은 판매자가 수량을 정한 뒤 다시 반영
            }
            CustomerOrder order = new CustomerOrder(groupBuy, ++orderNo, OrderSource.COMMENT, t.customerKey(),
                    Tokens.random(16), now);
            order.fillCustomer(null, null, t.authorName(), null);
            order.fillReceive(ReceiveMethod.PICKUP, null, payment);
            order.addItem(new OrderItem(order, product, t.quantity()));
            order.fillAmounts(product.getUnitPrice() * t.quantity(), 0);
            orderRepository.save(order);
            productRepository.addSoldUnchecked(product.getId(), t.quantity());
            created++;
        }
        entityManager.refresh(product);
        return created;
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
