package com.anadepot.infra.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.anadepot.core.notification.model.NotificationType;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Every template in both languages, rendered from a view. No Spring, no database, no mail server. */
class EmailRenderingTest {

	final EmailRenderer renderer = new EmailRenderer();

	static EmailView view(String locale, String firstName, String productName) {
		return new EmailView(locale, "ANA-K7M2QX", firstName, "18 novembre 2026",
				List.of(new EmailView.Line(productName, "Taille L", 2, "PAIR", "1 500,00 HTG", "3 000,00 HTG", "RETAIL"), new EmailView.Line("Tuyau", "", 1, "METER", "12,50 USD", "12,50 USD", "RETAIL")),
				List.of(new EmailView.Total("HTG", "3 000,00 HTG"), new EmailView.Total("USD", "12,50 USD")),
				List.of(new EmailView.Balance("HTG", "3 000,00 HTG", "1 000,00 HTG", "2 000,00 HTG", false), new EmailView.Balance("USD", "12,50 USD", "12,50 USD", "0,00 USD", true)),
				new EmailView.Pickup("Magasin de test", "1, rue de l'Essai, Port-au-Prince", "+509 0000 0000", "magasin@example.test", List.of("Lundi : 09:00 – 17:00", "Dimanche : Fermé"), "Entrée côté rue", "Apportez votre référence."),
				"https://shop.example.test/" + locale + "/reservations", "PRODUCT_UNAVAILABLE",
				List.of(new EmailView.Payment("HTG", "1 000,00 HTG", "email.method.cash", "18 novembre 2026"), new EmailView.Payment("USD", "12,50 USD", "email.method.card", "18 novembre 2026")),
				new EmailView.Payment("USD", "12,50 USD", "email.method.card", "18 novembre 2026"), new EmailView.Attempt("USD", "12,50 USD"),
				true, true, new EmailView.Support("magasin@example.test", "+509 0000 0000"), "https://shop.example.test/conditions", "https://shop.example.test/confidentialite", "Ana Dépôt", "21 novembre 2026");
	}

	@ParameterizedTest
	@EnumSource(NotificationType.class)
	void everyEventRendersInFrenchAndHaitianCreoleWithReferenceSubjectAndBothParts(NotificationType type) {
		for (var locale : List.of("fr", "ht")) {
			var r = renderer.render(type, view(locale, "Marie", "Chaussure"));
			assertThat(r.subject()).as(type + " subject " + locale).contains("ANA-K7M2QX").doesNotContain("{0}").doesNotContain("??");
			assertThat(r.html()).startsWith("<!DOCTYPE html>").contains("ANA-K7M2QX", "#0b3732", "#b5441b", "charset=\"UTF-8\"").doesNotContain("<script", "javascript:", "??");
			assertThat(r.text()).contains("ANA-K7M2QX").doesNotContain("<", "??", "[(").doesNotContain("th:");
			assertThat(r.text().length()).isGreaterThan(80);
			assertThat(r.html()).doesNotContain("${").doesNotContain("#{");
		}
	}

	@Test
	void theTwoLanguagesAreReallyDifferentAndNeverLeakIntoEachOther() {
		var fr = renderer.render(NotificationType.RESERVATION_READY_FOR_PICKUP, view("fr", "Marie", "Chaussure"));
		var ht = renderer.render(NotificationType.RESERVATION_READY_FOR_PICKUP, view("ht", "Marie", "Chaussure"));
		assertThat(fr.subject()).isEqualTo("Réservation ANA-K7M2QX prête à retirer");
		assertThat(ht.subject()).isEqualTo("Rezèvasyon ANA-K7M2QX pare pou pran");
		assertThat(fr.html()).contains("Vos produits sont prêts", "Reste à payer", "Retrait au magasin").doesNotContain("Pwodwi w yo");
		assertThat(ht.html()).contains("Pwodwi w yo pare", "Sa ki rete pou peye", "Retrè nan magazen an").doesNotContain("Vos produits", "Reste à payer");
		assertThat(ht.text()).contains("Bonjou Marie,").doesNotContain("Bonjour");
	}

	@Test
	void accentsAndSpecialCharactersSurviveUtf8() {
		var r = renderer.render(NotificationType.RESERVATION_SUBMITTED, view("ht", "Évelyne", "Chèz bwa « solid »"));
		assertThat(r.html()).contains("Évelyne", "Chèz bwa « solid »", "Rezèvasyon", "1 500,00 HTG");
		assertThat(r.text()).contains("Évelyne", "Chèz bwa");
		assertThat(r.subject()).contains("Rezèvasyon");
	}

