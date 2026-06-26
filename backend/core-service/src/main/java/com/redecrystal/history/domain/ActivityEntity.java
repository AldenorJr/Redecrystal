package com.redecrystal.history.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One player activity event (row of {@code player_activity}). */
@Entity
@Table(name = "player_activity")
public class ActivityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_uuid", nullable = false)
    private UUID playerUuid;

    @Column(name = "username", length = 16)
    private String username;

    @Column(name = "type", length = 32, nullable = false)
    private String type;

    @Column(name = "detail")
    private String detail;

    @Column(name = "server", length = 64)
    private String server;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ActivityEntity() {
        // for JPA
    }

    public ActivityEntity(UUID playerUuid, String username, String type, String detail, String server) {
        this.playerUuid = playerUuid;
        this.username = username;
        this.type = type;
        this.detail = detail;
        this.server = server;
        this.createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public UUID getPlayerUuid() { return playerUuid; }
    public String getUsername() { return username; }
    public String getType() { return type; }
    public String getDetail() { return detail; }
    public String getServer() { return server; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
