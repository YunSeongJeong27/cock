package com.depositcock.deposit;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BankColumnPresetRepository extends JpaRepository<BankColumnPreset, Long> {

    List<BankColumnPreset> findBySellerIdOrderByNameAsc(Long sellerId);

    Optional<BankColumnPreset> findByIdAndSellerId(Long id, Long sellerId);

    Optional<BankColumnPreset> findBySellerIdAndName(Long sellerId, String name);
}
