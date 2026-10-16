package com.depositcock.order;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DepositConfirmationRepository extends JpaRepository<DepositConfirmation, Long> {

    /** 이 공구의 살아 있는 확정 (입금 줄 → 주문 연결) */
    @Query("select c from DepositConfirmation c join fetch c.order o where o.groupBuy.id = :groupBuyId and c.cancelledAt is null")
    List<DepositConfirmation> findActiveByGroupBuyId(@Param("groupBuyId") Long groupBuyId);

    boolean existsByActiveDepositRowId(Long depositRowId);
}
