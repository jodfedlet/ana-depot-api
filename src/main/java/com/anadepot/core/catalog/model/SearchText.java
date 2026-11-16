package com.anadepot.core.catalog.model;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Accent- and case-insensitive text used for catalog search (so "Matelas", "matelas" and "mátelás" match, in any stored language). */
public final class SearchText {

	private SearchText() {
	}

	public static String normalize(String s) {
		if (s == null) {
			return "";
		}
		return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
	}

	/** Normalised text reduced to letters and digits separated by single spaces ("SKU-AB12" -> "sku ab12"): the form stored in and matched against the search vector. */
	public static String words(String s) {
		return normalize(s).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
	}

	public static List<String> tokens(String normalized) {
		return normalized.isEmpty() ? List.of() : Arrays.asList(normalized.split(" "));
	}
}
