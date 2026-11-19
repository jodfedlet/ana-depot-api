package com.anadepot.infra.notification;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Delivery through Spring's {@link JavaMailSender}: a multipart/alternative message (plain text + HTML), UTF-8, with the sender, reply-to and correlation headers. It never logs an address,
 * a body or a credential: every failure becomes a {@link MailFailure} with a stable code.
 */
class SmtpMailTransport implements MailTransport {

	private final JavaMailSender sender;
	private final MailProperties config;

	SmtpMailTransport(JavaMailSender sender, MailProperties config) {
		this.sender = sender;
		this.config = config;
	}

	@Override
	public boolean delivers() {
		return true;
	}

	@Override
	public void send(OutgoingMail mail) {
		try {
			var message = sender.createMimeMessage();
			var helper = new MimeMessageHelper(message, true, "UTF-8");
			helper.setFrom(new InternetAddress(config.fromAddress(), config.fromName(), "UTF-8"));
			if (config.replyTo() != null && !config.replyTo().isBlank()) {
				helper.setReplyTo(config.replyTo().strip());
			}
			helper.setTo(mail.to());
			helper.setSubject(mail.subject());
			helper.setText(mail.text(), mail.html());
			message.setHeader("X-Ana-Notification-Id", mail.notificationId().toString());
			message.setHeader("Auto-Submitted", "auto-generated");   // an automatic transactional message: auto-responders must not answer it
			sender.send(message);
		} catch (MailException e) {
			throw classify(e);
		} catch (MessagingException | UnsupportedEncodingException | RuntimeException e) {
			throw new MailFailure("mail_build_failed", true, e);
		}
	}

	/** Maps the library's exceptions to stable codes. Authentication and connection problems are configuration/availability problems (retried, bounded); a rejected recipient is permanent. */
	static MailFailure classify(Throwable e) {
		if (e instanceof MailAuthenticationException) {
			return new MailFailure("smtp_auth_failed", false, e);
		}
		if (e instanceof MailParseException) {
			return new MailFailure("mail_build_failed", true, e);
		}
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof AuthenticationFailedException) {
				return new MailFailure("smtp_auth_failed", false, e);
			}
			if (t instanceof SendFailedException sf && sf.getInvalidAddresses() != null && sf.getInvalidAddresses().length > 0) {
				return new MailFailure("recipient_rejected", true, e);
			}
			if (t instanceof SocketTimeoutException || (t.getMessage() != null && t.getMessage().toLowerCase().contains("timed out"))) {
				return new MailFailure("smtp_timeout", false, e);
			}
			if (t instanceof ConnectException || t instanceof UnknownHostException) {
				return new MailFailure("smtp_unavailable", false, e);
			}
		}
		if (e instanceof MailSendException) {
			// a server answer that is not an address problem (for example a 4xx "try later", a 5xx policy refusal, a dropped connection): retry within the bounded budget
			return new MailFailure("smtp_error", false, e);
		}
		return new MailFailure("smtp_error", false, e);
	}
}
