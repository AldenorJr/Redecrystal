package com.redecrystal.skin.menu;

import com.redecrystal.skin.menu.Menus.MenuHolder;
import com.redecrystal.skin.skin.SkinApplier;
import com.redecrystal.skin.skin.SkinHistory;
import com.redecrystal.skin.skin.SkinTexture;
import com.redecrystal.skin.store.SkinHistoryStore;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * GUI listing a player's skin history (most-recent first, marked active). Clicking
 * a head re-applies that skin instantly — the texture is already cached, so no
 * Mojang round-trip — and a "Limpar histórico" button wipes it. Opened by
 * {@code /skin} with no arguments.
 */
public final class SkinHistoryMenu implements Listener {

    private static final String TYPE = "skin:history";
    private static final String CLEAR = "__clear__";
    private static final String USE_PERM = "crystal.skin.use";
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final JavaPlugin plugin;
    private final SkinHistoryStore store;
    private final SkinApplier applier;
    private final NamespacedKey indexKey;
    private final NamespacedKey actionKey;

    public SkinHistoryMenu(JavaPlugin plugin, SkinHistoryStore store, SkinApplier applier) {
        this.plugin = plugin;
        this.store = store;
        this.applier = applier;
        this.indexKey = new NamespacedKey(plugin, "skin-index");
        this.actionKey = new NamespacedKey(plugin, "skin-action");
    }

    public void open(Player player) {
        List<SkinTexture> entries = store.get(player.getUniqueId()).entries();

        MenuHolder holder = new MenuHolder(TYPE, player.getUniqueId(), player.getName());
        Inventory inv = plugin.getServer().createInventory(
                holder, Menus.framedSize(Math.max(entries.size(), 1), true),
                Component.text("Skins · " + player.getName()));

        if (entries.isEmpty()) {
            inv.setItem(Menus.bodySlots(1).get(0), Menus.item(Material.BARRIER,
                    "§cNenhuma skin ainda", "§7Use §f/skin set <nick>§7 para aplicar uma"));
            player.openInventory(inv);
            return;
        }

        List<Integer> slots = Menus.bodySlots(entries.size());
        for (int i = 0; i < entries.size() && i < slots.size(); i++) {
            SkinTexture tex = entries.get(i);
            boolean active = i == 0; // the most-recent entry is the one currently worn
            ItemStack head = head(tex, active);
            var meta = head.getItemMeta();
            meta.getPersistentDataContainer().set(indexKey, PersistentDataType.INTEGER, i);
            head.setItemMeta(meta);
            inv.setItem(slots.get(i), head);
        }

        ItemStack clear = Menus.item(Material.LAVA_BUCKET, "§c§lLimpar histórico",
                "§7Remove todas as skins salvas");
        var cm = clear.getItemMeta();
        cm.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, CLEAR);
        clear.setItemMeta(cm);
        inv.setItem(Menus.barCenter(inv), clear);

        player.openInventory(inv);
    }

    /** A player-head icon rendering the stored skin texture. */
    private ItemStack head(SkinTexture tex, boolean active) {
        ItemStack it = Menus.item(Material.PLAYER_HEAD, "§f" + tex.name(),
                "§7Aplicada em §f" + DATE_FMT.format(Instant.ofEpochMilli(tex.appliedAt())),
                " ",
                active ? "§a✔ Skin atual" : "§eClique para reaplicar");
        if (it.getItemMeta() instanceof SkullMeta skull) {
            skull.setPlayerProfile(SkinApplier.profileFor(tex));
            it.setItemMeta(skull);
        }
        if (active) {
            Menus.glow(it);
        }
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof MenuHolder menu) || !TYPE.equals(menu.type())) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission(USE_PERM)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getItemMeta() == null) {
            return;
        }
        var pdc = clicked.getItemMeta().getPersistentDataContainer();

        if (CLEAR.equals(pdc.get(actionKey, PersistentDataType.STRING))) {
            store.clear(player.getUniqueId());
            player.sendActionBar(Component.text("Histórico de skins limpo", NamedTextColor.GRAY));
            open(player);
            return;
        }

        Integer index = pdc.get(indexKey, PersistentDataType.INTEGER);
        if (index == null) {
            return;
        }
        List<SkinTexture> entries = store.get(player.getUniqueId()).entries();
        if (index < 0 || index >= entries.size()) {
            return;
        }
        SkinTexture chosen = entries.get(index).usedAt(System.currentTimeMillis());
        applier.apply(player, chosen);
        store.record(player.getUniqueId(), chosen); // moves it back to the top
        player.sendActionBar(Component.text("Skin reaplicada: " + chosen.name(), NamedTextColor.GREEN));
        open(player); // refresh "active" marker / ordering
    }
}
