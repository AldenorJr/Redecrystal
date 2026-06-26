package com.redecrystal.inventory;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.InventoryData;
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
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Inventory sync, per server type. The inventory is namespaced by this server's
 * {@code SERVER_TYPE} (factions ≠ rankup ≠ lobby) and only syncs when that type
 * opts in via the centralized config flag {@code <type>.syncInventory = true}.
 * When a type doesn't sync (e.g. the lobby, which has a fixed cosmetic hotbar),
 * the plugin does nothing. Item (de)serialization uses Paper's native byte
 * format, Base64-encoded; saves use an optimistic-lock version.
 */
public final class CrystalInventoryPlugin extends JavaPlugin implements Listener {

    private CrystalCore crystal;
    private String serverType;
    /** Last version loaded/saved per player, for optimistic locking. */
    private final Map<UUID, Integer> versions = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());
        this.serverType = crystal.config().serverType();

        boolean syncEnabled = crystal.configProvider().get(serverType).bool("syncInventory", false);
        if (!syncEnabled) {
            getLogger().info("CrystalInventory: sync disabled for type '" + serverType + "' — doing nothing.");
            crystal.close();
            crystal = null;
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("CrystalInventory enabled for type '" + serverType + "'.");
    }

    @Override
    public void onDisable() {
        if (crystal != null) {
            crystal.close();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                InventoryData data = crystal.backend().getInventory(uuid.toString(), serverType);
                versions.put(uuid, data.version());
                if (data.isEmpty()) {
                    return;
                }
                ItemStack[] items = ItemStack.deserializeItemsFromBytes(
                        Base64.getDecoder().decode(data.content()));
                getServer().getScheduler().runTask(this, () -> {
                    if (player.isOnline()) {
                        player.getInventory().setContents(items);
                        getLogger().info("Loaded inventory v" + data.version() + " for " + player.getName());
                    }
                });
            } catch (Exception e) {
                getLogger().warning("Inventory load failed for " + player.getName() + ": " + e);
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
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                int newVersion = crystal.backend().saveInventory(uuid.toString(), serverType, b64, version);
                getLogger().info("Saved inventory v" + newVersion + " for " + player.getName());
            } catch (Exception e) {
                // 409 = a newer save won (e.g. a fast server switch); drop this stale one.
                getLogger().warning("Inventory save skipped for " + player.getName() + ": " + e.getMessage());
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
