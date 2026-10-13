package com.depositcock.groupbuy;

import java.time.LocalDateTime;

/**
 * 공구 설정 값 묶음 (요청 DTO 에서 정리한 값).
 */
public record GroupBuySettings(
        String title,
        String description,
        LocalDateTime closeAt,
        String bankName,
        String accountNumber,
        String accountHolder,
        boolean pickupEnabled,
        boolean pickupPrepayEnabled,
        boolean pickupOnsiteEnabled,
        boolean deliveryEnabled,
        ShippingFeeType shippingFeeType,
        int shippingFee,
        boolean nicknameFieldEnabled,
        boolean unitAddressFieldEnabled,
        String kakaoInquiryUrl,
        String adminNames) {
}
