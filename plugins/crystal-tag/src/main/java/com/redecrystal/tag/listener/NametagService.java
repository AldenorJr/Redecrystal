package com.redecrystal.tag.listener;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.cargo.CargoResolver;
import com.redecrystal.core.cargo.TagOverrides;
import com.redecrystal.core.http.RemoteConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Renders each player's cargo tag above their head. Minecraft shows the nametag
 * from a scoreboard team's prefix, and the lobby gives every player their own
 * scoreboard, so we apply one cargo team per cargo onto each viewer's current
 * board ({@code player.getScoreboard()}) and put each target in their cargo's
 * team. Team names ({@code ct_*}) don't collide with the lobby sidebar's
 * ({@code line0..6}). Updates are guarded so unchanged tags send no packets.
 */
public final class NametagService implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String CHAT_CONFIG_KEY = "chat";
    private static final String TEAM_PREFIX = "ct_";
    private static final String DEFAULT_TEAM = "ct_default";
    private static final int MAX_TEAM_NAME = 16;
    private static final long REFRESH_TICKS = 40L;

    private final JavaPlugin plugin;
    private final CrystalCore crystal;

    public NametagService(JavaPlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
    }

    /** A target's resolved tag, computed once per tick and applied to every board.
     *  The name itself is always white (the team's default colour). */
    private record Resolved(String teamName, Component prefix) { }

    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refresh, REFRESH_TICKS, REFRESH_TICKS);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Apply shortly after join, once the lobby has (re)built the player's board.
        plugin.getServer().getScheduler().runTaskLater(plugin, this::refresh, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        String entry = event.getPlayer().getName();
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (viewer.equals(event.getPlayer())) {
                continue;
            }
            Team team = viewer.getScoreboard().getEntryTeam(entry);
            if (team != null) {
                team.removeEntry(entry);
            }
        }
    }

    private void refresh() {
        Map<String, String> overrides;
        try {
            overrides = crystal.redis().hgetAll(TagOverrides.KEY);
        } catch (Exception e) {
            overrides = Map.of(); // Redis down → permission-based tags only
        }
        RemoteConfig chat = crystal.configProvider().get(CHAT_CONFIG_KEY);

        List<Player> players = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        Map<UUID, Resolved> resolved = new HashMap<>();
        for (Player target : players) {
            String overrideId = overrides.get(target.getUniqueId().toString());
            resolved.put(target.getUniqueId(), resolve(chat, overrideId, target));
        }
        for (Player viewer : players) {
            Scoreboard board = viewer.getScoreboard();
            for (Player target : players) {
                try {
                    apply(board, target.getName(), resolved.get(target.getUniqueId()));
                } catch (Exception e) {
                    plugin.getLogger().warning("Nametag falhou para " + target.getName() + ": " + e);
                }
            }
        }
    }

    private Resolved resolve(RemoteConfig chat, String overrideId, Player target) {
        CargoResolver.Cargo cargo = CargoResolver.resolve(chat, overrideId, target::hasPermission);
        if (cargo == null) {
            return new Resolved(DEFAULT_TEAM, Component.empty());
        }
        Component prefix = cargo.prefix().isEmpty()
                ? Component.empty()
                : parse(cargo.prefix()).append(Component.space());
        return new Resolved(teamName(cargo.id()), prefix);
    }

    /** Ensure {@code entry} sits in its cargo team with the right prefix. The name
     *  stays white (the team's default colour); we never set a team colour, which
     *  also avoids the {@code team.color()} "must have hex values" pitfall. */
    private void apply(Scoreboard board, String entry, Resolved r) {
        Team team = board.getTeam(r.teamName());
        if (team == null) {
            team = board.registerNewTeam(r.teamName());
        }
        if (!team.prefix().equals(r.prefix())) {
            team.prefix(r.prefix());
        }
        if (board.getEntryTeam(entry) != team) {
            team.addEntry(entry); // moves the entry out of any previous team
        }
    }

    private static String teamName(String cargoId) {
        String name = TEAM_PREFIX + cargoId;
        return name.length() <= MAX_TEAM_NAME ? name : name.substring(0, MAX_TEAM_NAME);
    }

    /** Parse a MiniMessage string, falling back to legacy '&' codes if present. */
    private static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        if (raw.indexOf('&') >= 0 || raw.indexOf('§') >= 0) {
            return LEGACY.deserialize(raw.replace('§', '&'));
        }
        return MM.deserialize(raw);
    }
}
