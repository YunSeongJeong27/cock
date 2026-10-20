package com.depositcock.notify;

import java.util.List;

import com.depositcock.groupbuy.GroupBuy;
import com.depositcock.seller.NotificationType;
import com.depositcock.seller.Seller;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 판매자 메일 알림 내기 (2026-10-19): 판매자가 알림 설정에서 켠 것만. 메일은 트랜잭션이 끝난 뒤 보낸다
 * (요청이 실패해 롤백되면 보내지 않음, SellerNoticeMailer). 재고 알림은 StockAlerts 가 따로 낸다.
 */
@Service
@RequiredArgsConstructor
public class SellerNotices {

    /** 메일 한 통. lines 는 본문 줄 (HTML 이스케이프는 보낼 때) */
    public record SellerNoticeEvent(NotificationType type, String sellerEmail, Long groupBuyId, String groupBuyTitle,
                                    String headline, List<String> lines) {
    }

    private final ApplicationEventPublisher events;

    public void notify(GroupBuy groupBuy, NotificationType type, String headline, List<String> lines) {
        Seller seller = groupBuy.getSeller();
        if (!seller.isNotificationOn(type)) {
            return;
        }
        events.publishEvent(new SellerNoticeEvent(type, seller.getEmail(), groupBuy.getId(), groupBuy.getTitle(),
                headline, List.copyOf(lines)));
    }
}
