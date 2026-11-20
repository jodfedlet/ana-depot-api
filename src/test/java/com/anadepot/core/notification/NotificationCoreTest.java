package com.anadepot.core.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.anadepot.core.notification.model.EmailAddress;
import com.anadepot.core.notification.model.Notification;
import com.anadepot.core.notification.model.NotificationQuery;
import com.anadepot.core.notification.model.NotificationRequest;
import com.anadepot.core.notification.model.NotificationStatus;
import com.anadepot.core.notification.model.NotificationType;
import com.anadepot.core.notification.repository.NotificationRepository;
import com.anadepot.core.notification.serviceImpl.NotificationRecorderImpl;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.core.shared.PageResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The notification rules in plain Java: no Spring, no database, no mail library. */
class NotificationCoreTest {

	static final class Memory implements NotificationRepository {
		final List<Notification> rows = new ArrayList<>();
		@Override public boolean insertIfAbsent(Notification n) { if (rows.stream().anyMatch(r -> r.dedupeKey().equals(n.dedupeKey()))) { return false; } rows.add(n); return true; }
		@Override public long countRecentForRecipient(String hash, Instant since) { return rows.stream().filter(r -> hash.equals(r.recipientHash()) && !r.createdAt().isBefore(since) && !r.status().name().startsWith("SKIPPED")).count(); }
		@Override public List<Notification> claimDue(Instant now, int limit, Instant lease) { return List.of(); }
		@Override public Optional<Notification> find(UUID id) { return rows.stream().filter(r -> r.id().equals(id)).findFirst(); }
		@Override public Optional<Notification> findForUpdate(UUID id) { return find(id); }
		@Override public Notification save(Notification n) { return n; }
		@Override public PageResult<Notification> list(NotificationQuery q, PageRequest p) { return new PageResult<>(rows, 0, 20, rows.size()); }
		@Override public Map<NotificationStatus, Long> counts() { return new EnumMap<>(NotificationStatus.class); }
		@Override public Instant oldestWaiting() { return null; }
		@Override public long attentionCount() { return 0; }
	}

	final Memory repo = new Memory();
	final Clock clock = Clock.fixed(Instant.parse("2026-11-18T12:00:00Z"), ZoneOffset.UTC);
	final NotificationRecorderImpl recorder = new NotificationRecorderImpl(repo, clock, 3);

	@Test
	void anAddressIsValidatedMaskedAndHashedWithoutKeepingIt() {
		assertThat(EmailAddress.isValid("marie.dupont@example.com")).isTrue();
		assertThat(EmailAddress.isValid("  Marie@Example.COM ")).isTrue();
		for (var bad : new String[] { "", " ", "no-at-sign", "a@b", "a@@b.com", "a b@example.com", "a..b@example.com", ".a@example.com", "a@-x.com", "a@example..com", "x".repeat(65) + "@example.com", "<script>@example.com", "a@exa mple.com" }) {
			assertThat(EmailAddress.isValid(bad)).as(bad).isFalse();
		}
		assertThat(EmailAddress.isValid(null)).isFalse();
		assertThat(EmailAddress.mask("marie.dupont@example.com")).isEqualTo("m***@example.com").doesNotContain("dupont");
		assertThat(EmailAddress.hash("Marie@Example.com")).isEqualTo(EmailAddress.hash("marie@example.com")).hasSize(64).doesNotContain("marie");
	}

	@Test
	void everyRequestBecomesOneRowWithReferencesOnly() {
		var reservation = UUID.randomUUID();
		recorder.record(NotificationRequest.reservation(NotificationType.RESERVATION_READY_FOR_PICKUP, reservation, "ht", "client@example.com"));
		assertThat(repo.rows).hasSize(1);
		var n = repo.rows.get(0);
		assertThat(n.status()).isEqualTo(NotificationStatus.PENDING);
		assertThat(n.recipientHint()).isEqualTo("c***@example.com");
		assertThat(n.locale()).isEqualTo("ht");
		assertThat(n.toString()).doesNotContain("client@example.com");
		assertThat(NotificationRequest.reservation(NotificationType.RESERVATION_READY_FOR_PICKUP, reservation, "fr", "client@example.com").toString()).doesNotContain("client@example.com");
	}

	@Test
	void theSameBusinessFactIsRecordedOnceWhateverTheNumberOfRequests() {
		var reservation = UUID.randomUUID();
		var payment = UUID.randomUUID();
		for (int i = 0; i < 5; i++) {
			recorder.record(NotificationRequest.payment(reservation, payment, "fr", "client@example.com"));
			recorder.record(NotificationRequest.reservation(NotificationType.RESERVATION_COLLECTED, reservation, "fr", "client@example.com"));
		}
		assertThat(repo.rows).hasSize(2);
		recorder.record(NotificationRequest.payment(reservation, UUID.randomUUID(), "fr", "client@example.com"));   // another payment IS another fact
		assertThat(repo.rows).hasSize(3);
	}

	@Test
	void aMissingOrInvalidRecipientIsRecordedAsSkippedNeverRetried() {
		recorder.record(NotificationRequest.reservation(NotificationType.RESERVATION_SUBMITTED, UUID.randomUUID(), "fr", null));
		recorder.record(NotificationRequest.reservation(NotificationType.RESERVATION_SUBMITTED, UUID.randomUUID(), "fr", "   "));
		recorder.record(NotificationRequest.reservation(NotificationType.RESERVATION_SUBMITTED, UUID.randomUUID(), "fr", "not-an-address"));
		assertThat(repo.rows).extracting(Notification::status).containsExactly(NotificationStatus.SKIPPED_NO_RECIPIENT, NotificationStatus.SKIPPED_NO_RECIPIENT, NotificationStatus.SKIPPED_INVALID_RECIPIENT);
		assertThat(repo.rows).allMatch(n -> n.status().isFinal() && n.recipientHash() == null);
	}

	@Test
	void oneAddressCannotBeFloodedThroughTheShop() {
		for (int i = 0; i < 6; i++) {
			recorder.record(NotificationRequest.reservation(NotificationType.RESERVATION_SUBMITTED, UUID.randomUUID(), "fr", "victim@example.com"));
		}
		assertThat(repo.rows).extracting(Notification::status).containsExactly(NotificationStatus.PENDING, NotificationStatus.PENDING, NotificationStatus.PENDING,
				NotificationStatus.SKIPPED_THROTTLED, NotificationStatus.SKIPPED_THROTTLED, NotificationStatus.SKIPPED_THROTTLED);
		recorder.record(NotificationRequest.reservation(NotificationType.RESERVATION_SUBMITTED, UUID.randomUUID(), "fr", "someone-else@example.com"));
		assertThat(repo.rows.get(6).status()).isEqualTo(NotificationStatus.PENDING);   // other addresses are unaffected
	}

	@Test
	void theCatalogHasNoAuthenticationMessages() {
		// Supabase Auth owns address verification and password recovery: this application must never send a duplicate of those messages
		for (var t : NotificationType.values()) {
			assertThat(t.name().toLowerCase() + t.template()).doesNotContain("verif", "password", "recover", "reset", "welcome", "signup", "sign-up", "login");
		}
	}
}