	@Test
	void htgAndUsdAreNeverCombined() {
		var r = renderer.render(NotificationType.RESERVATION_SUBMITTED, view("fr", "Marie", "Chaussure"));
		assertThat(r.html()).contains("3 000,00 HTG", "12,50 USD").contains("aucun taux de change");
		assertThat(r.text()).contains("3 000,00 HTG", "12,50 USD");
		// no figure that adds the two (3 012,50 or 3 000 + 12,5) and no mixed-currency amount
		assertThat(r.html() + r.text()).doesNotContain("3 012,50", "3012,50", "3 012.50").doesNotContainPattern("\\d[\\d  ]*,\\d\\d (HTG|USD) (HTG|USD)");
		var ready = renderer.render(NotificationType.RESERVATION_READY_FOR_PICKUP, view("fr", "Marie", "Chaussure"));
		assertThat(ready.html()).contains("2 000,00 HTG", "Réglé");     // HTG still due, USD settled: independent balances
		assertThat(ready.text()).contains("HTG : 2 000,00", "USD : Réglé");
	}

	@Test
	void untrustedTextIsEscapedAndNeverBecomesMarkup() {
		var evil = view("fr", "\"><img src=x onerror=alert(1)>", "<script>alert('x')</script>");
		for (var type : NotificationType.values()) {
			var r = renderer.render(type, evil);
			assertThat(r.html()).as(type.name()).doesNotContain("<script>alert", "<img src=x", "onerror=alert(1)>").doesNotContain("\"><img");
			if (type == NotificationType.RESERVATION_SUBMITTED) {
				assertThat(r.html()).contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;");
				assertThat(r.html()).contains("&quot;&gt;&lt;img src=x onerror=alert(1)&gt;");
			}
		}
	}

	@Test
	void pickupDetailsAreShownAsSnapshottedAndAClearStatementAppearsWhenThereAreNone() {
		var withPickup = renderer.render(NotificationType.RESERVATION_SUBMITTED, view("fr", "Marie", "Chaussure"));
		assertThat(withPickup.html()).contains("Magasin de test", "1, rue de l&#39;Essai, Port-au-Prince", "Lundi : 09:00 – 17:00", "Dimanche : Fermé", "Apportez votre référence.");
		var base = view("fr", "Marie", "Chaussure");
		var none = new EmailView(base.locale(), base.reference(), base.firstName(), base.createdOn(), base.lines(), base.totals(), base.balances(), null, base.trackUrl(), base.reasonKey(), base.payments(),
				base.payment(), base.attempt(), base.cardAvailable(), base.pickupPayAvailable(), new EmailView.Support(null, null), null, null, base.brandName(), base.expiresOn());
		var r = renderer.render(NotificationType.RESERVATION_SUBMITTED, none);
		assertThat(r.html()).contains("Ne vous déplacez pas avant d’avoir reçu le message").doesNotContain("Magasin de test");
		assertThat(r.html()).doesNotContain("Une question ?").doesNotContain("mailto:").doesNotContain("Conditions de réservation");   // nothing configured: nothing invented
	}

	@Test
	void theTrackingButtonAppearsOnlyWithAConfiguredAddress() {
		var base = view("fr", "Marie", "Chaussure");
		var noLink = new EmailView(base.locale(), base.reference(), base.firstName(), base.createdOn(), base.lines(), base.totals(), base.balances(), base.pickup(), null, base.reasonKey(), base.payments(),
				base.payment(), base.attempt(), base.cardAvailable(), base.pickupPayAvailable(), base.support(), null, null, base.brandName(), base.expiresOn());
		assertThat(renderer.render(NotificationType.RESERVATION_SUBMITTED, view("fr", "Marie", "Chaussure")).html()).contains("href=\"https://shop.example.test/fr/reservations\"");
		var r = renderer.render(NotificationType.RESERVATION_SUBMITTED, noLink);
		assertThat(r.html()).doesNotContain("shop.example.test/fr/reservations").contains("Suivre ma réservation");   // the instructions in words remain
		assertThat(r.text()).doesNotContain("http");
	}

	@Test
	void paymentOptionsNameTheCardOnlyWhenItReallyExists() {
		var both = renderer.render(NotificationType.RESERVATION_READY_FOR_PICKUP, view("fr", "Marie", "Chaussure"));
		assertThat(both.html()).contains("payer par carte en ligne");
		var b = view("fr", "Marie", "Chaussure");
		var pickupOnly = new EmailView(b.locale(), b.reference(), b.firstName(), b.createdOn(), b.lines(), b.totals(), b.balances(), b.pickup(), b.trackUrl(), b.reasonKey(), b.payments(), b.payment(),
				b.attempt(), false, true, b.support(), b.termsUrl(), b.privacyUrl(), b.brandName(), b.expiresOn());
		var r = renderer.render(NotificationType.RESERVATION_READY_FOR_PICKUP, pickupOnly);
		assertThat(r.html()).contains("Vous payez au magasin lors du retrait.").doesNotContain("par carte");
		assertThat(r.text()).doesNotContain("carte");
	}

