package com.redecrystal.hologram;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Network hologram plugin. Boots the SDK, renders the holograms from the central
 * {@code holograms} config, and hot-reloads them when the config changes — so the
 * same holograms appear on every server running this plugin, surviving restarts.
 */
public final class CrystalHologramPlugin extends JavaPlugin {

    private CrystalCore crystal;
    private HologramRenderer renderer;

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());
        crystal.configProvider().preload(HologramStore.CONFIG_KEY);
        HologramStore store = new HologramStore(crystal);
        this.renderer = new HologramRenderer(this);
        // Defer the first render one tick so all worlds are loaded before we spawn.
        getServer().getScheduler().runTask(this, () -> renderer.render(store.all()));
        // Hot-reload: the Kafka callback is off-thread, so bounce to the main thread.
        crystal.configProvider().onChange(HologramStore.CONFIG_KEY, cfg ->
                getServer().getScheduler().runTask(this, () -> renderer.render(store.all())));
        getCommand("hologram").setExecutor(new HologramCommand(this, crystal, store));
        getLogger().info("CrystalHologram enabled.");
    }

    @Override
    public void onDisable() {
        if (renderer != null) {
            renderer.clearAll();
        }
        if (crystal != null) {
            crystal.close();
        }
    }
}
