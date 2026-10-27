package com.depositcock.order;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

    List<CustomerOrder> findByGroupBuyIdOrderByOrderNoAsc(Long groupBuyId);

    /** 여러 공구(같은 입금 계좌를 쓰는 공구들)의 주문 (2026-10-27) */
    List<CustomerOrder> findByGroupBuyIdInOrderByGroupBuyIdAscOrderNoAsc(Collection<Long> groupBuyIds);

    /**
     * 공구 목록 요약: 판매자의 공구마다 주문 집계 한 줄 (주문이 없는 공구는 빠짐).
     * 전에는 판매자의 모든 주문(지난 공구 포함)과 주문 상품·입금 확정을 읽어 자바에서 셌는데, 판매자의 누적 주문이 늘수록
     * 목록이 느려져서 DB 가 세게 했다 (2026-10-21, 주문 1만 2천 건에서 0.8초·쿼리 242번).
     * 규칙은 GroupBuyService.dashboard(공구 상세가 씀)와 같아야 한다: 살아 있는 주문 = ORDERED, 입금 확인 = 살아 있는 입금 확정
     * (취소 안 된 deposit_confirmation, 주문당 최대 1건이라 붙여도 줄이 늘지 않음). 같은지는 GroupBuyListTotalsTest 가 확인한다
     */
    @Query("select new com.depositcock.order.OrderTotals(o.groupBuy.id,"
            + " sum(case when o.status = com.depositcock.order.OrderStatus.ORDERED then 1 else 0 end),"
            + " sum(case when o.status = com.depositcock.order.OrderStatus.ORDERED then o.expectedAmount else 0 end),"
            + " sum(case when o.status = com.depositcock.order.OrderStatus.ORDERED"
            + "     and o.paymentMethod = com.depositcock.order.PaymentMethod.PREPAID then 1 else 0 end),"
            + " sum(case when o.status = com.depositcock.order.OrderStatus.ORDERED"
            + "     and o.paymentMethod = com.depositcock.order.PaymentMethod.PREPAID and c.id is not null then 1 else 0 end),"
            + " sum(case when o.status = com.depositcock.order.OrderStatus.ORDERED"
            + "     and o.paymentMethod <> com.depositcock.order.PaymentMethod.PREPAID then 1 else 0 end),"
            + " sum(case when o.status = com.depositcock.order.OrderStatus.ORDERED and o.pickedUp = true then 1 else 0 end),"
            + " sum(case when o.status <> com.depositcock.order.OrderStatus.ORDERED"
            + "     and o.refundRequired = true and o.refundedAt is null then 1 else 0 end),"
            + " sum(case when o.status = com.depositcock.order.OrderStatus.ORDERED"
            + "     and o.refundRequestedAt is not null and o.refundRejectedAt is null then 1 else 0 end),"
            + " sum(case when o.status <> com.depositcock.order.OrderStatus.ORDERED then 1 else 0 end))"
            + " from CustomerOrder o left join DepositConfirmation c on c.order = o and c.cancelledAt is null"
            + " where o.groupBuy.seller.id = :sellerId group by o.groupBuy.id")
    List<OrderTotals> totalsBySeller(@Param("sellerId") Long sellerId);

    /** 공구 목록 요약: 공구마다 살아 있는 주문의 상품 수량 합계 [공구 id, 합계] (규칙은 GroupBuyService.dashboard 의 totalQuantity) */
    @Query("select o.groupBuy.id, sum(i.quantity) from OrderItem i join i.order o"
            + " where o.groupBuy.seller.id = :sellerId and o.status = com.depositcock.order.OrderStatus.ORDERED"
            + " group by o.groupBuy.id")
    List<Object[]> quantitiesBySeller(@Param("sellerId") Long sellerId);

    List<CustomerOrder> findByGroupBuyIdAndCustomer_IdOrderByOrderNoAsc(Long groupBuyId, Long customerId);

    List<CustomerOrder> findByGroupBuyIdAndSource(Long groupBuyId, OrderSource source);

    boolean existsByGroupBuyIdAndSourceAndStatus(Long groupBuyId, OrderSource source, OrderStatus status);

    Optional<CustomerOrder> findByIdAndGroupBuyId(Long id, Long groupBuyId);

    /** 고른 여러 주문을 한 번에 (이 공구 것만) */
    List<CustomerOrder> findByGroupBuyIdAndIdIn(Long groupBuyId, Collection<Long> ids);

    List<CustomerOrder> findByGroupBuyIdAndDeviceTokenOrderByOrderNoAsc(Long groupBuyId, String deviceToken);

    @Query("select coalesce(max(o.orderNo), 0) from CustomerOrder o where o.groupBuy.id = :groupBuyId")
    int maxOrderNo(@Param("groupBuyId") Long groupBuyId);

    boolean existsByItemsProductId(Long productId);

    boolean existsByPickupPointId(Long pickupPointId);

    /** 이 고객의 다른 입금 확정 주문 (별칭을 되돌려도 되는지 볼 때. 고객은 판매자별이라 판매자 조건은 따로 필요 없음) */
    @Query("select o from CustomerOrder o where o.customer.id = :customerId and o.id <> :excludeId and exists"
            + " (select c from DepositConfirmation c where c.order = o and c.cancelledAt is null)")
    List<CustomerOrder> findOtherPaidOfCustomer(@Param("customerId") Long customerId, @Param("excludeId") Long excludeId);
}
