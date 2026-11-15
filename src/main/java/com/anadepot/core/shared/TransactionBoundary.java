package com.anadepot.core.shared;

import java.util.function.Supplier;

/**
 * The one place where core says "this use case is atomic". Core stays free of Spring; infrastructure implements it
 * (a database transaction). Use cases run entirely inside {@link #inTransaction}.
 */
public interface TransactionBoundary {

	<T> T inTransaction(Supplier<T> work);

	default void inTransaction(Runnable work) {
		inTransaction(() -> {
			work.run();
			return null;
		});
	}
}
