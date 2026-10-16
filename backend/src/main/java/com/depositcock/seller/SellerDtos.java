package com.depositcock.seller;

import java.time.LocalDate;
import java.time.LocalDateTime;

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
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 100) String password,
            @NotBlank @Size(max = 100) String displayName) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record UpdateProfileRequest(
            @NotBlank @Size(max = 100) String displayName,
            @Size(max = 50) String defaultBankName,
            @Size(max = 50) String defaultAccountNumber,
            @Size(max = 50) String defaultAccountHolder) {
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
            boolean commentFeatureEnabled) {

        public static MeResponse of(Seller s, LocalDateTime now, boolean commentFeatureEnabled) {
            return new MeResponse(s.getId(), s.getEmail(), s.getDisplayName(), s.getRole(), s.getPlan(),
                    s.getTrialEndsAt(), s.getPaidUntil(), s.isExpired(now), s.isSuspended(),
                    s.getDefaultBankName(), s.getDefaultAccountNumber(), s.getDefaultAccountHolder(), commentFeatureEnabled);
        }
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
            @NotNull Plan plan,
            LocalDate paidUntil,
            LocalDateTime trialEndsAt,
            boolean suspended,
            @Size(max = 1000) String adminMemo,
            /** 하루 캡처 제한 장수. null 이면 기본값(설정) 사용 */
            @Min(0) @Max(10000) Integer dailyCaptureLimit,
            /** 판매자별 댓글 주문 기능 허용. null 이면 그대로 둔다 */
            Boolean commentFeatureEnabled) {
    }

    /** 서비스 전체 설정 (관리자) */
    public record AdminSettings(boolean commentFeatureOpen) {
    }
}
