package com.anadepot.core.payment.service;

import com.anadepot.core.shared.money.Money;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The online payment provider as the business sees it (hosted checkout). Implemented in infra; core never touches a provider SDK. The gateway only
 * creates sessions and answers eligibility: it never decides that money was received (only a verified provider event does, see {@link OnlinePaymentService}).
 */
public interface CheckoutGateway {

	/** False when the integration is switched off or not configured: no session is ever created. */
	boolean enabled();

	/** Currencies that are configured AND that the provider account can actually charge. Empty when unsure (fail closed). */
	Set<SupportedCurrency> eligibleCurrencies();

	/** What customers may rely on right now. {@code TEMPORARILY_UNAVAILABLE}: switched on, but the provider could not be asked (no currency is offered until it can). */
	enum Availability { DISABLED, AVAILABLE, TEMPORARILY_UNAVAILABLE }

	default Availability availability() {
		if (!enabled()) {
			return Availability.DISABLED;
		}
		return eligibleCurrencies().isEmpty() ? Availability.DISABLED : Availability.AVAILABLE;
	}

	/** The smallest charge the provider accepts in this currency, when known. */
	Optional<Money> minimumCharge(SupportedCurrency currency);

	/**
	 * Creates a hosted checkout session for exactly one currency. {@code attemptId} makes the provider call idempotent: calling twice with the same
	 * request returns the same session.
	 */
	Session createSession(Request request);

	record Request(UUID attemptId, String reservationReference, Money amount, String locale, String customerEmail) {
	}

	record Session(String sessionId, String url, Instant expiresAt) {
	}
}
