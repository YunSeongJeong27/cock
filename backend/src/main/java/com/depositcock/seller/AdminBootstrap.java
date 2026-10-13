package com.depositcock.seller;

import com.depositcock.common.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 앱 시작 시 app.admin-email / app.admin-password 로 관리자 계정을 만든다 (이미 있으면 그대로 둔다).
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final SellerService sellerService;
    private final AppProperties properties;

    public AdminBootstrap(SellerService sellerService, AppProperties properties) {
        this.sellerService = sellerService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.adminEmail() == null || properties.adminEmail().isBlank()
                || properties.adminPassword() == null || properties.adminPassword().isBlank()) {
            log.info("관리자 계정 설정(APP_ADMIN_EMAIL/APP_ADMIN_PASSWORD)이 없어 관리자 계정을 만들지 않습니다.");
            return;
        }
        sellerService.ensureAdmin(properties.adminEmail(), properties.adminPassword());
    }
}
