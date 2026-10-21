package com.depositcock.deposit;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DepositorAliasRepository extends JpaRepository<DepositorAlias, Long> {

    List<DepositorAlias> findBySellerId(Long sellerId);

    Optional<DepositorAlias> findBySellerIdAndDepositorName(Long sellerId, String depositorName);

    Optional<DepositorAlias> findByIdAndSellerId(Long id, Long sellerId);
}
