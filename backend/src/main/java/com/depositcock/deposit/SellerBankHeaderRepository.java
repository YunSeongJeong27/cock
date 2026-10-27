package com.depositcock.deposit;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerBankHeaderRepository extends JpaRepository<SellerBankHeader, Long> {

    List<SellerBankHeader> findBySellerIdOrderByBankNameAscFileTypeAsc(Long sellerId);

    Optional<SellerBankHeader> findBySellerIdAndBankNameAndFileType(Long sellerId, String bankName, StatementType fileType);
}
