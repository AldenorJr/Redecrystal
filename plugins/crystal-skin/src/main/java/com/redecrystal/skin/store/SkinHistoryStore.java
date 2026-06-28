package com.redecrystal.skin.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.BackendHttpClient;
import com.redecrystal.core.http.InventoryData;
import com.redecrystal.core.json.Json;
import com.redecrystal.skin.skin.SkinHistory;
import com.redecrystal.skin.skin.SkinTexture;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Per-player skin history, persisted by re-using the inventory blob store under a
 * dedicated {@code skin-history} server-type (same trick the lobby cosmetics use —
 * no new backend endpoint). The in-memory map is the source of truth for the open
 * GUI; the backend is written through asynchronously with optimistic locking.
 */
public final class SkinHistoryStore {

    /** Re-uses the inventory blob store as a per-player skin-history row. */
    private static final String SKIN_HISTORY_TYPE = "skin-history";
    private static final TypeReference<List<SkinTexture>> LIST_TYPE = new TypeReference<>() { };

    private final JavaPlugin plugin;
    private final CrystalCore crystal;

    private final Map<UUID, SkinHistory> cache = new ConcurrentHashMap<>();
    /** Optimistic-lock version last seen for each player's history row. */
    private final Map<UUID, Integer> version = new ConcurrentHashMap<>();

    public SkinHistoryStore(JavaPlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
    }

    /** The cached history (empty until {@link #preload} finishes, never null). */
    public SkinHistory get(UUID uuid) {
        return cache.getOrDefault(uuid, SkinHistory.empty());
    }

    /**
     * Blocking history fetch for the async pre-login path (where doing HTTP is
     * fine). Warms the in-memory cache so the GUI is ready, and returns the
     * history (empty on any failure — never throws).
     */
    public SkinHistory fetchBlocking(UUID uuid) {
        try {
            InventoryData data = crystal.backend().getInventory(uuid.toString(), SKIN_HISTORY_TYPE);
            version.put(uuid, data.version());
            SkinHistory history = deserialize(uuid, data);
            cache.put(uuid, history);
            return history;
        } catch (Exception e) {
            cache.putIfAbsent(uuid, SkinHistory.empty()); // backend down → clean session
            return SkinHistory.empty();
        }
    }

    /** Fetch the player's history into the cache on join. Runs off-thread. */
    public void preload(UUID uuid) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            InventoryData data;
            try {
                data = crystal.backend().getInventory(uuid.toString(), SKIN_HISTORY_TYPE);
            } catch (Exception e) {
                cache.putIfAbsent(uuid, SkinHistory.empty()); // backend down → clean session
                return;
            }
            version.put(uuid, data.version());
            cache.put(uuid, deserialize(uuid, data));
        });
    }

    /** Push a freshly-applied texture to the top of the history and persist it. */
    public void record(UUID uuid, SkinTexture texture) {
        SkinHistory updated = get(uuid).withMostRecent(texture);
        cache.put(uuid, updated);
        save(uuid, updated);
    }

    /** Wipe the player's history (the GUI's "Limpar histórico" button). */
    public void clear(UUID uuid) {
        SkinHistory empty = SkinHistory.empty();
        cache.put(uuid, empty);
        save(uuid, empty);
    }

    /** Drop the cached state on quit (limpe o que cria). */
    public void evict(UUID uuid) {
        cache.remove(uuid);
        version.remove(uuid);
    }

    private void save(UUID uuid, SkinHistory history) {
        final String content;
        try {
            content = Json.MAPPER.writeValueAsString(history.entries());
        } catch (Exception e) {
            plugin.getLogger().warning("Falha ao serializar histórico de skin de " + uuid);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int v = version.getOrDefault(uuid, 0);
            try {
                version.put(uuid, crystal.backend().saveInventory(uuid.toString(), SKIN_HISTORY_TYPE, content, v));
            } catch (BackendHttpClient.BackendException ex) {
                // Version drifted (another save raced us) → refetch and retry once.
                try {
                    InventoryData fresh = crystal.backend().getInventory(uuid.toString(), SKIN_HISTORY_TYPE);
                    version.put(uuid,
                            crystal.backend().saveInventory(uuid.toString(), SKIN_HISTORY_TYPE, content, fresh.version()));
                } catch (Exception ignored) {
                    // give up silently — the cache still holds the latest in-session state
                }
            } catch (Exception ignored) {
                // backend down → keep the in-memory history; nothing crashes
            }
        });
    }

    private SkinHistory deserialize(UUID uuid, InventoryData data) {
        if (data.isEmpty()) {
            return SkinHistory.empty();
        }
        try {
            return new SkinHistory(Json.MAPPER.readValue(data.content(), LIST_TYPE));
        } catch (Exception e) {
            plugin.getLogger().warning("Histórico de skin inválido para " + uuid + ": " + e.getMessage());
            return SkinHistory.empty();
        }
    }
}
