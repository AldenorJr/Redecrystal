package com.redecrystal.parkour.listener;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.ParkourResult;
import com.redecrystal.parkour.CrystalParkourPlugin;
import com.redecrystal.parkour.ParkourCourse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Cohesive, stateful parkour run handler. All four event handlers
 * (move/interact/quit/command) share the per-player {@link Run} state and the
 * run lifecycle, so by the §3.1 cohesion exception they live in a single
 * listener instead of one class per event.
 *
 * <p>During a run the player is locked into the minigame: ADVENTURE mode, no
 * flight, commands blocked, and a dedicated hotbar (back-to-checkpoint / restart
 * / exit). The lobby hotbar is saved on start and restored on finish/exit.
 * Finish times go to the backend best-time leaderboard. The course/hologram
 * wiring stays in {@link CrystalParkourPlugin}; this class reads the current
 * course through it.
 */
public final class ParkourListener implements Listener {

    private static final String FLY_PERM = "crystal.fly";

    /** Marks a player mid-run so the lobby's void rescue leaves them alone. */
    private static final String RUNNING_TAG = "crystal_parkour_running";

    /** Minigame hotbar items (handled by material in onInteract; no command needed). */
    private static final Material ITEM_CHECKPOINT = Material.ENDER_PEARL;
    private static final Material ITEM_RESTART = Material.CLOCK;
    private static final Material ITEM_EXIT = Material.BARRIER;

    private final CrystalParkourPlugin plugin;
    private final CrystalCore crystal;
    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();

    public ParkourListener(CrystalParkourPlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
    }

    private static final class Run {
        final long startNanos;
        int lastCheckpoint = -1;   // index into the configured checkpoints; -1 = none
        ItemStack[] savedStorage;  // the lobby hotbar to restore on finish/exit
        Run(long startNanos) { this.startNanos = startNanos; }
    }

