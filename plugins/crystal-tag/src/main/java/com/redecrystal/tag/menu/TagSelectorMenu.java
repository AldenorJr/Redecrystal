package com.redecrystal.tag.menu;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.cargo.TagOverrides;
import com.redecrystal.core.http.RemoteConfig;
import com.redecrystal.tag.menu.Menus.MenuHolder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Admin GUI to pick a player's "test" tag (override). Lists every cargo defined
 * in the shared {@code chat} config; clicking one writes the override to Redis
 * (it then shows up everywhere within ≤2s); a "Limpar" button removes it. The
 * target may be the admin themselves or another player ({@code /tag <jogador>}).
 */
public final class TagSelectorMenu implements Listener {

    private static final String TYPE = "tag:select";
    private static final String REMOVE = "__remove__";
    private static final String ADMIN_PERM = "crystal.tag.admin";

    private final JavaPlugin plugin;
    private final CrystalCore crystal;
    private final NamespacedKey cargoKey;

    public TagSelectorMenu(JavaPlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
        this.cargoKey = new NamespacedKey(plugin, "tag-cargo");
    }

    /** A cargo as listed in the selector. */
    private record CargoEntry(String id, String prefix) { }

    public void open(Player admin, UUID target, String targetName) {
        List<CargoEntry> cargos = loadCargos();
        String current = TagOverrides.read(crystal.redis(), target);

        MenuHolder holder = new MenuHolder(TYPE, target, targetName);
        Inventory inv = plugin.getServer().createInventory(
                holder, Menus.framedSize(Math.max(cargos.size(), 1), true),
                Component.text("Tags · " + targetName));

        List<Integer> slots = Menus.bodySlots(cargos.size());
        for (int i = 0; i < cargos.size() && i < slots.size(); i++) {
            CargoEntry c = cargos.get(i);
            boolean selected = c.id().equals(current);
            List<String> lore = new ArrayList<>();
            lore.add("§7Pré-visualização: " + c.prefix());
            lore.add(" ");
            lore.add(selected ? "§a✔ Override ativo" : "§eClique para usar (teste)");
            ItemStack it = Menus.item(Material.NAME_TAG, "§f" + c.id(), lore.toArray(new String[0]));
            if (selected) {
                Menus.glow(it);
            }
            var meta = it.getItemMeta();
            meta.getPersistentDataContainer().set(cargoKey, PersistentDataType.STRING, c.id());
            it.setItemMeta(meta);
            inv.setItem(slots.get(i), it);
        }
        if (cargos.isEmpty()) {
            inv.setItem(Menus.bodySlots(1).get(0),
                    Menus.item(Material.BARRIER, "§cNenhum cargo definido", "§7Use §f/tag editar§7 para criar"));
        }

        ItemStack clear = Menus.item(Material.BARRIER, "§c§lLimpar override",
                "§7Voltar ao cargo por permissão");
        var cm = clear.getItemMeta();
        cm.getPersistentDataContainer().set(cargoKey, PersistentDataType.STRING, REMOVE);
        clear.setItemMeta(cm);
        inv.setItem(Menus.barCenter(inv), clear);

        admin.openInventory(inv);
    }

    private List<CargoEntry> loadCargos() {
        RemoteConfig chat = crystal.configProvider().get("chat");
        List<CargoEntry> out = new ArrayList<>();
        if (chat.value("roles") instanceof Map<?, ?> roles) {
            for (Map.Entry<?, ?> e : roles.entrySet()) {
                String id = String.valueOf(e.getKey());
                String prefix = e.getValue() instanceof Map<?, ?> m && m.get("prefix") != null
                        ? String.valueOf(m.get("prefix")) : "";
                out.add(new CargoEntry(id, prefix));
            }
        }
        out.sort(Comparator.comparing(CargoEntry::id));
        return out;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof MenuHolder menu) || !TYPE.equals(menu.type())) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player admin)) {
            return;
        }
        if (!admin.hasPermission(ADMIN_PERM)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getItemMeta() == null) {
            return;
        }
        String cargoId = clicked.getItemMeta().getPersistentDataContainer()
                .get(cargoKey, PersistentDataType.STRING);
        if (cargoId == null) {
            return;
        }
        if (REMOVE.equals(cargoId)) {
            TagOverrides.clear(crystal.redis(), menu.target());
            admin.sendActionBar(Component.text("§aOverride removido de " + menu.targetName()));
        } else {
            TagOverrides.set(crystal.redis(), menu.target(), cargoId);
            admin.sendActionBar(Component.text("§aTag '" + cargoId + "' aplicada a " + menu.targetName()));
        }
        open(admin, menu.target(), menu.targetName()); // refresh selection state
    }
}
