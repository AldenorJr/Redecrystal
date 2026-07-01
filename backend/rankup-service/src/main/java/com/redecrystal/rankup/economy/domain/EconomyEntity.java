package com.redecrystal.rankup.economy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A player's RankUP balance (row of {@code player_economy}): Money for
 * progression, Tokens for cosmetics. {@code version} is bumped only by absolute
 * (admin) sets so an optimistic lock can reject stale overwrites; additive deltas
 * and conditional debits are atomic SQL and don't touch it here. Field access
 * (@Id on the field), so accessors are named record-style.
 */
@Entity
@Table(name = "player_economy")
public class EconomyEntity {

    @Id
    @Column(name = "player_uuid")
    private UUID playerUuid;

    @Column(name = "money", nullable = false)
    private long money;

    @Column(name = "tokens", nullable = false)
    private long tokens;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected EconomyEntity() {
        // for JPA
    }

    /** Fresh, zeroed balance for a player seen for the first time. */
    public EconomyEntity(UUID playerUuid) {
        this.playerUuid = playerUuid;
        this.money = 0;
        this.tokens = 0;
        this.version = 0;
        this.updatedAt = OffsetDateTime.now();
    }

    /** Explicit-state constructor (tests / absolute set reconstruction). */
    public EconomyEntity(UUID playerUuid, long money, long tokens, int version) {
        this.playerUuid = playerUuid;
        this.money = money;
        this.tokens = tokens;
        this.version = version;
        this.updatedAt = OffsetDateTime.now();
    }

    /** Absolute admin set: overwrite the balance and bump the optimistic-lock version. */
    public void setBalance(long money, long tokens) {
        this.money = money;
        this.tokens = tokens;
        this.version++;
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID playerUuid() { return playerUuid; }
    public long money() { return money; }
    public long tokens() { return tokens; }
    public int version() { return version; }
    public OffsetDateTime updatedAt() { return updatedAt; }
}
