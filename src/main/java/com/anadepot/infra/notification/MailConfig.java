package com.anadepot.infra.notification;

import com.anadepot.core.notification.model.EmailAddress;
import java.util.ArrayList;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Mail wiring. SMTP settings come from the environment only (docs/notifications.md). A deployment that asks for {@code smtp} mode without the required settings does NOT start: the message
 * lists exactly which variables are missing (never a value). {@code log} writes files and sends nothing; {@code disabled} leaves messages waiting and says so.
 */
@Configuration
class MailConfig {

	private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

	@Bean
	EmailRenderer emailRenderer() {
		return new EmailRenderer();
	}

	@Bean
	MailTransport mailTransport(MailProperties p) {
		var problems = problems(p);
		if (!problems.isEmpty()) {
			throw new IllegalStateException("Customer e-mail is in smtp mode but is not configured correctly:\n - " + String.join("\n - ", problems)
					+ "\nSet the variables above, or set ANA_MAIL_MODE=log (development: files only) or ANA_MAIL_MODE=disabled (messages wait in the queue and are NOT sent).");
		}
		if (p.smtpMode()) {
			log.info("Customer e-mail: SMTP mode, host={} port={} auth={} starttls={} ssl={} from={} (password hidden)", p.smtp().host(), p.smtp().port(), p.smtp().auth(), p.smtp().starttls(), p.smtp().ssl(),
					p.fromAddress());
			return new SmtpMailTransport(sender(p), p);
		}
		if (p.logMode()) {
			log.warn("Customer e-mail: LOG mode (development only): messages are written to '{}' and are NOT sent; they are recorded as CAPTURED, never SENT.", p.captureDirectory());
		} else {
			log.warn("Customer e-mail: DISABLED. Notifications are queued (PENDING) and are NOT sent until ANA_MAIL_MODE=smtp is configured.");
		}
		return new CaptureMailTransport(p);
	}

	/** What is missing for smtp mode (empty for the other modes). Names environment variables, never values. */
	static java.util.List<String> problems(MailProperties p) {
		var out = new ArrayList<String>();
		if (!p.smtpMode()) {
			return out;
		}
		var s = p.smtp();
		if (s.host() == null || s.host().isBlank()) {
			out.add("ANA_MAIL_SMTP_HOST is not set");
		}
		if (s.port() < 1 || s.port() > 65535) {
			out.add("ANA_MAIL_SMTP_PORT must be between 1 and 65535");
		}
		if (p.fromAddress() == null || !EmailAddress.isValid(p.fromAddress())) {
			out.add("ANA_MAIL_FROM_ADDRESS is not set or is not a valid e-mail address");
		}
		if (s.auth() && (s.username() == null || s.username().isBlank() || s.password() == null || s.password().isBlank())) {
			out.add("ANA_MAIL_SMTP_USERNAME and ANA_MAIL_SMTP_PASSWORD are required while ANA_MAIL_SMTP_AUTH=true");
		}
		if (s.ssl() && s.starttls()) {
			out.add("ANA_MAIL_SMTP_SSL (implicit TLS, usually port 465) and ANA_MAIL_SMTP_STARTTLS (upgrade, usually port 587) are mutually exclusive");
		}
		if (p.replyTo() != null && !p.replyTo().isBlank() && !EmailAddress.isValid(p.replyTo())) {
			out.add("ANA_MAIL_REPLY_TO is not a valid e-mail address");
		}
		return out;
	}

	static JavaMailSenderImpl sender(MailProperties p) {
		var s = p.smtp();
		var sender = new JavaMailSenderImpl();
		sender.setHost(s.host());
		sender.setPort(s.port());
		sender.setProtocol(s.ssl() ? "smtps" : "smtp");
		sender.setDefaultEncoding("UTF-8");
		if (s.auth()) {
			sender.setUsername(s.username());
			sender.setPassword(s.password());
		}
		var props = new Properties();
		var prefix = s.ssl() ? "mail.smtps." : "mail.smtp.";
		props.put(prefix + "auth", String.valueOf(s.auth()));
		props.put(prefix + "connectiontimeout", String.valueOf(s.connectionTimeout().toMillis()));
		props.put(prefix + "timeout", String.valueOf(s.readTimeout().toMillis()));
		props.put(prefix + "writetimeout", String.valueOf(s.writeTimeout().toMillis()));
		if (s.starttls()) {
			props.put(prefix + "starttls.enable", "true");
			props.put(prefix + "starttls.required", "true");   // never fall back to plain text on a server that does not offer TLS
		}
		if (s.starttls() || s.ssl()) {
			props.put(prefix + "ssl.checkserveridentity", "true");   // the certificate must match the host name
		}
		sender.setJavaMailProperties(props);
		return sender;
	}
}
