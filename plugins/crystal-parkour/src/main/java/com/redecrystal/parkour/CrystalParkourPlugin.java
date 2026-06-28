package com.redecrystal.parkour;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.ParkourEntry;
import com.redecrystal.core.http.ParkourResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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
import org.bukkit.plugin.java.JavaPlugin;

/**
 * In-lobby parkour. The whole course is command-defined and lives in the
 * centralized {@code parkour} config, so it replicates to every (identical)
 * lobby like the spawn: {@code /parkour setspawn} (compass arrival),
 * {@code setstart} (start line / iron plate), {@code setcheckpoint} (each
 * checkpoint) and {@code setfinish}. A floating hologram marks every point.
 * Detection is by proximity to those points.
 *
 * <p>During a run the player is locked into the minigame: ADVENTURE mode, no
 * flight, commands blocked, and a dedicated hotbar (back-to-checkpoint / restart
 * / exit). The lobby hotbar is saved on start and restored on finish/exit.
 * Finish times go to the backend best-time leaderboard.
 */
public final class CrystalParkourPlugin extends JavaPlugin implements Listener {

    private static final String CONFIG_KEY = "parkour";
    private static final String ADMIN_PERM = "crystal.parkour.admin";
    private static final String FLY_PERM = "crystal.fly";

    /** Plate (and themed support block) placed under each point's hologram. */
    private static final Material START_PLATE = Material.HEAVY_WEIGHTED_PRESSURE_PLATE;
    private static final Material START_SUPPORT = Material.IRON_BLOCK;
    private static final Material CHECKPOINT_PLATE = Material.LIGHT_WEIGHTED_PRESSURE_PLATE;
    private static final Material CHECKPOINT_SUPPORT = Material.GOLD_BLOCK;
    private static final Material FINISH_PLATE = Material.POLISHED_BLACKSTONE_PRESSURE_PLATE;
    private static final Material FINISH_SUPPORT = Material.DIAMOND_BLOCK;

    /** Minigame hotbar items (handled by material in onInteract; no command needed). */
    private static final Material ITEM_CHECKPOINT = Material.ENDER_PEARL;
    private static final Material ITEM_RESTART = Material.CLOCK;
    private static final Material ITEM_EXIT = Material.BARRIER;

    /** Marks a player mid-run so the lobby's void rescue leaves them alone. */
    private static final String RUNNING_TAG = "crystal_parkour_running";

    private CrystalCore crystal;
    private volatile ParkourCourse course;
    private ParkourHologram hologram;
    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();

