package com.depositcock.seller;

import java.util.List;

import java.time.Clock;
import java.time.LocalDateTime;

import com.depositcock.seller.SellerDtos.AdminSellerRow;
import com.depositcock.seller.SellerDtos.AdminSettings;
import com.depositcock.seller.SellerDtos.AdminUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 슈퍼 관리자(윤성) 전용: 판매자 목록, 플랜·유료 만료일·사용 제한·댓글 기능 허용 변경, 탈퇴 처리, 서비스 전체 설정.
 */
@RestController
public class AdminController {

    private final SellerService sellerService;
    private final FeatureAccess featureAccess;
    private final Clock clock;

    public AdminController(SellerService sellerService, FeatureAccess featureAccess, Clock clock) {
        this.sellerService = sellerService;
        this.featureAccess = featureAccess;
        this.clock = clock;
    }

    @GetMapping("/api/admin/settings")
    public AdminSettings settings() {
        return new AdminSettings(featureAccess.commentFeatureOpen());
    }

    /** 댓글 주문 기능 전체 공개 켜기/끄기 */
    @PutMapping("/api/admin/settings")
    public AdminSettings updateSettings(@RequestBody AdminSettings request) {
        featureAccess.setCommentFeatureOpen(request.commentFeatureOpen(), LocalDateTime.now(clock));
        return settings();
    }

    @GetMapping("/api/admin/sellers")
    public List<AdminSellerRow> sellers() {
        return sellerService.listForAdmin();
    }

    @PutMapping("/api/admin/sellers/{sellerId}")
    public void update(@PathVariable Long sellerId, @Valid @RequestBody AdminUpdateRequest request) {
        sellerService.updateByAdmin(sellerId, request);
    }

    @DeleteMapping("/api/admin/sellers/{sellerId}")
    public void delete(@PathVariable Long sellerId) {
        sellerService.deleteByAdmin(sellerId);
    }
}
