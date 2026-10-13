package com.depositcock.vision;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaptureUsageRepository extends JpaRepository<CaptureUsage, Long> {

    Optional<CaptureUsage> findBySellerIdAndUsageDate(Long sellerId, LocalDate usageDate);

    @Query("select coalesce(sum(u.imageCount), 0) from CaptureUsage u "
            + "where u.sellerId = :sellerId and u.usageDate between :from and :to")
    int sumBetween(@Param("sellerId") Long sellerId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
