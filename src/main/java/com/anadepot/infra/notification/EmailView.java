package com.anadepot.infra.notification;

import java.util.List;

/**
 * Everything a customer e-mail may show, already formatted as text (so a template can only print it, always escaped). There is deliberately NO field for internal notes, staff names,
 * ledger or provider references, the customer's phone number or any free text written by staff: what is not in this type cannot leak into a message.
 */
public record EmailView(String locale, String reference, String firstName, String createdOn, List<Line> lines, List<Total> totals, List<Balance> balances, Pickup pickup,
		String trackUrl, String reasonKey, List<Payment> payments, Payment payment, Attempt attempt, boolean cardAvailable, boolean pickupPayAvailable, Support support,
		String termsUrl, String privacyUrl, String brandName) {

	/** One reservation line as it was snapshotted: name, variant, quantity, selling unit, and the unit price and subtotal in the line's own currency. */
	public record Line(String name, String variant, int quantity, String unit, String unitPrice, String subtotal, String priceKind) {
	}

	/** The total of ONE currency. There is never a combined figure. */
	public record Total(String currency, String amount) {
	}

	public record Balance(String currency, String total, String paid, String due, boolean settled) {
	}

	public record Pickup(String storeName, String address, String phone, String email, List<String> hours, String info, String instructions) {
	}

	/** {@code methodKey}: message key of the payment method (card online / at the store); never described as the other. */
	public record Payment(String currency, String amount, String methodKey, String date) {
	}

	public record Attempt(String currency, String amount) {
	}

	/** Contact details exactly as configured by the store (pickup settings); empty fields stay empty, nothing is invented. */
	public record Support(String email, String phone) {
		public boolean present() {
			return (email != null && !email.isBlank()) || (phone != null && !phone.isBlank());
		}
	}

	@Override
	public String toString() {
		return "EmailView[" + reference + "]";
	}
}
