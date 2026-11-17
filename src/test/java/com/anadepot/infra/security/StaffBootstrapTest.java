package com.anadepot.infra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anadepot.core.shared.AuditEvent;
import com.anadepot.core.shared.AuditRecorder;
import com.anadepot.core.shared.TransactionBoundary;
import com.anadepot.infra.config.AnaProperties;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

/** Provisioning of the first administrator: explicit, one-time, no credentials, and never re-granted from a stale setting. */
class StaffBootstrapTest {

	private final StaffDirectory directory = mock(StaffDirectory.class);
	private final AuditRecorder audit = mock(AuditRecorder.class);
	private final TransactionBoundary tx = new TransactionBoundary() {
		@Override
		public <T> T inTransaction(Supplier<T> work) {
			return work.get();
		}
	};

	private StaffBootstrap bootstrap(String subject) {
		var props = new AnaProperties(null, null, null, new AnaProperties.Security(null, null, null, "authenticated", subject), null, null, null);
		return new StaffBootstrap(directory, props, audit, tx);
	}

	@Test
	void grantsTheConfiguredUserWhenNoStaffExistAndAuditsIt() {
		var user = UUID.randomUUID();
		when(directory.hasAnyStaff()).thenReturn(false);

		bootstrap(user.toString()).run(new DefaultApplicationArguments());

		verify(directory).grant(eq(user), eq(null), any());
		var event = ArgumentCaptor.forClass(AuditEvent.class);
		verify(audit).record(event.capture());
		assertThat(event.getValue().action()).isEqualTo("STAFF_BOOTSTRAPPED");
		assertThat(event.getValue().targetId()).isEqualTo(user.toString());
	}

	@Test
	void doesNothingWhenNotConfigured_whenStaffAlreadyExist_orWhenTheValueIsNotAUserId() {
		when(directory.hasAnyStaff()).thenReturn(false);
		bootstrap(null).run(new DefaultApplicationArguments());
		bootstrap("  ").run(new DefaultApplicationArguments());
		bootstrap("admin").run(new DefaultApplicationArguments());     // an e-mail / name is not an identity

		when(directory.hasAnyStaff()).thenReturn(true);
		bootstrap(UUID.randomUUID().toString()).run(new DefaultApplicationArguments());   // a stale setting never re-grants

		verify(directory, never()).grant(any(), any(), any());
		verify(audit, never()).record(any());
	}
}
