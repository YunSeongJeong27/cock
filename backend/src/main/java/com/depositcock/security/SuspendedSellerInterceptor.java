package com.depositcock.security;

import com.depositcock.common.ErrorCode;
import com.depositcock.common.ProblemWriter;
import com.depositcock.seller.Seller;
import com.depositcock.seller.SellerRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
            // 탈퇴·삭제된 판매자의 남은 로그인 쿠키. 다른 오류와 같은 모양으로 응답 (전에는 본문 없는 401, 2026-10-20 점검)
            ProblemWriter.write(response, ErrorCode.LOGIN_REQUIRED);
            return false;
        }
        if (seller.isSuspended()) {
            ProblemWriter.write(response, ErrorCode.SELLER_SUSPENDED);
            return false;
        }
        return true;
    }
}
