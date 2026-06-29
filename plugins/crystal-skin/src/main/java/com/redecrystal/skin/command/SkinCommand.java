package com.redecrystal.skin.command;

import com.redecrystal.skin.menu.SkinHistoryMenu;
import com.redecrystal.skin.skin.MojangSkinService;
import com.redecrystal.skin.skin.MojangSkinService.SkinLookupException;
import com.redecrystal.skin.skin.SkinApplier;
import com.redecrystal.skin.skin.SkinTexture;
import com.redecrystal.skin.store.SkinHistoryStore;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * {@code /skin} — no args opens the history GUI; {@code /skin set <nick>} fetches
 * that player's skin from Mojang (off-thread) and applies it. Gated by
 * {@code crystal.skin.use}.
 */
public final class SkinCommand implements CommandExecutor, TabCompleter {

    private static final String SET = "set";

    private static final String USE_PERM = "crystal.skin.use";

    private final JavaPlugin plugin;
    private final MojangSkinService mojang;
    private final SkinApplier applier;
    private final SkinHistoryStore store;
    private final SkinHistoryMenu menu;

    public SkinCommand(JavaPlugin plugin, MojangSkinService mojang, SkinApplier applier,
                       SkinHistoryStore store, SkinHistoryMenu menu) {
        this.plugin = plugin;
        this.mojang = mojang;
        this.applier = applier;
        this.store = store;
        this.menu = menu;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Apenas jogadores.");
            return true;
        }
        if (!player.hasPermission(USE_PERM)) {
            player.sendMessage(Component.text("Você não tem permissão.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            menu.open(player);
            return true;
        }
        if (args[0].equalsIgnoreCase(SET) && args.length >= 2) {
            applyFromNick(player, args[1]);
            return true;
        }
        player.sendMessage(Component.text("Use: /skin set <nick>", NamedTextColor.GRAY));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !player.hasPermission(USE_PERM)) {
            return List.of();
        }
        if (args.length == 1) {
            return prefixMatch(List.of(SET), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase(SET)) {
            return prefixMatch(onlineNamesVisibleTo(player), args[1]);
        }
        return List.of();
    }

    /** Online players this one can see, so vanished/hidden names don't leak. */
    private List<String> onlineNamesVisibleTo(Player player) {
        return Bukkit.getOnlinePlayers().stream()
                .filter(player::canSee)
                .map(Player::getName)
                .toList();
    }

    private static List<String> prefixMatch(List<String> options, String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }

    /** Fetch off-thread, then apply + record back on the main thread. */
    private void applyFromNick(Player player, String nick) {
        player.sendActionBar(Component.text("Buscando skin de " + nick + "...", NamedTextColor.YELLOW));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final SkinTexture texture;
            try {
                texture = mojang.fetch(nick, System.currentTimeMillis());
            } catch (SkinLookupException e) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(failure(e, nick)));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                applier.apply(player, texture);
                store.record(player.getUniqueId(), texture);
                player.sendMessage(Component.text("Skin de " + texture.name() + " aplicada!",
                        NamedTextColor.GREEN));
            });
        });
    }

    private Component failure(SkinLookupException e, String nick) {
        String msg = switch (e.reason()) {
            case NOT_FOUND -> "Jogador '" + nick + "' não encontrado.";
            case RATE_LIMITED -> "Muitas requisições à Mojang, tente novamente em instantes.";
            case TRANSPORT -> "Mojang indisponível, tente novamente.";
        };
        return Component.text(msg, NamedTextColor.RED);
    }
}
