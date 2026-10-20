package com.depositcock.security;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.depositcock.common.AppProperties;
import com.depositcock.common.ErrorCode;
import com.depositcock.common.ProblemWriter;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * 판매자 로그인: JWT 를 httpOnly 쿠키(DC_TOKEN)에 담는다 (개발 전 인터뷰 결과 5번).
 * 프론트와 백엔드가 같은 도메인이므로 쿠키는 SameSite=Lax 로 두고 CSRF 토큰은 쓰지 않는다.
 */
@Configuration
public class SecurityConfig {

    public static final String TOKEN_COOKIE = "DC_TOKEN";

    /**
     * 로그인이 없거나 만료된 쿠키(401), 관리자 아닌 판매자의 관리자 주소(403)도 다른 오류와 같은 모양 {detail, code} 으로.
     * 전에는 본문 없이 상태만 나갔음 (2026-10-20 점검 검토)
     */
    private static final AuthenticationEntryPoint LOGIN_REQUIRED =
            (request, response, e) -> ProblemWriter.write(response, ErrorCode.LOGIN_REQUIRED);
    private static final AccessDeniedHandler ADMIN_ONLY =
            (request, response, e) -> ProblemWriter.write(response, ErrorCode.ADMIN_ONLY);

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/api/public/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(cookieTokenResolver())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(LOGIN_REQUIRED)
                        .accessDeniedHandler(ADMIN_ONLY))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(LOGIN_REQUIRED).accessDeniedHandler(ADMIN_ONLY));
        return http.build();
    }

    /**
     * 쿠키에서 토큰을 읽는다. 로그인·공개 API 는 토큰을 읽지 않는다
     * (만료된 쿠키가 남아 있어도 로그인 화면이 401 로 막히지 않게).
     */
    @Bean
    public BearerTokenResolver cookieTokenResolver() {
        return (HttpServletRequest request) -> {
            String uri = request.getRequestURI();
            if (!uri.startsWith("/api/") || uri.startsWith("/api/auth/") || uri.startsWith("/api/public/")) {
                return null;
            }
            Cookie[] cookies = request.getCookies();
            if (cookies == null) {
                return null;
            }
            for (Cookie cookie : cookies) {
                if (TOKEN_COOKIE.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
            return null;
        };
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt ->
                List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role"))));
        return converter;
    }

    @Bean
    public SecretKey jwtSecretKey(AppProperties properties) {
        String secret = properties.jwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.jwt-secret(환경변수 APP_JWT_SECRET)은 32바이트 이상이어야 합니다.");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
