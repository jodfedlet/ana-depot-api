package com.anadepot.core.settings.model;

import com.anadepot.core.shared.LocalizedText;

/** Seller-authored, bilingual (open set of languages) texts. The pickup policy stays empty until the business decides one. */
public record PickupInstructions(LocalizedText general, LocalizedText contact, LocalizedText accepted, LocalizedText ready, LocalizedText policy) {

	public PickupInstructions {
		general = general == null ? LocalizedText.empty() : general;
		contact = contact == null ? LocalizedText.empty() : contact;
		accepted = accepted == null ? LocalizedText.empty() : accepted;
		ready = ready == null ? LocalizedText.empty() : ready;
		policy = policy == null ? LocalizedText.empty() : policy;
	}

	public static PickupInstructions empty() {
		return new PickupInstructions(null, null, null, null, null);
	}
}
