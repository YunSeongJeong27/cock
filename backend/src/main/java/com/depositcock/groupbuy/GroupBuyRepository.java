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

    /** 판매자별 공구 수 (관리자 판매자 목록에서 판매자마다 따로 세지 않게) */
    @Query("select g.seller.id as sellerId, count(g) as count from GroupBuy g group by g.seller.id")
    List<SellerCount> countPerSeller();

    interface SellerCount {
        Long getSellerId();

        Long getCount();
    }

    /** 주문 생성 시 공구 행을 잠가 주문번호가 겹치지 않게 한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from GroupBuy g where g.id = :id")
    Optional<GroupBuy> findByIdForUpdate(@Param("id") Long id);

    /** 마감 시각이 지난 OPEN 공구 */
    @Query("select g from GroupBuy g where g.status = com.depositcock.groupbuy.GroupBuyStatus.OPEN "
            + "and g.closeAt is not null and g.closeAt <= :now")
    List<GroupBuy> findExpiredOpen(@Param("now") LocalDateTime now);

    /**
     * 마감 시각이 지난 OPEN 공구를 CLOSED 로 바꾼다. 그 사이 판매자가 직접 마감했거나 마감 시각을 바꿨으면 0.
     * JPA Auditing 을 거치지 않는 UPDATE 라 행 수정 일시(rowUpdatedAt)도 직접 넣는다 (BaseTimeEntity)
     */
    @Modifying
    @Query("update GroupBuy g set g.status = com.depositcock.groupbuy.GroupBuyStatus.CLOSED, g.updatedAt = :now, "
            + "g.rowUpdatedAt = :now "
            + "where g.id = :id and g.status = com.depositcock.groupbuy.GroupBuyStatus.OPEN "
            + "and g.closeAt is not null and g.closeAt <= :now")
    int closeIfExpired(@Param("id") Long id, @Param("now") LocalDateTime now);
}
