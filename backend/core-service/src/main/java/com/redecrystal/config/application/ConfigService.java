package com.redecrystal.config.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redecrystal.config.domain.NetworkConfig;
import com.redecrystal.config.domain.NetworkConfigRepository;
import com.redecrystal.shared.messaging.EventPublisher;
import com.redecrystal.shared.messaging.KafkaTopics;
import com.redecrystal.shared.web.NotFoundException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Config Service — single source of truth for network configuration.
 *
 * <p>Reads are served from a Redis cache ({@code config:<key>}) and fall back to
 * PostgreSQL on a miss. Writes go to PostgreSQL, refresh the Redis cache, and
 * publish a {@code config-updated} event so every server hot-reloads. Toggling
 * the {@code maintenance} flag additionally emits a maintenance event.
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);
    private static final String CACHE_PREFIX = "config:";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final NetworkConfigRepository repository;
    private final StringRedisTemplate redis;
    private final EventPublisher events;
    private final ObjectMapper objectMapper;

    public ConfigService(NetworkConfigRepository repository,
                         StringRedisTemplate redis,
                         EventPublisher events,
                         ObjectMapper objectMapper) {
        this.repository = repository;
        this.redis = redis;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<NetworkConfig> findAll() {
        return repository.findAll();
    }

    /** Read config by key, preferring the Redis cache. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String key) {
        String cached = redis.opsForValue().get(CACHE_PREFIX + key);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, MAP_TYPE);
            } catch (Exception e) {
                log.warn("Corrupt cache for {}, falling back to DB", key, e);
            }
        }
        NetworkConfig config = repository.findById(key)
                .orElseThrow(() -> new NotFoundException("config not found: " + key));
        cache(key, config.getConfig());
        return config.getConfig();
    }

    public NetworkConfig getEntity(String key) {
        return repository.findById(key)
                .orElseThrow(() -> new NotFoundException("config not found: " + key));
    }

    /**
     * Upsert config for {@code key}: persist, refresh cache, and publish events.
     * Returns the updated entity.
     */
    @Transactional
    public NetworkConfig upsert(String key, Map<String, Object> newConfig) {
        NetworkConfig entity = repository.findById(key).orElse(null);
        boolean wasMaintenance = entity != null && entity.isMaintenance();

        if (entity == null) {
            entity = new NetworkConfig(key, newConfig);
        } else {
            entity.update(newConfig);
        }
        entity = repository.save(entity);

        cache(key, entity.getConfig());

        events.publish(KafkaTopics.CONFIG_UPDATED, key, Map.of(
                "configKey", key,
                "version", entity.getVersion(),
                "config", entity.getConfig()));

        boolean nowMaintenance = entity.isMaintenance();
        if (nowMaintenance != wasMaintenance) {
            String topic = nowMaintenance
                    ? KafkaTopics.MAINTENANCE_ENABLED
                    : KafkaTopics.MAINTENANCE_DISABLED;
            events.publish(topic, key, Map.of("configKey", key));
        }

        log.info("Config '{}' updated to version {}", key, entity.getVersion());
        return entity;
    }

    private void cache(String key, Map<String, Object> config) {
        try {
            redis.opsForValue().set(CACHE_PREFIX + key, objectMapper.writeValueAsString(config));
        } catch (Exception e) {
            log.warn("Failed to refresh Redis cache for {}", key, e);
        }
    }
}
