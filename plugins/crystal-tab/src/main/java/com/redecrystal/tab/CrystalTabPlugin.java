package com.redecrystal.tab;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.cargo.TagOverrides;
import com.redecrystal.core.http.RemoteConfig;
import com.redecrystal.tab.listener.PlayerJoinListener;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Tab-list plugin. Renders a config-driven header/footer (MiniMessage templates
 * from the central {@code tab} config, hot-reloaded) and shows each player's
 * chat-role tag ({@code [PREFIX]} + colored name) in the list, resolved from the
 * shared {@code chat} role config so the tab matches the chat. The online count
 * is network-wide (Redis).
 */
public final class CrystalTabPlugin extends JavaPlugin {

    private static final String CONFIG_KEY = "tab";
    private static final String CHAT_CONFIG_KEY = "chat";
    private static final String GLOBAL_CONFIG_KEY = "global";

    private static final String DEFAULT_MAINT_HEADER =
            "\n<red><bold>⚠ EM MANUTENÇÃO ⚠</bold></red>\n<#c9a6ff>Estamos melhorando a rede para você\n";
    private static final String DEFAULT_MAINT_FOOTER =
            "\n<gray>Acesso liberado para a equipe   <dark_gray>|</dark_gray>   <#b14aed>redecrystal.com.br\n";

    private CrystalCore crystal;
    private TabRenderer renderer;
    private BukkitTask task;

    private volatile String header = "";
    private volatile String footer = "";
    private volatile String maintenanceHeader = DEFAULT_MAINT_HEADER;
    private volatile String maintenanceFooter = DEFAULT_MAINT_FOOTER;
    private volatile boolean maintenance = false;
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

        crystal.configProvider().preload(GLOBAL_CONFIG_KEY);
        this.maintenance = crystal.configProvider().get(GLOBAL_CONFIG_KEY).bool("maintenance", false);
        crystal.configProvider().onChange(GLOBAL_CONFIG_KEY,
                cfg -> this.maintenance = cfg.bool("maintenance", false));

        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
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
        this.footer = cfg.string("footer", "<gray>redecrystal.com.br");
        this.maintenanceHeader = cfg.string("maintenanceHeader", DEFAULT_MAINT_HEADER);
        this.maintenanceFooter = cfg.string("maintenanceFooter", DEFAULT_MAINT_FOOTER);
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

    /** Highest-weight role, but an admin override (by cargo id) wins. */
    private Role resolveRole(Player player, String overrideId) {
        if (overrideId != null && !overrideId.isBlank()) {
            for (Role role : roles) {
                if (role.id().equals(overrideId)) {
                    return role;
                }
            }
        }
        return resolveRole(player);
    }

    private void reschedule() {
        if (task != null) {
            task.cancel();
        }
        this.task = getServer().getScheduler().runTaskTimer(this, this::refresh, 20L, Math.max(refreshTicks, 1));
    }

    private void refresh() {
        int online = (int) crystal.redis().onlineCount();
        // One hgetAll per tick instead of one hget per player — keeps Redis round-trips O(1).
        Map<String, String> overrides;
        try {
            overrides = crystal.redis().hgetAll(TagOverrides.KEY);
        } catch (Exception e) {
            overrides = Map.of();
        }
        for (Player p : getServer().getOnlinePlayers()) {
            try {
                renderTab(p, online, overrides.get(p.getUniqueId().toString()));
            } catch (Exception e) {
                getLogger().warning("Tab render failed for " + p.getName() + ": " + e);
            }
        }
    }

    /** Render the tab for a player that just joined, using the live online count. */
    public void renderOnJoin(Player player) {
        int online = (int) crystal.redis().onlineCount();
        String overrideId = TagOverrides.read(crystal.redis(), player.getUniqueId());
        renderTab(player, online, overrideId);
    }

    private void renderTab(Player player, int online, String overrideId) {
        String h = maintenance ? maintenanceHeader : header;
        String f = maintenance ? maintenanceFooter : footer;
        Role role = resolveRole(player, overrideId);
        String prefix = role == null ? "" : role.prefix();
        String nameColor = role == null ? "" : role.nameColor();
        renderer.apply(player, h, f, online, maxPlayers, prefixInTab, prefix, nameColor);
    }
}
