package com.anadepot.core.catalog.model;

import com.anadepot.core.shared.FieldValidationException;
import com.anadepot.core.shared.FieldValidationException.FieldIssue;
import com.anadepot.core.shared.LocalizedText;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What staff submit to create or replace a category. Pure input: nothing is trusted. {@link #toCategory} reports EVERY problem at once as
 * {@code {field, code}} issues (stable codes), and builds the domain {@link Category}. Attribute and option ids are kept when they belong to
 * the category being edited (so products keep pointing at them) and generated otherwise.
 */
public record CategoryDraft(String slug, Map<String, String> name, Map<String, String> description, String imageUrl, Boolean active,
		Integer displayOrder, List<AttributeDraft> attributes) {

	public record AttributeDraft(UUID id, String key, Map<String, String> label, String type, String unit, Boolean required, Boolean filterable,
			Boolean variantAxis, Integer displayOrder, Boolean active, List<OptionDraft> options) {
	}

	public record OptionDraft(UUID id, String code, Map<String, String> label, Integer displayOrder, Boolean active) {
	}

	private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
	private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,59}");
	private static final Pattern CODE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,59}");
	private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})?");
	static final int MAX_ATTRIBUTES = 50;
	static final int MAX_OPTIONS = 100;

	/** Validates and builds the category. {@code existing} is the category being replaced (null on creation). */
	public Category toCategory(UUID id, Category existing, int defaultOrder) {
		var issues = new ArrayList<FieldIssue>();
		if (slug == null || slug.isBlank()) {
			issues.add(new FieldIssue("slug", "required"));
		} else if (slug.length() > 100) {
			issues.add(new FieldIssue("slug", "too_long"));
		} else if (!SLUG.matcher(slug).matches()) {
			issues.add(new FieldIssue("slug", "invalid"));
		}
		var names = texts("name", name, 200, true, issues);
		var descriptions = texts("description", description, 2000, false, issues);
		if (imageUrl != null && !imageUrl.isBlank() && (imageUrl.length() > 500 || !imageUrl.startsWith("https://"))) {
			issues.add(new FieldIssue("imageUrl", "invalid"));
		}
		if (displayOrder != null && (displayOrder < 0 || displayOrder > 100_000)) {
			issues.add(new FieldIssue("displayOrder", "invalid"));
		}
		var knownAttributes = new HashMap<UUID, AttributeDefinition>();
		if (existing != null) {
			existing.attributes().forEach(a -> knownAttributes.put(a.id(), a));
		}
		var built = new ArrayList<AttributeDefinition>();
		var list = attributes == null ? List.<AttributeDraft>of() : attributes;
		if (list.size() > MAX_ATTRIBUTES) {
			issues.add(new FieldIssue("attributes", "too_many"));
		} else {
			var keys = new HashSet<String>();
			var ids = new HashSet<UUID>();
			for (int i = 0; i < list.size(); i++) {
				var a = list.get(i);
				var at = "attributes[" + i + "]";
				if (a == null) {
					issues.add(new FieldIssue(at, "required"));
					continue;
				}
				var attribute = attribute(a, at, knownAttributes, keys, ids, i, issues);
				if (attribute != null) {
					built.add(attribute);
				}
			}
		}
		if (!issues.isEmpty()) {
			throw new FieldValidationException(issues);
		}
		return new Category(id, slug.strip(), names, descriptions, imageUrl == null || imageUrl.isBlank() ? null : imageUrl.strip(),
				active == null || active, displayOrder == null ? defaultOrder : displayOrder, built);
	}

	private static AttributeDefinition attribute(AttributeDraft a, String at, Map<UUID, AttributeDefinition> known, HashSet<String> keys, HashSet<UUID> ids, int index,
			List<FieldIssue> issues) {
		int before = issues.size();
		UUID id = a.id();
		AttributeDefinition previous = null;
		if (id != null) {
			previous = known.get(id);
			if (previous == null) {
				issues.add(new FieldIssue(at + ".id", "unknown"));
			} else if (!ids.add(id)) {
				issues.add(new FieldIssue(at + ".id", "duplicate"));
			}
		} else {
			id = UUID.randomUUID();
		}
		if (a.key() == null || a.key().isBlank()) {
			issues.add(new FieldIssue(at + ".key", "required"));
		} else if (!KEY.matcher(a.key()).matches()) {
			issues.add(new FieldIssue(at + ".key", "invalid"));
		} else if (!keys.add(a.key())) {
			issues.add(new FieldIssue(at + ".key", "duplicate"));
		}
		var label = texts(at + ".label", a.label(), 200, true, issues);
		AttributeType type = null;
		if (a.type() == null || a.type().isBlank()) {
			issues.add(new FieldIssue(at + ".type", "required"));
		} else {
			try {
				type = AttributeType.valueOf(a.type().strip().toUpperCase(java.util.Locale.ROOT));
			} catch (IllegalArgumentException e) {
				issues.add(new FieldIssue(at + ".type", "invalid"));
			}
		}
		if (a.unit() != null && a.unit().length() > 20) {
			issues.add(new FieldIssue(at + ".unit", "too_long"));
		}
		boolean filterable = Boolean.TRUE.equals(a.filterable());
		boolean variantAxis = Boolean.TRUE.equals(a.variantAxis());
		var options = new ArrayList<AttributeOption>();
		var drafts = a.options() == null ? List.<OptionDraft>of() : a.options();
		if (type != null && type != AttributeType.CHOICE) {
			if (filterable || variantAxis) {
				issues.add(new FieldIssue(at + ".filterable", "choice_only"));
			}
			if (!drafts.isEmpty()) {
				issues.add(new FieldIssue(at + ".options", "choice_only"));
			}
		} else if (type == AttributeType.CHOICE) {
			if (drafts.isEmpty()) {
				issues.add(new FieldIssue(at + ".options", "required"));
			} else if (drafts.size() > MAX_OPTIONS) {
				issues.add(new FieldIssue(at + ".options", "too_many"));
			} else {
				var codes = new HashSet<String>();
				var optionIds = new HashSet<UUID>();
				for (int j = 0; j < drafts.size(); j++) {
					var o = drafts.get(j);
					var ot = at + ".options[" + j + "]";
					if (o == null) {
						issues.add(new FieldIssue(ot, "required"));
						continue;
					}
					UUID optionId = o.id();
					if (optionId != null) {
						if (previous == null || previous.option(optionId) == null) {
							issues.add(new FieldIssue(ot + ".id", "unknown"));
						} else if (!optionIds.add(optionId)) {
							issues.add(new FieldIssue(ot + ".id", "duplicate"));
						}
					} else {
						optionId = UUID.randomUUID();
					}
					if (o.code() == null || o.code().isBlank()) {
						issues.add(new FieldIssue(ot + ".code", "required"));
					} else if (!CODE.matcher(o.code()).matches()) {
						issues.add(new FieldIssue(ot + ".code", "invalid"));
					} else if (!codes.add(o.code())) {
						issues.add(new FieldIssue(ot + ".code", "duplicate"));
					}
					var optionLabel = texts(ot + ".label", o.label(), 200, true, issues);
					if (issues.size() == before && o.code() != null) {
						options.add(new AttributeOption(optionId, o.code(), optionLabel, o.displayOrder() == null ? j : o.displayOrder(), o.active() == null || o.active()));
					}
				}
			}
		}
		if (issues.size() > before || type == null) {
			return null;
		}
		return new AttributeDefinition(id, a.key(), label, type, a.unit() == null || a.unit().isBlank() ? null : a.unit().strip(), Boolean.TRUE.equals(a.required()),
				filterable, variantAxis, a.displayOrder() == null ? index : a.displayOrder(), a.active() == null || a.active(), options);
	}

	/** Localized text: language codes like {@code fr}, {@code ht}; at least one non-blank value when {@code required}; bounded length. */
	static LocalizedText texts(String field, Map<String, String> value, int max, boolean required, List<FieldIssue> issues) {
		var clean = new LinkedHashMap<String, String>();
		if (value != null) {
			for (var e : value.entrySet()) {
				if (e.getKey() == null || !LANGUAGE.matcher(e.getKey()).matches()) {
					issues.add(new FieldIssue(field, "invalid_language"));
					continue;
				}
				if (e.getValue() == null || e.getValue().isBlank()) {
					continue;
				}
				if (e.getValue().strip().length() > max) {
					issues.add(new FieldIssue(field + "." + e.getKey(), "too_long"));
					continue;
				}
				clean.put(e.getKey(), e.getValue().strip());
			}
		}
		if (required && clean.isEmpty() && issues.stream().noneMatch(i -> i.field().startsWith(field))) {
			issues.add(new FieldIssue(field, "required"));
		}
		return new LocalizedText(clean);
	}
}
