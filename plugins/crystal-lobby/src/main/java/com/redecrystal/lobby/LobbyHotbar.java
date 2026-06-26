package com.redecrystal.lobby;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.ProfileData;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    public LobbyHotbar(JavaPlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
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
        p.getInventory().setItem(0, named(new ItemStack(Material.COMPASS), "§bJogos", "§7Clique para escolher um jogo"));
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

    private void handleMenuClick(Player p, MenuHolder menu, ItemStack clicked) {
        if ("games".equals(menu.type()) && clicked != null && clicked.getType() == Material.FEATHER) {
            p.closeInventory();
            p.performCommand("parkour");
        }
    }

    private void showProfile(Player p) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ProfileData d = crystal.backend().getProfile(p.getUniqueId().toString());
                if (d == null) {
                    p.sendMessage(Component.text("Perfil ainda não carregado.", NamedTextColor.RED));
                    return;
                }
                p.sendMessage(Component.text("Rank ", NamedTextColor.GRAY)
                        .append(Component.text(d.rank(), NamedTextColor.GOLD))
                        .append(Component.text("  •  Level ", NamedTextColor.GRAY))
                        .append(Component.text(d.level(), NamedTextColor.AQUA))
                        .append(Component.text("  •  Coins ", NamedTextColor.GRAY))
                        .append(Component.text(d.coins(), NamedTextColor.YELLOW)));
            } catch (Exception e) {
                p.sendMessage(Component.text("Erro ao carregar perfil.", NamedTextColor.RED));
            }
        });
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
