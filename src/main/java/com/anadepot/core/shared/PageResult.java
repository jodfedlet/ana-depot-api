package com.anadepot.core.shared;

import java.util.List;

public record PageResult<T>(List<T> items, int page, int size, long totalItems) {

	public int totalPages() {
		return (int) Math.ceil((double) totalItems / size);
	}
}
