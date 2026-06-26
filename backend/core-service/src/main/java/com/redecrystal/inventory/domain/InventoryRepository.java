package com.redecrystal.inventory.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryRepository extends JpaRepository<InventoryEntity, InventoryId> {
}
