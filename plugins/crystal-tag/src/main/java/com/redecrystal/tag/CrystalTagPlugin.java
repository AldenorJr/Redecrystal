package com.redecrystal.tag;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.tag.command.TagCommand;
import com.redecrystal.tag.listener.NametagService;
import com.redecrystal.tag.menu.TagEditorMenu;
import com.redecrystal.tag.menu.TagSelectorMenu;
import org.bukkit.plugin.PluginManager;
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
        NametagService nametags = new NametagService(this, crystal);
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(nametags, this);
        nametags.start();
        TagSelectorMenu selector = new TagSelectorMenu(this, crystal);
        TagEditorMenu editor = new TagEditorMenu(); // full wiring in Task 6
        pm.registerEvents(selector, this);
        getCommand("tag").setExecutor(new TagCommand(crystal, selector, editor));
        getLogger().info("CrystalTag enabled.");
    }

    @Override
    public void onDisable() {
        if (crystal != null) {
            crystal.close();
        }
    }
}
