package com.anadepot.infra.inventory;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface InventoryMovementJpaRepository extends JpaRepository<InventoryMovementEntity, UUID> {

	List<InventoryMovementEntity> findByInventoryItemIdOrderByCreatedAtAscIdAsc(UUID inventoryItemId);
}
