package com.redecrystal.lobby;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.RemoteConfig;
import com.redecrystal.core.messaging.KafkaTopics;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Lobby plugin. Boots the SDK, self-registers, and serves the lobby experience:
 * a config-driven spawn (set live with {@code /lobby setspawn}, hot-reloaded to
 * every lobby), ADVENTURE mode, and protection from damage/death (see
 * {@link LobbyProtection}). Join/quit update Redis presence + Kafka events.
 */
public final class CrystalLobbyPlugin extends JavaPlugin implements Listener {

    private static final String CONFIG_KEY = "lobby";
    private static final String GLOBAL_KEY = "global";
    private static final String ADMIN_PERM = "crystal.lobby.admin";
    private static final String MAINT_PERM = "crystal.maintenance";

    private CrystalCore crystal;
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

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        getServer().getPluginManager().registerEvents(new LobbyHotbar(this, crystal), this);
        getServer().getPluginManager().registerEvents(new LobbyProtection(this), this);
        LobbyScoreboard scoreboard = new LobbyScoreboard(this, crystal);
        getServer().getPluginManager().registerEvents(scoreboard, this);
        scoreboard.start();
        getLogger().info("CrystalLobby enabled.");
    }

    @Override
    public void onDisable() {
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

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String uuid = player.getUniqueId().toString();
        crystal.redis().addOnlinePlayer(uuid);
        crystal.kafka().publish(KafkaTopics.PLAYER_CONNECTED, uuid, Map.of(
                "player", player.getName(), "uuid", uuid, "server", crystal.config().serverId()));

        player.setGameMode(GameMode.ADVENTURE);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setFireTicks(0);
        if (player.hasPermission("crystal.fly")) {
            player.setAllowFlight(true);
            player.setFlying(true);
        }
        sendToSpawn(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        String uuid = event.getPlayer().getUniqueId().toString();
        crystal.redis().removeOnlinePlayer(uuid);
        crystal.kafka().publish(KafkaTopics.PLAYER_DISCONNECTED, uuid, Map.of(
                "player", event.getPlayer().getName(), "uuid", uuid, "server", crystal.config().serverId()));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if ("manutencao".equalsIgnoreCase(command.getName())) {
            return handleMaintenance(sender, args);
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Apenas jogadores.");
            return true;
        }
        if (args.length == 0) {
            sendToSpawn(player);
            return true;
        }
        if ("setspawn".equalsIgnoreCase(args[0])) {
            if (!player.hasPermission(ADMIN_PERM)) {
                player.sendMessage(Component.text("Sem permissão.", NamedTextColor.RED));
                return true;
            }
            Location loc = player.getLocation();
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                Map<String, Object> cfg = new HashMap<>(crystal.configProvider().get(CONFIG_KEY).config());
                cfg.put("spawn", Map.of(
                        "x", round(loc.getX()), "y", round(loc.getY()), "z", round(loc.getZ()),
                        "yaw", round(loc.getYaw()), "pitch", round(loc.getPitch())));
                crystal.backend().putConfig(CONFIG_KEY, cfg);
                player.sendMessage(Component.text("Spawn do lobby definido para todos os lobbies.", NamedTextColor.GREEN));
            });
            return true;
        }
        player.sendMessage(Component.text("/lobby [setspawn]", NamedTextColor.GRAY));
        return true;
    }

    /** {@code /manutencao <on|off>} — toggles global.maintenance for the whole network. */
    private boolean handleMaintenance(CommandSender sender, String[] args) {
        if (!sender.hasPermission(MAINT_PERM)) {
            sender.sendMessage(Component.text("Sem permissão.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            boolean current = crystal.configProvider().get(GLOBAL_KEY).bool("maintenance", false);
            sender.sendMessage(Component.text("Manutenção está " + (current ? "LIGADA" : "DESLIGADA")
                    + ". Use /manutencao <on|off>.", NamedTextColor.YELLOW));
            return true;
        }
        String arg = args[0].toLowerCase(Locale.ROOT);
        final boolean enable;
        if (arg.equals("on") || arg.equals("ligar") || arg.equals("true")) {
            enable = true;
        } else if (arg.equals("off") || arg.equals("desligar") || arg.equals("false")) {
            enable = false;
        } else {
            sender.sendMessage(Component.text("Use /manutencao <on|off>.", NamedTextColor.GRAY));
            return true;
        }

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Map<String, Object> cfg = new HashMap<>(crystal.configProvider().get(GLOBAL_KEY).config());
                cfg.put("maintenance", enable);
                crystal.backend().putConfig(GLOBAL_KEY, cfg);
                sender.sendMessage(Component.text("Manutenção " + (enable ? "LIGADA" : "DESLIGADA")
                        + " para toda a rede.", enable ? NamedTextColor.GOLD : NamedTextColor.GREEN));
            } catch (Exception e) {
                sender.sendMessage(Component.text("Falha ao alterar manutenção: " + e.getMessage(),
                        NamedTextColor.RED));
            }
        });
        return true;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String describe(Location l) {
        return l == null ? "unset" : (Math.round(l.getX()) + "," + Math.round(l.getY()) + "," + Math.round(l.getZ()));
    }
}
