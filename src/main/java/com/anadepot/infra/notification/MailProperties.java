package com.anadepot.infra.notification;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Customer e-mail (docs/notifications.md). Everything comes from the environment; nothing here has a real value and {@code toString} never prints a secret.
 * {@code mode}: {@code smtp} sends through the configured server; {@code log} (local development) writes each message to a file and marks it CAPTURED, never SENT; {@code disabled}
 * leaves messages waiting (PENDING) and reports mail as not configured. {@code webBaseUrl}: the storefront origin used for the tracking link; with none, the message gives instructions in words.
 */
@ConfigurationProperties(prefix = "ana.mail")
public record MailProperties(@DefaultValue("log") String mode, Smtp smtp, String fromAddress, @DefaultValue("Ana Dépôt") String fromName, String replyTo, String webBaseUrl,
		String termsUrl, String privacyUrl, @DefaultValue("target/mail-capture") String captureDirectory, Dispatcher dispatcher, @DefaultValue("10") int perRecipientPerHour) {

	public MailProperties {
		smtp = smtp == null ? new Smtp(null, 587, null, null, true, true, false, Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(10)) : smtp;
		dispatcher = dispatcher == null ? new Dispatcher(true, Duration.ofSeconds(5), 20, 8, Duration.ofMinutes(1), 2.0, Duration.ofHours(6), Duration.ofMinutes(2), Duration.ofHours(72)) : dispatcher;
	}

	public record Smtp(String host, @DefaultValue("587") int port, String username, String password, @DefaultValue("true") boolean auth, @DefaultValue("true") boolean starttls,
			@DefaultValue("false") boolean ssl, @DefaultValue("5s") Duration connectionTimeout, @DefaultValue("10s") Duration readTimeout, @DefaultValue("10s") Duration writeTimeout) {

		@Override
		public String toString() {
			return "Smtp[host=" + host + ", port=" + port + ", username=" + (username == null || username.isBlank() ? "unset" : "set") + ", password=hidden, auth=" + auth + ", starttls=" + starttls
					+ ", ssl=" + ssl + "]";
		}
	}

	/** {@code maxAge}: a message that waited longer than this (for example while mail was not configured) is not sent any more: "your reservation is ready" from three days ago would mislead. */
	public record Dispatcher(@DefaultValue("true") boolean enabled, @DefaultValue("5s") Duration pollInterval, @DefaultValue("20") int batchSize, @DefaultValue("8") int maxAttempts,
			@DefaultValue("1m") Duration initialBackoff, @DefaultValue("2.0") double backoffMultiplier, @DefaultValue("6h") Duration maxBackoff, @DefaultValue("2m") Duration lease,
			@DefaultValue("72h") Duration maxAge) {
	}

	public boolean smtpMode() {
		return "smtp".equalsIgnoreCase(mode);
	}

	public boolean logMode() {
		return "log".equalsIgnoreCase(mode);
	}

	public boolean disabledMode() {
		return !smtpMode() && !logMode();
	}

	@Override
	public String toString() {
		return "MailProperties[mode=" + mode + ", from=" + fromAddress + ", smtp=" + smtp + "]";
	}
}
