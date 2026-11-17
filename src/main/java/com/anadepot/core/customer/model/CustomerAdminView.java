package com.anadepot.core.customer.model;

import java.time.Instant;

/** A customer as staff see it: the record, its version for optimistic concurrency, and its reservation activity. {@code summary} is null in lists. */
public record CustomerAdminView(Customer customer, long version, Instant updatedAt, CustomerActivity activity, CustomerSummary summary) {
}
