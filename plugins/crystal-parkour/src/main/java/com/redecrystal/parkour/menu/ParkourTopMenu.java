package com.redecrystal.parkour.menu;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.ParkourEntry;
import com.redecrystal.parkour.CrystalParkourPlugin;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * The {@code /parkour top} leaderboard GUI: the top 10 as player heads plus a
 * footer with the viewer's own position. Data is fetched off the main thread and
 * the inventory is opened back on it. Clicks are purely visual (cancelled).
 */
public final class ParkourTopMenu implements Listener {

    private static final int SIZE = 36;                 // 4 rows
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 19, 20, 21, 22, 23};
    private static final int FOOTER_SLOT = 31;
    private static final int FETCH_LIMIT = 100;         // enough to locate the viewer's rank

    private final CrystalParkourPlugin plugin;
    private final CrystalCore crystal;

    public ParkourTopMenu(CrystalParkourPlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
    }

    /** Marks an inventory this menu owns so its clicks are cancelled. */
    private record Holder() implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    /** Fetch the leaderboard off-thread, build the heads, open on the main thread. */
    public void open(Player player) {
        String name = player.getName();
        String uuid = player.getUniqueId().toString();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<ParkourEntry> top;
            long best;
            try {
                top = crystal.backend().parkourTop(FETCH_LIMIT);
                best = crystal.backend().parkourBest(uuid);
            } catch (Exception e) {
                top = List.of();
                best = -1;
            }
            long myRank = -1;
            long myTime = best;
            for (ParkourEntry e : top) {
                if (e.username().equalsIgnoreCase(name)) {
                    myRank = e.rank();
                    myTime = e.timeMs();
                    break;
                }
            }
            Inventory inv = Bukkit.createInventory(new Holder(), SIZE, Component.text("Ranking do Parkour"));
            int shown = Math.min(SLOTS.length, top.size());
            for (int i = 0; i < shown; i++) {
                inv.setItem(SLOTS[i], head(top.get(i)));
            }
            inv.setItem(FOOTER_SLOT, footer(name, myRank, myTime));
            final Inventory built = inv;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.openInventory(built);
                }
            });
        });
    }

    private ItemStack head(ParkourEntry e) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(e.username()));
        meta.displayName(Component.text("#" + e.rank() + " " + e.username(),
                e.rank() == 1 ? NamedTextColor.GOLD : NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(CrystalParkourPlugin.formatTime(e.timeMs()), NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false)));
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack footer(String name, long rank, long timeMs) {
        ItemStack it = new ItemStack(Material.NAME_TAG);
        var meta = it.getItemMeta();
        meta.displayName(Component.text("Você: " + name, NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (timeMs > 0) {
            lore.add(Component.text("Tempo: " + CrystalParkourPlugin.formatTime(timeMs), NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text(rank > 0 ? "Posição: #" + rank : "Fora do top " + FETCH_LIMIT, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("Você ainda não completou.", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }
}
