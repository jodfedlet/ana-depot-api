package com.anadepot.infra.catalog;

import com.anadepot.core.catalog.service.CatalogService;
import com.anadepot.infra.security.Actors;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Staff-only catalog maintenance. */
@RestController
@RequestMapping("/api/v1/admin/catalog")
class AdminCatalogController {

	record ReindexResponse(int indexed) {
	}

	private final CatalogService catalog;

	AdminCatalogController(CatalogService catalog) {
		this.catalog = catalog;
	}

	/** Rebuilds the full-text index of every product (idempotent repair/backfill; audited; strictly rate limited). */
	@PostMapping("/search/reindex")
	ReindexResponse reindex(Authentication authentication) {
		return new ReindexResponse(catalog.reindexSearch(Actors.staff(authentication)));
	}
}
