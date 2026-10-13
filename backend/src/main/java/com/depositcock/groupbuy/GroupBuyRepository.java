package com.depositcock.groupbuy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupBuyRepository extends JpaRepository<GroupBuy, Long> {

    List<GroupBuy> findBySellerIdOrderByIdDesc(Long sellerId);

    Optional<GroupBuy> findByIdAndSellerId(Long id, Long sellerId);

    Optional<GroupBuy> findByPublicToken(String publicToken);

    long countBySellerId(Long sellerId);

    /** 주문 생성 시 공구 행을 잠가 주문번호가 겹치지 않게 한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from GroupBuy g where g.id = :id")
    Optional<GroupBuy> findByIdForUpdate(@Param("id") Long id);

    /** 마감 시각이 지난 OPEN 공구를 CLOSED 로 바꾼다. */
    @Modifying
    @Query("update GroupBuy g set g.status = com.depositcock.groupbuy.GroupBuyStatus.CLOSED, g.updatedAt = :now "
            + "where g.status = com.depositcock.groupbuy.GroupBuyStatus.OPEN and g.closeAt is not null and g.closeAt <= :now")
    int closeExpired(@Param("now") LocalDateTime now);
}
