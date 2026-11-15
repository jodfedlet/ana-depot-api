package com.anadepot.core.shared;

import java.util.Map;
import java.util.TreeMap;

/**
 * Text authored by the seller in several languages (language code -> text), e.g. {@code fr}, {@code ht}.
 * Languages are open-ended so a new language needs no schema or code change.
 */
public record LocalizedText(Map<String, String> values) {

	public LocalizedText {
		var clean = new TreeMap<String, String>();
		if (values != null) {
			values.forEach((lang, text) -> {
				if (lang != null && text != null && !text.isBlank()) {
					clean.put(lang, text.strip());
				}
			});
		}
		values = Map.copyOf(clean);
	}

	public static LocalizedText of(String lang, String text) {
		return new LocalizedText(Map.of(lang, text));
	}

	public static LocalizedText empty() {
		return new LocalizedText(Map.of());
	}

	public boolean isEmpty() {
		return values.isEmpty();
	}

	/** Text in {@code lang}, else the first available language, else an empty string. */
	public String in(String lang) {
		var exact = values.get(lang);
		if (exact != null) {
			return exact;
		}
		return values.values().stream().findFirst().orElse("");
	}
}
