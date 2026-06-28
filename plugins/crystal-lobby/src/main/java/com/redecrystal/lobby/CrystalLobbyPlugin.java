package com.redecrystal.lobby;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.RemoteConfig;
import com.redecrystal.lobby.commands.LobbyCommand;
import com.redecrystal.lobby.commands.MaintenanceCommand;
import com.redecrystal.lobby.listener.LobbyHotbar;
import com.redecrystal.lobby.listener.LobbyProtection;
import com.redecrystal.lobby.listener.LobbyScoreboard;
import com.redecrystal.lobby.listener.PlayerJoinListener;
import com.redecrystal.lobby.listener.PlayerQuitListener;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Lobby plugin. Boots the SDK, self-registers, wires up the lobby's listeners
 * (see {@code listener/}) and commands (see {@code commands/}), and serves a
 * config-driven spawn (set live with {@code /lobby setspawn}, hot-reloaded to
 * every lobby) in ADVENTURE mode. This class only boots and registers — the
 * behaviour lives in the listener/command classes.
 */
public final class CrystalLobbyPlugin extends JavaPlugin {

    private static final String CONFIG_KEY = "lobby";

    private CrystalCore crystal;
    private LobbyHotbar hotbar;
    private volatile String motd = "RedeCrystal";
    private volatile Location spawn;
    private volatile double voidY = 30;

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());

        RemoteConfig cfg = crystal.configProvider().get(CONFIG_KEY);
        applyConfig(cfg);
        // Cache the cargo/role config so the profile GUI resolves tags without
        // blocking the main thread on a backend call.
        crystal.configProvider().preload("chat");
        getLogger().info("Lobby config v" + cfg.version() + ": motd='" + motd + "', spawn=" + describe(spawn));

        crystal.registerThisServer(cfg.integer("maxPlayers", 100));
        crystal.startHeartbeat(() -> getServer().getOnlinePlayers().size(),
                () -> getServer().getTPS()[0]);

        crystal.configProvider().onChange(CONFIG_KEY, updated -> {
            applyConfig(updated);
            getLogger().info("Hot-reloaded lobby config to v" + updated.version() + " (spawn=" + describe(spawn) + ")");
        });

        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerJoinListener(this, crystal), this);
        pm.registerEvents(new PlayerQuitListener(crystal), this);
        this.hotbar = new LobbyHotbar(this, crystal);
        pm.registerEvents(hotbar, this);
        hotbar.start();
        pm.registerEvents(new LobbyProtection(this), this);
        LobbyScoreboard scoreboard = new LobbyScoreboard(this, crystal);
        pm.registerEvents(scoreboard, this);
        scoreboard.start();

        getCommand("lobby").setExecutor(new LobbyCommand(this, crystal));
        getCommand("manutencao").setExecutor(new MaintenanceCommand(this, crystal));

        getLogger().info("CrystalLobby enabled.");
    }

    @Override
    public void onDisable() {
        if (hotbar != null) {
            hotbar.shutdown();
        }
        if (crystal != null) {
            crystal.close();
        }
    }

    /** Parse spawn + voidY from config; align the world spawn with it. */
    private void applyConfig(RemoteConfig cfg) {
        this.motd = cfg.string("motd", "RedeCrystal");
        this.voidY = cfg.value("voidY") instanceof Number n ? n.doubleValue() : 30;
        this.spawn = parseSpawn(cfg.value("spawn"));
        if (spawn != null && spawn.getWorld() != null) {
            spawn.getWorld().setSpawnLocation(spawn);
        }
    }

    private Location parseSpawn(Object raw) {
        World world = getServer().getWorlds().isEmpty() ? null : getServer().getWorlds().get(0);
        if (world == null) {
            return null;
        }
        if (raw instanceof Map<?, ?> m && m.get("x") != null) {
            return new Location(world, num(m.get("x")), num(m.get("y")), num(m.get("z")),
                    (float) num(m.get("yaw")), (float) num(m.get("pitch")));
        }
        return world.getSpawnLocation();
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    public Location getSpawn() {
        return spawn == null ? null : spawn.clone();
    }

    public double getVoidY() {
        return voidY;
    }

    public void sendToSpawn(Player player) {
        Location s = getSpawn();
        if (s != null) {
            player.teleport(s);
        }
    }

    private static String describe(Location l) {
        return l == null ? "unset" : (Math.round(l.getX()) + "," + Math.round(l.getY()) + "," + Math.round(l.getZ()));
    }
}
