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

    /** 이 기기의 고객 (마지막으로 주문하거나 본인 확인한 고객) */
    @Column(nullable = false)
    private Long customerId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime lastSeenAt;

    /**
     * customerId 고객으로 본인 확인(입금자명 + 휴대폰)을 거쳤는지. true 면 그 고객의 다른 기기 주문도 (상태만) 보여준다.
     * 주문을 넣은 것만으로는 true 가 되지 않는다 (남의 휴대폰 번호로 주문해 그 사람 주문을 보는 것 방지).
     */
    @Column(nullable = false)
    private boolean identified;

    protected CustomerDevice() {
    }

    public CustomerDevice(Long groupBuyId, String cookieToken, Long customerId, boolean identified,
                          LocalDateTime now) {
        this.groupBuyId = groupBuyId;
        this.cookieToken = cookieToken;
        this.customerId = customerId;
        this.identified = identified;
        this.createdAt = now;
        this.lastSeenAt = now;
    }

    /**
     * 이 기기를 customerId 고객의 기기로 기억한다.
     * 본인 확인을 거치면 true 로 올리고, 다른 사람으로 바뀌면 그 사람 기준으로 다시 정한다.
     */
    public void seen(Long customerId, boolean identified, LocalDateTime now) {
        if (!customerId.equals(this.customerId)) {
            this.identified = identified;
        } else if (identified) {
            this.identified = true;
        }
        this.customerId = customerId;
        this.lastSeenAt = now;
    }

    public boolean isIdentified() {
        return identified;
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

    public Long getCustomerId() {
        return customerId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getLastSeenAt() {
        return lastSeenAt;
    }
}
