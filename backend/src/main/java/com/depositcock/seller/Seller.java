package com.depositcock.seller;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "seller")
public class Seller {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Plan plan;

    @Column(nullable = false)
    private LocalDateTime trialEndsAt;

    private LocalDate paidUntil;

    /** 관리자가 켜는 사용 제한. 켜지면 판매자 화면에서 쓰기 작업이 막힌다. */
    @Column(nullable = false)
    private boolean suspended;

    private String adminMemo;

    /** 하루 캡처(비전 AI) 제한 장수. null 이면 설정 기본값 */
    private Integer dailyCaptureLimit;

    /** 판매자별 댓글 주문 기능 허용 (관리자가 켬). 전체 공개 여부는 FeatureAccess */
    @Column(nullable = false)
    private boolean commentFeatureEnabled;

    private String defaultBankName;
    private String defaultAccountNumber;
    private String defaultAccountHolder;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected Seller() {
    }

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

    public void updateDefaults(String displayName, String bankName, String accountNumber, String accountHolder) {
        this.displayName = displayName;
        this.defaultBankName = bankName;
        this.defaultAccountNumber = accountNumber;
        this.defaultAccountHolder = accountHolder;
    }

    public void changeDailyCaptureLimit(Integer dailyCaptureLimit) {
        this.dailyCaptureLimit = dailyCaptureLimit;
    }

    public Integer getDailyCaptureLimit() {
        return dailyCaptureLimit;
    }

    public boolean isCommentFeatureEnabled() {
        return commentFeatureEnabled;
    }

    public void changeCommentFeature(boolean enabled) {
        this.commentFeatureEnabled = enabled;
    }

    public void updateByAdmin(Plan plan, LocalDate paidUntil, LocalDateTime trialEndsAt, boolean suspended, String adminMemo) {
        this.plan = plan;
        this.paidUntil = paidUntil;
        if (trialEndsAt != null) {
            this.trialEndsAt = trialEndsAt;
        }
        this.suspended = suspended;
        this.adminMemo = adminMemo;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Role getRole() {
        return role;
    }

    public Plan getPlan() {
        return plan;
    }

    public LocalDateTime getTrialEndsAt() {
        return trialEndsAt;
    }

    public LocalDate getPaidUntil() {
        return paidUntil;
    }

    public boolean isSuspended() {
        return suspended;
    }

    public String getAdminMemo() {
        return adminMemo;
    }

    public String getDefaultBankName() {
        return defaultBankName;
    }

    public String getDefaultAccountNumber() {
        return defaultAccountNumber;
    }

    public String getDefaultAccountHolder() {
        return defaultAccountHolder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
