package com.anadepot.infra.inventory;

import com.anadepot.core.inventory.model.InventoryItem;
import com.anadepot.core.inventory.model.StockMovement;
import com.anadepot.core.inventory.repository.InventoryRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
class InventoryRepositoryAdapter implements InventoryRepository {

	private final InventoryItemJpaRepository items;
	private final InventoryMovementJpaRepository movements;
	private final Clock clock;
	@jakarta.persistence.PersistenceContext
	private jakarta.persistence.EntityManager em;

	InventoryRepositoryAdapter(InventoryItemJpaRepository items, InventoryMovementJpaRepository movements, Clock clock) {
		this.items = items;
		this.movements = movements;
		this.clock = clock;
	}

	@Override
	public InventoryItem save(InventoryItem item) {
		var now = clock.instant();
		var e = items.findById(item.id()).orElseGet(() -> {
			var n = new InventoryItemEntity();
			n.id = item.id();
			n.createdAt = now;
			return n;
		});
		e.productId = item.productId();
		e.variantId = item.variantId();
		e.tracked = item.tracked();
		e.lowStockThreshold = item.lowStockThreshold();
		e.onHand = item.onHand();
		e.reserved = item.reserved();
		e.updatedAt = now;
		return toDomain(items.saveAndFlush(e));
	}

	@Override
	public Optional<InventoryItem> findById(UUID id) {
		return items.findById(id).map(InventoryRepositoryAdapter::toDomain);
	}

	@Override
	public Optional<Meta> meta(UUID id) {
		return items.findById(id).map(e -> new Meta(e.version == null ? 0 : e.version, e.createdAt, e.updatedAt));
	}

	@Override
	public com.anadepot.core.shared.PageResult<InventoryItem> findPage(com.anadepot.core.inventory.model.InventoryQuery q, com.anadepot.core.shared.PageRequest page) {
		var from = new StringBuilder(" from InventoryItemEntity i join ProductEntity p on p.id = i.productId left join ProductVariantEntity v on v.id = i.variantId where 1 = 1");
		var params = new java.util.LinkedHashMap<String, Object>();
		if (q.productId() != null) {
			from.append(" and i.productId = :productId");
			params.put("productId", q.productId());
		}
		if (q.categoryId() != null) {
			from.append(" and p.categoryId = :categoryId");
			params.put("categoryId", q.categoryId());
		}
		if (q.text() != null) {
			var plain = q.text().toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
			var folded = com.anadepot.core.catalog.model.SearchText.normalize(q.text()).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
			from.append(" and (lower(p.sku) like :ref escape '\\' or lower(v.sku) like :ref escape '\\' or p.sortName like :name escape '\\')");
			params.put("ref", plain + "%");
			params.put("name", "%" + folded + "%");
		}
		if (q.state() != null) {
			switch (q.state()) {
				case UNTRACKED -> from.append(" and i.tracked = false");
				case OUT -> from.append(" and i.tracked = true and i.onHand - i.reserved <= 0");
				case LOW -> from.append(" and i.tracked = true and i.onHand - i.reserved > 0 and i.lowStockThreshold is not null and i.onHand - i.reserved <= i.lowStockThreshold");
				case OK -> from.append(" and i.tracked = true and i.onHand - i.reserved > 0 and (i.lowStockThreshold is null or i.onHand - i.reserved > i.lowStockThreshold)");
			}
		}
		var order = q.sort() == com.anadepot.core.inventory.model.InventoryQuery.Sort.AVAILABLE_ASC
				? " order by case when i.tracked = true then 0 else 1 end, i.onHand - i.reserved, i.id" : " order by i.updatedAt desc, i.id";
		var query = em.createQuery("select i" + from + order, InventoryItemEntity.class);
		var count = em.createQuery("select count(i)" + from, Long.class);
		params.forEach((k, v) -> {
			query.setParameter(k, v);
			count.setParameter(k, v);
		});
		query.setFirstResult(page.page() * page.size()).setMaxResults(page.size());
		return new com.anadepot.core.shared.PageResult<>(query.getResultList().stream().map(InventoryRepositoryAdapter::toDomain).toList(), page.page(), page.size(), count.getSingleResult());
	}

	@Override
	public com.anadepot.core.shared.PageResult<StockMovement> movementsPage(UUID inventoryItemId, com.anadepot.core.shared.PageRequest page) {
		var pageable = org.springframework.data.domain.PageRequest.of(page.page(), page.size(), org.springframework.data.domain.Sort.by("createdAt").descending().and(org.springframework.data.domain.Sort.by("id")));
		var result = movements.findByInventoryItemId(inventoryItemId, pageable);
		return new com.anadepot.core.shared.PageResult<>(result.getContent().stream()
				.map(e -> new StockMovement(e.id, e.inventoryItemId, e.movementType, e.delta, e.balanceAfter, e.reason, e.reservationId, e.note, e.createdAt, e.recordedBy)).toList(),
				page.page(), page.size(), result.getTotalElements());
	}

	@Override
	public Optional<InventoryItem> find(UUID productId, UUID variantId) {
		return (variantId == null ? items.findForProduct(productId) : items.findForVariant(variantId)).map(InventoryRepositoryAdapter::toDomain);
	}

	@Override
	public Optional<InventoryItem> findForUpdate(UUID productId, UUID variantId) {
		return (variantId == null ? items.lockForProduct(productId) : items.lockForVariant(variantId)).map(InventoryRepositoryAdapter::toDomain);
	}

	@Override
	public java.util.Map<UUID, List<InventoryItem>> findAllForProducts(java.util.Collection<UUID> productIds) {
		var result = new java.util.HashMap<UUID, List<InventoryItem>>();
		if (productIds.isEmpty()) {
			return result;
		}
		items.findAllForProducts(productIds).forEach(e -> result.computeIfAbsent(e.productId, k -> new java.util.ArrayList<>()).add(toDomain(e)));
		return result;
	}

	@Override
	public StockMovement saveMovement(StockMovement m) {
		var e = new InventoryMovementEntity();
		e.id = m.id();
		e.inventoryItemId = m.inventoryItemId();
		e.movementType = m.type();
		e.delta = m.delta();
		e.balanceAfter = m.balanceAfter();
		e.reason = m.reason();
		e.reservationId = m.reservationId();
		e.note = m.note();
		e.createdAt = m.at();
		e.recordedBy = m.recordedBy();
		movements.saveAndFlush(e);
		return m;
	}

	@Override
	public List<StockMovement> movements(UUID inventoryItemId) {
		return movements.findByInventoryItemIdOrderByCreatedAtAscIdAsc(inventoryItemId).stream()
				.map(e -> new StockMovement(e.id, e.inventoryItemId, e.movementType, e.delta, e.balanceAfter, e.reason, e.reservationId, e.note, e.createdAt, e.recordedBy)).toList();
	}

	private static InventoryItem toDomain(InventoryItemEntity e) {
		return new InventoryItem(e.id, e.productId, e.variantId, e.tracked, e.lowStockThreshold, e.onHand, e.reserved);
	}
}
