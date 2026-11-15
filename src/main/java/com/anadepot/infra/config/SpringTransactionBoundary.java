package com.anadepot.infra.config;

import com.anadepot.core.shared.TransactionBoundary;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A use case is one database transaction (REQUIRED: joins an outer one, e.g. the idempotency transaction). */
@Component
public class SpringTransactionBoundary implements TransactionBoundary {

	private final TransactionTemplate template;

	public SpringTransactionBoundary(PlatformTransactionManager manager) {
		this.template = new TransactionTemplate(manager);
	}

	@Override
	public <T> T inTransaction(Supplier<T> work) {
		return template.execute(status -> work.get());
	}
}
