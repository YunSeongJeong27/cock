package com.depositcock.seller;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.depositcock.common.ApiException;
import com.depositcock.common.ErrorCode;
import com.depositcock.common.AppProperties;
import com.depositcock.common.Texts;
import com.depositcock.groupbuy.GroupBuyRepository;
import com.depositcock.vision.CaptureUsageService;
import com.depositcock.seller.SellerDtos.AdminSellerRow;
import com.depositcock.seller.SellerDtos.AdminUpdateRequest;
import com.depositcock.seller.SellerDtos.SignupRequest;
import com.depositcock.seller.SellerDtos.UpdateProfileRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SellerService {

    private final SellerRepository sellerRepository;
    private final GroupBuyRepository groupBuyRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties properties;
    private final CaptureUsageService captureUsageService;
    private final Clock clock;

    @Transactional
    public Seller signup(SignupRequest request) {
        String email = normalizeEmail(request.email());
        if (sellerRepository.existsByEmail(email)) {
            throw new ApiException(ErrorCode.EMAIL_TAKEN);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Seller seller = new Seller(email, passwordEncoder.encode(request.password()), request.displayName().trim(),
                Role.SELLER, now, now.plusDays(properties.trialDays()));
        return sellerRepository.save(seller);
    }

    public Seller login(String email, String password) {
        Seller seller = sellerRepository.findByEmail(normalizeEmail(email))
                .orElseThrow(() -> new ApiException(ErrorCode.LOGIN_FAILED));
        if (!passwordEncoder.matches(password, seller.getPasswordHash())) {
            throw new ApiException(ErrorCode.LOGIN_FAILED);
        }
        return seller;
    }

    public Seller get(Long sellerId) {
        return sellerRepository.findById(sellerId)
                .orElseThrow(() -> new ApiException(ErrorCode.LOGIN_REQUIRED));
    }

    @Transactional
    public Seller updateProfile(Long sellerId, UpdateProfileRequest request) {
        Seller seller = get(sellerId);
        seller.setDisplayName(request.displayName().trim());
        seller.setDefaultBankName(Texts.blankToNull(request.defaultBankName()));
        seller.setDefaultAccountNumber(Texts.blankToNull(request.defaultAccountNumber()));
        seller.setDefaultAccountHolder(Texts.blankToNull(request.defaultAccountHolder()));
        return seller;
    }

    @Transactional
    public Seller updateNotifications(Long sellerId, Map<NotificationType, Boolean> notifications) {
        Seller seller = get(sellerId);
        seller.changeNotifications(notifications);
        return seller;
    }

    public List<AdminSellerRow> listForAdmin() {
        LocalDateTime now = LocalDateTime.now(clock);
        return sellerRepository.findAllByOrderByIdDesc().stream()
                .map(s -> {
                    CaptureUsageService.Usage usage = captureUsageService.usage(s);
                    return AdminSellerRow.builder()
                            .id(s.getId())
                            .email(s.getEmail())
                            .displayName(s.getDisplayName())
                            .role(s.getRole())
                            .plan(s.getPlan())
                            .trialEndsAt(s.getTrialEndsAt())
                            .paidUntil(s.getPaidUntil())
                            .expired(s.isExpired(now))
                            .suspended(s.isSuspended())
                            .adminMemo(s.getAdminMemo())
                            .createdAt(s.getCreatedAt())
                            .groupBuyCount(groupBuyRepository.countBySellerId(s.getId()))
                            .captureToday(usage.usedToday())
                            .captureThisMonth(usage.usedThisMonth())
                            .dailyCaptureLimit(usage.dailyLimit())
                            .customCaptureLimit(usage.customLimit())
                            .commentFeatureEnabled(s.isCommentFeatureEnabled())
                            .build();
                })
                .toList();
    }

    @Transactional
    public void updateByAdmin(Long sellerId, AdminUpdateRequest request) {
        Seller seller = sellerRepository.findById(sellerId)
                .orElseThrow(() -> new ApiException(ErrorCode.SELLER_NOT_FOUND));
        if (seller.getRole() == Role.ADMIN && request.suspended()) {
            throw new ApiException(ErrorCode.ADMIN_CANNOT_SUSPEND);
        }
        seller.setPlan(request.plan());
        seller.setPaidUntil(request.paidUntil());
        if (request.trialEndsAt() != null) {
            seller.setTrialEndsAt(request.trialEndsAt());
        }
        seller.setSuspended(request.suspended());
        seller.setAdminMemo(Texts.blankToNull(request.adminMemo()));
        seller.setDailyCaptureLimit(request.dailyCaptureLimit());
        if (request.commentFeatureEnabled() != null) {
            seller.setCommentFeatureEnabled(request.commentFeatureEnabled());
        }
    }

    /**
     * 판매자 탈퇴(관리자 처리): 판매자의 공구·주문·입금 정보를 함께 파기한다 (개발 전 인터뷰 결과 8번).
     * FK 의 ON DELETE CASCADE 로 하위 데이터가 같이 지워진다.
     */
    @Transactional
    public void deleteByAdmin(Long sellerId) {
        Seller seller = sellerRepository.findById(sellerId)
                .orElseThrow(() -> new ApiException(ErrorCode.SELLER_NOT_FOUND));
        if (seller.getRole() == Role.ADMIN) {
            throw new ApiException(ErrorCode.ADMIN_CANNOT_DELETE);
        }
        sellerRepository.delete(seller);
    }

    /** 판매자 본인 탈퇴: 비밀번호 확인 후 판매자·공구·주문·입금 정보를 모두 파기한다. */
    @Transactional
    public void withdraw(Long sellerId, String rawPassword) {
        Seller seller = get(sellerId);
        if (seller.getRole() == Role.ADMIN) {
            throw new ApiException(ErrorCode.ADMIN_CANNOT_WITHDRAW);
        }
        if (rawPassword == null || !passwordEncoder.matches(rawPassword, seller.getPasswordHash())) {
            throw new ApiException(ErrorCode.WRONG_PASSWORD);
        }
        sellerRepository.delete(seller);
    }

    /**
     * 관리자 계정이 없으면 만든다. 같은 이메일로 일반 판매자가 먼저 가입해 있으면 관리자로 바꾸지 않고 false 를 돌려준다
     * (가입 시 이메일 인증이 없어서, 남이 관리자 이메일로 먼저 가입했을 수 있기 때문).
     */
    @Transactional
    public boolean ensureAdmin(String email, String rawPassword) {
        String normalized = normalizeEmail(email);
        Seller existing = sellerRepository.findByEmail(normalized).orElse(null);
        if (existing != null) {
            return existing.getRole() == Role.ADMIN;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        sellerRepository.save(new Seller(normalized, passwordEncoder.encode(rawPassword), "관리자", Role.ADMIN,
                now, now.plusYears(100)));
        return true;
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
