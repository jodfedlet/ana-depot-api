package com.anadepot.infra.web;

import com.anadepot.core.shared.PageResult;
import java.util.List;
import java.util.function.Function;

/** The paged envelope of every staff list: {@code {items, page, size, totalItems, totalPages}}. */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

	public static <S, T> PageResponse<T> of(PageResult<S> result, Function<S, T> mapper) {
		return new PageResponse<>(result.items().stream().map(mapper).toList(), result.page(), result.size(), result.totalItems(), result.totalPages());
	}
}
