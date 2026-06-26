package com.redecrystal.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A stored inventory for one (player, server type). */
@Entity
@Table(name = "player_inventories")
@IdClass(InventoryId.class)
public class InventoryEntity {

    @Id
    @Column(name = "player_uuid")
    private UUID playerUuid;

    @Id
    @Column(name = "server_type", length = 32)
    private String serverType;

    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected InventoryEntity() {
        // for JPA
    }

    public InventoryEntity(UUID playerUuid, String serverType, String content) {
        this.playerUuid = playerUuid;
        this.serverType = serverType;
        this.content = content;
        this.version = 1;
        this.updatedAt = OffsetDateTime.now();
    }

    public void update(String content) {
        this.content = content;
        this.version += 1;
        this.updatedAt = OffsetDateTime.now();
    }

    public String getContent() { return content; }
    public int getVersion() { return version; }
}
