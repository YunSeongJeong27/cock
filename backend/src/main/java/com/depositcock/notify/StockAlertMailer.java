package com.depositcock.notify;

import com.depositcock.groupbuy.StockAlerts.StockAlertEvent;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.HtmlUtils;

/**
 * 재고 알림 메일. SMTP 는 환경 변수로 설정한다 (SPRING_MAIL_HOST 등, README). 설정이 없으면 보내지 않고 기록만 남긴다.
 * 처음에는 Gmail 계정 + 앱 비밀번호로 보내고, 판매자가 늘면 Amazon SES 등으로 설정만 바꾼다 [2026-10-16 사용자 결정].
 * 주문 응답이 메일 때문에 늦어지지 않게 따로(비동기) 보낸다.
 */
@Component
public class StockAlertMailer {

    private static final Logger log = LoggerFactory.getLogger(StockAlertMailer.class);

    static final String ABUSE_WARNING = "악용하는 고객이 있을 수 있으니 계좌 입금내역 대조를 통해 확인하십시오.";

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String from;
    private final String baseUrl;

    public StockAlertMailer(ObjectProvider<JavaMailSender> mailSender,
                            @Value("${app.mail-from:}") String from,
                            @Value("${spring.mail.username:}") String username,
                            @Value("${app.public-base-url:}") String baseUrl) {
        this.mailSender = mailSender;
        this.from = from == null || from.isBlank() ? username : from;
        this.baseUrl = baseUrl;
    }

    /** 알림 제목·첫 줄. 남은 수량이 0 이하면 소진·초과로 쓴다 */
    static String headline(StockAlertEvent e) {
        if (e.remaining() > 0) {
            return "'" + e.productName() + "' 재고가 " + e.remaining() + "개 남았습니다.";
        }
        if (e.remaining() == 0) {
            return "'" + e.productName() + "' 준비 수량이 모두 주문됐습니다.";
        }
        return "'" + e.productName() + "' 재고보다 " + (-e.remaining()) + "개 더 주문됐습니다.";
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStockAlert(StockAlertEvent e) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || from == null || from.isBlank()) {
            log.info("재고 알림 메일 미설정 → 보내지 않음: {} / {}", e.groupBuyTitle(), headline(e));
            return;
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(e.sellerEmail());
            helper.setSubject("[입금콕] " + headline(e));
            String link = baseUrl == null || baseUrl.isBlank() ? ""
                    : "<p><a href=\"" + HtmlUtils.htmlEscape(baseUrl.replaceAll("/+$", "") + "/seller/group-buys/" + e.groupBuyId())
                    + "\">공구 화면 열기</a></p>";
            helper.setText("<p>공구: " + HtmlUtils.htmlEscape(e.groupBuyTitle()) + "</p>"
                    + "<p><b>" + HtmlUtils.htmlEscape(headline(e)) + "</b></p>"
                    + "<p style=\"color:#dc2626;font-size:13px\">" + ABUSE_WARNING + "</p>"
                    + link
                    + "<p style=\"color:#6b7280;font-size:12px\">알림 기준은 공구 수정 화면에서 상품마다 바꿀 수 있습니다.</p>", true);
            sender.send(message);
        } catch (Exception ex) {
            // 메일이 실패해도 주문은 이미 저장됨. 화면 알림은 그대로 보인다
            log.warn("재고 알림 메일 실패: {} / {}", e.sellerEmail(), ex.getMessage());
        }
    }
}
