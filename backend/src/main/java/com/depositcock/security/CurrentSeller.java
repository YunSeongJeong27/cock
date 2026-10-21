package com.depositcock.security;

import com.depositcock.common.ApiException;
import com.depositcock.common.ErrorCode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 로그인한 판매자 id. 모든 판매자 데이터 조회는 이 id 로 필터한다 (멀티테넌트).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CurrentSeller {

    public static Long id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new ApiException(ErrorCode.LOGIN_REQUIRED);
        }
        return Long.valueOf(jwt.getSubject());
    }
}
