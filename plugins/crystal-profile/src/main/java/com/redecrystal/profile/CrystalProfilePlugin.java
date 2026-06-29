package com.redecrystal.profile;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.profile.commands.ProfileCommand;
import com.redecrystal.profile.listener.CommandActivityListener;
import com.redecrystal.profile.listener.PlayerDeathListener;
import com.redecrystal.profile.listener.ProfileSessionListener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Profile + history plugin. Ensures the player's profile, banks session play
 * time, and records the player's activity trail network-wide (join/quit, deaths,
 * kills, commands) plus combat stats. All persistence goes through the backend
 * (HTTP), off the main thread. The visual profile lives in the lobby GUI; the
 * {@code /profile} command prints a quick summary (cargo + stats).
 */
public final class CrystalProfilePlugin extends JavaPlugin {

    private CrystalCore crystal;

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());
        crystal.configProvider().preload("chat"); // cargo/role config for the summary

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new ProfileSessionListener(this, crystal), this);
        pm.registerEvents(new PlayerDeathListener(this, crystal), this);
        pm.registerEvents(new CommandActivityListener(this, crystal), this);

        getCommand("profile").setExecutor(new ProfileCommand(this, crystal));
        getLogger().info("CrystalProfile enabled.");
    }

    @Override
    public void onDisable() {
        if (crystal != null) {
            crystal.close();
        }
    }
}
