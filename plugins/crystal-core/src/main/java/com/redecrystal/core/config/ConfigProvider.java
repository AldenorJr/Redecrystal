package com.redecrystal.core.config;

import com.redecrystal.core.CrystalLogger;
import com.redecrystal.core.event.EventBus;
import com.redecrystal.core.http.BackendHttpClient;
import com.redecrystal.core.http.RemoteConfig;
import com.redecrystal.core.json.Json;
import com.redecrystal.core.messaging.KafkaTopics;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Client-side configuration with hot reload. Fetches config from the backend
 * (via the gateway) and caches it locally; when a {@code config-updated} event
 * arrives, the cache is refreshed and registered listeners are notified — no
 * restart required.
 *
 * <p>Fails open: if the backend is unreachable, reads return the last known
 * value (or empty), so a backend blip never takes a server down.
 */
public final class ConfigProvider {

    private static final CrystalLogger log = CrystalLogger.of(ConfigProvider.class);

    private final BackendHttpClient backend;
    private final Map<String, RemoteConfig> cache = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<RemoteConfig>>> listeners = new ConcurrentHashMap<>();

    public ConfigProvider(BackendHttpClient backend, EventBus eventBus) {
        this.backend = backend;
        eventBus.on(KafkaTopics.CONFIG_UPDATED, this::onConfigUpdated);
    }

    /** Get a config by key, fetching + caching on a miss. Fails open to last-known. */
    public RemoteConfig get(String key) {
        RemoteConfig cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            RemoteConfig fetched = backend.getConfig(key);
            cache.put(key, fetched);
            return fetched;
        } catch (Exception e) {
            log.warn("Could not fetch config '{}', serving empty: {}", key, e.toString());
            return new RemoteConfig(key, 0, Map.of());
        }
    }

    /** Pre-load configs at bootstrap so the first read is instant. */
    public void preload(String... keys) {
        for (String key : keys) {
            get(key);
        }
    }

    /** Register a listener fired whenever {@code key}'s config changes. */
    public void onChange(String key, Consumer<RemoteConfig> listener) {
        listeners.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    @SuppressWarnings("unchecked")
    private void onConfigUpdated(com.redecrystal.core.messaging.EventEnvelope event) {
        String key = event.get("configKey");
        if (key == null) {
            return;
        }
        Object cfg = event.payload().get("config");
        int version = event.payload().get("version") instanceof Number n ? n.intValue() : 0;
        Map<String, Object> map = Json.MAPPER.convertValue(cfg, Map.class);
        RemoteConfig updated = new RemoteConfig(key, version, map);
        cache.put(key, updated);
        log.info("Hot-reloaded config '{}' to version {}", key, version);

        List<Consumer<RemoteConfig>> ls = listeners.get(key);
        if (ls != null) {
            for (Consumer<RemoteConfig> l : ls) {
                try {
                    l.accept(updated);
                } catch (Exception e) {
                    log.error("Config listener for " + key + " failed", e);
                }
            }
        }
    }
}
