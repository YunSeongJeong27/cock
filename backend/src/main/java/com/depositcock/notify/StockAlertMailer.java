package com.depositcock.notify;

import com.depositcock.common.Notice;
import com.depositcock.groupbuy.StockAlerts;
import com.depositcock.groupbuy.StockAlerts.StockAlertEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 재고 알림 메일. 판매자가 알림 설정에서 끄면 StockAlerts 가 이벤트를 내지 않는다 (2026-10-19).
 * 주문 응답이 메일 때문에 늦어지지 않게 따로(비동기) 보낸다.
 */
@Component
@RequiredArgsConstructor
public class StockAlertMailer {

    private final SellerMail mail;

    /** 알림 제목·첫 줄 (판매자 화면 알림 칸과 같은 문장) */
    static String headline(StockAlertEvent e) {
        return StockAlerts.headline(e.productName(), e.remaining());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStockAlert(StockAlertEvent e) {
        mail.send(e.sellerEmail(), headline(e), "<p>" + SellerMail.escape(MailText.GROUP_BUY.text(e.groupBuyTitle())) + "</p>"
                + "<p><b>" + SellerMail.escape(headline(e)) + "</b></p>"
                + "<p style=\"color:#dc2626;font-size:13px\">" + Notice.STOCK_ABUSE_WARNING.text() + "</p>"
                + mail.groupBuyLink(e.groupBuyId())
                + "<p style=\"color:#6b7280;font-size:12px\">" + MailText.STOCK_THRESHOLD_NOTE.text() + " "
                + MailText.SETTINGS_NOTE.text() + "</p>");
    }
}
