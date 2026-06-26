package com.redecrystal.lobby;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.cargo.CargoResolver;
import com.redecrystal.core.http.NetworkServer;
import com.redecrystal.core.http.ProfileData;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The lobby's locked cosmetic hotbar and its menus (all GUIs use a bordered
 * 3-row layout). Hotbar slots: 0 games, 3 lobbys, 4 profile (head), 7 hide
 * toggle, 8 cosmetics. The lobby inventory is read-only. Cosmetics are particle
 * trails applied per-player (session-scoped) by a repeating task.
 */
public final class LobbyHotbar implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JavaPlugin plugin;
    private final CrystalCore crystal;
    private final Map<UUID, Boolean> playersHidden = new ConcurrentHashMap<>();
    private final Map<UUID, Cosmetic> activeCosmetic = new ConcurrentHashMap<>();
    private final NamespacedKey lobbyKey;
    private final NamespacedKey cosmeticKey;

    public LobbyHotbar(JavaPlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
        this.lobbyKey = new NamespacedKey(plugin, "lobby-id");
        this.cosmeticKey = new NamespacedKey(plugin, "cosmetic-id");
    }

    /** Particle-trail cosmetics offered in the cosmetics menu. */
    private enum Cosmetic {
        NONE(Material.BARRIER, "§cRemover cosmético", null),
        FLAME(Material.BLAZE_POWDER, "§6Trilha de Fogo", Particle.FLAME),
        HEART(Material.POPPY, "§dCorações", Particle.HEART),
        HAPPY(Material.SLIME_BALL, "§aVila Feliz", Particle.HAPPY_VILLAGER),
        NOTE(Material.NOTE_BLOCK, "§bNotas Musicais", Particle.NOTE),
        CLOUD(Material.WHITE_WOOL, "§fNuvem", Particle.CLOUD),
        PORTAL(Material.OBSIDIAN, "§5Portal", Particle.PORTAL);

        final Material icon;
        final String name;
        final Particle particle;

        Cosmetic(Material icon, String name, Particle particle) {
            this.icon = icon;
            this.name = name;
            this.particle = particle;
        }
    }

    /** Marks an inventory we own so clicks in it are handled, not blocked generically. */
    private record MenuHolder(String type) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    /** Start the cosmetic particle task. Called once from the plugin's onEnable. */
    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickCosmetics, 10L, 6L);
    }

    private void tickCosmetics() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            Cosmetic c = activeCosmetic.get(p.getUniqueId());
            if (c != null && c.particle != null) {
                p.getWorld().spawnParticle(c.particle, p.getLocation().add(0, 0.15, 0),
                        8, 0.3, 0.1, 0.3, 0.0);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> giveHotbar(p)); // after inventory load
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        playersHidden.remove(uuid);
        activeCosmetic.remove(uuid);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> giveHotbar(event.getPlayer()));
    }

    private void giveHotbar(Player p) {
        if (!p.isOnline()) {
            return;
        }
        p.getInventory().clear();
        p.getInventory().setItem(0, item(Material.COMPASS, "§bModos de Jogo", "§7Clique para escolher um modo"));
        p.getInventory().setItem(3, item(Material.RED_BED, "§aLobbys", "§7Clique para trocar de lobby"));
        p.getInventory().setItem(4, profileHead(p));
        p.getInventory().setItem(7, hideToggleItem(p));
        p.getInventory().setItem(8, item(Material.NETHER_STAR, "§dCosméticos", "§7Personalize o seu visual"));
        p.getInventory().setHeldItemSlot(0);
    }

    // ── locks ──

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        event.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        event.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof MenuHolder menu) {
            event.setCancelled(true);
            handleMenuClick((Player) event.getWhoClicked(), menu, event.getCurrentItem());
            return;
        }
        // Lock the lobby inventory itself (no moving the cosmetic hotbar).
        if (event.getClickedInventory() != null
                && event.getClickedInventory().equals(event.getWhoClicked().getInventory())) {
            event.setCancelled(true);
        }
    }

    // ── interactions ──

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getItem() == null
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK
                && event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK)) {
            return;
        }
        Player p = event.getPlayer();
        switch (event.getItem().getType()) {
            case COMPASS -> { event.setCancelled(true); openGames(p); }
            case RED_BED -> { event.setCancelled(true); openLobbys(p); }
            case PLAYER_HEAD -> { event.setCancelled(true); showProfile(p); }
            case LIME_DYE, GRAY_DYE -> { event.setCancelled(true); toggleHide(p); }
            case NETHER_STAR -> { event.setCancelled(true); openCosmetics(p); }
            default -> { }
        }
    }

    private void openGames(Player p) {
        MenuHolder holder = new MenuHolder("games");
        Inventory inv = Bukkit.createInventory(holder, 27, Component.text("Modos de Jogo"));
        border(inv, 27);
        inv.setItem(13, item(Material.FEATHER, "§aParkour", "§7Teste a sua agilidade", "§eClique para jogar"));
        p.openInventory(inv);
    }

    // ── cosmetics ──

    private void openCosmetics(Player p) {
        MenuHolder holder = new MenuHolder("cosmetics");
        Inventory inv = Bukkit.createInventory(holder, 27, Component.text("Cosméticos"));
        border(inv, 27);
        Cosmetic current = activeCosmetic.get(p.getUniqueId());
        int slot = 10;
        for (Cosmetic c : Cosmetic.values()) {
            boolean selected = (current == null ? Cosmetic.NONE : current) == c;
            List<String> lore = new ArrayList<>();
            if (c == Cosmetic.NONE) {
                lore.add("§7Desativa o cosmético atual");
            } else {
                lore.add("§7Partículas que seguem você");
            }
            lore.add(" ");
            lore.add(selected ? "§a✔ Selecionado" : "§eClique para usar");
            ItemStack it = item(c.icon, c.name, lore.toArray(new String[0]));
            if (selected && c != Cosmetic.NONE) {
                glow(it);
            }
            var meta = it.getItemMeta();
            meta.getPersistentDataContainer().set(cosmeticKey, PersistentDataType.STRING, c.name());
            it.setItemMeta(meta);
            inv.setItem(slot++, it);
        }
        p.openInventory(inv);
    }

    private void selectCosmetic(Player p, Cosmetic c) {
        if (c == Cosmetic.NONE) {
            activeCosmetic.remove(p.getUniqueId());
            p.sendActionBar(Component.text("Cosmético removido", NamedTextColor.GRAY));
        } else {
            activeCosmetic.put(p.getUniqueId(), c);
            p.sendActionBar(MM.deserialize("<green>Cosmético ativado!"));
        }
        openCosmetics(p); // refresh selection state
    }

    // ── lobbys ──

    private void openLobbys(Player p) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<NetworkServer> fetched;
            try {
                fetched = crystal.backend().listServers("lobby");
            } catch (Exception e) {
                fetched = List.of();
            }
            final List<NetworkServer> online = fetched.stream()
                    .filter(NetworkServer::isOnline)
                    .sorted(Comparator.comparing(NetworkServer::serverId))
                    .toList();
            Bukkit.getScheduler().runTask(plugin, () -> openLobbyMenu(p, online));
        });
    }

    private void openLobbyMenu(Player p, List<NetworkServer> lobbies) {
        if (!p.isOnline()) {
            return;
        }
        List<Integer> slots = innerSlots(lobbies.size());
        int size = ((slots.isEmpty() ? 1 : (slots.get(slots.size() - 1) / 9) + 1) + 1) * 9;
        size = Math.max(27, Math.min(size, 54));
        MenuHolder holder = new MenuHolder("lobbys");
        Inventory inv = Bukkit.createInventory(holder, size, Component.text("Lobbys"));
        border(inv, size);

        String current = crystal.config().serverId();
        int i = 0;
        for (NetworkServer s : lobbies) {
            if (i >= slots.size()) {
                break;
            }
            boolean isCurrent = s.serverId().equals(current);
            boolean full = s.onlinePlayers() >= s.maxPlayers() && s.maxPlayers() > 0;

            Material block = isCurrent ? Material.EMERALD_BLOCK
                    : full ? Material.REDSTONE_BLOCK : Material.LIME_CONCRETE;
            String title = (isCurrent ? "§a§l" : full ? "§c§l" : "§e§l") + s.serverId().toUpperCase();
            String status = isCurrent ? "§a➜ Você está aqui"
                    : full ? "§c✖ Servidor cheio" : "§a✔ Clique para conectar";

            ItemStack item = item(block, title,
                    "§8» RedeCrystal Lobby",
                    " ",
                    "§7Jogadores: §f" + s.onlinePlayers() + "§7/§f" + s.maxPlayers(),
                    "§7Status: " + (full ? "§cCheio" : "§aDisponível"),
                    " ",
                    status);
            if (isCurrent) {
                glow(item);
            } else if (!full) {
                var meta = item.getItemMeta();
                meta.getPersistentDataContainer().set(lobbyKey, PersistentDataType.STRING, s.serverId());
                item.setItemMeta(meta);
            }
            inv.setItem(slots.get(i++), item);
        }
        if (lobbies.isEmpty()) {
            inv.setItem(13, item(Material.BARRIER, "§cNenhum lobby disponível", "§7Tente novamente em instantes"));
        }
        p.openInventory(inv);
    }

    private void connectTo(Player p, String server) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(out)) {
            dos.writeUTF("Connect");
            dos.writeUTF(server);
        } catch (IOException e) {
            return;
        }
        p.sendPluginMessage(plugin, "BungeeCord", out.toByteArray());
    }

    private void handleMenuClick(Player p, MenuHolder menu, ItemStack clicked) {
        if (clicked == null || clicked.getItemMeta() == null) {
            return;
        }
        switch (menu.type()) {
            case "games" -> {
                if (clicked.getType() == Material.FEATHER) {
                    p.closeInventory();
                    p.performCommand("parkour");
                }
            }
            case "lobbys" -> {
                String target = clicked.getItemMeta().getPersistentDataContainer()
                        .get(lobbyKey, PersistentDataType.STRING);
                if (target != null) {
                    p.closeInventory();
                    connectTo(p, target);
                }
            }
            case "cosmetics" -> {
                String id = clicked.getItemMeta().getPersistentDataContainer()
                        .get(cosmeticKey, PersistentDataType.STRING);
                if (id != null) {
                    try {
                        selectCosmetic(p, Cosmetic.valueOf(id));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            default -> { }
        }
    }

    // ── profile ──

    private void showProfile(Player p) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ProfileData fetched;
            try {
                fetched = crystal.backend().getProfile(p.getUniqueId().toString());
            } catch (Exception e) {
                fetched = null;
            }
            final ProfileData data = fetched;
            Bukkit.getScheduler().runTask(plugin, () -> openProfile(p, data));
        });
    }

    private void openProfile(Player p, ProfileData d) {
        if (!p.isOnline()) {
            return;
        }
        MenuHolder holder = new MenuHolder("profile");
        Inventory inv = Bukkit.createInventory(holder, 27, Component.text("Perfil"));
        border(inv, 27);

        Component cargo = resolveCargo(p);

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(p);
            skull.displayName(cargo.append(line(" <white>" + p.getName())));
            List<Component> lore = new ArrayList<>();
            lore.add(line("<gray>Cargo: ").append(cargo));
            lore.add(Component.empty());
            lore.add(line("<gray>Membro desde: <white>" + (d == null ? "-" : formatDate(d.createdAt()))));
            skull.lore(lore);
            head.setItemMeta(skull);
        }
        inv.setItem(13, head);

        if (d != null) {
            inv.setItem(11, item(Material.CLOCK, "§bTempo Online",
                    "§7Total jogado:", "§f" + formatDuration(d.playSeconds())));
            inv.setItem(15, item(Material.DIAMOND_SWORD, "§cCombate",
                    "§7Abates: §a" + d.kills(), "§7Mortes: §c" + d.deaths()));
            inv.setItem(22, item(Material.WRITABLE_BOOK, "§eMensagens",
                    "§7Enviadas na rede:", "§f" + d.messagesSent()));
        } else {
            inv.setItem(22, item(Material.BARRIER, "§cPerfil indisponível",
                    "§7Tente novamente em instantes"));
        }
        p.openInventory(inv);
    }

    /** The player's cargo tag (e.g. [CEO]) as a Component, defaulting to [MEMBRO]. */
    private Component resolveCargo(Player p) {
        CargoResolver.Cargo c = CargoResolver.resolve(
                crystal.configProvider().get("chat"), p::hasPermission);
        String prefix = c == null ? "<gray>[MEMBRO]" : c.prefix();
        return line(prefix);
    }

    // ── hide players ──

    private void toggleHide(Player p) {
        boolean nowHidden = !playersHidden.getOrDefault(p.getUniqueId(), false);
        playersHidden.put(p.getUniqueId(), nowHidden);
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(p)) {
                continue;
            }
            if (nowHidden) {
                p.hidePlayer(plugin, other);
            } else {
                p.showPlayer(plugin, other);
            }
        }
        p.getInventory().setItem(7, hideToggleItem(p));
        p.sendActionBar(Component.text(nowHidden ? "Jogadores escondidos" : "Jogadores visíveis",
                nowHidden ? NamedTextColor.GRAY : NamedTextColor.GREEN));
    }

    // ── item builders ──

    private ItemStack hideToggleItem(Player p) {
        boolean hidden = playersHidden.getOrDefault(p.getUniqueId(), false);
        return item(hidden ? Material.GRAY_DYE : Material.LIME_DYE,
                hidden ? "§7Jogadores: §cEscondidos" : "§aJogadores: §aVisíveis",
                "§7Clique para alternar");
    }

    private ItemStack profileHead(Player p) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(p);
            skull.displayName(Component.text("§aMeu Perfil").decoration(TextDecoration.ITALIC, false));
            skull.lore(List.of(Component.text("§7Veja as suas estatísticas").decoration(TextDecoration.ITALIC, false)));
            head.setItemMeta(skull);
        }
        return head;
    }

    /** Build an item with a legacy-coloured name and multi-line lore. */
    private ItemStack item(Material material, String name, String... lore) {
        ItemStack it = new ItemStack(material);
        var meta = it.getItemMeta();
        meta.displayName(Component.text(name).decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) {
            List<Component> loreLines = new ArrayList<>();
            for (String l : lore) {
                loreLines.add(Component.text(l).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(loreLines);
        }
        it.setItemMeta(meta);
        return it;
    }

    /** Add a subtle enchant glow (hidden enchant text). */
    private void glow(ItemStack it) {
        var meta = it.getItemMeta();
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        it.setItemMeta(meta);
    }

    /** Fill the border (edges) of a chest inventory with purple glass panes. */
    private void border(Inventory inv, int size) {
        ItemStack pane = item(Material.PURPLE_STAINED_GLASS_PANE, "§r");
        int rows = size / 9;
        for (int i = 0; i < size; i++) {
            int col = i % 9;
            int row = i / 9;
            if (row == 0 || row == rows - 1 || col == 0 || col == 8) {
                inv.setItem(i, pane);
            }
        }
    }

    /** Inner (non-border) slots for as many lobbies as needed, 7 per content row. */
    private List<Integer> innerSlots(int count) {
        int rows = Math.max(1, (int) Math.ceil(count / 7.0));
        List<Integer> slots = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 1; c <= 7; c++) {
                slots.add((r + 1) * 9 + c);
            }
        }
        return slots;
    }

    private static Component line(String mini) {
        return MM.deserialize(mini).decoration(TextDecoration.ITALIC, false);
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

    private static String formatDate(String iso) {
        if (iso == null || iso.isBlank()) {
            return "-";
        }
        try {
            return OffsetDateTime.parse(iso).format(DATE_FMT);
        } catch (Exception e) {
            return iso.length() >= 10 ? iso.substring(0, 10) : iso;
        }
    }
}
