package com.depositcock.deposit;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DepositRowRepository extends JpaRepository<DepositRow, Long> {

    /** 판매자의 한 입금 계좌에 올린 입금 줄 전체 (2026-10-27 계좌별로 모음) */
    @Query("select r from DepositRow r where r.uploadId in (select u.id from DepositUpload u"
            + " where u.sellerId = :sellerId and u.accountKey = :accountKey) order by r.uploadId, r.rowNo")
    List<DepositRow> findByPool(@Param("sellerId") Long sellerId, @Param("accountKey") String accountKey);

    @Query("select r from DepositRow r where r.id = :rowId and r.uploadId in (select u.id from DepositUpload u"
            + " where u.sellerId = :sellerId and u.accountKey = :accountKey)")
    Optional<DepositRow> findByIdAndPool(@Param("rowId") Long rowId, @Param("sellerId") Long sellerId,
                                         @Param("accountKey") String accountKey);

    List<DepositRow> findByUploadId(Long uploadId);
}
