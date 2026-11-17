package com.anadepot.core.reservation.service;

import com.anadepot.core.reservation.model.Contact;
import com.anadepot.core.shared.Actor;
import com.anadepot.core.shared.money.SupportedCurrency;
import java.util.List;
import java.util.UUID;

/**
 * A guest (or signed-in) customer's reservation request. Prices are NOT supplied by the client: the server computes the
 * applied price from the catalog for the requested currency. The same product may appear once per currency.
 */
public record SubmitReservationCommand(List<Line> lines, Contact contact, String locale, String pickupSnapshotJson,
		Actor actor, String idempotencyKey, String termsVersion) {

	/** Without a terms version (tests, internal callers): the acceptance time is still recorded, the version stays null. */
	public SubmitReservationCommand(List<Line> lines, Contact contact, String locale, String pickupSnapshotJson, Actor actor, String idempotencyKey) {
		this(lines, contact, locale, pickupSnapshotJson, actor, idempotencyKey, null);
	}

	/** The version of the terms the customer accepted, as configured by the business ({@code ana.legal.terms-version}); null when none is configured. */
	public SubmitReservationCommand withTermsVersion(String version) {
		return new SubmitReservationCommand(lines, contact, locale, pickupSnapshotJson, actor, idempotencyKey, version);
	}

	public record Line(UUID productId, UUID variantId, int quantity, SupportedCurrency currency) {
	}
}
