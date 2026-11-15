package com.anadepot.infra.security;

import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.AuditEvent;
import com.anadepot.core.shared.AuditRecorder;
import com.anadepot.core.shared.TransactionBoundary;
import com.anadepot.infra.config.AnaProperties;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Provisions the FIRST store administrator (docs/auth.md). If {@code ana.security.bootstrap-admin-subject} holds the
 * Supabase user id (UUID) of a person who already registered through normal sign-up AND the staff table is empty,
 * that person becomes staff. No credentials are involved, nothing is hardcoded, and the property is ignored once any
 * staff member exists (so a stale value cannot re-grant access).
 */
@Component
class StaffBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(StaffBootstrap.class);

	private final StaffDirectory staff;
	private final AnaProperties properties;
	private final AuditRecorder audit;
	private final TransactionBoundary tx;

	StaffBootstrap(StaffDirectory staff, AnaProperties properties, AuditRecorder audit, TransactionBoundary tx) {
		this.staff = staff;
		this.properties = properties;
		this.audit = audit;
		this.tx = tx;
	}

	@Override
	public void run(ApplicationArguments args) {
		var subject = properties.security().bootstrapAdminSubject();
		if (subject == null || subject.isBlank() || staff.hasAnyStaff()) {
			return;
		}
		UUID userId;
		try {
			userId = UUID.fromString(subject.strip());
		} catch (IllegalArgumentException e) {
			log.error("ana.security.bootstrap-admin-subject is not a UUID; no administrator was provisioned");
			return;
		}
		tx.inTransaction(() -> {
			staff.grant(userId, null, "bootstrap administrator");
			audit.record(AuditEvent.of("STAFF_BOOTSTRAPPED", "staff_member", userId, Actor.system(), Map.of(), Map.of("active", true), "initial administrator provisioning"));
		});
		log.info("Initial store administrator provisioned from configuration (user id {})", userId);
	}
}
