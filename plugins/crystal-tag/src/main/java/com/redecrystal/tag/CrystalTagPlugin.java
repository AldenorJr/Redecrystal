package com.redecrystal.tag;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Tag plugin. Boots the SDK and (in later tasks) renders the in-world nametag
 * (cargo prefix above the head) and serves the admin {@code /tag} command + GUIs.
 * The cargo definitions live in the shared {@code chat} config (hot-reloaded).
 */
public final class CrystalTagPlugin extends JavaPlugin {

    private CrystalCore crystal;

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());
        crystal.configProvider().preload("chat"); // cargo/role config (shared)
        getLogger().info("CrystalTag enabled.");
    }

    @Override
    public void onDisable() {
        if (crystal != null) {
            crystal.close();
        }
    }
}
