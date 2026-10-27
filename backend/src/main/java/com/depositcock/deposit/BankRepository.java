package com.depositcock.deposit;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BankRepository extends JpaRepository<Bank, Long> {

    List<Bank> findAllByOrderBySortOrderAscBankNameAsc();
}
