package com.redecrystal.profile;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.cargo.CargoResolver;
import com.redecrystal.core.http.ProfileData;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Profile + history plugin. Ensures the player's profile, banks session play
 * time, and records the player's activity trail network-wide (join/quit, deaths,
 * kills, commands) plus combat stats. All persistence goes through the backend
 * (HTTP), off the main thread. The visual profile lives in the lobby GUI; the
 * {@code /profile} command prints a quick summary (cargo + stats).
 */
public final class CrystalProfilePlugin extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private CrystalCore crystal;
    private final Map<UUID, Long> joinedAt = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());
        crystal.configProvider().preload("chat"); // cargo/role config for the summary
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("CrystalProfile enabled.");
    }

    @Override
    public void onDisable() {
        if (crystal != null) {
            crystal.close();
        }
    }

    private String server() {
        return crystal.config().serverId();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        joinedAt.put(uuid, System.currentTimeMillis());
        String name = player.getName();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                crystal.backend().ensureProfile(uuid.toString(), name);
                crystal.backend().recordActivity(uuid.toString(), name, "JOIN", null, server());
            } catch (Exception e) {
                getLogger().warning("Profile join failed for " + name + ": " + e);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();
        Long start = joinedAt.remove(uuid);
        long seconds = start == null ? 0 : Math.max(0, (System.currentTimeMillis() - start) / 1000);
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                if (seconds > 0) {
                    crystal.backend().addStats(uuid.toString(), 0, 0, seconds);
                }
                crystal.backend().recordActivity(uuid.toString(), name, "QUIT",
                        seconds > 0 ? "sessão: " + seconds + "s" : null, server());
            } catch (Exception e) {
                getLogger().warning("Profile quit save failed: " + e);
            }
        });
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        String vUuid = victim.getUniqueId().toString();
        String vName = victim.getName();
        String detail = event.deathMessage() == null ? null
                : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(event.deathMessage());
        String kUuid = killer == null ? null : killer.getUniqueId().toString();
        String kName = killer == null ? null : killer.getName();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                crystal.backend().addCombat(vUuid, 0, 1);
                crystal.backend().recordActivity(vUuid, vName, "DEATH", detail, server());
                if (kUuid != null) {
                    crystal.backend().addCombat(kUuid, 1, 0);
                    crystal.backend().recordActivity(kUuid, kName, "KILL", "matou " + vName, server());
                }
            } catch (Exception e) {
                getLogger().warning("Combat record failed: " + e);
            }
        });
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String uuid = event.getPlayer().getUniqueId().toString();
        String name = event.getPlayer().getName();
        String cmd = event.getMessage();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                crystal.backend().recordActivity(uuid, name, "COMMAND", cmd, server());
            } catch (Exception e) {
                getLogger().warning("Command record failed: " + e);
            }
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            ProfileData p = crystal.backend().getProfile(player.getUniqueId().toString());
            if (p == null) {
                player.sendMessage(Component.text("Perfil ainda não carregado.", NamedTextColor.RED));
                return;
            }
            CargoResolver.Cargo cargo = CargoResolver.resolve(
                    crystal.configProvider().get("chat"), player::hasPermission);
            String cargoMini = cargo == null ? "<gray>[MEMBRO]" : cargo.prefix();

            player.sendMessage(MM.deserialize("<dark_gray>=== <gradient:#b14aed:#8e2de2><bold>Perfil</bold></gradient> <dark_gray>==="));
            player.sendMessage(MM.deserialize("<gray>Cargo: ").append(MM.deserialize(cargoMini)));
            player.sendMessage(MM.deserialize("<gray>Tempo online: <white>" + formatDuration(p.playSeconds())));
            player.sendMessage(MM.deserialize("<gray>Abates: <green>" + p.kills()
                    + "   <gray>Mortes: <red>" + p.deaths()));
            player.sendMessage(MM.deserialize("<gray>Mensagens enviadas: <aqua>" + p.messagesSent()));
        });
        return true;
    }

    private static String formatDuration(long seconds) {
        long d = seconds / 86400;
        long h = (seconds % 86400) / 3600;
        long m = (seconds % 3600) / 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0 || d > 0) sb.append(h).append("h ");
        sb.append(m).append("m");
        return sb.toString().trim();
    }
}
