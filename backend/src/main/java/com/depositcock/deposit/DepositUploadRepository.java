package com.depositcock.deposit;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DepositUploadRepository extends JpaRepository<DepositUpload, Long> {

    List<DepositUpload> findBySellerIdAndAccountKeyOrderByIdDesc(Long sellerId, String accountKey);

    Optional<DepositUpload> findByIdAndSellerIdAndAccountKey(Long id, Long sellerId, String accountKey);
}
