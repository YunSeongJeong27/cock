package com.depositcock.seller;

import java.util.List;

import com.depositcock.seller.SellerDtos.AdminSellerRow;
import com.depositcock.seller.SellerDtos.AdminUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 슈퍼 관리자(윤성) 전용: 판매자 목록, 플랜·유료 만료일·사용 제한 변경, 탈퇴 처리.
 */
@RestController
public class AdminController {

    private final SellerService sellerService;

    public AdminController(SellerService sellerService) {
        this.sellerService = sellerService;
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
