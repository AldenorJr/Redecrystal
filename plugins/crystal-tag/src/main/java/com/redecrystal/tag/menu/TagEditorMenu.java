package com.redecrystal.tag.menu;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.RemoteConfig;
import com.redecrystal.tag.menu.Menus.MenuHolder;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Admin GUI to edit cargo definitions (prefix / name colour / weight) and to
 * create or delete cargos. Edits mutate the shared {@code chat} config and save
 * via {@code putConfig}, which the backend persists and broadcasts as a
 * {@code config-updated} event — so chat, tab, nametag, sidebar and profile all
 * hot-reload. Value edits use a type-in-chat flow (like the lobby's pet rename).
 */
public final class TagEditorMenu implements Listener {

    private static final String CONFIG_KEY = "chat";
    private static final String LIST_TYPE = "tag:editor";
    private static final String CARGO_TYPE = "tag:cargo";
    private static final String CREATE = "__create__";
    private static final String ADMIN_PERM = "crystal.tag.admin";

    private final JavaPlugin plugin;
    private final CrystalCore crystal;
    private final NamespacedKey cargoKey;
    private final NamespacedKey actionKey;

    /** Admins whose next chat line feeds a pending edit. */
    private final Map<UUID, Pending> awaiting = new ConcurrentHashMap<>();

    public TagEditorMenu(JavaPlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
        this.cargoKey = new NamespacedKey(plugin, "tag-edit-cargo");
        this.actionKey = new NamespacedKey(plugin, "tag-edit-action");
    }

    /** A pending type-in-chat edit. {@code field} is "prefix"/"nameColor"/"weight"
     *  for a value edit, or {@code CREATE} when {@code cargoId} is the new id. */
    private record Pending(String cargoId, String field) { }

    // ── cargo list ──

    public void open(Player admin) {
        List<String> ids = cargoIds();
        MenuHolder holder = new MenuHolder(LIST_TYPE, null, null);
        Inventory inv = plugin.getServer().createInventory(
                holder, Menus.framedSize(Math.max(ids.size(), 1), true), Component.text("Editar tags"));

        List<Integer> slots = Menus.bodySlots(ids.size());
        for (int i = 0; i < ids.size() && i < slots.size(); i++) {
            String id = ids.get(i);
            ItemStack it = Menus.item(Material.NAME_TAG, "§f" + id, "§7Clique para editar");
            var meta = it.getItemMeta();
            meta.getPersistentDataContainer().set(cargoKey, PersistentDataType.STRING, id);
            it.setItemMeta(meta);
            inv.setItem(slots.get(i), it);
        }
        if (ids.isEmpty()) {
            inv.setItem(Menus.bodySlots(1).get(0), Menus.item(Material.BARRIER, "§cNenhum cargo"));
        }

        ItemStack create = Menus.item(Material.EMERALD, "§a§lCriar cargo", "§7Define um novo id no chat");
        var cm = create.getItemMeta();
        cm.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, CREATE);
        create.setItemMeta(cm);
        inv.setItem(Menus.barCenter(inv), create);

        admin.openInventory(inv);
    }

    // ── single cargo ──

    private void openCargo(Player admin, String cargoId) {
        Map<String, Object> role = role(cargoId);
        MenuHolder holder = new MenuHolder(CARGO_TYPE, null, cargoId);
        Inventory inv = plugin.getServer().createInventory(holder, 27, Component.text("Cargo · " + cargoId));

        inv.setItem(10, action(Material.NAME_TAG, "§bPrefixo", "prefix",
                "§7Atual: §f" + String.valueOf(role.getOrDefault("prefix", ""))));
        inv.setItem(12, action(Material.PAINTING, "§bCor do nome", "nameColor",
                "§7Atual: §f" + String.valueOf(role.getOrDefault("nameColor", ""))));
        inv.setItem(14, action(Material.ANVIL, "§bPeso", "weight",
                "§7Atual: §f" + String.valueOf(role.getOrDefault("weight", 0))));
        inv.setItem(16, action(Material.BARRIER, "§c§lExcluir cargo", "delete",
                "§7Remove o cargo da config"));
        admin.openInventory(inv);
    }

    private ItemStack action(Material mat, String name, String action, String lore) {
        ItemStack it = Menus.item(mat, name, lore, " ", "§eClique para alterar");
        var meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        it.setItemMeta(meta);
        return it;
    }

    // ── click routing ──

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof MenuHolder menu)) {
            return;
        }
        if (!LIST_TYPE.equals(menu.type()) && !CARGO_TYPE.equals(menu.type())) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player admin) || !admin.hasPermission(ADMIN_PERM)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getItemMeta() == null) {
            return;
        }
        var pdc = clicked.getItemMeta().getPersistentDataContainer();

        if (LIST_TYPE.equals(menu.type())) {
            String create = pdc.get(actionKey, PersistentDataType.STRING);
            if (CREATE.equals(create)) {
                startPrompt(admin, new Pending(null, CREATE),
                        "Digite o §fid§r do novo cargo (ou §fcancelar§r).");
                return;
            }
            String id = pdc.get(cargoKey, PersistentDataType.STRING);
            if (id != null) {
                openCargo(admin, id);
            }
            return;
        }
        // CARGO_TYPE
        String action = pdc.get(actionKey, PersistentDataType.STRING);
        if (action == null) {
            return;
        }
        String cargoId = menu.targetName();
        switch (action) {
            case "prefix" -> startPrompt(admin, new Pending(cargoId, "prefix"),
                    "Digite o novo §fprefixo§r (MiniMessage ou &-códigos).");
            case "nameColor" -> startPrompt(admin, new Pending(cargoId, "nameColor"),
                    "Digite a nova §fcor do nome§r (ex.: <gold> ou &6).");
            case "weight" -> startPrompt(admin, new Pending(cargoId, "weight"),
                    "Digite o novo §fpeso§r (número inteiro).");
            case "delete" -> deleteCargo(admin, cargoId);
            default -> { }
        }
    }

    // ── type-in-chat flow ──

    private void startPrompt(Player admin, Pending pending, String message) {
        awaiting.put(admin.getUniqueId(), pending);
        admin.closeInventory();
        admin.sendMessage(Component.text("» ", NamedTextColor.AQUA)
                .append(Component.text(message.replace("§r", "").replace("§f", ""), NamedTextColor.GRAY)));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        awaiting.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player admin = event.getPlayer();
        Pending pending = awaiting.remove(admin.getUniqueId());
        if (pending == null) {
            return;
        }
        event.setCancelled(true); // never broadcast the edit text
        String raw = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> applyPrompt(admin, pending, raw));
    }

    private void applyPrompt(Player admin, Pending pending, String raw) {
        if (raw.equalsIgnoreCase("cancelar")) {
            admin.sendMessage(Component.text("Edição cancelada.", NamedTextColor.GRAY));
            return;
        }
        if (CREATE.equals(pending.field())) {
            String id = raw.toLowerCase().replaceAll("[^a-z0-9_]", "");
            if (id.isEmpty()) {
                admin.sendMessage(Component.text("Id inválido.", NamedTextColor.RED));
                return;
            }
            Map<String, Object> defaults = new LinkedHashMap<>();
            defaults.put("permission", "tag." + id);
            defaults.put("weight", 0);
            defaults.put("prefix", "");
            defaults.put("nameColor", "");
            saveRole(admin, id, defaults, "Cargo '" + id + "' criado.");
            return;
        }
        Object value = "weight".equals(pending.field()) ? parseWeight(admin, raw) : raw;
        if (value == null) {
            return; // weight parse already messaged
        }
        Map<String, Object> role = new LinkedHashMap<>(role(pending.cargoId()));
        role.put(pending.field(), value);
        saveRole(admin, pending.cargoId(), role, pending.field() + " atualizado.");
    }

    private Integer parseWeight(Player admin, String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            admin.sendMessage(Component.text("Peso inválido (use um inteiro).", NamedTextColor.RED));
            return null;
        }
    }

    // ── persistence (putConfig → hot-reload) ──

    @SuppressWarnings("unchecked")
    private void saveRole(Player admin, String cargoId, Map<String, Object> role, String okMessage) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                RemoteConfig cfg = crystal.backend().getConfig(CONFIG_KEY);
                Map<String, Object> map = new LinkedHashMap<>(cfg.config());
                Map<String, Object> roles = map.get("roles") instanceof Map<?, ?> r
                        ? new LinkedHashMap<>((Map<String, Object>) r) : new LinkedHashMap<>();
                roles.put(cargoId, role);
                map.put("roles", roles);
                crystal.backend().putConfig(CONFIG_KEY, map);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    admin.sendMessage(Component.text(okMessage, NamedTextColor.GREEN));
                    if (admin.isOnline()) {
                        openCargo(admin, cargoId);
                    }
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        admin.sendMessage(Component.text("Falha ao salvar: " + e.getMessage(), NamedTextColor.RED)));
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void deleteCargo(Player admin, String cargoId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                RemoteConfig cfg = crystal.backend().getConfig(CONFIG_KEY);
                Map<String, Object> map = new LinkedHashMap<>(cfg.config());
                if (map.get("roles") instanceof Map<?, ?> r) {
                    Map<String, Object> roles = new LinkedHashMap<>((Map<String, Object>) r);
                    roles.remove(cargoId);
                    map.put("roles", roles);
                    crystal.backend().putConfig(CONFIG_KEY, map);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        admin.sendMessage(Component.text("Cargo '" + cargoId + "' excluído.", NamedTextColor.GREEN));
                        if (admin.isOnline()) {
                            open(admin);
                        }
                    });
                } else {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            admin.sendMessage(Component.text("Nenhum cargo para excluir.", NamedTextColor.RED)));
                }
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        admin.sendMessage(Component.text("Falha ao excluir: " + e.getMessage(), NamedTextColor.RED)));
            }
        });
    }

    // ── config reads (from the local hot-reloaded cache) ──

    private List<String> cargoIds() {
        RemoteConfig chat = crystal.configProvider().get(CONFIG_KEY);
        List<String> ids = new ArrayList<>();
        if (chat.value("roles") instanceof Map<?, ?> roles) {
            for (Object k : roles.keySet()) {
                ids.add(String.valueOf(k));
            }
        }
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> role(String cargoId) {
        RemoteConfig chat = crystal.configProvider().get(CONFIG_KEY);
        if (chat.value("roles") instanceof Map<?, ?> roles && roles.get(cargoId) instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return new LinkedHashMap<>();
    }
}
