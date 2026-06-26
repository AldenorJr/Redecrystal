package com.redecrystal.history.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One chat message (row of {@code chat_messages}); global or private (/tell). */
@Entity
@Table(name = "chat_messages")
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_uuid", nullable = false)
    private UUID playerUuid;

    @Column(name = "username", length = 16)
    private String username;

    @Column(name = "server", length = 64)
    private String server;

    @Column(name = "scope", length = 16, nullable = false)
    private String scope;

    @Column(name = "target", length = 16)
    private String target;

    @Column(name = "message", nullable = false)
    private String message;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ChatMessageEntity() {
        // for JPA
    }

    public ChatMessageEntity(UUID playerUuid, String username, String server,
                             String scope, String target, String message) {
        this.playerUuid = playerUuid;
        this.username = username;
        this.server = server;
        this.scope = scope == null ? "global" : scope;
        this.target = target;
        this.message = message;
        this.createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public UUID getPlayerUuid() { return playerUuid; }
    public String getUsername() { return username; }
    public String getServer() { return server; }
    public String getScope() { return scope; }
    public String getTarget() { return target; }
    public String getMessage() { return message; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
