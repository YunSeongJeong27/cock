package com.depositcock.seller;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import com.depositcock.common.ApiException;
import com.depositcock.common.AppProperties;
import com.depositcock.groupbuy.GroupBuyRepository;
import com.depositcock.vision.CaptureUsageService;
import com.depositcock.seller.SellerDtos.AdminSellerRow;
import com.depositcock.seller.SellerDtos.AdminUpdateRequest;
import com.depositcock.seller.SellerDtos.SignupRequest;
import com.depositcock.seller.SellerDtos.UpdateProfileRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SellerService {

    private final SellerRepository sellerRepository;
    private final GroupBuyRepository groupBuyRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties properties;
    private final CaptureUsageService captureUsageService;
    private final Clock clock;

    public SellerService(SellerRepository sellerRepository, GroupBuyRepository groupBuyRepository,
                         PasswordEncoder passwordEncoder, AppProperties properties,
                         CaptureUsageService captureUsageService, Clock clock) {
        this.sellerRepository = sellerRepository;
        this.groupBuyRepository = groupBuyRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.captureUsageService = captureUsageService;
        this.clock = clock;
    }

    @Transactional
    public Seller signup(SignupRequest request) {
        String email = normalizeEmail(request.email());
        if (sellerRepository.existsByEmail(email)) {
            throw ApiException.conflict("이미 가입된 이메일입니다.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Seller seller = new Seller(email, passwordEncoder.encode(request.password()), request.displayName().trim(),
                Role.SELLER, now, now.plusDays(properties.trialDays()));
        return sellerRepository.save(seller);
    }

    public Seller login(String email, String password) {
        Seller seller = sellerRepository.findByEmail(normalizeEmail(email))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 맞지 않습니다."));
        if (!passwordEncoder.matches(password, seller.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 맞지 않습니다.");
        }
        return seller;
    }

    public Seller get(Long sellerId) {
        return sellerRepository.findById(sellerId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
    }

    @Transactional
    public Seller updateProfile(Long sellerId, UpdateProfileRequest request) {
        Seller seller = get(sellerId);
        seller.updateDefaults(request.displayName().trim(), blankToNull(request.defaultBankName()),
                blankToNull(request.defaultAccountNumber()), blankToNull(request.defaultAccountHolder()));
        return seller;
    }

    public List<AdminSellerRow> listForAdmin() {
        LocalDateTime now = LocalDateTime.now(clock);
        return sellerRepository.findAllByOrderByIdDesc().stream()
                .map(s -> {
                    CaptureUsageService.Usage usage = captureUsageService.usage(s);
                    return new AdminSellerRow(s.getId(), s.getEmail(), s.getDisplayName(), s.getRole(), s.getPlan(),
                            s.getTrialEndsAt(), s.getPaidUntil(), s.isExpired(now), s.isSuspended(), s.getAdminMemo(),
                            s.getCreatedAt(), groupBuyRepository.countBySellerId(s.getId()), usage.usedToday(),
                            usage.usedThisMonth(), usage.dailyLimit(), usage.customLimit());
                })
                .toList();
    }

    @Transactional
    public void updateByAdmin(Long sellerId, AdminUpdateRequest request) {
        Seller seller = sellerRepository.findById(sellerId)
                .orElseThrow(() -> ApiException.notFound("판매자를 찾을 수 없습니다."));
        if (seller.getRole() == Role.ADMIN && request.suspended()) {
            throw ApiException.badRequest("관리자 계정은 사용 제한할 수 없습니다.");
        }
        seller.updateByAdmin(request.plan(), request.paidUntil(), request.trialEndsAt(), request.suspended(),
                blankToNull(request.adminMemo()));
        seller.changeDailyCaptureLimit(request.dailyCaptureLimit());
    }

    /**
     * 판매자 탈퇴(관리자 처리): 판매자의 공구·주문·입금 정보를 함께 파기한다 (개발 전 인터뷰 결과 8번).
     * FK 의 ON DELETE CASCADE 로 하위 데이터가 같이 지워진다.
     */
    @Transactional
    public void deleteByAdmin(Long sellerId) {
        Seller seller = sellerRepository.findById(sellerId)
                .orElseThrow(() -> ApiException.notFound("판매자를 찾을 수 없습니다."));
        if (seller.getRole() == Role.ADMIN) {
            throw ApiException.badRequest("관리자 계정은 삭제할 수 없습니다.");
        }
        sellerRepository.delete(seller);
    }

    /** 판매자 본인 탈퇴: 비밀번호 확인 후 판매자·공구·주문·입금 정보를 모두 파기한다. */
    @Transactional
    public void withdraw(Long sellerId, String rawPassword) {
        Seller seller = get(sellerId);
        if (seller.getRole() == Role.ADMIN) {
            throw ApiException.badRequest("관리자 계정은 탈퇴할 수 없습니다.");
        }
        if (rawPassword == null || !passwordEncoder.matches(rawPassword, seller.getPasswordHash())) {
            throw ApiException.badRequest("비밀번호가 맞지 않습니다.");
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

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
