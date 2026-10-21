package com.depositcock.deposit;

import java.time.LocalDateTime;

import com.depositcock.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 입금자명 → 고객 키. 입금을 확정할 때 저장하고 다음 공구부터 자동 매칭에 쓴다 (단골 반복).
 */
@Entity
@Table(name = "depositor_alias")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DepositorAlias extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sellerId;

    /** 공백을 지운 입금자명 */
    @Column(nullable = false)
    private String depositorName;

    /** 이 입금자명이 가리키는 고객 */
    @Setter
    @Column(nullable = false)
    private Long customerId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public DepositorAlias(Long sellerId, String depositorName, Long customerId, LocalDateTime createdAt) {
        this.sellerId = sellerId;
        this.depositorName = depositorName;
        this.customerId = customerId;
        this.createdAt = createdAt;
    }
}
