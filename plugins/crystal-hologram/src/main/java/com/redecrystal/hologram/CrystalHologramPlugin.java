package com.redecrystal.hologram;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Network hologram plugin. Boots the SDK; full rendering + command wiring is
 * added in a later task. Holograms are driven by the central {@code holograms}
 * config so the same set appears on every server running this plugin.
 */
public final class CrystalHologramPlugin extends JavaPlugin {

    private CrystalCore crystal;

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());
        getLogger().info("CrystalHologram enabled.");
    }

    @Override
    public void onDisable() {
        if (crystal != null) {
            crystal.close();
        }
    }
}
