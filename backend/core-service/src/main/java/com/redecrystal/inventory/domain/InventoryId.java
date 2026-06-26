package com.redecrystal.inventory.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key for {@link InventoryEntity}: (player, server type). */
public class InventoryId implements Serializable {

    private UUID playerUuid;
    private String serverType;

    public InventoryId() {
    }

    public InventoryId(UUID playerUuid, String serverType) {
        this.playerUuid = playerUuid;
        this.serverType = serverType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof InventoryId that)) {
            return false;
        }
        return Objects.equals(playerUuid, that.playerUuid) && Objects.equals(serverType, that.serverType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerUuid, serverType);
    }
}
