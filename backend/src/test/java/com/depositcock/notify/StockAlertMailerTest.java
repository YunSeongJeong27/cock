package com.depositcock.notify;

import java.util.Properties;

import com.depositcock.groupbuy.StockAlerts.StockAlertEvent;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockAlertMailerTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return provider;
    }

    @Test
    void sendsHtmlMailWithAbuseWarning() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        StockAlertMailer mailer = new StockAlertMailer(new SellerMail(provider(sender), "", "alert@gmail.com", "https://example.com/"));
        mailer.onStockAlert(new StockAlertEvent("seller@test.local", 7L, "홍게 공구", "홍게", 3));
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(sent.capture());
        MimeMessage m = sent.getValue();
        assertThat(m.getSubject()).isEqualTo("[입금콕] '홍게' 재고가 3개 남았습니다.");
        assertThat(m.getAllRecipients()[0].toString()).isEqualTo("seller@test.local");
        String body = (String) m.getContent();
        assertThat(body).contains(com.depositcock.common.Notice.STOCK_ABUSE_WARNING.text()).contains("color:#dc2626")
                .contains("https://example.com/seller/group-buys/7");
    }

    @Test
    void headlineForSoldOutAndOver() {
        assertThat(StockAlertMailer.headline(new StockAlertEvent("a", 1L, "t", "홍게", 0))).isEqualTo("'홍게' 준비 수량이 모두 주문됐습니다.");
        assertThat(StockAlertMailer.headline(new StockAlertEvent("a", 1L, "t", "홍게", -2))).isEqualTo("'홍게' 재고보다 2개 더 주문됐습니다.");
    }

    @Test
    void skipsWhenMailNotConfigured() {
        JavaMailSender sender = mock(JavaMailSender.class);
        new StockAlertMailer(new SellerMail(provider(null), "", "", "")).onStockAlert(new StockAlertEvent("a", 1L, "t", "홍게", 1));
        new StockAlertMailer(new SellerMail(provider(sender), "", "", "")).onStockAlert(new StockAlertEvent("a", 1L, "t", "홍게", 1));
        verify(sender, never()).send(any(MimeMessage.class));
    }
}
