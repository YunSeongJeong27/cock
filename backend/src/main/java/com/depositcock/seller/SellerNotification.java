package com.depositcock.seller;

import java.io.Serializable;

import com.depositcock.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 판매자가 켜고 끈 알림 한 줄 (2026-10-19). 줄이 없는 알림은 NotificationType 기본값을 따른다.
 * 전에는 Seller 의 @ElementCollection(값만 있는 표)이었는데, 모든 테이블에 행 등록·수정 일시를 넣으면서(2026-10-21)
 * 줄마다 수정 일시를 기록하려고 엔티티로 바꿨다. 테이블 모양(키 = 판매자 + 알림 종류)은 그대로다.
 */
@Entity
@Table(name = "seller_notification")
@IdClass(SellerNotification.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerNotification extends BaseTimeEntity {

    /** Seller.notifications 의 @JoinColumn 과 같은 칸이라 이름을 직접 적는다 */
    @Id
    @Column(name = "seller_id")
    private Long sellerId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type")
    private NotificationType notificationType;

    @Setter
    @Column(nullable = false)
    private boolean enabled;

    SellerNotification(Long sellerId, NotificationType notificationType, boolean enabled) {
        this.sellerId = sellerId;
        this.notificationType = notificationType;
        this.enabled = enabled;
    }

    /** 키: (seller_id, notification_type) */
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long sellerId;
        private NotificationType notificationType;
    }
}
