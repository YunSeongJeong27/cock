package com.depositcock.security;

import com.depositcock.seller.Seller;
import com.depositcock.seller.SellerRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 관리자가 "사용 제한"을 켠 판매자는 조회만 되고 쓰기(POST/PUT/PATCH/DELETE)가 막힌다.
 * 이미 열려 있는 공구의 고객 주문(/api/public)은 막지 않는다. 탈퇴는 허용한다.
 */
@Component
public class SuspendedSellerInterceptor implements HandlerInterceptor {

    private final SellerRepository sellerRepository;

    public SuspendedSellerInterceptor(SellerRepository sellerRepository) {
        this.sellerRepository = sellerRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("GET".equalsIgnoreCase(request.getMethod()) || "HEAD".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // 사용 제한 중에도 탈퇴(개인정보 파기)는 할 수 있어야 한다
        if (request.getRequestURI().equals("/api/seller/withdraw")) {
            return true;
        }
        Seller seller = sellerRepository.findById(CurrentSeller.id()).orElse(null);
        if (seller == null) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return false;
        }
        if (seller.isSuspended()) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"status\":403,\"detail\":\"관리자가 사용을 제한한 계정입니다. 조회만 할 수 있습니다.\"}");
            return false;
        }
        return true;
    }
}
