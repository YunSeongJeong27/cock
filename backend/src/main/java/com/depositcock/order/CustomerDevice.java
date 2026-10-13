package com.depositcock.order;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 쿠키 토큰 → 고객 키. 공구 단위로 따로 기억한다 (다른 공구에서는 새 고객).
 */
@Entity
@Table(name = "customer_device")
public class CustomerDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long groupBuyId;

    @Column(nullable = false, unique = true)
    private String cookieToken;

    @Column(nullable = false)
    private String customerKey;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime lastSeenAt;

    protected CustomerDevice() {
    }

    public CustomerDevice(Long groupBuyId, String cookieToken, String customerKey, LocalDateTime now) {
        this.groupBuyId = groupBuyId;
        this.cookieToken = cookieToken;
        this.customerKey = customerKey;
        this.createdAt = now;
        this.lastSeenAt = now;
    }

    public void seen(String customerKey, LocalDateTime now) {
        this.customerKey = customerKey;
        this.lastSeenAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getGroupBuyId() {
        return groupBuyId;
    }

    public String getCookieToken() {
        return cookieToken;
    }

    public String getCustomerKey() {
        return customerKey;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getLastSeenAt() {
        return lastSeenAt;
    }
}
