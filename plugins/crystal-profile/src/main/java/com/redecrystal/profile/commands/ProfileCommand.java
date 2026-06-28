package com.redecrystal.profile.commands;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.cargo.CargoResolver;
import com.redecrystal.core.cargo.TagOverrides;
import com.redecrystal.core.http.ProfileData;
import com.redecrystal.profile.CrystalProfilePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /profile} — prints a quick summary (cargo + stats) for the caller. The
 * visual profile lives in the lobby GUI; this command only reads the backend
 * profile, off the main thread, and echoes a few lines.
 */
public final class ProfileCommand implements CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final CrystalProfilePlugin plugin;
    private final CrystalCore crystal;

    public ProfileCommand(CrystalProfilePlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            ProfileData p = crystal.backend().getProfile(player.getUniqueId().toString());
            if (p == null) {
                player.sendMessage(Component.text("Perfil ainda não carregado.", NamedTextColor.RED));
                return;
            }
            String overrideId = TagOverrides.read(crystal.redis(), player.getUniqueId());
            CargoResolver.Cargo cargo = CargoResolver.resolve(
                    crystal.configProvider().get("chat"), overrideId, player::hasPermission);
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
