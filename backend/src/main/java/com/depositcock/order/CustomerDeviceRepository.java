package com.depositcock.order;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerDeviceRepository extends JpaRepository<CustomerDevice, Long> {

    Optional<CustomerDevice> findByCookieTokenAndGroupBuyId(String cookieToken, Long groupBuyId);
}
