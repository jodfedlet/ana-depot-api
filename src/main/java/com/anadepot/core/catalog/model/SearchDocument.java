package com.anadepot.core.catalog.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The text of one product as indexed, already normalised (accent-folded, lower case, letters/digits only) and split by weight:
 * A = names in every language; B = SKU, variant SKUs, brand, category name; C = descriptions; D = attribute option labels and text values.
 * Built here (pure Java) so that the index is maintained by the catalog use cases, in the same transaction as the change that affects it.
 */
public record SearchDocument(String names, String references, String description, String attributes) {

	public static SearchDocument of(Product p, Category category) {
		var attributes = new ArrayList<String>();
		for (var value : p.attributes()) {
			if (value.text() != null) {
				attributes.add(value.text());
			}
			if (value.number() != null) {
				attributes.add(value.number().toPlainString());
			}
			if (value.optionId() != null && category != null) {
				var def = category.attribute(value.attributeId());
				var option = def == null ? null : def.option(value.optionId());
				if (option != null) {
					attributes.addAll(option.label().values().values());
				}
			}
		}
		var references = new ArrayList<String>();
		references.add(p.sku() == null ? "" : p.sku());
		p.variants().forEach(v -> {
			references.add(v.sku());
			if (category != null) {
				v.axisValues().forEach((attr, opt) -> {
					var def = category.attribute(attr);
					var option = def == null ? null : def.option(opt);
					if (option != null) {
						attributes.addAll(option.label().values().values());
					}
				});
			}
		});
		references.add(p.brand() == null ? "" : p.brand());
		if (category != null) {
			references.addAll(category.name().values().values());
		}
		return new SearchDocument(words(p.name().values().values()), words(references), words(p.description().values().values()), words(attributes));
	}

	private static String words(Iterable<String> parts) {
		var sb = new StringBuilder();
		for (var part : parts) {
			sb.append(SearchText.words(part)).append(' ');
		}
		return sb.toString().strip();
	}
}
