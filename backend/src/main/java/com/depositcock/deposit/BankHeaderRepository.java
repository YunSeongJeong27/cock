package com.depositcock.deposit;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BankHeaderRepository extends JpaRepository<BankHeader, Long> {

    List<BankHeader> findAllByOrderBySortOrderAscBankNameAsc();
}
