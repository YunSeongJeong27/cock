package com.depositcock.seller;

import com.depositcock.common.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 앱 시작 시 app.admin-email / app.admin-password 로 관리자 계정을 만든다 (이미 있으면 그대로 둔다).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrap implements ApplicationRunner {

    private final SellerService sellerService;
    private final AppProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        if (properties.adminEmail() == null || properties.adminEmail().isBlank()
                || properties.adminPassword() == null || properties.adminPassword().isBlank()) {
            log.info("관리자 계정 설정(APP_ADMIN_EMAIL/APP_ADMIN_PASSWORD)이 없어 관리자 계정을 만들지 않습니다.");
            return;
        }
        if (!sellerService.ensureAdmin(properties.adminEmail(), properties.adminPassword())) {
            log.warn("관리자 이메일({})로 일반 판매자 계정이 이미 있어 관리자 계정을 만들지 않았습니다. "
                    + "APP_ADMIN_EMAIL 을 다른 이메일로 바꾸거나 그 계정을 정리하세요.", properties.adminEmail());
        }
    }
}
