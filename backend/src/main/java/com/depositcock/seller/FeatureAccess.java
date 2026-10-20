package com.depositcock.seller;

import java.time.LocalDateTime;
import java.util.List;

import com.depositcock.common.ApiException;
import com.depositcock.common.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자가 쓸 수 있는 기능을 관리자(윤성)가 정한다 (2026-10-15 사용자 결정).
 * 댓글 주문 기능(댓글 캡처·집계·주문 반영, 공구의 "주문 링크 + 댓글")은 처음에 닫아 두고 나중에 연다.
 * 사용 가능 = 전체 공개(app_setting) 이거나 판매자별 허용(seller.comment_feature_enabled). 둘 다 기본 꺼짐.
 * 주문 링크·입금 대조 등 나머지 기능은 이 설정과 상관없이 쓴다.
 */
@Service
public class FeatureAccess {

    static final String COMMENT_FEATURE_OPEN = "comment_feature_open";

    private final JdbcTemplate jdbc;
    private final SellerRepository sellerRepository;

    public FeatureAccess(JdbcTemplate jdbc, SellerRepository sellerRepository) {
        this.jdbc = jdbc;
        this.sellerRepository = sellerRepository;
    }

    /** 댓글 주문 기능을 모든 판매자에게 열었는지 */
    public boolean commentFeatureOpen() {
        List<String> values = jdbc.queryForList("select setting_value from app_setting where setting_key = ?",
                String.class, COMMENT_FEATURE_OPEN);
        return !values.isEmpty() && Boolean.parseBoolean(values.get(0));
    }

    @Transactional
    public void setCommentFeatureOpen(boolean open, LocalDateTime now) {
        int updated = jdbc.update("update app_setting set setting_value = ?, updated_at = ? where setting_key = ?",
                String.valueOf(open), now, COMMENT_FEATURE_OPEN);
        if (updated == 0) {
            jdbc.update("insert into app_setting (setting_key, setting_value, updated_at) values (?, ?, ?)",
                    COMMENT_FEATURE_OPEN, String.valueOf(open), now);
        }
    }

    public boolean commentsAllowed(Seller seller) {
        return seller.isCommentFeatureEnabled() || commentFeatureOpen();
    }

    public boolean commentsAllowed(Long sellerId) {
        return sellerRepository.findById(sellerId).map(this::commentsAllowed).orElse(false);
    }

    public void requireComments(Long sellerId) {
        if (!commentsAllowed(sellerId)) {
            throw new ApiException(ErrorCode.COMMENT_FEATURE_CLOSED);
        }
    }
}
