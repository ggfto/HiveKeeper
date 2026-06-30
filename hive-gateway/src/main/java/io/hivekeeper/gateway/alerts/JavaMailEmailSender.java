package io.hivekeeper.gateway.alerts;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Default {@link EmailSender} over Spring's {@link JavaMailSender}. Email is "configured" only when an SMTP
 * host is set (Spring auto-configures the bean) AND a from-address is given; otherwise {@link #isConfigured()}
 * is false and the notifier skips email channels (with no error).
 */
@Component
@Slf4j
public class JavaMailEmailSender implements EmailSender {

    private final JavaMailSender mail;   // null when no SMTP host is configured
    private final String from;

    public JavaMailEmailSender(ObjectProvider<JavaMailSender> mail,
                               @Value("${hivekeeper.alert.email.from:}") String from) {
        this.mail = mail.getIfAvailable();
        this.from = from == null ? "" : from.trim();
    }

    @Override
    public boolean isConfigured() {
        return mail != null && !from.isEmpty();
    }

    @Override
    public void send(String to, String subject, String body) {
        if (!isConfigured()) {
            log.warn("email channel '{}' skipped: SMTP not configured (set spring.mail.host + "
                    + "hivekeeper.alert.email.from)", to);
            return;
        }
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject(subject);
        msg.setText(body);
        mail.send(msg);
    }
}
