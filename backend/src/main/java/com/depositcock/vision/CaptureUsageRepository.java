package com.depositcock.vision;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaptureUsageRepository extends JpaRepository<CaptureUsage, Long> {

    Optional<CaptureUsage> findBySellerIdAndUsageDate(Long sellerId, LocalDate usageDate);

    @Query("select coalesce(sum(u.imageCount), 0) from CaptureUsage u "
            + "where u.sellerId = :sellerId and u.usageDate between :from and :to")
    int sumBetween(@Param("sellerId") Long sellerId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 그날 모든 판매자의 사용량 (관리자 판매자 목록) */
    List<CaptureUsage> findByUsageDate(LocalDate usageDate);

    /** 기간 안 판매자별 합계 (관리자 판매자 목록) */
    @Query("select u.sellerId as sellerId, sum(u.imageCount) as count from CaptureUsage u "
            + "where u.usageDate between :from and :to group by u.sellerId")
    List<SellerImages> sumPerSellerBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    interface SellerImages {
        Long getSellerId();

        Long getCount();
    }
}
