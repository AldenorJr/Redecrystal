package com.redecrystal.network.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * A self-registered server instance (one row of {@code server_registry}). New
 * instances (lobby-04, login-03, ...) appear here automatically on startup with
 * no code change — this is the Service Discovery backbone the proxy reads.
 */
@Entity
@Table(name = "server_registry")
public class ServerInstance {

    @Id
    @Column(name = "server_id", length = 64)
    private String serverId;

    @Column(name = "type", length = 32, nullable = false)
    private String type;

    @Column(name = "host", length = 255, nullable = false)
    private String host;

    @Column(name = "port", nullable = false)
    private int port;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private ServerStatus status;

    @Column(name = "max_players", nullable = false)
    private int maxPlayers;

    @Column(name = "online_players", nullable = false)
    private int onlinePlayers;

    @Column(name = "registered_at", nullable = false)
    private OffsetDateTime registeredAt;

    @Column(name = "last_heartbeat", nullable = false)
    private OffsetDateTime lastHeartbeat;

    protected ServerInstance() {
        // for JPA
    }

    public ServerInstance(String serverId, String type, String host, int port, int maxPlayers) {
        this.serverId = serverId;
        this.type = type;
        this.host = host;
        this.port = port;
        this.maxPlayers = maxPlayers;
        this.status = ServerStatus.ONLINE;
        this.onlinePlayers = 0;
        OffsetDateTime now = OffsetDateTime.now();
        this.registeredAt = now;
        this.lastHeartbeat = now;
    }

    /** Apply (re)registration details for an existing instance. */
    public void reregister(String type, String host, int port, int maxPlayers) {
        this.type = type;
        this.host = host;
        this.port = port;
        this.maxPlayers = maxPlayers;
        this.status = ServerStatus.ONLINE;
        this.lastHeartbeat = OffsetDateTime.now();
    }

    public void heartbeat(int onlinePlayers, ServerStatus status) {
        this.onlinePlayers = onlinePlayers;
        if (status != null) {
            this.status = status;
        }
        this.lastHeartbeat = OffsetDateTime.now();
    }

    public void markOffline() {
        this.status = ServerStatus.OFFLINE;
        this.lastHeartbeat = OffsetDateTime.now();
    }

    public String getServerId() { return serverId; }
    public String getType() { return type; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public ServerStatus getStatus() { return status; }
    public int getMaxPlayers() { return maxPlayers; }
    public int getOnlinePlayers() { return onlinePlayers; }
    public OffsetDateTime getRegisteredAt() { return registeredAt; }
    public OffsetDateTime getLastHeartbeat() { return lastHeartbeat; }
}
