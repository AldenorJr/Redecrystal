package com.redecrystal.profile;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.ProfileData;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Profile plugin. On join it ensures the player's profile exists, rewards a
 * small join bonus, and greets them with their stats; on quit it banks the
 * session's play time. All persistence goes through the backend (profile
 * service + Redis cache). Runs backend calls off the main thread.
 */
public final class CrystalProfilePlugin extends JavaPlugin implements Listener {

    private CrystalCore crystal;
    private final Map<UUID, Long> joinedAt = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("CrystalProfile enabled.");
    }

    @Override
    public void onDisable() {
        if (crystal != null) {
            crystal.close();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        joinedAt.put(uuid, System.currentTimeMillis());
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                crystal.backend().ensureProfile(uuid.toString(), player.getName());
                // Small reward each session to demonstrate persisted mutation.
                ProfileData p = crystal.backend().addStats(uuid.toString(), 10, 50, 0);
                player.sendMessage(Component.text("Bem-vindo de volta, " + player.getName() + "!", NamedTextColor.GREEN));
                player.sendMessage(greeting(p));
            } catch (Exception e) {
                getLogger().warning("Profile load failed for " + player.getName() + ": " + e);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Long start = joinedAt.remove(uuid);
        if (start == null) {
            return;
        }
        long seconds = Math.max(0, (System.currentTimeMillis() - start) / 1000);
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                crystal.backend().addStats(uuid.toString(), 0, 0, seconds);
            } catch (Exception e) {
                getLogger().warning("Profile save failed: " + e);
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
            } else {
                player.sendMessage(greeting(p));
            }
        });
        return true;
    }

    private Component greeting(ProfileData p) {
        return Component.text("Rank ", NamedTextColor.GRAY)
                .append(Component.text(p.rank(), NamedTextColor.GOLD))
                .append(Component.text("  •  Level ", NamedTextColor.GRAY))
                .append(Component.text(p.level(), NamedTextColor.AQUA))
                .append(Component.text("  •  XP ", NamedTextColor.GRAY))
                .append(Component.text(p.experience(), NamedTextColor.AQUA))
                .append(Component.text("  •  Coins ", NamedTextColor.GRAY))
                .append(Component.text(p.coins(), NamedTextColor.YELLOW));
    }
}
