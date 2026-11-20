package com.anadepot.infra.reservation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * D6 (owner-approved): a reservation that became READY is held for {@code defaultHoldDays} (three) unless the store's pickup settings name another number of days; the customer is reminded
 * {@code reminderLead} before the deadline; a background job checks every {@code expiryInterval}.
 */
@ConfigurationProperties(prefix = "ana.reservation")
public record ReservationPolicyProperties(@DefaultValue("3") int defaultHoldDays, @DefaultValue("24h") Duration reminderLead, @DefaultValue("5m") Duration expiryInterval,
		@DefaultValue("true") boolean expiryEnabled) {
}
