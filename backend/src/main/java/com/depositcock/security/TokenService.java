package com.depositcock.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.depositcock.common.AppProperties;
import com.depositcock.seller.Seller;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final AppProperties properties;
    private final Clock clock;

    public String issue(Seller seller) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("depositcock")
                .subject(String.valueOf(seller.getId()))
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofHours(properties.jwtTtlHours())))
                .claim("role", seller.getRole().name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public void writeCookie(HttpServletResponse response, String token) {
        setCookie(response, token, Duration.ofHours(properties.jwtTtlHours()));
    }

    public void clearCookie(HttpServletResponse response) {
        setCookie(response, "", Duration.ZERO);
    }

    /** 로그인 쿠키 (넣을 때·지울 때 같은 속성이어야 브라우저가 같은 쿠키로 본다) */
    private void setCookie(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(SecurityConfig.TOKEN_COOKIE, value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
