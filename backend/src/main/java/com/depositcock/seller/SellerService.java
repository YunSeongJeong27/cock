package com.depositcock.seller;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import com.depositcock.common.ApiException;
import com.depositcock.common.AppProperties;
import com.depositcock.groupbuy.GroupBuyRepository;
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
    private final Clock clock;

    public SellerService(SellerRepository sellerRepository, GroupBuyRepository groupBuyRepository,
                         PasswordEncoder passwordEncoder, AppProperties properties, Clock clock) {
        this.sellerRepository = sellerRepository;
        this.groupBuyRepository = groupBuyRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
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
                .map(s -> new AdminSellerRow(s.getId(), s.getEmail(), s.getDisplayName(), s.getRole(), s.getPlan(),
                        s.getTrialEndsAt(), s.getPaidUntil(), s.isExpired(now), s.isSuspended(), s.getAdminMemo(),
                        s.getCreatedAt(), groupBuyRepository.countBySellerId(s.getId())))
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

    @Transactional
    public void ensureAdmin(String email, String rawPassword) {
        String normalized = normalizeEmail(email);
        if (sellerRepository.existsByEmail(normalized)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        sellerRepository.save(new Seller(normalized, passwordEncoder.encode(rawPassword), "관리자", Role.ADMIN,
                now, now.plusYears(100)));
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
