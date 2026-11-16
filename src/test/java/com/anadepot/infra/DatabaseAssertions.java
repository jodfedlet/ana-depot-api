package com.anadepot.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs work in a transaction and asserts that PostgreSQL itself refused it (deferred constraints fire at commit). */
public final class DatabaseAssertions {

	private DatabaseAssertions() {
	}

	public static void assertRejected(PlatformTransactionManager manager, String expectedFragment, Runnable work) {
		try {
			new TransactionTemplate(manager).executeWithoutResult(s -> work.run());
		} catch (Throwable e) {
			var root = e;
			while (root.getCause() != null && root.getCause() != root) {
				root = root.getCause();
			}
			assertThat(root.getMessage()).as("database error for: " + expectedFragment).containsIgnoringCase(expectedFragment);
			return;
		}
		fail("PostgreSQL accepted data that should have been rejected (" + expectedFragment + ")");
	}
}
