package com.redecrystal.tab;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.RemoteConfig;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Tab-list plugin. Renders a config-driven header/footer (MiniMessage templates
 * from the central {@code tab} config, hot-reloaded) and shows each player's
 * LuckPerms prefix in the list. The online count is network-wide (Redis).
 */
public final class CrystalTabPlugin extends JavaPlugin implements Listener {

    private static final String CONFIG_KEY = "tab";

    private CrystalCore crystal;
    private TabRenderer renderer;
    private BukkitTask task;

    private volatile String header = "";
    private volatile String footer = "";
    private volatile boolean prefixInTab = true;
    private volatile int maxPlayers = 500;
    private volatile long refreshTicks = 40;

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());
        this.renderer = new TabRenderer(new LuckPermsHook(), "RedeCrystal", crystal.config().serverId());

        applyConfig(crystal.configProvider().get(CONFIG_KEY));
        crystal.configProvider().onChange(CONFIG_KEY, cfg -> {
            long oldInterval = refreshTicks;
            applyConfig(cfg);
            if (refreshTicks != oldInterval) {
                reschedule();
            }
        });

        getServer().getPluginManager().registerEvents(this, this);
        reschedule();
        getLogger().info("CrystalTab enabled.");
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
        }
        if (crystal != null) {
            crystal.close();
        }
    }

    private void applyConfig(RemoteConfig cfg) {
        this.header = cfg.string("header", "<aqua>RedeCrystal");
        this.footer = cfg.string("footer", "<gray>play.redecrystal.net");
        this.prefixInTab = cfg.bool("prefixInTab", true);
        this.maxPlayers = cfg.integer("maxPlayers", 500);
        this.refreshTicks = cfg.value("refreshTicks") instanceof Number n ? n.longValue() : 40;
    }

    private void reschedule() {
        if (task != null) {
            task.cancel();
        }
        this.task = getServer().getScheduler().runTaskTimer(this, this::refresh, 20L, Math.max(refreshTicks, 1));
    }

    private void refresh() {
        int online = (int) crystal.redis().onlineCount();
        for (Player p : getServer().getOnlinePlayers()) {
            try {
                renderer.apply(p, header, footer, online, maxPlayers, prefixInTab);
            } catch (Exception e) {
                getLogger().warning("Tab render failed for " + p.getName() + ": " + e);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        int online = (int) crystal.redis().onlineCount();
        renderer.apply(event.getPlayer(), header, footer, online, maxPlayers, prefixInTab);
    }
}
