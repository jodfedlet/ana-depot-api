package com.anadepot.infra.notification;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Local development: writes each message as an .eml file in a directory you can open with any mail client, and sends NOTHING. The queue row ends as CAPTURED, never SENT, so a screen
 * can never claim a delivery that did not happen.
 */
class CaptureMailTransport implements MailTransport {

	private final Path directory;
	private final MailProperties config;

	CaptureMailTransport(MailProperties config) {
		this.config = config;
		this.directory = Path.of(config.captureDirectory());
	}

	@Override
	public boolean delivers() {
		return false;
	}

	@Override
	public void send(OutgoingMail mail) {
		try {
			Files.createDirectories(directory);
			var message = new MimeMessage(Session.getInstance(new Properties()));
			var helper = new MimeMessageHelper(message, true, "UTF-8");
			helper.setFrom(new InternetAddress(config.fromAddress() == null || config.fromAddress().isBlank() ? "ana-depot@localhost" : config.fromAddress(), config.fromName(), "UTF-8"));
			helper.setTo(mail.to());
			helper.setSubject(mail.subject());
			helper.setText(mail.text(), mail.html());
			message.setHeader("X-Ana-Notification-Id", mail.notificationId().toString());
			message.saveChanges();
			try (var out = Files.newOutputStream(directory.resolve(mail.notificationId() + ".eml"))) {
				message.writeTo(out);
			}
		} catch (IOException | jakarta.mail.MessagingException e) {
			throw new MailFailure("capture_failed", false, e);
		}
	}
}
