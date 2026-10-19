package com.depositcock.notify;

import com.depositcock.notify.SellerNotices.SellerNoticeEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.HtmlUtils;

/** 주문·환불 신청·마감 알림 메일 (2026-10-19). 응답이 늦어지지 않게 따로(비동기) 보낸다. 이스케이프는 <>&"' 만 (한글·· 그대로) */
@Component
public class SellerNoticeMailer {

    static final String SETTINGS_NOTE = "알림은 [내 정보 → 알림 설정]에서 끄고 켤 수 있습니다.";

    private final SellerMail mail;

    public SellerNoticeMailer(SellerMail mail) {
        this.mail = mail;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotice(SellerNoticeEvent e) {
        StringBuilder html = new StringBuilder()
                .append("<p>공구: ").append(HtmlUtils.htmlEscape(e.groupBuyTitle(), "UTF-8")).append("</p>")
                .append("<p><b>").append(HtmlUtils.htmlEscape(e.headline(), "UTF-8")).append("</b></p>");
        e.lines().forEach(line -> html.append("<p>").append(HtmlUtils.htmlEscape(line, "UTF-8")).append("</p>"));
        html.append(mail.groupBuyLink(e.groupBuyId()))
                .append("<p style=\"color:#6b7280;font-size:12px\">").append(SETTINGS_NOTE).append("</p>");
        mail.send(e.sellerEmail(), e.headline(), html.toString());
    }
}
