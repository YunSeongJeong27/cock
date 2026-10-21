package com.depositcock.order;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

    List<CustomerOrder> findByGroupBuyIdOrderByOrderNoAsc(Long groupBuyId);

    /** 판매자의 모든 공구 주문 (공구 목록 요약을 공구마다 따로 읽지 않고 한 번에 계산할 때) */
    List<CustomerOrder> findByGroupBuySellerId(Long sellerId);

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
