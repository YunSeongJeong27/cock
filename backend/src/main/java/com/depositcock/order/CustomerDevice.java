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

    /** 이 기기에서 주문을 직접 넣었는지. false 면 "입금자명 + 뒷4자리"로만 확인한 기기 */
    @Column(nullable = false)
    private boolean verifiedByOrder;

    protected CustomerDevice() {
    }

    public CustomerDevice(Long groupBuyId, String cookieToken, String customerKey, boolean verifiedByOrder,
                          LocalDateTime now) {
        this.groupBuyId = groupBuyId;
        this.cookieToken = cookieToken;
        this.customerKey = customerKey;
        this.verifiedByOrder = verifiedByOrder;
        this.createdAt = now;
        this.lastSeenAt = now;
    }

    /**
     * 이 기기를 customerKey 의 기기로 기억한다.
     * 주문으로 확인되면 true 로 올리고, 다른 사람으로 바뀌면 그 사람 기준으로 다시 정한다.
     */
    public void seen(String customerKey, boolean byOrder, LocalDateTime now) {
        if (!customerKey.equals(this.customerKey)) {
            this.verifiedByOrder = byOrder;
        } else if (byOrder) {
            this.verifiedByOrder = true;
        }
        this.customerKey = customerKey;
        this.lastSeenAt = now;
    }

    public boolean isVerifiedByOrder() {
        return verifiedByOrder;
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
