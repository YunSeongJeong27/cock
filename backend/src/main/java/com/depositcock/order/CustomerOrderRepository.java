package com.depositcock.order;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

    List<CustomerOrder> findByGroupBuyIdOrderByOrderNoAsc(Long groupBuyId);

    List<CustomerOrder> findByGroupBuyIdAndCustomerKeyOrderByOrderNoAsc(Long groupBuyId, String customerKey);

    List<CustomerOrder> findByGroupBuyIdAndSource(Long groupBuyId, OrderSource source);

    Optional<CustomerOrder> findByIdAndGroupBuyId(Long id, Long groupBuyId);

    List<CustomerOrder> findByGroupBuyIdAndDeviceTokenOrderByOrderNoAsc(Long groupBuyId, String deviceToken);

    boolean existsByGroupBuyIdAndCustomerKeyAndStatus(Long groupBuyId, String customerKey, OrderStatus status);


    @Query("select coalesce(max(o.orderNo), 0) from CustomerOrder o where o.groupBuy.id = :groupBuyId")
    int maxOrderNo(@Param("groupBuyId") Long groupBuyId);

    boolean existsByItemsProductId(Long productId);

    boolean existsByPickupPointId(Long pickupPointId);

    /** 이 판매자의 모든 공구에서 이 고객의 입금 확정 주문 (별칭을 되돌려도 되는지 볼 때) */
    @Query("select o from CustomerOrder o where o.groupBuy.seller.id = :sellerId and o.customerKey = :customerKey"
            + " and o.paid = true and o.id <> :excludeId")
    List<CustomerOrder> findOtherPaidOfCustomer(@Param("sellerId") Long sellerId,
                                                @Param("customerKey") String customerKey,
                                                @Param("excludeId") Long excludeId);
}
