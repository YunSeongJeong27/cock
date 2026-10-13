package com.depositcock.deposit;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DepositRowRepository extends JpaRepository<DepositRow, Long> {

    @Query("select r from DepositRow r where r.uploadId in "
            + "(select u.id from DepositUpload u where u.groupBuyId = :groupBuyId) order by r.uploadId, r.rowNo")
    List<DepositRow> findByGroupBuyId(@Param("groupBuyId") Long groupBuyId);

    @Query("select r from DepositRow r where r.id = :rowId and r.uploadId in "
            + "(select u.id from DepositUpload u where u.groupBuyId = :groupBuyId)")
    Optional<DepositRow> findByIdAndGroupBuyId(@Param("rowId") Long rowId, @Param("groupBuyId") Long groupBuyId);

    List<DepositRow> findByUploadId(Long uploadId);

    Optional<DepositRow> findByUsedOrderId(Long orderId);
}
