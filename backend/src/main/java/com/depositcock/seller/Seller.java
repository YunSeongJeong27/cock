package com.depositcock.seller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import com.depositcock.common.BaseTimeEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKey;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "seller")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seller extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Setter
    @Column(nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Plan plan;

    @Setter
    @Column(nullable = false)
    private LocalDateTime trialEndsAt;

    @Setter
    private LocalDate paidUntil;

    /** 관리자가 켜는 사용 제한. 켜지면 판매자 화면에서 쓰기 작업이 막힌다. */
    @Setter
    @Column(nullable = false)
    private boolean suspended;

    @Setter
    private String adminMemo;

    /** 하루 캡처(비전 AI) 제한 장수. null 이면 설정 기본값 */
    @Setter
    private Integer dailyCaptureLimit;

    /** 판매자별 댓글 주문 기능 허용 (관리자가 켬). 전체 공개 여부는 FeatureAccess */
    @Setter
    @Column(nullable = false)
    private boolean commentFeatureEnabled;

    /** 공구를 만든 뒤 뜨는 "제품 판매 TIP" 안내를 다시 보지 않기 (판매자 계정마다, 2026-10-26) */
    @Setter
    @Column(nullable = false)
    private boolean salesTipHidden;

    @Setter
    private String defaultBankName;
    @Setter
    private String defaultAccountNumber;
    @Setter
    private String defaultAccountHolder;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    /**
     * 판매자가 켜고 끈 알림 (2026-10-19). 없는 알림은 NotificationType 기본값.
     * seller_id 는 SellerNotification 이 직접 넣으므로 여기서는 읽기만 한다 (insertable·updatable = false)
     */
    @OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "seller_id", insertable = false, updatable = false)
    @MapKey(name = "notificationType")
    @Getter(AccessLevel.NONE)
    private Map<NotificationType, SellerNotification> notifications = new HashMap<>();

    public Seller(String email, String passwordHash, String displayName, Role role,
                  LocalDateTime createdAt, LocalDateTime trialEndsAt) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
        this.plan = Plan.TRIAL;
        this.createdAt = createdAt;
        this.trialEndsAt = trialEndsAt;
    }

    /**
     * 이용 기간이 끝났는지. 끝나도 자동으로 막지 않고 화면에 표시만 한다 (사용자 결정 2026-10-13).
     */
    public boolean isExpired(LocalDateTime now) {
        if (role == Role.ADMIN) {
            return false;
        }
        if (plan == Plan.PAID) {
            return paidUntil == null || paidUntil.isBefore(now.toLocalDate());
        }
        return trialEndsAt.isBefore(now);
    }

    public boolean isNotificationOn(NotificationType type) {
        SellerNotification setting = notifications.get(type);
        return setting == null ? type.isDefaultOn() : setting.isEnabled();
    }

    /** 알림 전체 (판매자가 안 정한 것은 기본값으로 채움) */
    public Map<NotificationType, Boolean> notificationSettings() {
        Map<NotificationType, Boolean> all = new EnumMap<>(NotificationType.class);
        for (NotificationType type : NotificationType.values()) {
            all.put(type, isNotificationOn(type));
        }
        return all;
    }

    /** 보낸 것만 바꾼다 (보내지 않은 알림은 그대로) */
    public void changeNotifications(Map<NotificationType, Boolean> changes) {
        changes.forEach((type, on) -> {
            if (type == null || on == null) {
                return;
            }
            SellerNotification setting = notifications.get(type);
            if (setting == null) {
                notifications.put(type, new SellerNotification(id, type, on));
            } else {
                setting.setEnabled(on);
            }
        });
    }
}
