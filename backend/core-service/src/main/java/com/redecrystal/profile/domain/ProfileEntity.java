package com.redecrystal.profile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Player progression profile (row of {@code player_profiles}). */
@Entity
@Table(name = "player_profiles")
public class ProfileEntity {

    @Id
    @Column(name = "player_uuid")
    private UUID playerUuid;

    @Column(name = "username", length = 16)
    private String username;

    @Column(name = "rank", length = 32, nullable = false)
    private String rank;

    @Column(name = "level", nullable = false)
    private int level;

    @Column(name = "experience", nullable = false)
    private long experience;

    @Column(name = "coins", nullable = false)
    private long coins;

    @Column(name = "play_seconds", nullable = false)
    private long playSeconds;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ProfileEntity() {
        // for JPA
    }

    public ProfileEntity(UUID playerUuid, String username) {
        this.playerUuid = playerUuid;
        this.username = username;
        this.rank = "DEFAULT";
        this.level = 1;
        this.experience = 0;
        this.coins = 0;
        this.playSeconds = 0;
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Apply additive deltas (any may be zero) and bump level from experience. */
    public void addStats(long coinsDelta, long expDelta, long playSecondsDelta) {
        this.coins += coinsDelta;
        this.experience += expDelta;
        this.playSeconds += playSecondsDelta;
        this.level = (int) (1 + this.experience / 1000); // 1000 xp per level
        this.updatedAt = OffsetDateTime.now();
    }

    public void touchUsername(String name) {
        if (name != null && !name.equals(this.username)) {
            this.username = name;
            this.updatedAt = OffsetDateTime.now();
        }
    }

    public UUID getPlayerUuid() { return playerUuid; }
    public String getUsername() { return username; }
    public String getRank() { return rank; }
    public int getLevel() { return level; }
    public long getExperience() { return experience; }
    public long getCoins() { return coins; }
    public long getPlaySeconds() { return playSeconds; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
