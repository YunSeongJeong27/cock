package com.depositcock.seller;

import java.time.Clock;
import java.time.LocalDateTime;

import com.depositcock.security.CurrentSeller;
import com.depositcock.security.TokenService;
import com.depositcock.seller.SellerDtos.LoginRequest;
import com.depositcock.seller.SellerDtos.MeResponse;
import com.depositcock.seller.SellerDtos.SignupRequest;
import com.depositcock.seller.SellerDtos.UpdateProfileRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final SellerService sellerService;
    private final TokenService tokenService;
    private final Clock clock;

    public AuthController(SellerService sellerService, TokenService tokenService, Clock clock) {
        this.sellerService = sellerService;
        this.tokenService = tokenService;
        this.clock = clock;
    }

    @PostMapping("/api/auth/signup")
    public MeResponse signup(@Valid @RequestBody SignupRequest request, HttpServletResponse response) {
        Seller seller = sellerService.signup(request);
        tokenService.writeCookie(response, tokenService.issue(seller));
        return MeResponse.of(seller, LocalDateTime.now(clock));
    }

    @PostMapping("/api/auth/login")
    public MeResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        Seller seller = sellerService.login(request.email(), request.password());
        tokenService.writeCookie(response, tokenService.issue(seller));
        return MeResponse.of(seller, LocalDateTime.now(clock));
    }

    @PostMapping("/api/auth/logout")
    public void logout(HttpServletResponse response) {
        tokenService.clearCookie(response);
    }

    @GetMapping("/api/me")
    public MeResponse me() {
        return MeResponse.of(sellerService.get(CurrentSeller.id()), LocalDateTime.now(clock));
    }

    @PutMapping("/api/seller/profile")
    public MeResponse updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return MeResponse.of(sellerService.updateProfile(CurrentSeller.id(), request), LocalDateTime.now(clock));
    }
}
