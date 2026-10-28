package com.depositcock.seller;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;

import com.depositcock.common.ApiException;
import com.depositcock.common.AppProperties;
import com.depositcock.common.ErrorCode;
import com.depositcock.common.Texts;
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
    private final PasswordEncoder passwordEncoder;
    private final AppProperties properties;
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

    @Transactional
    public Seller hideSalesTip(Long sellerId) {
        Seller seller = get(sellerId);
        seller.setSalesTipHidden(true);
        return seller;
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

    /** 이메일 비교용: 앞뒤 공백을 빼고 소문자로 (관리자 계정 만들기도 같이 씀) */
    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
