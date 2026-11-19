package com.anadepot.infra.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Multipart;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

/** The real JavaMailSender over a real (in-process) SMTP server: delivery, authentication failure, unavailable server, timeout, and how each failure is classified. */
class SmtpMailTransportTest {

	GreenMail smtp;

	@BeforeEach
	void start() {
		smtp = new GreenMail(ServerSetupTest.SMTP.dynamicPort());
		smtp.setUser("smtp-user", "smtp-secret-pw");
		smtp.start();
	}

	@AfterEach
	void stop() {
		smtp.stop();
	}

	private MailProperties props(String host, int port, String user, String password, Duration read) {
		return new MailProperties("smtp", new MailProperties.Smtp(host, port, user, password, true, false, false, Duration.ofSeconds(2), read, Duration.ofSeconds(2)), "no-reply@example.test", "Ana Dépôt",
				"support@example.test", null, null, null, "target/x", null, 10);
	}

	private SmtpMailTransport transport(MailProperties p) {
		return new SmtpMailTransport(MailConfig.sender(p), p);
	}

	private OutgoingMail mail(String to) {
		return new OutgoingMail(UUID.randomUUID(), to, "Réservation ANA-K7M2QX prête à retirer", "<html><body><p>Vos produits sont prêts — Évelyne « ok »</p></body></html>", "Vos produits sont prêts — Évelyne « ok »\n");
	}

	@Test
	void aMessageIsDeliveredAsUtf8TextPlusHtmlWithSenderReplyToAndCorrelationHeaders() throws Exception {
		var p = props("127.0.0.1", smtp.getSmtp().getPort(), "smtp-user", "smtp-secret-pw", Duration.ofSeconds(5));
		var m = mail("client@example.com");
		transport(p).send(m);
		var received = smtp.getReceivedMessages();
		assertThat(received).hasSize(1);
		var msg = received[0];
		assertThat(msg.getSubject()).isEqualTo("Réservation ANA-K7M2QX prête à retirer");
		assertThat(((InternetAddress) msg.getFrom()[0]).getAddress()).isEqualTo("no-reply@example.test");
		assertThat(((InternetAddress) msg.getFrom()[0]).getPersonal()).isEqualTo("Ana Dépôt");
		assertThat(((InternetAddress) msg.getReplyTo()[0]).getAddress()).isEqualTo("support@example.test");
		assertThat(((InternetAddress) msg.getAllRecipients()[0]).getAddress()).isEqualTo("client@example.com");
		assertThat(msg.getHeader("X-Ana-Notification-Id")[0]).isEqualTo(m.notificationId().toString());
		assertThat(msg.getHeader("Auto-Submitted")[0]).isEqualTo("auto-generated");
		assertThat(msg.getContentType()).startsWith("multipart/");
		var all = new StringBuilder();
		collect((Multipart) msg.getContent(), all);
		assertThat(all.toString()).contains("text/plain", "text/html", "Évelyne « ok »");
	}

	private static void collect(Multipart mp, StringBuilder out) throws Exception {
		for (int i = 0; i < mp.getCount(); i++) {
			var part = mp.getBodyPart(i);
			if (part.getContent() instanceof Multipart inner) {
				collect(inner, out);
			} else {
				out.append(part.getContentType().split(";")[0]).append(" ").append(part.getContent()).append("\n");
			}
		}
	}

	@Test
	void aWrongPasswordIsAnAuthenticationFailureNotAPermanentOneAndNeverLeaksTheSecret() {
		var p = props("127.0.0.1", smtp.getSmtp().getPort(), "smtp-user", "WRONG-pw-12345", Duration.ofSeconds(5));
		assertThatThrownBy(() -> transport(p).send(mail("client@example.com"))).isInstanceOf(MailFailure.class).satisfies(e -> {
			var f = (MailFailure) e;
			assertThat(f.code()).isEqualTo("smtp_auth_failed");
			assertThat(f.permanent()).isFalse();                 // configuration problem: retried within the bounded budget, so mail resumes once credentials are fixed
			assertThat(f.getMessage() + f).doesNotContain("WRONG-pw-12345").doesNotContain("smtp-secret-pw");
		});
		assertThat(smtp.getReceivedMessages()).isEmpty();
	}

