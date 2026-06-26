package com.redecrystal.lobby;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.NetworkServer;
import com.redecrystal.core.http.ProfileData;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The lobby's locked cosmetic hotbar and its menus. Slots: 0 games selector
 * (compass → GUI), 4 profile (head), 7 hide-players toggle, 8 cosmetics (stub).
 * The lobby inventory is read-only (no drop/move/click). Clicking the parkour
 * entry simply runs {@code /parkour}, keeping this plugin decoupled from it.
 */
public final class LobbyHotbar implements Listener {

    private final JavaPlugin plugin;
    private final CrystalCore crystal;
    private final Map<UUID, Boolean> playersHidden = new ConcurrentHashMap<>();
    private final NamespacedKey lobbyKey;

    public LobbyHotbar(JavaPlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
        this.lobbyKey = new NamespacedKey(plugin, "lobby-id");
    }

    /** Marks an inventory we own so clicks in it are handled, not blocked generically. */
    private record MenuHolder(String type) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> giveHotbar(p)); // after inventory load
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
        p.getInventory().setItem(0, named(new ItemStack(Material.COMPASS), "§bModos de Jogo", "§7Clique para escolher um modo"));
        p.getInventory().setItem(3, named(new ItemStack(Material.RED_BED), "§aLobbys", "§7Clique para trocar de lobby"));
        p.getInventory().setItem(4, profileHead(p));
        p.getInventory().setItem(7, hideToggleItem(p));
        p.getInventory().setItem(8, named(new ItemStack(Material.NETHER_STAR), "§dCosméticos", "§7Em breve"));
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
        Inventory inv = Bukkit.createInventory(holder, 9, Component.text("Jogos"));
        inv.setItem(4, named(new ItemStack(Material.FEATHER), "§aParkour", "§7Clique para jogar"));
        p.openInventory(inv);
    }

    private void openCosmetics(Player p) {
        MenuHolder holder = new MenuHolder("cosmetics");
        Inventory inv = Bukkit.createInventory(holder, 9, Component.text("Cosméticos"));
        inv.setItem(4, named(new ItemStack(Material.BARRIER), "§cEm breve", "§7Ainda não disponível"));
        p.openInventory(inv);
    }

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
        int rows = Math.max(1, (lobbies.size() + 8) / 9);
        int size = Math.min(rows * 9, 54);
        MenuHolder holder = new MenuHolder("lobbys");
        Inventory inv = Bukkit.createInventory(holder, size, Component.text("Lobbys"));
        String current = crystal.config().serverId();
        int slot = 0;
        for (NetworkServer s : lobbies) {
            if (slot >= inv.getSize()) {
                break;
            }
            boolean isCurrent = s.serverId().equals(current);
            ItemStack item = new ItemStack(isCurrent ? Material.EMERALD_BLOCK : Material.GRASS_BLOCK);
            var meta = item.getItemMeta();
            meta.displayName(Component.text((isCurrent ? "§a» " : "§e") + s.serverId())
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(java.util.List.of(
                    Component.text("§7Jogadores: §f" + s.onlinePlayers() + "/" + s.maxPlayers())
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text(isCurrent ? "§7Você está aqui" : "§aClique para conectar")
                            .decoration(TextDecoration.ITALIC, false)));
            if (!isCurrent) {
                meta.getPersistentDataContainer().set(lobbyKey, PersistentDataType.STRING, s.serverId());
            }
            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }
        if (lobbies.isEmpty()) {
            inv.setItem(4, named(new ItemStack(Material.BARRIER), "§cNenhum lobby disponível", "§7Tente novamente"));
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
        if ("games".equals(menu.type()) && clicked != null && clicked.getType() == Material.FEATHER) {
            p.closeInventory();
            p.performCommand("parkour");
            return;
        }
        if ("lobbys".equals(menu.type()) && clicked != null && clicked.getItemMeta() != null) {
            String target = clicked.getItemMeta().getPersistentDataContainer()
                    .get(lobbyKey, PersistentDataType.STRING);
            if (target != null) {
                p.closeInventory();
                connectTo(p, target);
            }
        }
    }

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
        Inventory inv = Bukkit.createInventory(holder, 9, Component.text("Perfil"));
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(p);
            skull.displayName(Component.text("§a" + p.getName()).decoration(TextDecoration.ITALIC, false));
            if (d != null) {
                skull.lore(java.util.List.of(
                        Component.text("§7Rank: §6" + d.rank()).decoration(TextDecoration.ITALIC, false),
                        Component.text("§7Level: §b" + d.level()).decoration(TextDecoration.ITALIC, false),
                        Component.text("§7Coins: §e" + d.coins()).decoration(TextDecoration.ITALIC, false)));
            } else {
                skull.lore(java.util.List.of(
                        Component.text("§cPerfil ainda não carregado.").decoration(TextDecoration.ITALIC, false)));
            }
            head.setItemMeta(skull);
        }
        inv.setItem(4, head);
        p.openInventory(inv);
    }

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
        return named(new ItemStack(hidden ? Material.GRAY_DYE : Material.LIME_DYE),
                hidden ? "§7Jogadores: Escondidos" : "§aJogadores: Visíveis", "§7Clique para alternar");
    }

    private ItemStack profileHead(Player p) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(p);
            skull.displayName(Component.text("§aPerfil").decoration(TextDecoration.ITALIC, false));
            head.setItemMeta(skull);
        }
        return head;
    }

    private ItemStack named(ItemStack item, String name, String lore) {
        var meta = item.getItemMeta();
        meta.displayName(Component.text(name).decoration(TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(Component.text(lore).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }
}
