package com.depositcock.notification;

import java.util.List;
import java.util.Properties;

import com.depositcock.notification.SellerNotices.SellerNoticeEvent;
import com.depositcock.seller.NotificationType;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SellerNoticeMailerTest {

    @Test
    @SuppressWarnings("unchecked")
    void sendsHeadlineLinesLinkAndSettingsNote() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        SellerNoticeMailer mailer = new SellerNoticeMailer(new SellerMail(provider, "", "alert@gmail.com", "https://example.com"));

        mailer.onNotice(new SellerNoticeEvent(NotificationType.MAIL_REFUND_REQUEST, "seller@test.local", 9L, "홍게 <공구>",
                "3번 주문에 환불 신청이 들어왔습니다.", List.of("홍게 2개 · 20,000원", "주문 확정된 주문입니다.")));

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(sent.capture());
        MimeMessage m = sent.getValue();
        assertThat(m.getSubject()).isEqualTo("[입금콕] 3번 주문에 환불 신청이 들어왔습니다.");
        assertThat(m.getAllRecipients()[0].toString()).isEqualTo("seller@test.local");
        assertThat((String) m.getContent()).contains("홍게 &lt;공구&gt;").contains("홍게 2개 · 20,000원")
                .contains("주문 확정된 주문입니다.").contains("https://example.com/seller/group-buys/9")
                .contains(MailText.SETTINGS_NOTE.text());
    }
}