	@Test
	void aCardPaymentIsNeverCalledCashAndACashPaymentIsNeverCalledACard() {
		var r = renderer.render(NotificationType.PAYMENT_RECEIVED, view("fr", "Marie", "Chaussure"));    // the payment in the view is a CARD payment of 12,50 USD
		assertThat(r.html()).contains("12,50 USD", "Carte en ligne");
		var detail = r.html().substring(r.html().indexOf("Montant reçu"), r.html().indexOf("Montant reçu") + 400);
		assertThat(detail).doesNotContain("Espèces");
		var failed = renderer.render(NotificationType.PAYMENT_FAILED, view("fr", "Marie", "Chaussure"));
		assertThat(failed.html()).contains("n’est pas confirmé").doesNotContain("Nous avons bien reçu votre paiement");   // no receipt wording for an unconfirmed payment
		assertThat(renderer.render(NotificationType.CHECKOUT_EXPIRED, view("fr", "Marie", "Chaussure")).html()).doesNotContain("Nous avons bien reçu votre paiement");
	}

	@Test
	void aRejectionShowsTheReasonCodeTextOnlyAndACancellationShowsNoStaffText() {
		var rejected = renderer.render(NotificationType.RESERVATION_REJECTED, view("fr", "Marie", "Chaussure"));
		assertThat(rejected.html()).contains("Un ou plusieurs produits ne sont plus disponibles.");
		var cancelled = renderer.render(NotificationType.RESERVATION_CANCELLED, view("fr", "Marie", "Chaussure"));
		assertThat(cancelled.html()).contains("annulé votre réservation", "contactez le magasin à leur sujet").doesNotContain("remboursé", "remboursement");   // no refund is promised or claimed
	}

	/** Not an assertion: writes every rendered message to target/email-samples so the real layout can be looked at in a browser (docs/notifications.md, "Local development"). */
	@Test
	void writesSamplesForVisualReview() throws Exception {
		var dir = java.nio.file.Path.of("target", "email-samples");
		java.nio.file.Files.createDirectories(dir);
		for (var type : NotificationType.values()) {
			for (var locale : List.of("fr", "ht")) {
				var r = renderer.render(type, view(locale, "Marie", "Chaussure de ville"));
				java.nio.file.Files.writeString(dir.resolve(type.template() + "." + locale + ".html"), r.html());
				java.nio.file.Files.writeString(dir.resolve(type.template() + "." + locale + ".txt"), "Subject: " + r.subject() + "\n\n" + r.text());
			}
		}
		assertThat(java.nio.file.Files.list(dir).count()).isGreaterThanOrEqualTo(40);
	}

	@Test
	void everyTemplateAndMessageKeyExistsInBothLanguages() throws Exception {
		var fr = new java.util.Properties();
		var ht = new java.util.Properties();
		try (var a = new java.io.InputStreamReader(getClass().getResourceAsStream("/email/messages_fr.properties"), java.nio.charset.StandardCharsets.UTF_8);
				var b = new java.io.InputStreamReader(getClass().getResourceAsStream("/email/messages_ht.properties"), java.nio.charset.StandardCharsets.UTF_8)) {
			fr.load(a);
			ht.load(b);
		}
		assertThat(ht.stringPropertyNames()).isEqualTo(fr.stringPropertyNames());
		for (var k : fr.stringPropertyNames()) {
			assertThat(Pattern.compile("\\{\\d\\}").matcher(ht.getProperty(k)).results().map(m -> m.group()).sorted().toList()).as(k).isEqualTo(Pattern.compile("\\{\\d\\}").matcher(fr.getProperty(k)).results().map(m -> m.group()).sorted().toList());
		}
		for (var t : NotificationType.values()) {
			assertThat(getClass().getResource("/templates/email/html/" + t.template() + ".html")).as(t + " html").isNotNull();
			assertThat(getClass().getResource("/templates/email/text/" + t.template() + ".txt")).as(t + " text").isNotNull();
			for (var part : List.of("subject", "preheader", "title", "intro")) {
				assertThat(fr.getProperty("email." + EmailRenderer.key(t) + "." + part)).as(t + " " + part).isNotBlank();
			}
		}
		// no authentication template exists: Supabase Auth owns verification and password recovery
		var names = new java.io.File(getClass().getResource("/templates/email/html").toURI()).list();
		assertThat(String.join(",", names)).doesNotContain("verif", "password", "recover", "reset", "welcome");
	}
}
