package com.anadepot.core.catalog.model;

import com.anadepot.core.shared.DomainRuleViolation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * A user's search text, sanitised for PostgreSQL full-text search. User text is NEVER concatenated into SQL: only these sanitised tokens
 * (letters and digits, accent-folded, lower case) are turned into a tsquery that is passed as a bound parameter.
 *
 * <ul>
 *   <li>at most {@value #MAX_CHARS} characters and {@value #MAX_TOKENS} terms (otherwise {@code search_query_too_long});</li>
 *   <li>one-character noise terms are dropped unless they are the only term;</li>
 *   <li>AND semantics across terms; every term is a PREFIX match ("matel" finds "matelas");</li>
 *   <li>a term that looks like a French plural (ends in s/x, 4+ letters) is searched without that ending, as a prefix, so "chaises" finds "chaise"
 *       and "chaises". No dictionary stemming is used: there is no Haitian Creole dictionary (docs/adr/0015).</li>
 * </ul>
 */
public record SearchQuery(List<String> tokens, String exact) {

	public static final int MAX_CHARS = 100;
	public static final int MAX_TOKENS = 8;

	public SearchQuery {
		tokens = List.copyOf(tokens);
	}

	public static SearchQuery empty() {
		return new SearchQuery(List.of(), "");
	}

	public static SearchQuery of(String raw) {
		if (raw == null || raw.isBlank()) {
			return empty();
		}
		if (raw.length() > MAX_CHARS) {
			throw new DomainRuleViolation("search_query_too_long", "The search text is limited to " + MAX_CHARS + " characters");
		}
		var all = SearchText.tokens(SearchText.words(raw));
		var kept = new ArrayList<>(all.stream().filter(t -> t.length() > 1).toList());   // one-character noise does not count
		if (kept.isEmpty() && !all.isEmpty()) {
			kept.add(all.getFirst());
		}
		if (kept.size() > MAX_TOKENS) {
			throw new DomainRuleViolation("search_query_too_long", "The search text is limited to " + MAX_TOKENS + " words");
		}
		return new SearchQuery(kept, raw.replaceAll("\\p{Cntrl}", " ").strip().toLowerCase(Locale.ROOT));   // control characters (NUL...) are never sent to the database
	}

	public boolean isEmpty() {
		return tokens.isEmpty();
	}

	/** The tsquery text: {@code chaise:* & matel:*}. Only letters/digits reach it, so it contains no operator a user could inject. */
	public String tsQuery() {
		return tokens.stream().map(SearchQuery::prefixTerm).collect(Collectors.joining(" & "));
	}

	private static String prefixTerm(String token) {
		boolean plural = token.length() >= 4 && (token.endsWith("s") || token.endsWith("x"));
		return (plural ? token.substring(0, token.length() - 1) : token) + ":*";
	}
}
