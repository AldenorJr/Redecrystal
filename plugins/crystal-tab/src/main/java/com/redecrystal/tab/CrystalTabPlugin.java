package com.redecrystal.tab;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.RemoteConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Tab-list plugin. Renders a config-driven header/footer (MiniMessage templates
 * from the central {@code tab} config, hot-reloaded) and shows each player's
 * chat-role tag ({@code [PREFIX]} + colored name) in the list, resolved from the
 * shared {@code chat} role config so the tab matches the chat. The online count
 * is network-wide (Redis).
 */
public final class CrystalTabPlugin extends JavaPlugin implements Listener {

    private static final String CONFIG_KEY = "tab";
    private static final String CHAT_CONFIG_KEY = "chat";

    private CrystalCore crystal;
    private TabRenderer renderer;
    private BukkitTask task;

    private volatile String header = "";
    private volatile String footer = "";
    private volatile boolean prefixInTab = true;
    private volatile int maxPlayers = 500;
    private volatile long refreshTicks = 40;

    /** Roles sorted by weight (desc); highest match wins. Shared with crystal-chat. */
    private volatile List<Role> roles = List.of();

    /** A chat tag/cargo loaded from the centralized {@code chat} config. */
    private record Role(String id, String permission, int weight, String prefix, String nameColor) { }

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());
        this.renderer = new TabRenderer("RedeCrystal", crystal.config().serverId());

        applyConfig(crystal.configProvider().get(CONFIG_KEY));
        crystal.configProvider().onChange(CONFIG_KEY, cfg -> {
            long oldInterval = refreshTicks;
            applyConfig(cfg);
            if (refreshTicks != oldInterval) {
                reschedule();
            }
        });

        crystal.configProvider().preload(CHAT_CONFIG_KEY);
        applyRoles(crystal.configProvider().get(CHAT_CONFIG_KEY));
        crystal.configProvider().onChange(CHAT_CONFIG_KEY, this::applyRoles);

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

    /** Rebuild the role cache from the shared {@code chat} config. */
    @SuppressWarnings("unchecked")
    private void applyRoles(RemoteConfig cfg) {
        List<Role> loaded = new ArrayList<>();
        if (cfg.value("roles") instanceof Map<?, ?> rolesMap) {
            for (Map.Entry<?, ?> entry : rolesMap.entrySet()) {
                String id = String.valueOf(entry.getKey());
                if (!(entry.getValue() instanceof Map<?, ?> data)) {
                    continue;
                }
                Map<String, Object> m = (Map<String, Object>) data;
                String permission = m.get("permission") == null ? "tag." + id : String.valueOf(m.get("permission"));
                int weight = m.get("weight") instanceof Number n ? n.intValue() : 0;
                String prefix = m.get("prefix") == null ? "" : String.valueOf(m.get("prefix"));
                String nameColor = m.get("nameColor") == null ? "" : String.valueOf(m.get("nameColor"));
                loaded.add(new Role(id, permission, weight, prefix, nameColor));
            }
        }
        loaded.sort(Comparator.comparingInt(Role::weight).reversed());
        this.roles = List.copyOf(loaded);
        getLogger().info("CrystalTab: " + this.roles.size() + " cargo(s) carregado(s) para o tab.");
    }

    /** Highest-weight role whose permission the player has, or {@code null}. */
    private Role resolveRole(Player player) {
        for (Role role : roles) {
            if (player.hasPermission(role.permission())) {
                return role;
            }
        }
        return null;
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
                renderTab(p, online);
            } catch (Exception e) {
                getLogger().warning("Tab render failed for " + p.getName() + ": " + e);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        int online = (int) crystal.redis().onlineCount();
        renderTab(event.getPlayer(), online);
    }

    private void renderTab(Player player, int online) {
        Role role = resolveRole(player);
        String prefix = role == null ? "" : role.prefix();
        String nameColor = role == null ? "" : role.nameColor();
        renderer.apply(player, header, footer, online, maxPlayers, prefixInTab, prefix, nameColor);
    }
}
