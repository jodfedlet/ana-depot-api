package com.anadepot.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.anadepot.core.catalog.model.CategoryDraft;
import com.anadepot.core.shared.FieldValidationException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A category image URL is staff input that ends up in a storefront {@code <img src>}: only plain absolute https URLs are stored. */
class CategoryImageUrlTest {

	private static List<FieldValidationException.FieldIssue> issuesFor(String imageUrl) {
		var draft = new CategoryDraft("tools", Map.of("fr", "Outils", "ht", "Zouti"), Map.of(), imageUrl, true, 1, List.of());
		try {
			draft.toCategory(java.util.UUID.randomUUID(), null, 1);
			return List.of();
		} catch (FieldValidationException e) {
			return e.issues();
		}
	}

	private static boolean refused(String imageUrl) {
		return issuesFor(imageUrl).stream().anyMatch(i -> i.field().equals("imageUrl"));
	}

	@Test
	void plainHttpsUrlsAndNoUrlAreAccepted() {
		assertThat(refused("https://img.example.com/category/tools.webp")).isFalse();
		assertThat(refused("https://cdn.example.com:8443/a/b.png?v=3")).isFalse();
		assertThat(refused(null)).isFalse();
		assertThat(refused("  ")).isFalse();
	}

	@Test
	void everythingElseIsRefused() {
		for (var bad : new String[] {
				"http://img.example.com/a.png", "javascript:alert(1)", "data:image/svg+xml;base64,AAAA", "//img.example.com/a.png", "ftp://x.test/a.png",
				"https://user:secret@img.example.com/a.png", "https:///no-host.png", "https://img.example.com/a b.png", "https://img.example.com/a.png#frag",
				"https://img.example.com/\"onerror=alert(1)", "https://img.example.com/<script>", "https://img.example.com/a\nb", "https://img.example.com/a\\b.png",
				"https://" + "a".repeat(500) + ".test/x" }) {
			assertThat(refused(bad)).as(bad).isTrue();
		}
	}
}
