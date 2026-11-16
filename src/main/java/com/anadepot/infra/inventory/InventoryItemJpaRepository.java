package com.anadepot.infra.inventory;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface InventoryItemJpaRepository extends JpaRepository<InventoryItemEntity, UUID> {

	@Query("select i from InventoryItemEntity i where i.productId = :productId and i.variantId is null")
	Optional<InventoryItemEntity> findForProduct(UUID productId);

	@Query("select i from InventoryItemEntity i where i.variantId = :variantId")
	Optional<InventoryItemEntity> findForVariant(UUID variantId);

	/** SELECT ... FOR UPDATE: blocks other transactions that want the same stock position until this one ends. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from InventoryItemEntity i where i.productId = :productId and i.variantId is null")
	Optional<InventoryItemEntity> lockForProduct(UUID productId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from InventoryItemEntity i where i.variantId = :variantId")
	Optional<InventoryItemEntity> lockForVariant(UUID variantId);

	@Query("select i from InventoryItemEntity i where i.productId in :productIds")
	java.util.List<InventoryItemEntity> findAllForProducts(java.util.Collection<UUID> productIds);
}
