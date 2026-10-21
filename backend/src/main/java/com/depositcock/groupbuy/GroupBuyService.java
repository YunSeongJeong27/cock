package com.depositcock.groupbuy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.depositcock.common.ApiException;
import com.depositcock.common.ErrorCode;
import com.depositcock.common.Notice;
import com.depositcock.common.Texts;
import com.depositcock.common.Tokens;
import com.depositcock.customer.Customers;
import com.depositcock.deposit.AliasBook;
import com.depositcock.groupbuy.GroupBuyDtos.Dashboard;
import com.depositcock.groupbuy.GroupBuyDtos.GroupBuyRequest;
import com.depositcock.groupbuy.GroupBuyDtos.GroupBuyResponse;
import com.depositcock.groupbuy.GroupBuyDtos.GroupBuySummary;
import com.depositcock.groupbuy.GroupBuyDtos.PauseRequest;
import com.depositcock.groupbuy.GroupBuyDtos.PickupPointRequest;
import com.depositcock.groupbuy.GroupBuyDtos.ProductRequest;
import com.depositcock.order.CustomerOrder;
import com.depositcock.order.CustomerOrderRepository;
import com.depositcock.order.OrderSource;
import com.depositcock.order.OrderStatus;
import com.depositcock.seller.FeatureAccess;
import com.depositcock.seller.Seller;
import com.depositcock.seller.SellerRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GroupBuyService {

    private final GroupBuyRepository groupBuyRepository;
    private final SellerRepository sellerRepository;
    private final CustomerOrderRepository orderRepository;
    private final EntityManager entityManager;
    private final AliasBook aliasBook;
    private final Customers customers;
    private final Clock clock;
    private final FeatureAccess featureAccess;
    private final StockAlerts stockAlerts;

    /**
     * 내 공구를 찾아 잠근다 (공구 행 SELECT ... FOR UPDATE).
     * 같은 공구의 주문·취소·입금 확정·댓글 반영·공구 수정을 한 줄로 세워서, 동시에 들어온 요청 때문에
     * 재고가 두 번 복구되거나 입금 1건이 두 주문에 확정되는 일을 막는다. 이 뒤에 읽는 주문·입금은 최신 상태다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public GroupBuy lockOwned(Long sellerId, Long groupBuyId) {
        return lock(getOwned(sellerId, groupBuyId));
    }

    /** id 로 공구 행만 잠근다 (소유 확인은 호출한 쪽에서 이미 했을 때) */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockById(Long groupBuyId) {
        groupBuyRepository.findByIdForUpdate(groupBuyId);
    }

    /** 이미 찾은 공구를 잠그고, 잠그기 전에 읽은 값이 낡았을 수 있으므로 다시 읽는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public GroupBuy lock(GroupBuy groupBuy) {
        groupBuyRepository.findByIdForUpdate(groupBuy.getId());
        entityManager.refresh(groupBuy);
        return groupBuy;
    }

    /** 로그인한 판매자의 공구만 찾는다. 다른 판매자의 공구는 404 (존재 여부도 알려주지 않음). */
    public GroupBuy getOwned(Long sellerId, Long groupBuyId) {
        return groupBuyRepository.findByIdAndSellerId(groupBuyId, sellerId)
                .orElseThrow(() -> new ApiException(ErrorCode.GROUP_BUY_NOT_FOUND));
    }

    public List<GroupBuySummary> list(Long sellerId) {
        LocalDateTime now = LocalDateTime.now(clock);
        // 공구마다 주문을 따로 읽던 것(공구 수만큼 조회)을 판매자 주문 한 번 조회로
        Map<Long, List<CustomerOrder>> ordersByGroupBuy = orderRepository.findByGroupBuySellerId(sellerId).stream()
                .collect(Collectors.groupingBy(o -> o.getGroupBuy().getId()));
        return groupBuyRepository.findBySellerIdOrderByIdDesc(sellerId).stream()
                .map(g -> GroupBuySummary.builder()
                        .id(g.getId())
                        .title(g.getTitle())
                        .status(g.getStatus())
                        .statusLabel(g.statusLabel(now))
                        .acceptingOrders(g.isAcceptingOrders(now))
                        .closeAt(g.getCloseAt())
                        .publicToken(g.getPublicToken())
                        .createdAt(g.getCreatedAt())
                        .dashboard(dashboard(ordersByGroupBuy.getOrDefault(g.getId(), List.of())))
                        .paused(g.isPaused())
                        .build())
                .toList();
    }

    public GroupBuyResponse getResponse(Long sellerId, Long groupBuyId) {
        return GroupBuyResponse.of(getOwned(sellerId, groupBuyId), LocalDateTime.now(clock));
    }

    public Dashboard getDashboard(Long sellerId, Long groupBuyId) {
        GroupBuy groupBuy = getOwned(sellerId, groupBuyId);
        return dashboard(orderRepository.findByGroupBuyIdOrderByOrderNoAsc(groupBuy.getId()));
    }

    @Transactional
    public GroupBuyResponse create(Long sellerId, GroupBuyRequest request) {
        Seller seller = sellerRepository.getReferenceById(sellerId);
        LocalDateTime now = LocalDateTime.now(clock);
        GroupBuy groupBuy = new GroupBuy(seller, Tokens.random(12), now);
        apply(groupBuy, request, now);
        groupBuyRepository.saveAndFlush(groupBuy);
        return GroupBuyResponse.of(groupBuy, now);
    }

    @Transactional
    public GroupBuyResponse update(Long sellerId, Long groupBuyId, GroupBuyRequest request) {
        GroupBuy groupBuy = lockOwned(sellerId, groupBuyId);
        LocalDateTime now = LocalDateTime.now(clock);
        apply(groupBuy, request, now);
        if (groupBuy.getStatus() == GroupBuyStatus.OPEN) {
            validateOpenable(groupBuy);
        }
        groupBuyRepository.flush();
        return GroupBuyResponse.of(groupBuy, now);
    }

    @Transactional
    public GroupBuyResponse open(Long sellerId, Long groupBuyId) {
        GroupBuy groupBuy = lockOwned(sellerId, groupBuyId);
        LocalDateTime now = LocalDateTime.now(clock);
        validateOpenable(groupBuy);
        if (groupBuy.getCloseAt() != null && !groupBuy.getCloseAt().isAfter(now)) {
            throw new ApiException(ErrorCode.CLOSE_AT_PASSED);
        }
        groupBuy.changeStatus(GroupBuyStatus.OPEN, now);
        return GroupBuyResponse.of(groupBuy, now);
    }

    @Transactional
    public GroupBuyResponse close(Long sellerId, Long groupBuyId) {
        GroupBuy groupBuy = lockOwned(sellerId, groupBuyId);
        LocalDateTime now = LocalDateTime.now(clock);
        groupBuy.changeStatus(GroupBuyStatus.CLOSED, now);
        return GroupBuyResponse.of(groupBuy, now);
    }

    /**
     * 주문 잠시 멈춤 (2026-10-18 사용자 요청): 판매자가 입금 대조 중에 주문이 바뀌지 않게 고객의 새 주문·수정·취소·환불 신청을
     * 막는다. 판매자는 언제든 바로 푼다. 판매자 쪽 기능(주문 변경·대조 등)은 그대로 쓴다
     */
    @Transactional
    public GroupBuyResponse pause(Long sellerId, Long groupBuyId, PauseRequest r) {
        GroupBuy groupBuy = lockOwned(sellerId, groupBuyId);
        LocalDateTime now = LocalDateTime.now(clock);
        String link = Texts.blankToNull(r.linkUrl());
        if (link != null && !Texts.isWebLink(link)) {
            throw new ApiException(ErrorCode.PAUSE_LINK_INVALID);
        }
        String message = Texts.blankToNull(r.message());
        // 문구·링크는 풀어도 남겨서 다음에 다시 쓴다
        groupBuy.setPaused(r.paused());
        groupBuy.setPauseMessage(message == null || message.equals(Notice.PAUSE_DEFAULT.text()) ? null : message);
        groupBuy.setPauseLinkUrl(link);
        groupBuy.setUpdatedAt(now);
        return GroupBuyResponse.of(groupBuy, now);
    }

    /**
     * 공구 삭제: 그 공구의 주문자·입금 정보도 함께 파기한다 (개발 전 인터뷰 결과 8번).
     * 주문·입금은 DB 에서 함께 지워지고(ON DELETE CASCADE), 판매자 단위로 남는 입금자 별칭은 이 공구의 확정이
     * 만든·바꾼 것만 되돌린다 (다른 공구 확정으로도 쓰는 별칭은 남김).
     */
    @Transactional
    public void delete(Long sellerId, Long groupBuyId) {
        GroupBuy groupBuy = lockOwned(sellerId, groupBuyId);
        List<CustomerOrder> orders = orderRepository.findByGroupBuyIdOrderByOrderNoAsc(groupBuy.getId());
        aliasBook.undoAll(sellerId, orders);
        entityManager.flush();
        // 불러온 주문이 지울 공구를 가리키고 있으므로 떼어 낸다 (주문·입금 줄은 DB 의 ON DELETE CASCADE 로 지워짐)
        orders.forEach(entityManager::detach);
        groupBuyRepository.delete(groupBuy);
        // 이 공구에서만 주문한 고객(휴대폰 번호)과 그 기기·별칭도 파기한다 (다른 공구에 주문이 있는 고객은 남김)
        customers.deleteWithoutOrders(sellerId);
    }

    private void apply(GroupBuy groupBuy, GroupBuyRequest r, LocalDateTime now) {
        if (!r.pickupEnabled() && !r.deliveryEnabled()) {
            throw new ApiException(ErrorCode.RECEIVE_SETTING_REQUIRED);
        }
        if (r.pickupEnabled() && !r.pickupPrepayEnabled() && !r.pickupOnsiteEnabled()) {
            throw new ApiException(ErrorCode.PICKUP_PAYMENT_SETTING_REQUIRED);
        }
        String kakaoUrl = Texts.blankToNull(r.kakaoInquiryUrl());
        if (kakaoUrl != null && !Texts.isWebLink(kakaoUrl)) {
            throw new ApiException(ErrorCode.KAKAO_LINK_INVALID);
        }
        if (r.deliveryEnabled() && r.shippingFeeType() == ShippingFeeType.FIXED && r.shippingFee() <= 0) {
            throw new ApiException(ErrorCode.SHIPPING_FEE_REQUIRED);
        }
        if (r.orderChannel() == OrderChannel.LINK_AND_COMMENT && groupBuy.getOrderChannel() != OrderChannel.LINK_AND_COMMENT
                && !featureAccess.commentsAllowed(groupBuy.getSeller())) {
            // 관리자가 댓글 주문 기능을 열지 않은 판매자 (이미 "링크 + 댓글"인 공구는 그대로 둘 수 있게 바꾸는 경우만 막는다)
            throw new ApiException(ErrorCode.COMMENT_CHANNEL_CLOSED);
        }
        if (r.orderChannel() == OrderChannel.LINK_ONLY && groupBuy.getId() != null
                && orderRepository.existsByGroupBuyIdAndSourceAndStatus(groupBuy.getId(), OrderSource.COMMENT, OrderStatus.ORDERED)) {
            // 링크만 쓰는 공구에서는 댓글 집계를 숨기므로, 이미 있는 댓글 주문을 관리할 수 없게 된다
            throw new ApiException(ErrorCode.COMMENT_ORDERS_EXIST);
        }
        ShippingFeeType feeType = r.deliveryEnabled() ? r.shippingFeeType() : ShippingFeeType.NONE;
        groupBuy.setTitle(r.title().trim());
        groupBuy.setDescription(Texts.blankToNull(r.description()));
        groupBuy.setCloseAt(r.closeAt());
        groupBuy.setBankName(Texts.blankToNull(r.bankName()));
        groupBuy.setAccountNumber(Texts.blankToNull(r.accountNumber()));
        groupBuy.setAccountHolder(Texts.blankToNull(r.accountHolder()));
        groupBuy.setPickupEnabled(r.pickupEnabled());
        groupBuy.setPickupPrepayEnabled(r.pickupEnabled() && r.pickupPrepayEnabled());
        groupBuy.setPickupOnsiteEnabled(r.pickupEnabled() && r.pickupOnsiteEnabled());
        groupBuy.setDeliveryEnabled(r.deliveryEnabled());
        groupBuy.setShippingFeeType(feeType);
        groupBuy.setShippingFee(feeType == ShippingFeeType.FIXED ? r.shippingFee() : 0);
        groupBuy.setOrderChannel(r.orderChannel());
        groupBuy.setUnitAddressFieldEnabled(r.unitAddressFieldEnabled());
        groupBuy.setKakaoInquiryUrl(kakaoUrl);
        groupBuy.setAdminNames(Texts.blankToNull(r.adminNames()));
        groupBuy.setUpdatedAt(now);
        syncProducts(groupBuy, r.products());
        // 픽업을 꺼도 지점은 지우지 않는다: 이미 들어온 픽업 주문은 그 지점 그대로 두고, 새 주문만 픽업을 못 고른다 (2026-10-19).
        // 전에는 지점을 지우려다 "주문이 있는 픽업 지점은 삭제할 수 없습니다"로 막혀 배송만으로 바꿀 수 없었음
        if (r.pickupEnabled()) {
            syncPickupPoints(groupBuy, r.pickupPoints());
        }
    }

    /** 요청의 id 로 기존 상품을 찾아 수정하고, 없는 것은 추가, 빠진 것은 삭제(주문이 있으면 삭제 불가). */
    private void syncProducts(GroupBuy groupBuy, List<ProductRequest> requests) {
        Map<Long, Product> existing = new HashMap<>();
        groupBuy.getProducts().forEach(p -> existing.put(p.getId(), p));
        int order = 0;
        Set<Long> kept = new HashSet<>();
        for (ProductRequest r : requests) {
            order++;
            if (r.id() != null && existing.containsKey(r.id())) {
                Product product = existing.get(r.id());
                // 재고를 이미 팔린 수량보다 작게 바꿔도 된다 (넘친 만큼 초과로 보임, 2026-10-16)
                product.setName(r.name().trim());
                product.setUnitPrice(r.unitPrice());
                product.setStockLimit(r.stockLimit());
                product.setPerOrderLimit(r.perOrderLimit());
                product.setSortOrder(order);
                product.setStockAlertThreshold(r.stockAlertThreshold());
                product.setBlockWhenSoldOut(r.blockWhenSoldOut());
                stockAlerts.check(product);
                kept.add(r.id());
            } else if (r.id() != null) {
                throw new ApiException(ErrorCode.PRODUCT_ID_NOT_FOUND, r.id());
            } else {
                groupBuy.getProducts().add(Product.builder()
                        .groupBuy(groupBuy)
                        .name(r.name().trim())
                        .unitPrice(r.unitPrice())
                        .stockLimit(r.stockLimit())
                        .perOrderLimit(r.perOrderLimit())
                        .sortOrder(order)
                        .stockAlertThreshold(r.stockAlertThreshold())
                        .blockWhenSoldOut(r.blockWhenSoldOut())
                        .build());
            }
        }
        for (Product product : List.copyOf(groupBuy.getProducts())) {
            if (product.getId() != null && !kept.contains(product.getId())) {
                if (orderRepository.existsByItemsProductId(product.getId())) {
                    throw new ApiException(ErrorCode.PRODUCT_HAS_ORDERS, product.getName());
                }
                groupBuy.getProducts().remove(product);
            }
        }
    }

    private void syncPickupPoints(GroupBuy groupBuy, List<PickupPointRequest> requests) {
        Map<Long, PickupPoint> existing = new HashMap<>();
        groupBuy.getPickupPoints().forEach(p -> existing.put(p.getId(), p));
        int order = 0;
        Set<Long> kept = new HashSet<>();
        for (PickupPointRequest r : requests) {
            order++;
            if (r.id() != null && existing.containsKey(r.id())) {
                PickupPoint point = existing.get(r.id());
                point.setName(r.name().trim());
                point.setInfo(Texts.blankToNull(r.info()));
                point.setSortOrder(order);
                kept.add(r.id());
            } else {
                groupBuy.getPickupPoints().add(new PickupPoint(groupBuy, r.name().trim(), Texts.blankToNull(r.info()), order));
            }
        }
        for (PickupPoint point : List.copyOf(groupBuy.getPickupPoints())) {
            if (point.getId() != null && !kept.contains(point.getId())) {
                // 지우면 그 지점을 고른 주문의 픽업 지점이 조용히 비워지므로(FK SET NULL) 막는다
                if (orderRepository.existsByPickupPointId(point.getId())) {
                    throw new ApiException(ErrorCode.PICKUP_POINT_HAS_ORDERS, point.getName());
                }
                groupBuy.getPickupPoints().remove(point);
            }
        }
    }

    private void validateOpenable(GroupBuy groupBuy) {
        if (groupBuy.getProducts().isEmpty()) {
            throw new ApiException(ErrorCode.PRODUCT_REQUIRED_TO_OPEN);
        }
        if (groupBuy.usesPrepay() && (groupBuy.getBankName() == null || groupBuy.getAccountNumber() == null
                || groupBuy.getAccountHolder() == null)) {
            throw new ApiException(ErrorCode.ACCOUNT_REQUIRED_FOR_PREPAY);
        }
    }

    public static Dashboard dashboard(List<CustomerOrder> orders) {
        int orderCount = 0;
        int totalQuantity = 0;
        long totalAmount = 0;
        int prepaid = 0;
        int paid = 0;
        int unpaid = 0;
        int onsite = 0;
        int pickedUp = 0;
        int refundPending = 0;
        int refundRequests = 0;
        int cancelled = 0;
        for (CustomerOrder o : orders) {
            if (!o.isActive()) {
                cancelled++;
                if (o.isRefundRequired() && o.getRefundedAt() == null) {
                    refundPending++;
                }
                continue;
            }
            orderCount++;
            if (o.isRefundPending()) {
                refundRequests++;
            }
            totalQuantity += o.totalQuantity();
            totalAmount += o.getExpectedAmount();
            if (o.isPrepaid()) {
                prepaid++;
                if (o.isPaid()) {
                    paid++;
                } else {
                    unpaid++;
                }
            } else {
                onsite++;
            }
            if (o.isPickedUp()) {
                pickedUp++;
            }
        }
        return Dashboard.builder()
                .orderCount(orderCount)
                .totalQuantity(totalQuantity)
                .totalAmount(totalAmount)
                .prepaidCount(prepaid)
                .paidCount(paid)
                .unpaidCount(unpaid)
                .onsiteCount(onsite)
                .pickedUpCount(pickedUp)
                .refundPendingCount(refundPending)
                .refundRequestCount(refundRequests)
                .cancelledCount(cancelled)
                .build();
    }
}
