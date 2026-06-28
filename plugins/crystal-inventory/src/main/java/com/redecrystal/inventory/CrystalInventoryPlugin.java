package com.redecrystal.inventory;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.inventory.listener.InventorySyncListener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Inventory sync, per server type. The inventory is namespaced by this server's
 * {@code SERVER_TYPE} (factions ≠ rankup ≠ lobby) and only syncs when that type
 * opts in via the centralized config flag {@code <type>.syncInventory = true}.
 * When a type doesn't sync (e.g. the lobby, which has a fixed cosmetic hotbar),
 * the plugin does nothing. Boots the SDK, then registers the sync listener.
 */
public final class CrystalInventoryPlugin extends JavaPlugin {

    private CrystalCore crystal;

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());
        String serverType = crystal.config().serverType();

        boolean syncEnabled = crystal.configProvider().get(serverType).bool("syncInventory", false);
        if (!syncEnabled) {
            getLogger().info("CrystalInventory: sync disabled for type '" + serverType + "' — doing nothing.");
            crystal.close();
            crystal = null;
            return;
        }
        getServer().getPluginManager().registerEvents(
                new InventorySyncListener(this, crystal, serverType), this);
        getLogger().info("CrystalInventory enabled for type '" + serverType + "'.");
    }

    @Override
    public void onDisable() {
        if (crystal != null) {
            crystal.close();
        }
    }
}
