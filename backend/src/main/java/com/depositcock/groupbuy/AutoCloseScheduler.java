package com.depositcock.groupbuy;

import java.time.Clock;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마감 시각에 공구를 자동으로 CLOSED 로 바꾼다 (1분마다).
 * 마감 판정 자체는 GroupBuy.isAcceptingOrders 에서 시각으로도 하므로, 이 작업이 늦어도 마감 후 주문은 막힌다.
 */
@Component
public class AutoCloseScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoCloseScheduler.class);

    private final GroupBuyRepository groupBuyRepository;
    private final Clock clock;

    public AutoCloseScheduler(GroupBuyRepository groupBuyRepository, Clock clock) {
        this.groupBuyRepository = groupBuyRepository;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 10_000)
    @Transactional
    public void closeExpired() {
        int closed = groupBuyRepository.closeExpired(LocalDateTime.now(clock));
        if (closed > 0) {
            log.info("마감 시각이 지난 공구 {}개를 마감했습니다.", closed);
        }
    }
}