    /** Restore anyone still mid-run; called from the plugin's onDisable. */
    public void shutdown() {
        for (UUID id : new ArrayList<>(runs.keySet())) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) {
                endRun(p); // restore anyone still running
            }
        }
    }

    // ── gameplay ──

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null
                || (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ())) {
            return; // only react on block changes
        }
        ParkourCourse c = plugin.course();
        if (c == null || !c.isPlayable()) {
            return;
        }
        Player p = event.getPlayer();
        Run run = runs.get(p.getUniqueId());

        if (run == null) {
            if (c.inStart(to)) {
                startRun(p); // stepping on the start line begins the run
            }
            return;
        }

        // Re-entering the start (after 1s, so we don't get stuck at 0) restarts.
        if (c.inStart(to) && System.nanoTime() - run.startNanos > 1_000_000_000L) {
            startRun(p);
            return;
        }
        // Falling → back to the last checkpoint (never the lobby spawn).
        if (to.getY() < c.fallY()) {
            teleportToCheckpoint(p, run);
            return;
        }
        int cp = c.checkpointAt(to);
        if (cp > run.lastCheckpoint) {
            run.lastCheckpoint = cp;
            p.sendActionBar(Component.text("Checkpoint " + (cp + 1) + "!", NamedTextColor.AQUA));
        }
        if (c.inFinish(to)) {
            finishRun(p, run);
            return;
        }
        p.sendActionBar(Component.text(CrystalParkourPlugin.formatTime((System.nanoTime() - run.startNanos) / 1_000_000),
                NamedTextColor.YELLOW));
    }

    /** Begin (or restart) a run: lock the player into the minigame. */
    private void startRun(Player p) {
        Run existing = runs.get(p.getUniqueId());
        Run run = new Run(System.nanoTime());
        run.savedStorage = existing != null && existing.savedStorage != null
                ? existing.savedStorage
                : p.getInventory().getStorageContents().clone();
        runs.put(p.getUniqueId(), run);

        p.addScoreboardTag(RUNNING_TAG);
        p.setGameMode(GameMode.ADVENTURE);
        p.setFlying(false);
        p.setAllowFlight(false);
        giveParkourHotbar(p);
        p.sendActionBar(Component.text("Vai!", NamedTextColor.GREEN));
    }

    private void finishRun(Player p, Run run) {
        long ms = (System.nanoTime() - run.startNanos) / 1_000_000;
        endRun(p);
        p.teleport(arrival());
        String uuid = p.getUniqueId().toString();
        String name = p.getName();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ParkourResult r = crystal.backend().submitParkourTime(uuid, name, ms);
                p.sendMessage(Component.text("Você terminou em ", NamedTextColor.GREEN)
                        .append(Component.text(CrystalParkourPlugin.formatTime(ms), NamedTextColor.YELLOW))
                        .append(Component.text(r.record() ? "  (NOVO RECORDE! #" + r.rank() + ")"
                                : "  (seu recorde: " + CrystalParkourPlugin.formatTime(r.bestTimeMs()) + ", #" + r.rank() + ")",
                                NamedTextColor.GOLD)));
                plugin.refreshHologram(); // a new record updates the finish hologram
            } catch (Exception e) {
                plugin.getLogger().warning("Parkour submit failed: " + e);
            }
        });
    }

    /** End a run and restore the lobby state (hotbar, flight). */
    private void endRun(Player p) {
        Run run = runs.remove(p.getUniqueId());
        p.removeScoreboardTag(RUNNING_TAG);
        if (run != null && run.savedStorage != null) {
            p.getInventory().setStorageContents(run.savedStorage);
        }
        if (p.hasPermission(FLY_PERM)) {
            p.setAllowFlight(true); // mirrors the lobby granting flight on join
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        runs.remove(event.getPlayer().getUniqueId());
        event.getPlayer().removeScoreboardTag(RUNNING_TAG);
    }

    /** The point a player returns to: spawn (arrival) if set, else the start line. */
    private Location arrival() {
        ParkourCourse c = plugin.course();
        return c.hasSpawn() ? c.spawnLocation() : c.startLocation();
    }

    // ── minigame hotbar ──

    private void giveParkourHotbar(Player p) {
        PlayerInventory inv = p.getInventory();
        inv.setStorageContents(new ItemStack[36]); // clear storage; keep armour (cosmetics)
        inv.setItem(0, item(ITEM_CHECKPOINT, "§b§lÚltimo Checkpoint", "§7Voltar ao último checkpoint"));
        inv.setItem(1, item(ITEM_RESTART, "§e§lReiniciar", "§7Recomeçar do início"));
        inv.setItem(8, item(ITEM_EXIT, "§c§lSair do Parkour", "§7Voltar ao lobby"));
        inv.setHeldItemSlot(0);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getItem() == null) {
            return;
        }
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK
                && a != Action.LEFT_CLICK_AIR && a != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        Player p = event.getPlayer();
        if (!runs.containsKey(p.getUniqueId())) {
            return; // only the minigame hotbar reacts, and only mid-run
        }
        switch (event.getItem().getType()) {
            case ENDER_PEARL -> { event.setCancelled(true); teleportToCheckpoint(p, runs.get(p.getUniqueId())); }
            case CLOCK -> { event.setCancelled(true); restartRun(p); }
            case BARRIER -> { event.setCancelled(true); exitRun(p); }
            default -> { }
        }
    }

    /** Block all commands during a run (except /parkour itself). */
    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!runs.containsKey(event.getPlayer().getUniqueId())) {
            return;
        }
        String msg = event.getMessage().toLowerCase();
        if (msg.startsWith("/parkour") || msg.equals("/pk") || msg.startsWith("/pk ")) {
            return; // allow the parkour command as a fallback
        }
        event.setCancelled(true);
        event.getPlayer().sendActionBar(
                Component.text("Comandos desativados durante o parkour.", NamedTextColor.RED));
    }

    private void teleportToCheckpoint(Player p, Run run) {
        if (run == null) {
            return;
        }
        ParkourCourse c = plugin.course();
        Location back = run.lastCheckpoint >= 0
                ? c.checkpointLocation(run.lastCheckpoint)
                : c.startLocation();
        back.setYaw(p.getLocation().getYaw());
        back.setPitch(p.getLocation().getPitch());
        p.teleport(back);
    }

    private void restartRun(Player p) {
        p.teleport(plugin.course().startLocation());
        startRun(p); // resets the timer; keeps the saved lobby hotbar
    }

    // ── command entry points (driven by ParkourCommand) ──

    /** Compass entry: teleport to the spawn; stepping on the start line begins the run. */
    public void startPlaying(Player player) {
        ParkourCourse c = plugin.course();
        if (c == null || !c.isPlayable()) {
            player.sendMessage(Component.text("O parkour ainda não foi configurado.", NamedTextColor.RED));
            return;
        }
        if (runs.containsKey(player.getUniqueId())) {
            endRun(player); // leaving via the compass while running restores the lobby
        }
        player.teleport(arrival());
        player.sendActionBar(Component.text("Vá até o início para começar!", NamedTextColor.GREEN));
    }

    public void exitRun(Player p) {
        endRun(p);
        p.teleport(arrival());
        p.sendActionBar(Component.text("Você saiu do parkour.", NamedTextColor.GRAY));
    }

    // ── helpers ──

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack it = new ItemStack(material);
        var meta = it.getItemMeta();
        meta.displayName(Component.text(name).decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) {
            List<Component> lines = new ArrayList<>();
            for (String l : lore) {
                lines.add(Component.text(l).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lines);
        }
        it.setItemMeta(meta);
        return it;
    }
}
