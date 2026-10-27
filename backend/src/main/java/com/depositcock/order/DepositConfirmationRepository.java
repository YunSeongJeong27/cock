package com.depositcock.order;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DepositConfirmationRepository extends JpaRepository<DepositConfirmation, Long> {

    /** 이 공구의 살아 있는 확정 (입금 줄 → 주문 연결) */
    @Query("select c from DepositConfirmation c join fetch c.order o where o.groupBuy.id = :groupBuyId and c.cancelledAt is null")
    List<DepositConfirmation> findActiveByGroupBuyId(@Param("groupBuyId") Long groupBuyId);

    /** 여러 공구(같은 입금 계좌를 쓰는 공구들)의 살아 있는 확정 (2026-10-27) */
    @Query("select c from DepositConfirmation c join fetch c.order o where o.groupBuy.id in :groupBuyIds and c.cancelledAt is null")
    List<DepositConfirmation> findActiveByGroupBuyIds(@Param("groupBuyIds") Collection<Long> groupBuyIds);

    boolean existsByActiveDepositRowId(Long depositRowId);

    /*
     * 아래 두 UPDATE 는 지우기 직전에 부른다. 입금 줄·고객을 지우면 DB 의 ON DELETE SET NULL 이 확정 기록의 연결을 비우는데,
     * DB 가 바꾸면 행 수정 일시(row_updated_at)가 그대로 남는다 (2026-10-21 모든 테이블 행 등록·수정 일시).
     * 그래서 같은 값(null)을 앱이 먼저 넣으면서 수정 일시도 넣는다. 결과는 DB 가 비울 때와 같다.
     */

    /** 지울 입금 줄을 가리키는 확정 기록(취소된 이력)의 입금 줄 연결을 비운다 */
    @Modifying(flushAutomatically = true)
    @Query("update DepositConfirmation c set c.depositRowId = null, c.rowUpdatedAt = :now where c.depositRowId in :rowIds")
    int unlinkDepositRows(@Param("rowIds") Collection<Long> rowIds, @Param("now") LocalDateTime now);

    /** 주문이 하나도 남지 않아 지울 고객(Customers.deleteWithoutOrders)을 "별칭 바꾸기 전 고객"으로 가리키는 확정 기록의 연결을 비운다 */
    @Modifying(flushAutomatically = true)
    @Query("update DepositConfirmation c set c.aliasPreviousCustomerId = null, c.rowUpdatedAt = :now"
            + " where c.aliasPreviousCustomerId in (select cu.id from Customer cu where cu.sellerId = :sellerId"
            + " and not exists (select o.id from CustomerOrder o where o.customer = cu))")
    int unlinkPreviousCustomersWithoutOrders(@Param("sellerId") Long sellerId, @Param("now") LocalDateTime now);
}
