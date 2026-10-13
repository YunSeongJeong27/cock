package com.depositcock.groupbuy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.depositcock.common.ApiException;
import com.depositcock.common.Tokens;
import com.depositcock.deposit.AliasBook;
import com.depositcock.groupbuy.GroupBuyDtos.Dashboard;
import com.depositcock.groupbuy.GroupBuyDtos.GroupBuyRequest;
import com.depositcock.groupbuy.GroupBuyDtos.GroupBuyResponse;
import com.depositcock.groupbuy.GroupBuyDtos.GroupBuySummary;
import com.depositcock.groupbuy.GroupBuyDtos.PickupPointRequest;
import com.depositcock.groupbuy.GroupBuyDtos.ProductRequest;
import com.depositcock.order.CustomerOrder;
import com.depositcock.order.CustomerOrderRepository;
import com.depositcock.order.DisplayStatus;
import com.depositcock.seller.Seller;
import com.depositcock.seller.SellerRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class GroupBuyService {

    private final GroupBuyRepository groupBuyRepository;
    private final SellerRepository sellerRepository;
    private final CustomerOrderRepository orderRepository;
    private final EntityManager entityManager;
    private final AliasBook aliasBook;
    private final Clock clock;

    public GroupBuyService(GroupBuyRepository groupBuyRepository, SellerRepository sellerRepository,
                           CustomerOrderRepository orderRepository, EntityManager entityManager, AliasBook aliasBook,
                           Clock clock) {
        this.aliasBook = aliasBook;
        this.groupBuyRepository = groupBuyRepository;
        this.sellerRepository = sellerRepository;
        this.orderRepository = orderRepository;
        this.entityManager = entityManager;
        this.clock = clock;
    }

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
                .orElseThrow(() -> ApiException.notFound("공구를 찾을 수 없습니다."));
    }

    public List<GroupBuySummary> list(Long sellerId) {
        LocalDateTime now = LocalDateTime.now(clock);
        return groupBuyRepository.findBySellerIdOrderByIdDesc(sellerId).stream()
                .map(g -> new GroupBuySummary(g.getId(), g.getTitle(), g.getStatus(), g.isAcceptingOrders(now),
                        g.getCloseAt(), g.getPublicToken(), g.getCreatedAt(),
                        dashboard(orderRepository.findByGroupBuyIdOrderByOrderNoAsc(g.getId()))))
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
            throw ApiException.badRequest("마감 일시가 지났습니다. 마감 일시를 바꾼 뒤 다시 열어주세요.");
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
    }

    private void apply(GroupBuy groupBuy, GroupBuyRequest r, LocalDateTime now) {
        if (!r.pickupEnabled() && !r.deliveryEnabled()) {
            throw ApiException.badRequest("픽업과 배송 중 하나 이상은 켜야 합니다.");
        }
        if (r.pickupEnabled() && !r.pickupPrepayEnabled() && !r.pickupOnsiteEnabled()) {
            throw ApiException.badRequest("픽업 결제 방식(선입금, 현장결제) 중 하나 이상은 켜야 합니다.");
        }
        String kakaoUrl = blankToNull(r.kakaoInquiryUrl());
        if (kakaoUrl != null && !kakaoUrl.matches("(?i)^https?://\\S+$")) {
            // javascript: 같은 주소가 고객 화면 링크로 들어가지 않게 한다
            throw ApiException.badRequest("카톡 문의 링크는 http:// 또는 https:// 로 시작하는 주소만 넣을 수 있습니다.");
        }
        if (r.deliveryEnabled() && r.shippingFeeType() == ShippingFeeType.FIXED && r.shippingFee() <= 0) {
            throw ApiException.badRequest("고정 배송비 금액을 입력해 주세요.");
        }
        groupBuy.updateSettings(new GroupBuySettings(
                r.title().trim(), blankToNull(r.description()), r.closeAt(),
                blankToNull(r.bankName()), blankToNull(r.accountNumber()), blankToNull(r.accountHolder()),
                r.pickupEnabled(), r.pickupEnabled() && r.pickupPrepayEnabled(),
                r.pickupEnabled() && r.pickupOnsiteEnabled(), r.deliveryEnabled(),
                r.deliveryEnabled() ? r.shippingFeeType() : ShippingFeeType.NONE, r.shippingFee(),
                r.nicknameFieldEnabled(), r.unitAddressFieldEnabled(), blankToNull(r.kakaoInquiryUrl()),
                blankToNull(r.adminNames())), now);
        syncProducts(groupBuy, r.products());
        syncPickupPoints(groupBuy, r.pickupEnabled() ? r.pickupPoints() : List.of());
    }

    /** 요청의 id 로 기존 상품을 찾아 수정하고, 없는 것은 추가, 빠진 것은 삭제(주문이 있으면 삭제 불가). */
    private void syncProducts(GroupBuy groupBuy, List<ProductRequest> requests) {
        Map<Long, Product> existing = new HashMap<>();
        groupBuy.getProducts().forEach(p -> existing.put(p.getId(), p));
        int order = 0;
        java.util.Set<Long> kept = new java.util.HashSet<>();
        for (ProductRequest r : requests) {
            order++;
            if (r.id() != null && existing.containsKey(r.id())) {
                Product product = existing.get(r.id());
                if (r.stockLimit() != null && r.stockLimit() < product.getSold()) {
                    throw ApiException.badRequest("'" + product.getName() + "'의 재고 한도는 이미 팔린 수량("
                            + product.getSold() + ")보다 작을 수 없습니다.");
                }
                product.update(r.name().trim(), r.unitPrice(), r.stockLimit(), r.perOrderLimit(), order);
                kept.add(r.id());
            } else if (r.id() != null) {
                throw ApiException.badRequest("상품을 찾을 수 없습니다: " + r.id());
            } else {
                groupBuy.getProducts().add(new Product(groupBuy, r.name().trim(), r.unitPrice(), r.stockLimit(),
                        r.perOrderLimit(), order));
            }
        }
        for (Product product : List.copyOf(groupBuy.getProducts())) {
            if (product.getId() != null && !kept.contains(product.getId())) {
                if (orderRepository.existsByItemsProductId(product.getId())) {
                    throw ApiException.badRequest("주문이 있는 상품('" + product.getName() + "')은 삭제할 수 없습니다.");
                }
                groupBuy.getProducts().remove(product);
            }
        }
    }

    private void syncPickupPoints(GroupBuy groupBuy, List<PickupPointRequest> requests) {
        Map<Long, PickupPoint> existing = new HashMap<>();
        groupBuy.getPickupPoints().forEach(p -> existing.put(p.getId(), p));
        int order = 0;
        java.util.Set<Long> kept = new java.util.HashSet<>();
        for (PickupPointRequest r : requests) {
            order++;
            if (r.id() != null && existing.containsKey(r.id())) {
                existing.get(r.id()).update(r.name().trim(), blankToNull(r.info()), order);
                kept.add(r.id());
            } else {
                groupBuy.getPickupPoints().add(new PickupPoint(groupBuy, r.name().trim(), blankToNull(r.info()), order));
            }
        }
        for (PickupPoint point : List.copyOf(groupBuy.getPickupPoints())) {
            if (point.getId() != null && !kept.contains(point.getId())) {
                // 지우면 그 지점을 고른 주문의 픽업 지점이 조용히 비워지므로(FK SET NULL) 막는다
                if (orderRepository.existsByPickupPointId(point.getId())) {
                    throw ApiException.badRequest("주문이 있는 픽업 지점('" + point.getName() + "')은 삭제할 수 없습니다.");
                }
                groupBuy.getPickupPoints().remove(point);
            }
        }
    }

    private void validateOpenable(GroupBuy groupBuy) {
        if (groupBuy.getProducts().isEmpty()) {
            throw ApiException.badRequest("상품을 1개 이상 등록해야 공구를 열 수 있습니다.");
        }
        if (groupBuy.usesPrepay() && (groupBuy.getBankName() == null || groupBuy.getAccountNumber() == null
                || groupBuy.getAccountHolder() == null)) {
            throw ApiException.badRequest("선입금(배송 또는 픽업 선입금)을 받으려면 입금 계좌(은행·계좌번호·예금주)를 입력해야 합니다.");
        }
    }

    public static Dashboard dashboard(List<CustomerOrder> orders) {
        int orderCount = 0;
        int totalQuantity = 0;
        long totalAmount = 0;
        int prepaid = 0;
        int paid = 0;
        int unpaid = 0;
        int reported = 0;
        int onsite = 0;
        int pickedUp = 0;
        int refundPending = 0;
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
            totalQuantity += o.totalQuantity();
            totalAmount += o.getExpectedAmount();
            if (o.isPrepaid()) {
                prepaid++;
                if (o.isPaid()) {
                    paid++;
                } else {
                    unpaid++;
                    if (o.displayStatus() == DisplayStatus.DEPOSIT_REPORTED) {
                        reported++;
                    }
                }
            } else {
                onsite++;
            }
            if (o.isPickedUp()) {
                pickedUp++;
            }
        }
        return new Dashboard(orderCount, totalQuantity, totalAmount, prepaid, paid, unpaid, reported, onsite, pickedUp,
                refundPending, cancelled);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
