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

public final class SellerDtos {

    private SellerDtos() {
    }

    public record SignupRequest(
            @NotBlank(message = "이메일을 입력해 주세요.") @Email(message = "이메일 형식이 올바르지 않습니다.")
            @Size(max = 255, message = "이메일이 너무 깁니다.") String email,
            @NotBlank(message = "비밀번호를 입력해 주세요.") @Size(min = 8, max = 100, message = "비밀번호는 8~100자로 입력해 주세요.") String password,
            @NotBlank(message = "판매자 이름을 입력해 주세요.") @Size(max = 100, message = "판매자 이름은 100자까지 입력할 수 있습니다.") String displayName) {
    }

    public record LoginRequest(@NotBlank(message = "이메일을 입력해 주세요.") String email,
                               @NotBlank(message = "비밀번호를 입력해 주세요.") String password) {
    }

    public record UpdateProfileRequest(
            @NotBlank(message = "판매자 이름을 입력해 주세요.") @Size(max = 100, message = "판매자 이름은 100자까지 입력할 수 있습니다.") String displayName,
            @Size(max = 50, message = "은행 이름은 50자까지 입력할 수 있습니다.") String defaultBankName,
            @Size(max = 50, message = "계좌번호가 너무 깁니다.") String defaultAccountNumber,
            @Size(max = 50, message = "예금주는 50자까지 입력할 수 있습니다.") String defaultAccountHolder) {
    }

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
            return new MeResponse(s.getId(), s.getEmail(), s.getDisplayName(), s.getRole(), s.getPlan(),
                    s.getTrialEndsAt(), s.getPaidUntil(), s.isExpired(now), s.isSuspended(),
                    s.getDefaultBankName(), s.getDefaultAccountNumber(), s.getDefaultAccountHolder(), commentFeatureEnabled,
                    s.notificationSettings());
        }
    }

    /** 알림 켜고 끄기. 보낸 종류만 바뀐다 */
    public record UpdateNotificationsRequest(@NotNull(message = "바꿀 알림을 골라 주세요.") Map<NotificationType, Boolean> notifications) {
    }

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
            @NotNull(message = "플랜을 골라 주세요.") Plan plan,
            LocalDate paidUntil,
            LocalDateTime trialEndsAt,
            boolean suspended,
            @Size(max = 1000, message = "메모는 1,000자까지 입력할 수 있습니다.") String adminMemo,
            /** 하루 캡처 제한 장수. null 이면 기본값(설정) 사용 */
            @Min(value = 0, message = "캡처 제한은 0장 이상이어야 합니다.")
            @Max(value = 10000, message = "캡처 제한은 10,000장까지 정할 수 있습니다.") Integer dailyCaptureLimit,
            /** 판매자별 댓글 주문 기능 허용. null 이면 그대로 둔다 */
            Boolean commentFeatureEnabled) {
    }

    /** 서비스 전체 설정 (관리자) */
    public record AdminSettings(boolean commentFeatureOpen) {
    }
}
