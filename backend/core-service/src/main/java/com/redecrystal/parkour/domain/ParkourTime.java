package com.redecrystal.parkour.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A player's best parkour time (row of {@code parkour_times}). */
@Entity
@Table(name = "parkour_times")
public class ParkourTime {

    @Id
    @Column(name = "player_uuid")
    private UUID playerUuid;

    @Column(name = "username", length = 16)
    private String username;

    @Column(name = "best_time_ms", nullable = false)
    private long bestTimeMs;

    @Column(name = "achieved_at", nullable = false)
    private OffsetDateTime achievedAt;

    protected ParkourTime() {
        // for JPA
    }

    public ParkourTime(UUID playerUuid, String username, long bestTimeMs) {
        this.playerUuid = playerUuid;
        this.username = username;
        this.bestTimeMs = bestTimeMs;
        this.achievedAt = OffsetDateTime.now();
    }

    public void improve(String username, long bestTimeMs) {
        this.username = username;
        this.bestTimeMs = bestTimeMs;
        this.achievedAt = OffsetDateTime.now();
    }

    public UUID getPlayerUuid() { return playerUuid; }
    public String getUsername() { return username; }
    public long getBestTimeMs() { return bestTimeMs; }
}