    private static final class Run {
        final long startNanos;
        int lastCheckpoint = -1;   // index into the configured checkpoints; -1 = none
        ItemStack[] savedStorage;  // the lobby hotbar to restore on finish/exit
        Run(long startNanos) { this.startNanos = startNanos; }
    }

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());
        refreshCourse(crystal.configProvider().get(CONFIG_KEY).config());
        crystal.configProvider().onChange(CONFIG_KEY, cfg -> {
            refreshCourse(cfg.config());
            Bukkit.getScheduler().runTask(this, this::rebuildHolograms); // config events are off-thread
        });
        getServer().getPluginManager().registerEvents(this, this);

        this.hologram = new ParkourHologram(this);
        // Defer until the world is loaded; rebuildHolograms clears orphans first.
        Bukkit.getScheduler().runTaskLater(this, this::rebuildHolograms, 40L);
        Bukkit.getScheduler().runTaskTimer(this, this::refreshHologram, 200L, 600L);

        getLogger().info("CrystalParkour enabled (course "
                + (course.isPlayable() ? "loaded)" : "not configured yet)"));
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(runs.keySet())) {
            Player p = getServer().getPlayer(id);
            if (p != null) {
                endRun(p); // restore anyone still running
            }
        }
        if (hologram != null) {
            hologram.remove();
        }
        if (crystal != null) {
            crystal.close();
        }
    }

    private void refreshCourse(Map<String, Object> cfg) {
        this.course = ParkourCourse.fromConfig(cfg);
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
        ParkourCourse c = course;
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
        p.sendActionBar(Component.text(formatTime((System.nanoTime() - run.startNanos) / 1_000_000),
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
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                ParkourResult r = crystal.backend().submitParkourTime(uuid, name, ms);
                p.sendMessage(Component.text("Você terminou em ", NamedTextColor.GREEN)
                        .append(Component.text(formatTime(ms), NamedTextColor.YELLOW))
                        .append(Component.text(r.record() ? "  (NOVO RECORDE! #" + r.rank() + ")"
                                : "  (seu recorde: " + formatTime(r.bestTimeMs()) + ", #" + r.rank() + ")",
                                NamedTextColor.GOLD)));
                refreshHologram(); // a new record updates the finish hologram
            } catch (Exception e) {
                getLogger().warning("Parkour submit failed: " + e);
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
        ParkourCourse c = course;
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
        Location back = run.lastCheckpoint >= 0
                ? course.checkpointLocation(run.lastCheckpoint)
                : course.startLocation();
        back.setYaw(p.getLocation().getYaw());
        back.setPitch(p.getLocation().getPitch());
        p.teleport(back);
    }

    private void restartRun(Player p) {
        p.teleport(course.startLocation());
        startRun(p); // resets the timer; keeps the saved lobby hotbar
    }

    private void exitRun(Player p) {
        endRun(p);
        p.teleport(arrival());
        p.sendActionBar(Component.text("Você saiu do parkour.", NamedTextColor.GRAY));
    }

    // ── holograms (one over every point) ──

    private void rebuildHolograms() {
        if (hologram == null) {
            return;
        }
        hologram.clearAll();
        ParkourCourse c = course;
        if (c == null) {
            return;
        }
        // Each configured point gets its plate (+ themed support) and a hologram.
        if (c.hasStart()) {
            Location s = c.startLocation();
            placePlate(s, START_PLATE, START_SUPPORT);
            hologram.addLabel(above(s), startLabel());
        }
        for (int i = 0; i < c.checkpointCount(); i++) {
            Location cp = c.checkpointLocation(i);
            placePlate(cp, CHECKPOINT_PLATE, CHECKPOINT_SUPPORT);
            hologram.addLabel(above(cp), checkpointLabel(i + 1));
        }
        if (c.hasFinishPoint()) {
            Location f = c.finishLocation();
            placePlate(f, FINISH_PLATE, FINISH_SUPPORT);
            hologram.setFinish(above(f), finishLabel(null));
        }
        refreshHologram();
    }

    /** Place a pressure plate at the point with a themed solid block beneath it. */
    private void placePlate(Location point, Material plate, Material support) {
        World w = point.getWorld();
        if (w == null) {
            return;
        }
        int x = point.getBlockX();
        int y = point.getBlockY();
        int z = point.getBlockZ();
        w.getBlockAt(x, y - 1, z).setType(support, false);
        w.getBlockAt(x, y, z).setType(plate, false);
    }

    private void refreshHologram() {
        if (hologram == null) {
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            final List<ParkourEntry> top;
            try {
                top = crystal.backend().parkourTop(3);
            } catch (Exception e) {
                return; // backend down → keep the current text
            }
            getServer().getScheduler().runTask(this, () -> hologram.refreshFinish(finishLabel(top)));
        });
    }

    private static Location above(Location l) {
        return l.clone().add(0.0, 2.2, 0.0); // float a bit above the plate
    }

    private Component startLabel() {
        return Component.text("INÍCIO", NamedTextColor.GREEN, TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.text("Pise para começar", NamedTextColor.GRAY));
    }

    private Component checkpointLabel(int number) {
        return Component.text("CHECKPOINT " + number, NamedTextColor.AQUA, TextDecoration.BOLD);
    }

    private Component finishLabel(List<ParkourEntry> top) {
        Component head = Component.text("✦ CHEGADA ✦", NamedTextColor.GOLD, TextDecoration.BOLD);
        if (top == null || top.isEmpty()) {
            return head.append(Component.newline())
                    .append(Component.text("Seja o primeiro a terminar!", NamedTextColor.GRAY));
        }
        ParkourEntry best = top.get(0);
        return head.append(Component.newline())
                .append(Component.text("Recorde: ", NamedTextColor.GRAY))
                .append(Component.text(best.username() + " ", NamedTextColor.WHITE))
                .append(Component.text(formatTime(best.timeMs()), NamedTextColor.YELLOW));
    }

    // ── commands ──

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Apenas jogadores.");
            return true;
        }
        String sub = args.length == 0 ? "play" : args[0].toLowerCase();
        switch (sub) {
            case "play" -> startPlaying(player);
            case "top" -> showTop(player);
            case "reset", "sair" -> exitRun(player);
            case "setspawn" -> admin(player,
                    m -> m.put("spawn", ParkourCourse.facingMap(player.getLocation())),
                    "spawn (chegada da bússola) definido");
            case "setstart" -> admin(player,
                    m -> m.put("start", ParkourCourse.pointMap(player.getLocation())),
                    "início definido");
            case "setcheckpoint" -> admin(player, m -> {
                @SuppressWarnings("unchecked")
                List<Object> cps = new ArrayList<>((List<Object>) m.getOrDefault("checkpoints", new ArrayList<>()));
                cps.add(ParkourCourse.pointMap(player.getLocation()));
                m.put("checkpoints", cps);
            }, "checkpoint adicionado");
            case "removecheckpoint", "undocheckpoint" -> admin(player, m -> {
                if (m.get("checkpoints") instanceof List<?> l && !l.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    List<Object> cps = new ArrayList<>((List<Object>) l);
                    cps.remove(cps.size() - 1);
                    m.put("checkpoints", cps);
                }
            }, "último checkpoint removido");
            case "setfinish" -> admin(player,
                    m -> m.put("finish", ParkourCourse.pointMap(player.getLocation())),
                    "fim definido");
            case "setfall" -> admin(player,
                    m -> m.put("fallY", Math.round(player.getLocation().getY() - 3)),
                    "plano de queda definido");
            case "clear" -> admin(player, m -> {
                m.remove("spawn");
                m.remove("start");
                m.remove("checkpoints");
                m.remove("finish");
            }, "curso limpo");
            case "reload" -> getServer().getScheduler().runTaskAsynchronously(this, () -> {
                refreshCourse(crystal.backend().getConfig(CONFIG_KEY).config());
                Bukkit.getScheduler().runTask(this, this::rebuildHolograms);
                player.sendMessage(Component.text("Curso recarregado.", NamedTextColor.GREEN));
            });
            default -> player.sendMessage(Component.text(
                    "/parkour [top|reset|setspawn|setstart|setcheckpoint|removecheckpoint|setfinish|setfall|clear|reload]",
                    NamedTextColor.GRAY));
        }
        return true;
    }

    /** Compass entry: teleport to the spawn; stepping on the start line begins the run. */
    private void startPlaying(Player player) {
        ParkourCourse c = course;
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

    private void showTop(Player player) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            List<ParkourEntry> top = crystal.backend().parkourTop(10);
            player.sendMessage(Component.text("— Ranking Parkour (menor tempo) —", NamedTextColor.GOLD));
            if (top.isEmpty()) {
                player.sendMessage(Component.text("Ninguém completou ainda.", NamedTextColor.GRAY));
            }
            for (ParkourEntry e : top) {
                player.sendMessage(Component.text("#" + e.rank() + " ", NamedTextColor.YELLOW)
                        .append(Component.text(e.username() + " ", NamedTextColor.WHITE))
                        .append(Component.text(formatTime(e.timeMs()), NamedTextColor.AQUA)));
            }
        });
    }

    /** Admin helper: mutate the central parkour config and persist it (hot-reloads). */
    private void admin(Player player, Consumer<Map<String, Object>> mutator, String okMsg) {
        if (!player.hasPermission(ADMIN_PERM)) {
            player.sendMessage(Component.text("Sem permissão.", NamedTextColor.RED));
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            Map<String, Object> cfg = new HashMap<>(crystal.configProvider().get(CONFIG_KEY).config());
            cfg.put("world", player.getWorld().getName());
            cfg.putIfAbsent("fallY", Math.round(player.getLocation().getY() - 5));
            mutator.accept(cfg);
            crystal.backend().putConfig(CONFIG_KEY, cfg);
            player.sendMessage(Component.text("Parkour: " + okMsg + ".", NamedTextColor.GREEN));
        });
    }

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

    private static String formatTime(long ms) {
        return String.format("%.2fs", ms / 1000.0);
    }
}
