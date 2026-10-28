package com.depositcock.admin;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.depositcock.admin.AdminDtos.AdminSellerRow;
import com.depositcock.admin.AdminDtos.AdminUpdateRequest;
import com.depositcock.common.ApiException;
import com.depositcock.common.ErrorCode;
import com.depositcock.common.Texts;
import com.depositcock.groupbuy.GroupBuyRepository;
import com.depositcock.groupbuy.GroupBuyRepository.SellerCount;
import com.depositcock.seller.Role;
import com.depositcock.seller.Seller;
import com.depositcock.seller.SellerRepository;
import com.depositcock.seller.SellerService;
import com.depositcock.vision.CaptureUsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 슈퍼 관리자(윤성)의 판매자 관리: 목록, 플랜·사용 제한 등 변경, 탈퇴 처리, 관리자 계정 만들기.
 * 판매자 본인 기능은 SellerService (2026-10-28 나눔).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdminService {

    private final SellerRepository sellerRepository;
    private final GroupBuyRepository groupBuyRepository;
    private final PasswordEncoder passwordEncoder;
    private final CaptureUsageService captureUsageService;
    private final Clock clock;

    public List<AdminSellerRow> listSellers() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Seller> sellers = sellerRepository.findAllByOrderByIdDesc();
        // 공구 수·캡처 사용량은 판매자마다 따로 읽지 않고 한 번에 읽는다 (전에는 판매자 1명당 3번 조회, 2026-10-21)
        Map<Long, Long> groupBuyCounts = groupBuyRepository.countPerSeller().stream()
                .collect(Collectors.toMap(SellerCount::getSellerId, SellerCount::getCount));
        Map<Long, CaptureUsageService.Usage> usages = captureUsageService.usages(sellers);
        return sellers.stream()
                .map(s -> {
                    CaptureUsageService.Usage usage = usages.get(s.getId());
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
                            .groupBuyCount(groupBuyCounts.getOrDefault(s.getId(), 0L))
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
    public void updateSeller(Long sellerId, AdminUpdateRequest request) {
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
    public void deleteSeller(Long sellerId) {
        Seller seller = sellerRepository.findById(sellerId)
                .orElseThrow(() -> new ApiException(ErrorCode.SELLER_NOT_FOUND));
        if (seller.getRole() == Role.ADMIN) {
            throw new ApiException(ErrorCode.ADMIN_CANNOT_DELETE);
        }
        sellerRepository.delete(seller);
    }

    /**
     * 관리자 계정이 없으면 만든다. 같은 이메일로 일반 판매자가 먼저 가입해 있으면 관리자로 바꾸지 않고 false 를 돌려준다
     * (가입 시 이메일 인증이 없어서, 남이 관리자 이메일로 먼저 가입했을 수 있기 때문).
     */
    @Transactional
    public boolean ensureAdmin(String email, String rawPassword) {
        String normalized = SellerService.normalizeEmail(email);
        Seller existing = sellerRepository.findByEmail(normalized).orElse(null);
        if (existing != null) {
            return existing.getRole() == Role.ADMIN;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        sellerRepository.save(new Seller(normalized, passwordEncoder.encode(rawPassword), "관리자", Role.ADMIN,
                now, now.plusYears(100)));
        return true;
    }
}
