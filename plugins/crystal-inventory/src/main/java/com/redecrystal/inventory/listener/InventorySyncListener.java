package com.redecrystal.inventory.listener;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.InventoryData;
import com.redecrystal.inventory.CrystalInventoryPlugin;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Loads a player's inventory on join and persists it on quit, namespaced by this
 * server's type. Join and quit share the per-player {@code versions} map (the
 * optimistic-lock version read on load is presented again on save), so both
 * handlers stay in one cohesive listener. Item (de)serialization uses Paper's
 * native byte format, Base64-encoded.
 */
public final class InventorySyncListener implements Listener {

    private final CrystalInventoryPlugin plugin;
    private final CrystalCore crystal;
    private final String serverType;
    /** Last version loaded/saved per player, for optimistic locking. */
    private final Map<UUID, Integer> versions = new ConcurrentHashMap<>();

    public InventorySyncListener(CrystalInventoryPlugin plugin, CrystalCore crystal, String serverType) {
        this.plugin = plugin;
        this.crystal = crystal;
        this.serverType = serverType;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                InventoryData data = crystal.backend().getInventory(uuid.toString(), serverType);
                versions.put(uuid, data.version());
                if (data.isEmpty()) {
                    return;
                }
                ItemStack[] items = ItemStack.deserializeItemsFromBytes(
                        Base64.getDecoder().decode(data.content()));
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        player.getInventory().setContents(items);
                        plugin.getLogger().info("Loaded inventory v" + data.version() + " for " + player.getName());
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().warning("Inventory load failed for " + player.getName() + ": " + e);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        // Snapshot on the main thread (event thread); persist off-thread.
        ItemStack[] contents = sanitize(player.getInventory().getContents());
        int version = versions.getOrDefault(uuid, 0);
        String b64 = Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(contents));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                int newVersion = crystal.backend().saveInventory(uuid.toString(), serverType, b64, version);
                plugin.getLogger().info("Saved inventory v" + newVersion + " for " + player.getName());
            } catch (Exception e) {
                // 409 = a newer save won (e.g. a fast server switch); drop this stale one.
                plugin.getLogger().warning("Inventory save skipped for " + player.getName() + ": " + e.getMessage());
            } finally {
                versions.remove(uuid);
            }
        });
    }

    /** Replace null slots with AIR so Paper's serializer never sees nulls. */
    private static ItemStack[] sanitize(ItemStack[] contents) {
        ItemStack[] out = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            out[i] = contents[i] == null ? new ItemStack(Material.AIR) : contents[i];
        }
        return out;
    }
}
