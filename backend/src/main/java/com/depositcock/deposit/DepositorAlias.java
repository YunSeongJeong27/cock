package com.depositcock.deposit;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 입금자명 → 고객 키. 입금을 확정할 때 저장하고 다음 공구부터 자동 매칭에 쓴다 (단골 반복).
 */
@Entity
@Table(name = "depositor_alias")
public class DepositorAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sellerId;

    /** 공백을 지운 입금자명 */
    @Column(nullable = false)
    private String depositorName;

    @Column(nullable = false)
    private String customerKey;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected DepositorAlias() {
    }

    public DepositorAlias(Long sellerId, String depositorName, String customerKey, LocalDateTime createdAt) {
        this.sellerId = sellerId;
        this.depositorName = depositorName;
        this.customerKey = customerKey;
        this.createdAt = createdAt;
    }

    public void changeCustomerKey(String customerKey) {
        this.customerKey = customerKey;
    }

    public Long getId() {
        return id;
    }

    public Long getSellerId() {
        return sellerId;
    }

    public String getDepositorName() {
        return depositorName;
    }

    public String getCustomerKey() {
        return customerKey;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
