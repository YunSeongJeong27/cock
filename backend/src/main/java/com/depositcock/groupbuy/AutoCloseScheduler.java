package com.depositcock.groupbuy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.depositcock.notification.MailText;
import com.depositcock.notification.SellerNotices;
import com.depositcock.seller.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마감 시각에 공구를 자동으로 CLOSED 로 바꾼다 (1분마다).
 * 마감 판정 자체는 GroupBuy.isAcceptingOrders 에서 시각으로도 하므로, 이 작업이 늦어도 마감 후 주문은 막힌다.
 * 판매자가 알림 설정에서 켰으면 마감 메일을 보낸다 (2026-10-19, 기본 꺼짐).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AutoCloseScheduler {

    private static final DateTimeFormatter CLOSE_AT = DateTimeFormatter.ofPattern("M월 d일 HH:mm");

    private final GroupBuyRepository groupBuyRepository;
    private final Clock clock;
    private final SellerNotices notices;

    @Scheduled(fixedDelay = 60_000, initialDelay = 10_000)
    @Transactional
    public void closeExpired() {
        LocalDateTime now = LocalDateTime.now(clock);
        int closed = 0;
        for (GroupBuy groupBuy : groupBuyRepository.findExpiredOpen(now)) {
            if (groupBuyRepository.closeIfExpired(groupBuy.getId(), now) == 1) {
                closed++;
                notices.notify(groupBuy, NotificationType.MAIL_AUTO_CLOSE, MailText.AUTO_CLOSED.text(),
                        List.of(MailText.AUTO_CLOSED_AT.text(groupBuy.getCloseAt().format(CLOSE_AT))));
            }
        }
        if (closed > 0) {
            log.info("마감 시각이 지난 공구 {}개를 마감했습니다.", closed);
        }
    }
}
