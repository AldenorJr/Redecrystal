package com.redecrystal.skin;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.skin.command.SkinCommand;
import com.redecrystal.skin.listener.SkinSessionListener;
import com.redecrystal.skin.menu.SkinHistoryMenu;
import com.redecrystal.skin.skin.MojangSkinService;
import com.redecrystal.skin.skin.SkinApplier;
import com.redecrystal.skin.store.SkinHistoryStore;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Skin plugin. Boots the SDK and serves {@code /skin set <nick>} (apply a Mojang
 * skin) plus the {@code /skin} history GUI. The history is persisted per-player via
 * the shared inventory blob store (no dedicated backend endpoint).
 */
public final class CrystalSkinPlugin extends JavaPlugin {

    private CrystalCore crystal;

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());

        SkinHistoryStore store = new SkinHistoryStore(this, crystal);
        MojangSkinService mojang = new MojangSkinService();
        SkinApplier applier = new SkinApplier(this);
        SkinHistoryMenu menu = new SkinHistoryMenu(this, store, applier);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(menu, this);
        pm.registerEvents(new SkinSessionListener(this, store, applier), this);
        SkinCommand skin = new SkinCommand(this, mojang, applier, store, menu);
        getCommand("skin").setExecutor(skin);
        getCommand("skin").setTabCompleter(skin);

        getLogger().info("CrystalSkin enabled.");
    }

    @Override
    public void onDisable() {
        if (crystal != null) {
            crystal.close();
        }
    }
}
