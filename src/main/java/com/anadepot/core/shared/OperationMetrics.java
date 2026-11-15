package com.anadepot.core.shared;

/** Counts business operations (e.g. {@code reservation.submitted}). Implemented with Micrometer in infra. */
public interface OperationMetrics {

	void count(String name, String... tagKeyValues);
}
