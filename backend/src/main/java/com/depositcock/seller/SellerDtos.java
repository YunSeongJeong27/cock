package com.depositcock.seller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

public final class SellerDtos {

    private SellerDtos() {
    }

    public record SignupRequest(
            @NotBlank(message = "EMAIL_REQUIRED") @Email(message = "EMAIL_INVALID")
            @Size(max = 255, message = "EMAIL_TOO_LONG") String email,
            @NotBlank(message = "PASSWORD_REQUIRED") @Size(min = 8, max = 100, message = "PASSWORD_LENGTH") String password,
            @NotBlank(message = "SELLER_NAME_REQUIRED") @Size(max = 100, message = "SELLER_NAME_TOO_LONG") String displayName) {
    }

    public record LoginRequest(@NotBlank(message = "EMAIL_REQUIRED") String email,
                               @NotBlank(message = "PASSWORD_REQUIRED") String password) {
    }

    public record UpdateProfileRequest(
            @NotBlank(message = "SELLER_NAME_REQUIRED") @Size(max = 100, message = "SELLER_NAME_TOO_LONG") String displayName,
            @Size(max = 50, message = "BANK_NAME_TOO_LONG") String defaultBankName,
            @Size(max = 50, message = "ACCOUNT_NUMBER_TOO_LONG") String defaultAccountNumber,
            @Size(max = 50, message = "ACCOUNT_HOLDER_TOO_LONG") String defaultAccountHolder) {
    }

    @Builder

    public record MeResponse(
            Long id,
            String email,
            String displayName,
            Role role,
            Plan plan,
            LocalDateTime trialEndsAt,
            LocalDate paidUntil,
            boolean expired,
            boolean suspended,
            String defaultBankName,
            String defaultAccountNumber,
            String defaultAccountHolder,
            /** 댓글 주문 기능을 쓸 수 있는지 (전체 공개 또는 판매자별 허용) */
            boolean commentFeatureEnabled,
            /** 알림 켜짐/꺼짐 (모든 종류, 안 정한 것은 기본값) */
            Map<NotificationType, Boolean> notifications) {

        public static MeResponse of(Seller s, LocalDateTime now, boolean commentFeatureEnabled) {
            return MeResponse.builder()
                    .id(s.getId())
                    .email(s.getEmail())
                    .displayName(s.getDisplayName())
                    .role(s.getRole())
                    .plan(s.getPlan())
                    .trialEndsAt(s.getTrialEndsAt())
                    .paidUntil(s.getPaidUntil())
                    .expired(s.isExpired(now))
                    .suspended(s.isSuspended())
                    .defaultBankName(s.getDefaultBankName())
                    .defaultAccountNumber(s.getDefaultAccountNumber())
                    .defaultAccountHolder(s.getDefaultAccountHolder())
                    .commentFeatureEnabled(commentFeatureEnabled)
                    .notifications(s.notificationSettings())
                    .build();
        }
    }

    /** 알림 켜고 끄기. 보낸 종류만 바뀐다 */
    public record UpdateNotificationsRequest(@NotNull(message = "NOTIFICATIONS_REQUIRED") Map<NotificationType, Boolean> notifications) {
    }

    @Builder

    public record AdminSellerRow(
            Long id,
            String email,
            String displayName,
            Role role,
            Plan plan,
            LocalDateTime trialEndsAt,
            LocalDate paidUntil,
            boolean expired,
            boolean suspended,
            String adminMemo,
            LocalDateTime createdAt,
            long groupBuyCount,
            int captureToday,
            int captureThisMonth,
            int dailyCaptureLimit,
            boolean customCaptureLimit,
            /** 판매자별 댓글 주문 기능 허용 (전체 공개와 별개) */
            boolean commentFeatureEnabled) {
    }

    public record AdminUpdateRequest(
            @NotNull(message = "PLAN_REQUIRED") Plan plan,
            LocalDate paidUntil,
            LocalDateTime trialEndsAt,
            boolean suspended,
            @Size(max = 1000, message = "ADMIN_MEMO_TOO_LONG") String adminMemo,
            /** 하루 캡처 제한 장수. null 이면 기본값(설정) 사용 */
            @Min(value = 0, message = "CAPTURE_LIMIT_RANGE")
            @Max(value = 10000, message = "CAPTURE_LIMIT_RANGE") Integer dailyCaptureLimit,
            /** 판매자별 댓글 주문 기능 허용. null 이면 그대로 둔다 */
            Boolean commentFeatureEnabled) {
    }

    /** 서비스 전체 설정 (관리자) */
    public record AdminSettings(boolean commentFeatureOpen) {
    }
}