	@Test
	void anUnavailableServerIsATemporaryFailure() throws Exception {
		int closed;
		try (var s = new ServerSocket(0)) {
			closed = s.getLocalPort();
		}
		var p = props("127.0.0.1", closed, "smtp-user", "smtp-secret-pw", Duration.ofSeconds(2));
		assertThatThrownBy(() -> transport(p).send(mail("client@example.com"))).isInstanceOfSatisfying(MailFailure.class, f -> {
			assertThat(f.code()).isEqualTo("smtp_unavailable");
			assertThat(f.permanent()).isFalse();
		});
	}

	@Test
	void aServerThatAcceptsTheConnectionButNeverAnswersTimesOut() throws Exception {
		try (var silent = new ServerSocket(0)) {
			var p = props("127.0.0.1", silent.getLocalPort(), "smtp-user", "smtp-secret-pw", Duration.ofMillis(600));
			long t0 = System.nanoTime();
			assertThatThrownBy(() -> transport(p).send(mail("client@example.com"))).isInstanceOfSatisfying(MailFailure.class, f -> {
				assertThat(f.code()).isEqualTo("smtp_timeout");
				assertThat(f.permanent()).isFalse();
			});
			assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(8));   // bounded: the dispatcher is never stuck behind a dead server
		}
	}

	@Test
	void classificationSeparatesAPermanentAddressProblemFromTemporaryOnes() {
		var rejected = new MailSendException("failed", new SendFailedException("550 no such user", null, null, null, new InternetAddress[] { newAddress("nobody@example.com") }));
		assertThat(SmtpMailTransport.classify(rejected)).satisfies(f -> {
			assertThat(f.code()).isEqualTo("recipient_rejected");
			assertThat(f.permanent()).isTrue();
		});
		assertThat(SmtpMailTransport.classify(new MailSendException("451 try later"))).satisfies(f -> {
			assertThat(f.code()).isEqualTo("smtp_error");
			assertThat(f.permanent()).isFalse();
		});
		assertThat(SmtpMailTransport.classify(new org.springframework.mail.MailParseException("bad"))).satisfies(f -> assertThat(f.permanent()).isTrue());
	}

	private static InternetAddress newAddress(String a) {
		try {
			return new InternetAddress(a);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void theStartupDiagnosticNamesEveryMissingVariableAndNeverPrintsAValue() {
		var missing = new MailProperties("smtp", new MailProperties.Smtp(null, 587, "", "", true, true, false, Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(10)), null, "Ana Dépôt", "not-an-address", null, null, null, "t", null, 10);
		var problems = MailConfig.problems(missing);
		assertThat(String.join("|", problems)).contains("ANA_MAIL_SMTP_HOST", "ANA_MAIL_FROM_ADDRESS", "ANA_MAIL_SMTP_USERNAME", "ANA_MAIL_SMTP_PASSWORD", "ANA_MAIL_REPLY_TO");
		assertThatThrownBy(() -> new MailConfig().mailTransport(missing)).isInstanceOf(IllegalStateException.class).hasMessageContaining("ANA_MAIL_SMTP_HOST").hasMessageContaining("ANA_MAIL_MODE=disabled");
		var tls = new MailProperties("smtp", new MailProperties.Smtp("h", 587, "u", "p", true, true, true, Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(10)), "a@example.com", "x", null, null, null, null, "t", null, 10);
		assertThat(MailConfig.problems(tls).toString()).contains("mutually exclusive");
		assertThat(MailConfig.problems(new MailProperties("log", null, null, "x", null, null, null, null, "t", null, 10))).isEmpty();        // development mode needs no SMTP settings
		assertThat(MailConfig.problems(new MailProperties("disabled", null, null, "x", null, null, null, null, "t", null, 10))).isEmpty();
		var withSecret = props("smtp.example.test", 587, "user", "TOP-SECRET-PASSWORD", Duration.ofSeconds(5));
		assertThat(withSecret.toString() + withSecret.smtp()).doesNotContain("TOP-SECRET-PASSWORD");
	}

	@Test
	void theThreeModesAreExplicitAndOnlySmtpClaimsToDeliver() {
		var log = new MailConfig().mailTransport(new MailProperties("log", null, null, "x", null, null, null, null, "target/test-capture-" + UUID.randomUUID(), null, 10));
		assertThat(log.delivers()).isFalse();                  // nothing is sent: rows end CAPTURED, never SENT
		var smtpMode = new MailConfig().mailTransport(props("127.0.0.1", smtp.getSmtp().getPort(), "smtp-user", "smtp-secret-pw", Duration.ofSeconds(5)));
		assertThat(smtpMode.delivers()).isTrue();
	}
}
