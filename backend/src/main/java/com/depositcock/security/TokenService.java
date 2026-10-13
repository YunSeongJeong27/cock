package com.depositcock.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.depositcock.common.AppProperties;
import com.depositcock.seller.Seller;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final AppProperties properties;
    private final Clock clock;

    public TokenService(JwtEncoder jwtEncoder, AppProperties properties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

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
        ResponseCookie cookie = ResponseCookie.from(SecurityConfig.TOKEN_COOKIE, token)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofHours(properties.jwtTtlHours()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void clearCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(SecurityConfig.TOKEN_COOKIE, "")
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
