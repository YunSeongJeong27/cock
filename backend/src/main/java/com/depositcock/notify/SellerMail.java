package com.depositcock.notify;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * 판매자에게 메일 보내기 공통. SMTP 는 환경 변수로 설정한다 (SPRING_MAIL_HOST 등, README). 설정이 없으면 보내지 않고 기록만 남긴다.
 * 처음에는 Gmail 계정 + 앱 비밀번호로 보내고, 판매자가 늘면 Amazon SES 등으로 설정만 바꾼다 [2026-10-16 사용자 결정].
 */
@Component
@Slf4j
public class SellerMail {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String from;
    private final String baseUrl;

    public SellerMail(ObjectProvider<JavaMailSender> mailSender,
                      @Value("${app.mail-from:}") String from,
                      @Value("${spring.mail.username:}") String username,
                      @Value("${app.public-base-url:}") String baseUrl) {
        this.mailSender = mailSender;
        this.from = from == null || from.isBlank() ? username : from;
        this.baseUrl = baseUrl;
    }

    /** 메일 본문 글자 이스케이프: <>&"' 만 바꾸고 한글·가운뎃점(·)은 그대로 둔다 (두 알림 메일이 같은 방식으로) */
    static String escape(String text) {
        return HtmlUtils.htmlEscape(text, "UTF-8");
    }

    /** 공구 화면 링크 (주소 설정이 없으면 빈 문자열) */
    String groupBuyLink(Long groupBuyId) {
        return baseUrl == null || baseUrl.isBlank() ? ""
                : "<p><a href=\"" + escape(baseUrl.replaceAll("/+$", "") + "/seller/group-buys/" + groupBuyId)
                + "\">" + MailText.OPEN_GROUP_BUY.text() + "</a></p>";
    }

    /** 실패해도 예외를 던지지 않는다 (주문은 이미 저장됨, 화면 알림은 그대로 보인다) */
    void send(String to, String subject, String html) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || from == null || from.isBlank()) {
            log.info("메일 미설정 → 보내지 않음: {}", subject);
            return;
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(MailText.SUBJECT_PREFIX.text() + subject);
            helper.setText(html, true);
            sender.send(message);
        } catch (Exception ex) {
            log.warn("메일 실패: {} / {} / {}", to, subject, ex.getMessage());
        }
    }
}
