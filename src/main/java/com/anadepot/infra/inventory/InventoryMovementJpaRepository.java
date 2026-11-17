package com.anadepot.infra.inventory;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface InventoryMovementJpaRepository extends JpaRepository<InventoryMovementEntity, UUID> {

	org.springframework.data.domain.Page<InventoryMovementEntity> findByInventoryItemId(UUID inventoryItemId, org.springframework.data.domain.Pageable pageable);

	List<InventoryMovementEntity> findByInventoryItemIdOrderByCreatedAtAscIdAsc(UUID inventoryItemId);
}
