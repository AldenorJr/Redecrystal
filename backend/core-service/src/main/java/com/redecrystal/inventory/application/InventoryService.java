package com.redecrystal.inventory.application;

import com.redecrystal.inventory.domain.InventoryEntity;
import com.redecrystal.inventory.domain.InventoryId;
import com.redecrystal.inventory.domain.InventoryRepository;
import com.redecrystal.shared.web.ConflictException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory persistence with optimistic locking. A save must present the version
 * it last read; if the stored version moved on (a concurrent save — e.g. a fast
 * server switch), the write is rejected with 409 instead of clobbering data.
 * Redis ({@code inventory:{uuid}:{type}}) is a write-through cache of the version.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryRepository repository;
    private final StringRedisTemplate redis;

    public InventoryService(InventoryRepository repository, StringRedisTemplate redis) {
        this.repository = repository;
        this.redis = redis;
    }

    public record Snapshot(String content, int version) {}

    @Transactional(readOnly = true)
    public Snapshot get(UUID uuid, String serverType) {
        Optional<InventoryEntity> found = repository.findById(new InventoryId(uuid, serverType));
        return found.map(e -> new Snapshot(e.getContent(), e.getVersion()))
                .orElse(new Snapshot("", 0));
    }

    /**
     * Save with optimistic locking. {@code expectedVersion} must match the
     * current stored version (or 0 when no row exists yet). Returns the new version.
     */
    @Transactional
    public int save(UUID uuid, String serverType, String content, int expectedVersion) {
        InventoryEntity entity = repository.findById(new InventoryId(uuid, serverType)).orElse(null);
        int current = entity == null ? 0 : entity.getVersion();
        if (current != expectedVersion) {
            throw new ConflictException("version mismatch for " + uuid + "/" + serverType
                    + ": expected " + expectedVersion + " but stored is " + current);
        }
        if (entity == null) {
            entity = new InventoryEntity(uuid, serverType, content);
        } else {
            entity.update(content);
        }
        entity = repository.save(entity);
        redis.opsForValue().set("inventory:" + uuid + ":" + serverType, String.valueOf(entity.getVersion()));
        log.debug("Saved inventory {}/{} -> v{}", uuid, serverType, entity.getVersion());
        return entity.getVersion();
    }
}
