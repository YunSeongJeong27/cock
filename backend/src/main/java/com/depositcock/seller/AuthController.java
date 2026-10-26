package com.depositcock.seller;

import java.time.Clock;
import java.time.LocalDateTime;

import com.depositcock.security.CurrentSeller;
import com.depositcock.security.TokenService;
import com.depositcock.seller.SellerDtos.LoginRequest;
import com.depositcock.seller.SellerDtos.MeResponse;
import com.depositcock.seller.SellerDtos.SignupRequest;
import com.depositcock.seller.SellerDtos.UpdateNotificationsRequest;
import com.depositcock.seller.SellerDtos.UpdateProfileRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AuthController {

    private final SellerService sellerService;
    private final TokenService tokenService;
    private final Clock clock;
    private final FeatureAccess featureAccess;

    @PostMapping("/api/auth/signup")
    public MeResponse signup(@Valid @RequestBody SignupRequest request, HttpServletResponse response) {
        Seller seller = sellerService.signup(request);
        tokenService.writeCookie(response, tokenService.issue(seller));
        return toMe(seller);
    }

    @PostMapping("/api/auth/login")
    public MeResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        Seller seller = sellerService.login(request.email(), request.password());
        tokenService.writeCookie(response, tokenService.issue(seller));
        return toMe(seller);
    }

    @PostMapping("/api/auth/logout")
    public void logout(HttpServletResponse response) {
        tokenService.clearCookie(response);
    }

    @GetMapping("/api/me")
    public MeResponse me() {
        return toMe(sellerService.get(CurrentSeller.id()));
    }

    public record WithdrawRequest(String password) {
    }

    @PostMapping("/api/seller/withdraw")
    public void withdraw(@RequestBody WithdrawRequest request, HttpServletResponse response) {
        sellerService.withdraw(CurrentSeller.id(), request.password());
        tokenService.clearCookie(response);
    }

    @PutMapping("/api/seller/profile")
    public MeResponse updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return toMe(sellerService.updateProfile(CurrentSeller.id(), request));
    }

    @PutMapping("/api/seller/notifications")
    public MeResponse updateNotifications(@Valid @RequestBody UpdateNotificationsRequest request) {
        return toMe(sellerService.updateNotifications(CurrentSeller.id(), request.notifications()));
    }

    /** "제품 판매 TIP" 안내 다시 보지 않기 (2026-10-26) */
    @PostMapping("/api/seller/sales-tip/hide")
    public MeResponse hideSalesTip() {
        return toMe(sellerService.hideSalesTip(CurrentSeller.id()));
    }

    private MeResponse toMe(Seller seller) {
        return MeResponse.of(seller, LocalDateTime.now(clock), featureAccess.commentsAllowed(seller));
    }
}
