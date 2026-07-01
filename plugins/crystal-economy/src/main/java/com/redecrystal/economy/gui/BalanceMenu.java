package com.redecrystal.economy.gui;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.EconomyData;
import com.redecrystal.economy.CrystalEconomyPlugin;
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

/**
 * The {@code /saldo} balance GUI: a Money item and a Tokens item showing the
 * viewer's current amounts. The balance is fetched off the main thread (a null
 * row counts as zeroed) and the inventory is opened back on it. Clicks are purely
 * visual (cancelled).
 */
public final class BalanceMenu implements Listener {

    private static final int SIZE = 27;         // 3 rows
    private static final int MONEY_SLOT = 11;
    private static final int TOKENS_SLOT = 15;

    private final CrystalEconomyPlugin plugin;
    private final CrystalCore crystal;

    public BalanceMenu(CrystalEconomyPlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
    }

    /** Marks an inventory this menu owns so its clicks are cancelled. */
    private record Holder() implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    /** Fetch the balance off-thread, build the items, open on the main thread. */
    public void open(Player player) {
        String uuid = player.getUniqueId().toString();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            long money;
            long tokens;
            try {
                EconomyData data = crystal.economy().get(uuid);
                money = data == null ? 0 : data.money();
                tokens = data == null ? 0 : data.tokens();
            } catch (Exception e) {
                money = 0;
                tokens = 0;
            }
            Inventory inv = Bukkit.createInventory(new Holder(), SIZE, Component.text("Seu Saldo"));
            inv.setItem(MONEY_SLOT, item(Material.GOLD_INGOT, "Money", money, NamedTextColor.GOLD));
            inv.setItem(TOKENS_SLOT, item(Material.EMERALD, "Tokens", tokens, NamedTextColor.GREEN));
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.openInventory(inv);
                }
            });
        });
    }

    private ItemStack item(Material material, String label, long amount, NamedTextColor color) {
        ItemStack it = new ItemStack(material);
        var meta = it.getItemMeta();
        meta.displayName(Component.text(label + ": " + amount, color)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Seu total de " + label + ".", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
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
