package com.anadepot.infra.web;

import com.anadepot.core.shared.FieldValidationException;
import com.anadepot.core.shared.FieldValidationException.FieldIssue;
import java.util.List;
import java.util.Locale;

/** Parsing of optional query/body parameters; a bad value is a field issue ({@code invalid}), never a 500. */
public final class QueryParams {

	private QueryParams() {
	}

	/** The enum constant named by {@code value} (case-insensitive), or null when absent/blank. */
	public static <E extends Enum<E>> E enumOf(Class<E> type, String value, String field) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new FieldValidationException(List.of(new FieldIssue(field, "invalid")));
		}
	}
}
