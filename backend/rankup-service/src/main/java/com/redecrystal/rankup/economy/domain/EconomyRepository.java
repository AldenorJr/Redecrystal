package com.redecrystal.rankup.economy.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Economy persistence. The additive/conditional mutations are atomic SQL updates
 * — not read-modify-write — so concurrent miners/harvesters can't lose deltas and
 * a debit can never overdraw. {@code clearAutomatically} flushes the persistence
 * context so the caller's follow-up {@code findById} reads the mutated row.
 */
public interface EconomyRepository extends JpaRepository<EconomyEntity, UUID> {

    @Modifying(clearAutomatically = true)
    @Query("UPDATE EconomyEntity e SET e.money = e.money + :delta, e.updatedAt = CURRENT_TIMESTAMP"
            + " WHERE e.playerUuid = :uuid")
    int addMoney(@Param("uuid") UUID uuid, @Param("delta") long delta);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE EconomyEntity e SET e.tokens = e.tokens + :delta, e.updatedAt = CURRENT_TIMESTAMP"
            + " WHERE e.playerUuid = :uuid")
    int addTokens(@Param("uuid") UUID uuid, @Param("delta") long delta);

    /** Conditional debit; returns 0 rows (no-op) when the balance can't cover {@code cost}. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE EconomyEntity e SET e.money = e.money - :cost, e.version = e.version + 1,"
            + " e.updatedAt = CURRENT_TIMESTAMP WHERE e.playerUuid = :uuid AND e.money >= :cost")
    int debit(@Param("uuid") UUID uuid, @Param("cost") long cost);
}
