package com.depositcock.deposit;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DepositUploadRepository extends JpaRepository<DepositUpload, Long> {

    List<DepositUpload> findByGroupBuyIdOrderByIdDesc(Long groupBuyId);

    Optional<DepositUpload> findByIdAndGroupBuyId(Long id, Long groupBuyId);
}
