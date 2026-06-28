package com.redecrystal.lobby;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.cargo.CargoResolver;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Per-player lobby sidebar scoreboard. Each player gets their own board (built on
 * join) whose dynamic lines (online count, cargo) are refreshed on a timer. Lines
 * use the invisible-entry + team-prefix trick so they update flicker-free. Content
 * is purple-branded to match the MOTD/tab.
 */
public final class LobbyScoreboard implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String TITLE = "<gradient:#b14aed:#8e2de2><bold>REDECRYSTAL</bold></gradient>";
    private static final int LINES = 7;

    private final JavaPlugin plugin;
    private final CrystalCore crystal;

    public LobbyScoreboard(JavaPlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
    }

    /** Schedule the periodic refresh of every online player's sidebar. */
    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                try {
                    update(p);
                } catch (Exception e) {
                    plugin.getLogger().warning("Scoreboard update failed for " + p.getName() + ": " + e);
                }
            }
        }, 40L, 40L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        build(event.getPlayer());
    }

    /** Create the board (objective + one team per line) and assign it to the player. */
    private void build(Player p) {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = board.registerNewObjective("crystal", Criteria.DUMMY, MM.deserialize(TITLE));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        for (int i = 0; i < LINES; i++) {
            String entry = entry(i);
            Team team = board.registerNewTeam("line" + i);
            team.addEntry(entry);
            obj.getScore(entry).setScore(LINES - i); // line 0 on top
        }
        p.setScoreboard(board);
        update(p);
    }

    /** Refresh the dynamic line contents on the player's existing board. */
    private void update(Player p) {
        Scoreboard board = p.getScoreboard();
        if (board == null || board.getObjective("crystal") == null) {
            build(p);
            return;
        }
        List<Component> lines = lines(p);
        for (int i = 0; i < LINES; i++) {
            Team team = board.getTeam("line" + i);
            if (team != null) {
                team.prefix(i < lines.size() ? lines.get(i) : Component.empty());
            }
        }
    }

    /** The 8 sidebar lines, top to bottom. */
    private List<Component> lines(Player p) {
        int online = (int) crystal.redis().onlineCount();
        CargoResolver.Cargo cargo = CargoResolver.resolve(
                crystal.configProvider().get("chat"), p::hasPermission);
        String cargoMini = cargo == null ? "<gray>[MEMBRO]" : cargo.prefix();

        List<Component> l = new ArrayList<>();
        l.add(Component.empty());
        l.add(mm("<gray>Cargo: ").append(mm(cargoMini)));
        l.add(Component.empty());
        l.add(mm("<gray>Online: <#b14aed>" + online));
        l.add(mm("<gray>Servidor: <#b14aed>" + crystal.config().serverId()));
        l.add(Component.empty());
        l.add(mm("<#b14aed>redecrystal.com.br"));
        return l;
    }

    private static Component mm(String mini) {
        return MM.deserialize(mini).decoration(TextDecoration.ITALIC, false);
    }

    /** A unique, invisible entry token per line (color codes render to nothing). */
    private static String entry(int i) {
        return "§" + Integer.toHexString(i) + "§r";
    }
}
