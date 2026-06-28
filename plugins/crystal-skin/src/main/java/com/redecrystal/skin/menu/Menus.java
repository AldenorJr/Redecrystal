package com.redecrystal.skin.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

/** Shared GUI helpers + holder for the skin menus (mirrors the lobby/tag bordered
 *  layout: empty top row, content in columns 1–7, a control row at the bottom).
 *  All player-facing text is PT. */
public final class Menus {

    private Menus() {
    }

    /** Marks an inventory we own. {@code target}/{@code targetName} carry the owner. */
    public record MenuHolder(String type, UUID target, String targetName) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    /** Build an item with a legacy-coloured name and multi-line lore. */
    public static ItemStack item(Material material, String name, String... lore) {
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

    /** Add a subtle enchant glow (hidden enchant text). */
    public static void glow(ItemStack it) {
        var meta = it.getItemMeta();
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        it.setItemMeta(meta);
    }

    private static int contentRows(int count) {
        return Math.min(3, Math.max(1, (int) Math.ceil(count / 7.0)));
    }

    /** Centered content slots in columns 1–7; the top row stays empty. */
    public static List<Integer> bodySlots(int count) {
        int rows = contentRows(count);
        List<Integer> slots = new ArrayList<>();
        int remaining = count;
        for (int r = 0; r < rows; r++) {
            int inRow = Math.min(7, remaining);
            int startCol = 1 + (7 - inRow) / 2;
            for (int c = 0; c < inRow; c++) {
                slots.add((1 + r) * 9 + startCol + c);
            }
            remaining -= inRow;
        }
        return slots;
    }

    /** Inventory size for a framed list ({@code bar} reserves a bottom control row). */
    public static int framedSize(int count, boolean bar) {
        return (contentRows(count) + (bar ? 3 : 2)) * 9;
    }

    public static int barCenter(Inventory inv) {
        return inv.getSize() - 5;
    }

    public static int barLeft(Inventory inv) {
        return inv.getSize() - 9;
    }
}
