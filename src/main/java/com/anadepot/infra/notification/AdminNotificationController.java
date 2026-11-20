package com.anadepot.infra.notification;

import com.anadepot.core.notification.model.Notification;
import com.anadepot.core.notification.model.NotificationQuery;
import com.anadepot.core.notification.model.NotificationStatus;
import com.anadepot.core.notification.model.NotificationType;
import com.anadepot.core.notification.service.NotificationAdminService;
import com.anadepot.core.shared.DomainRuleViolation;
import com.anadepot.core.shared.PageRequest;
import com.anadepot.infra.security.Actors;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff visibility of customer e-mails and the one controlled action (retry a message that gave up). Staff only (SecurityConfig). The recipient is always MASKED, the failure is a short code,
 * and nothing about the mail server (host, credentials, provider text) is ever returned.
 */
@RestController
@RequestMapping("/api/v1/admin/notifications")
class AdminNotificationController {

	record Item(UUID id, String type, UUID reservationId, String reservationReference, String recipient, String status, int attempts, String lastError, Instant nextAttemptAt, Instant createdAt,
			Instant sentAt) {
	}

	record Page(List<Item> items, int page, int size, long totalItems, int totalPages) {
	}

	/** {@code mode}: SMTP (messages are sent), LOG (development: files, nothing is sent) or DISABLED (messages wait and are NOT sent). */
	/** {@code attention}: messages staff should look at (gave up, or an important message that expired unsent). Superseded messages are not failures and are not counted. */
	record Summary(Map<String, Long> counts, long attention, String mode, Instant oldestWaitingAt) {
	}

	private final NotificationAdminService service;
	private final NotificationJpaRepository jpa;
	private final MailProperties mail;

	AdminNotificationController(NotificationAdminService service, NotificationJpaRepository jpa, MailProperties mail) {
		this.service = service;
		this.jpa = jpa;
		this.mail = mail;
	}

	@GetMapping
	Page list(@RequestParam(required = false) String status, @RequestParam(required = false) String type, @RequestParam(required = false) UUID reservationId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		var result = service.list(new NotificationQuery(parse(NotificationStatus.class, status), parse(NotificationType.class, type), reservationId), new PageRequest(page, size));
		var refs = new java.util.HashMap<UUID, String>();
		var ids = result.items().stream().map(Notification::reservationId).distinct().toList();
		if (!ids.isEmpty()) {
			jpa.referencesOf(ids).forEach(row -> refs.put((UUID) row[0], (String) row[1]));
		}
		return new Page(result.items().stream().map(n -> item(n, refs.get(n.reservationId()))).toList(), result.page(), result.size(), result.totalItems(), result.totalPages());
	}

	@GetMapping("/summary")
	Summary summary() {
		var counts = new LinkedHashMap<String, Long>();
		service.summary().forEach((s, n) -> counts.put(s.name(), n));
		var oldest = jpa.oldestWaiting();
		return new Summary(counts, service.attention(), mail.smtpMode() ? "SMTP" : mail.logMode() ? "LOG" : "DISABLED", oldest);
	}

	@PostMapping("/{id}/retry")
	Item retry(@PathVariable UUID id, Authentication authentication) {
		var n = service.retry(id, Actors.staff(authentication));
		return item(n, jpa.referencesOf(List.of(n.reservationId())).stream().map(r -> (String) r[1]).findFirst().orElse(null));
	}

	private static Item item(Notification n, String reference) {
		return new Item(n.id(), n.type().name(), n.reservationId(), reference, n.recipientHint(), n.status().name(), n.attempts(), n.lastErrorCode(), n.nextAttemptAt(), n.createdAt(), n.sentAt());
	}

	private static <E extends Enum<E>> E parse(Class<E> type, String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Enum.valueOf(type, value.strip().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new DomainRuleViolation("filter_invalid", "Unknown filter value");
		}
	}
}
