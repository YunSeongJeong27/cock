package com.depositcock.seller;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.validation.constraints.Email;
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
            String defaultAccountHolder) {

        public static MeResponse of(Seller s, LocalDateTime now) {
            return new MeResponse(s.getId(), s.getEmail(), s.getDisplayName(), s.getRole(), s.getPlan(),
                    s.getTrialEndsAt(), s.getPaidUntil(), s.isExpired(now), s.isSuspended(),
                    s.getDefaultBankName(), s.getDefaultAccountNumber(), s.getDefaultAccountHolder());
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
            long groupBuyCount) {
    }

    public record AdminUpdateRequest(
            @NotNull Plan plan,
            LocalDate paidUntil,
            LocalDateTime trialEndsAt,
            boolean suspended,
            @Size(max = 1000) String adminMemo) {
    }
}
