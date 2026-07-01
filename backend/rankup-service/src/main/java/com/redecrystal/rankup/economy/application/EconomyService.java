package com.redecrystal.rankup.economy.application;

import com.redecrystal.rankup.economy.domain.EconomyEntity;
import com.redecrystal.rankup.economy.domain.EconomyRepository;
import com.redecrystal.rankup.shared.messaging.EventPublisher;
import com.redecrystal.rankup.shared.messaging.KafkaTopics;
import com.redecrystal.rankup.shared.web.ConflictException;
import com.redecrystal.rankup.shared.web.InsufficientFundsException;
import com.redecrystal.rankup.shared.web.NotFoundException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Economy: PostgreSQL is the source of truth, Redis ({@code economy:{uuid}}) the
 * write-through hot cache and {@code leaderboard:money} the ranking. The three
 * write-paths — additive delta, conditional debit (422 when broke) and absolute
 * set (409 on a stale version) — are the reason this context exists. Additive and
 * debit are atomic SQL; only the admin set uses the optimistic lock. Every
 * mutation re-reads, refreshes the cache (fail-open) and emits a Kafka event.
 */
@Service
public class EconomyService {

    private static final Logger log = LoggerFactory.getLogger(EconomyService.class);

    private static final String CACHE_PREFIX = "economy:";
    private static final String MONEY_BOARD = "leaderboard:money";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final EconomyRepository repository;
    private final StringRedisTemplate redis;
    private final EventPublisher events;

    public EconomyService(EconomyRepository repository, StringRedisTemplate redis, EventPublisher events) {
        this.repository = repository;
        this.redis = redis;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public EconomyEntity get(UUID uuid) {
        return repository.findById(uuid)
                .orElseThrow(() -> new NotFoundException("economy not found: " + uuid));
    }

    /** Create a zeroed row if absent; cache and return the balance. */
    @Transactional
    public EconomyEntity ensure(UUID uuid) {
        EconomyEntity entity = repository.findById(uuid).orElse(null);
        if (entity == null) {
            entity = repository.save(new EconomyEntity(uuid));
        }
        cache(entity);
        return entity;
    }

    /** Additive Money delta (mining/harvest/kill/admin give). Never rejected for funds. */
    @Transactional
    public EconomyEntity addMoney(UUID uuid, long delta, String source) {
        ensure(uuid);
        repository.addMoney(uuid, delta);
        return afterMoneyChange(uuid, delta, source);
    }

    /** Additive Tokens delta. */
    @Transactional
    public EconomyEntity addTokens(UUID uuid, long delta, String source) {
        ensure(uuid);
        repository.addTokens(uuid, delta);
        EconomyEntity updated = reload(uuid);
        cache(updated);
        events.publish(KafkaTopics.TOKEN_UPDATED, uuid.toString(), Map.of(
                "uuid", uuid.toString(), "tokens", updated.tokens(),
                "delta", delta, "source", source == null ? "" : source));
        return updated;
    }

    /** Conditional debit; throws {@link InsufficientFundsException} (422) when broke. */
    @Transactional
    public EconomyEntity debit(UUID uuid, long cost, String reason) {
        ensure(uuid);
        if (repository.debit(uuid, cost) == 0) {
            throw new InsufficientFundsException("insufficient funds for " + uuid + ": need " + cost);
        }
        return afterMoneyChange(uuid, -cost, reason);
    }

    /** Atomic transfer; throws {@link InsufficientFundsException} (422) when the payer is broke. */
    @Transactional
    public EconomyEntity transfer(UUID from, UUID to, long amount) {
        ensure(from);
        ensure(to);
        if (repository.debit(from, amount) == 0) {
            throw new InsufficientFundsException("insufficient funds for transfer from " + from);
        }
        repository.addMoney(to, amount);
        afterMoneyChange(to, amount, "transfer");
        return afterMoneyChange(from, -amount, "transfer");
    }

    /** Absolute admin set with optimistic locking; {@link ConflictException} (409) if stale. */
    @Transactional
    public EconomyEntity set(UUID uuid, long money, long tokens, int expectedVersion) {
        EconomyEntity entity = repository.findById(uuid).orElseGet(() -> new EconomyEntity(uuid));
        if (entity.version() != expectedVersion) {
            throw new ConflictException("version mismatch for " + uuid
                    + ": expected " + expectedVersion + " but stored is " + entity.version());
        }
        entity.setBalance(money, tokens);
        entity = repository.save(entity);
        cache(entity);
        events.publish(KafkaTopics.MONEY_UPDATED, uuid.toString(), Map.of(
                "uuid", uuid.toString(), "money", entity.money(), "delta", 0L, "source", "set"));
        return entity;
    }

    /** Re-read after a Money mutation, refresh the cache, emit {@code money-updated}. */
    private EconomyEntity afterMoneyChange(UUID uuid, long delta, String source) {
        EconomyEntity updated = reload(uuid);
        cache(updated);
        events.publish(KafkaTopics.MONEY_UPDATED, uuid.toString(), Map.of(
                "uuid", uuid.toString(), "money", updated.money(),
                "delta", delta, "source", source == null ? "" : source));
        return updated;
    }

    private EconomyEntity reload(UUID uuid) {
        return repository.findById(uuid)
                .orElseThrow(() -> new NotFoundException("economy vanished mid-update: " + uuid));
    }

    /** Write-through to Redis: hot balance hash + money leaderboard. Fail-open. */
    private void cache(EconomyEntity e) {
        try {
            String key = CACHE_PREFIX + e.playerUuid();
            redis.opsForHash().putAll(key, Map.of(
                    "money", String.valueOf(e.money()),
                    "tokens", String.valueOf(e.tokens()),
                    "version", String.valueOf(e.version())));
            redis.expire(key, CACHE_TTL);
            redis.opsForZSet().add(MONEY_BOARD, e.playerUuid().toString(), (double) e.money());
        } catch (Exception ex) {
            log.warn("Failed to cache economy {}", e.playerUuid(), ex);
        }
    }
}
