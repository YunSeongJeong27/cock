package com.depositcock.customer;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findBySellerIdAndPhone(Long sellerId, String phone);

    Optional<Customer> findBySellerIdAndCommentNickname(Long sellerId, String commentNickname);

    /** 주문이 하나도 남지 않은 고객을 지운다 (기기·별칭은 DB 에서 함께 지워짐) */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Customer c where c.sellerId = :sellerId"
            + " and not exists (select o.id from CustomerOrder o where o.customer = c)")
    int deleteWithoutOrders(@Param("sellerId") Long sellerId);
}
