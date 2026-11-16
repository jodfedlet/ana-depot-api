package com.anadepot.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anadepot.core.catalog.model.SearchQuery;
import com.anadepot.core.catalog.model.SearchText;
import com.anadepot.core.shared.DomainRuleViolation;
import org.junit.jupiter.api.Test;

class SearchQueryTest {

	@Test
	void normalisesAccentsCaseAndPunctuationIntoPrefixTerms() {
		assertThat(SearchQuery.of("  Matière  Première ").tsQuery()).isEqualTo("matiere:* & premiere:*");
		assertThat(SearchQuery.of("Chèz").tsQuery()).isEqualTo("chez:*");                       // Haitian Creole accents folded
		assertThat(SearchQuery.of("SKU-AB12").tokens()).containsExactly("sku", "ab12");        // punctuation splits, digits kept
		assertThat(SearchQuery.of("matelas").exact()).isEqualTo("matelas");
		assertThat(SearchQuery.of(" SKU-AB12 ").exact()).isEqualTo("sku-ab12");
	}

	@Test
	void blankNullAndOneCharNoise() {
		assertThat(SearchQuery.of(null).isEmpty()).isTrue();
		assertThat(SearchQuery.of("   ").isEmpty()).isTrue();
		assertThat(SearchQuery.of("!!! ??? ()").isEmpty()).isTrue();                            // nothing searchable survives
		assertThat(SearchQuery.of("a table").tokens()).containsExactly("table");               // one-character noise dropped
		assertThat(SearchQuery.of("a").tokens()).containsExactly("a");                         // unless it is the only term
	}

	@Test
	void frenchPluralsAreSearchedWithoutTheirEndingAsPrefix() {
		assertThat(SearchQuery.of("chaises").tsQuery()).isEqualTo("chaise:*");
		assertThat(SearchQuery.of("chevaux").tsQuery()).isEqualTo("cheval:*".replace("cheval", "chevau"));
		assertThat(SearchQuery.of("bus").tsQuery()).isEqualTo("bus:*");                         // too short to be treated as a plural
		assertThat(SearchQuery.of("matelas").tsQuery()).isEqualTo("matela:*");                  // still matches "matelas" (prefix)
	}

	@Test
	void tsqueryOperatorsAndQuotesNeverSurvive() {
		var q = SearchQuery.of("ab | !cd (ef) gh:* 'ij' \"kl\" \\mn <-> op");
		assertThat(q.tsQuery()).doesNotContain("|", "!", "(", ")", "'", "\"", "\\", "<").matches("[a-z0-9: &*]+");
		assertThat(SearchQuery.of("'; drop table products; --").tsQuery()).matches("[a-z0-9: &*]+");
	}

	@Test
	void limitsLengthAndNumberOfTerms() {
		assertThat(SearchQuery.of("x".repeat(100)).tokens()).hasSize(1);
		assertThatThrownBy(() -> SearchQuery.of("x".repeat(101))).isInstanceOf(DomainRuleViolation.class).extracting("code").isEqualTo("search_query_too_long");
		assertThat(SearchQuery.of("aa bb cc dd ee ff gg hh").tokens()).hasSize(8);
		assertThat(SearchQuery.of("&|!():*'\"\\").isEmpty()).isTrue();
		assertThatThrownBy(() -> SearchQuery.of("aa bb cc dd ee ff gg hh ii")).extracting("code").isEqualTo("search_query_too_long");
	}

	@Test
	void indexTextKeepsOnlyLettersAndDigits() {
		assertThat(SearchText.words("Chaise-Pliante n°3, Électrique!")).isEqualTo("chaise pliante n 3 electrique");
	}
}
