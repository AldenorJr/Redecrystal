package com.redecrystal.lobby;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.cargo.CargoResolver;
import com.redecrystal.core.http.BackendHttpClient;
import com.redecrystal.core.http.InventoryData;
import com.redecrystal.core.http.NetworkServer;
import com.redecrystal.core.http.ProfileData;
import com.redecrystal.core.json.Json;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
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
    private static final String VIP_PERM = "crystal.cosmetic.vip";

    /** Half-wing silhouette as (horizontal, vertical) offsets; mirrored for both wings. */
    private static final double[][] WING_POINTS = {
            {0.25, 0.95}, {0.50, 1.05}, {0.78, 1.08}, {1.05, 1.02}, {1.30, 0.90}, {1.50, 0.72},
            {0.30, 0.72}, {0.60, 0.78}, {0.92, 0.72}, {1.18, 0.60}, {1.38, 0.45},
            {0.35, 0.50}, {0.66, 0.50}, {0.96, 0.44}, {1.15, 0.30},
            {0.42, 0.28}, {0.72, 0.24},
    };

    private long tick;
    private long petTick;

    private final JavaPlugin plugin;
    private final CrystalCore crystal;
    private static final String PET_TAG = "crystal_pet";
    /** Reuses the inventory blob store as a per-player cosmetic-preferences row. */
    private static final String COSMETICS_TYPE = "lobby-cosmetics";

    private final Map<UUID, Boolean> playersHidden = new ConcurrentHashMap<>();
    private final Map<UUID, Cosmetic> activeCosmetic = new ConcurrentHashMap<>();
    private final Map<UUID, Hat> activeHat = new ConcurrentHashMap<>();
    private final Map<UUID, Pet> activePetType = new ConcurrentHashMap<>();
    private final Map<UUID, Entity> activePet = new ConcurrentHashMap<>();
    private final Map<UUID, Map<ArmorSlot, Armor>> activeArmor = new ConcurrentHashMap<>();
    /** Optimistic-lock version last seen for each player's cosmetics row. */
    private final Map<UUID, Integer> cosmeticsVersion = new ConcurrentHashMap<>();
    private final NamespacedKey lobbyKey;
    private final NamespacedKey cosmeticKey;
    private final NamespacedKey hatKey;
    private final NamespacedKey petKey;
    private final NamespacedKey armorKey;
    private final NamespacedKey navKey;

    public LobbyHotbar(JavaPlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
        this.lobbyKey = new NamespacedKey(plugin, "lobby-id");
        this.cosmeticKey = new NamespacedKey(plugin, "cosmetic-id");
        this.hatKey = new NamespacedKey(plugin, "hat-id");
        this.petKey = new NamespacedKey(plugin, "pet-id");
        this.armorKey = new NamespacedKey(plugin, "armor-id");
        this.navKey = new NamespacedKey(plugin, "nav-id");
    }

    /** How a cosmetic is rendered around the player. */
    private enum Style { TRAIL, WINGS, HALO }

    /**
     * Cosmetics shown in the menu. The flashy ones (wings, halo, fancy trails) are
     * {@code vip} — locked behind {@code crystal.cosmetic.vip}.
     */
    private enum Cosmetic {
        NONE(Material.BARRIER, "§cRemover cosmético", Style.TRAIL, null, null, false),

        // ── trilhas gratuitas ──
        FLAME(Material.BLAZE_POWDER, "§6Trilha de Fogo", Style.TRAIL, Particle.FLAME, null, false),
        HEART(Material.POPPY, "§dCorações", Style.TRAIL, Particle.HEART, null, false),
        HAPPY(Material.SLIME_BALL, "§aVila Feliz", Style.TRAIL, Particle.HAPPY_VILLAGER, null, false),
        NOTE(Material.NOTE_BLOCK, "§bNotas Musicais", Style.TRAIL, Particle.NOTE, null, false),
        CLOUD(Material.WHITE_WOOL, "§fNuvem", Style.TRAIL, Particle.CLOUD, null, false),
        SNOW(Material.SNOWBALL, "§fFloco de Neve", Style.TRAIL, Particle.SNOWFLAKE, null, false),

        // ── trilhas VIP (mais loucas) ──
        PORTAL(Material.OBSIDIAN, "§5Portal", Style.TRAIL, Particle.PORTAL, null, true),
        SOUL(Material.SOUL_SAND, "§3Almas", Style.TRAIL, Particle.SOUL, null, true),
        DRAGON(Material.DRAGON_HEAD, "§5Sopro do Dragão", Style.TRAIL, Particle.DRAGON_BREATH, null, true),
        LAVA(Material.MAGMA_BLOCK, "§cLava", Style.TRAIL, Particle.LAVA, null, true),
        RAINBOW(Material.FIREWORK_STAR, "§d§lArco-Íris", Style.TRAIL, Particle.DUST, Color.FUCHSIA, true),

        // ── asas VIP ──
        ANGEL_WINGS(Material.FEATHER, "§f§lAsas de Anjo", Style.WINGS, Particle.CLOUD, null, true),
        DEMON_WINGS(Material.BLAZE_ROD, "§c§lAsas Demoníacas", Style.WINGS, Particle.FLAME, null, true),
        ENDER_WINGS(Material.ENDER_EYE, "§5§lAsas do End", Style.WINGS, Particle.PORTAL, null, true),
        FAIRY_WINGS(Material.PINK_DYE, "§d§lAsas de Fada", Style.WINGS, Particle.DUST, Color.fromRGB(0xFF6EC7), true),

        // ── auréola VIP ──
        HALO(Material.GOLD_INGOT, "§e§lAuréola", Style.HALO, Particle.END_ROD, null, true);

        final Material icon;
        final String name;
        final Style style;
        final Particle particle;
        final Color color;
        final boolean vip;

        Cosmetic(Material icon, String name, Style style, Particle particle, Color color, boolean vip) {
            this.icon = icon;
            this.name = name;
            this.style = style;
            this.particle = particle;
            this.color = color;
            this.vip = vip;
        }
    }

    /** Wearable hats (the head item is the icon material). Fancy ones are VIP. */
    private enum Hat {
        NONE(Material.BARRIER, "§cRemover chapéu", false),
        PUMPKIN(Material.CARVED_PUMPKIN, "§6Abóbora", false),
        MELON(Material.MELON, "§aMelancia", false),
        TNT(Material.TNT, "§cTNT", false),
        CHEST(Material.CHEST, "§eBaú", false),
        CRAFTING(Material.CRAFTING_TABLE, "§6Mesa de Trabalho", false),
        ZOMBIE(Material.ZOMBIE_HEAD, "§2Zumbi", false),
        SKELETON(Material.SKELETON_SKULL, "§7Esqueleto", false),
        JACK(Material.JACK_O_LANTERN, "§6Lanterna", true),
        CREEPER(Material.CREEPER_HEAD, "§aCreeper", true),
        GOLD(Material.GOLD_BLOCK, "§eBloco de Ouro", true),
        DIAMOND(Material.DIAMOND_BLOCK, "§bBloco de Diamante", true),
        BEACON(Material.BEACON, "§bSinalizador", true),
        DRAGON(Material.DRAGON_HEAD, "§5Cabeça de Dragão", true);

        final Material material;
        final String name;
        final boolean vip;

        Hat(Material material, String name, boolean vip) {
            this.material = material;
            this.name = name;
            this.vip = vip;
        }
    }

    /** Companion pets that follow the player. Fancy ones are VIP. */
    private enum Pet {
        NONE(Material.BARRIER, "§cRemover pet", null, false, false, false),
        WOLF(Material.BONE, "§7Lobo", EntityType.WOLF, false, false, false),
        CAT(Material.STRING, "§6Gato", EntityType.CAT, true, false, false),
        CHICKEN(Material.EGG, "§eGalinha", EntityType.CHICKEN, true, false, false),
        PIG(Material.CARROT, "§dPorco", EntityType.PIG, true, false, false),
        RABBIT(Material.RABBIT_FOOT, "§fCoelho", EntityType.RABBIT, true, false, false),
        FOX(Material.SWEET_BERRIES, "§6Raposa", EntityType.FOX, true, false, true),
        PARROT(Material.FEATHER, "§aPapagaio", EntityType.PARROT, false, true, true),
        BEE(Material.HONEYCOMB, "§eAbelha", EntityType.BEE, true, true, true),
        ALLAY(Material.AMETHYST_SHARD, "§bAllay", EntityType.ALLAY, false, true, true);

        final Material icon;
        final String name;
        final EntityType type;
        final boolean baby;
        final boolean flying;
        final boolean vip;

        Pet(Material icon, String name, EntityType type, boolean baby, boolean flying, boolean vip) {
            this.icon = icon;
            this.name = name;
            this.type = type;
            this.baby = baby;
            this.flying = flying;
            this.vip = vip;
        }
    }

    /** The four wearable armour slots. */
    private enum ArmorSlot { HELMET, CHESTPLATE, LEGGINGS, BOOTS }

    /**
     * Cosmetic armour pieces, grouped by {@link ArmorSlot}. Dyed-leather pieces
     * carry a {@code leatherColor}; the flashy metals are {@code vip}. Each slot
     * has a {@code REMOVE_*} entry whose {@code material} is {@code null}.
     */
    private enum Armor {
        // ── helmet ──
        REMOVE_HELMET(null, ArmorSlot.HELMET, "§cRemover capacete", null, false),
        LEATHER_RED_HELMET(Material.LEATHER_HELMET, ArmorSlot.HELMET, "§cCapacete Vermelho", Color.RED, false),
        LEATHER_BLUE_HELMET(Material.LEATHER_HELMET, ArmorSlot.HELMET, "§9Capacete Azul", Color.BLUE, false),
        LEATHER_GREEN_HELMET(Material.LEATHER_HELMET, ArmorSlot.HELMET, "§aCapacete Verde", Color.LIME, false),
        CHAINMAIL_HELMET(Material.CHAINMAIL_HELMET, ArmorSlot.HELMET, "§7Capacete de Malha", null, false),
        IRON_HELMET(Material.IRON_HELMET, ArmorSlot.HELMET, "§fCapacete de Ferro", null, false),
        GOLDEN_HELMET(Material.GOLDEN_HELMET, ArmorSlot.HELMET, "§eCapacete de Ouro", null, true),
        DIAMOND_HELMET(Material.DIAMOND_HELMET, ArmorSlot.HELMET, "§bCapacete de Diamante", null, true),
        NETHERITE_HELMET(Material.NETHERITE_HELMET, ArmorSlot.HELMET, "§8Capacete de Netherita", null, true),

        // ── chestplate ──
        REMOVE_CHESTPLATE(null, ArmorSlot.CHESTPLATE, "§cRemover peitoral", null, false),
        LEATHER_RED_CHESTPLATE(Material.LEATHER_CHESTPLATE, ArmorSlot.CHESTPLATE, "§cPeitoral Vermelho", Color.RED, false),
        LEATHER_BLUE_CHESTPLATE(Material.LEATHER_CHESTPLATE, ArmorSlot.CHESTPLATE, "§9Peitoral Azul", Color.BLUE, false),
        LEATHER_GREEN_CHESTPLATE(Material.LEATHER_CHESTPLATE, ArmorSlot.CHESTPLATE, "§aPeitoral Verde", Color.LIME, false),
        CHAINMAIL_CHESTPLATE(Material.CHAINMAIL_CHESTPLATE, ArmorSlot.CHESTPLATE, "§7Peitoral de Malha", null, false),
        IRON_CHESTPLATE(Material.IRON_CHESTPLATE, ArmorSlot.CHESTPLATE, "§fPeitoral de Ferro", null, false),
        GOLDEN_CHESTPLATE(Material.GOLDEN_CHESTPLATE, ArmorSlot.CHESTPLATE, "§ePeitoral de Ouro", null, true),
        DIAMOND_CHESTPLATE(Material.DIAMOND_CHESTPLATE, ArmorSlot.CHESTPLATE, "§bPeitoral de Diamante", null, true),
        NETHERITE_CHESTPLATE(Material.NETHERITE_CHESTPLATE, ArmorSlot.CHESTPLATE, "§8Peitoral de Netherita", null, true),
        ELYTRA(Material.ELYTRA, ArmorSlot.CHESTPLATE, "§d§lElitros", null, true),

        // ── leggings ──
        REMOVE_LEGGINGS(null, ArmorSlot.LEGGINGS, "§cRemover calças", null, false),
        LEATHER_RED_LEGGINGS(Material.LEATHER_LEGGINGS, ArmorSlot.LEGGINGS, "§cCalças Vermelhas", Color.RED, false),
        LEATHER_BLUE_LEGGINGS(Material.LEATHER_LEGGINGS, ArmorSlot.LEGGINGS, "§9Calças Azuis", Color.BLUE, false),
        LEATHER_GREEN_LEGGINGS(Material.LEATHER_LEGGINGS, ArmorSlot.LEGGINGS, "§aCalças Verdes", Color.LIME, false),
        CHAINMAIL_LEGGINGS(Material.CHAINMAIL_LEGGINGS, ArmorSlot.LEGGINGS, "§7Calças de Malha", null, false),
        IRON_LEGGINGS(Material.IRON_LEGGINGS, ArmorSlot.LEGGINGS, "§fCalças de Ferro", null, false),
        GOLDEN_LEGGINGS(Material.GOLDEN_LEGGINGS, ArmorSlot.LEGGINGS, "§eCalças de Ouro", null, true),
        DIAMOND_LEGGINGS(Material.DIAMOND_LEGGINGS, ArmorSlot.LEGGINGS, "§bCalças de Diamante", null, true),
        NETHERITE_LEGGINGS(Material.NETHERITE_LEGGINGS, ArmorSlot.LEGGINGS, "§8Calças de Netherita", null, true),

        // ── boots ──
        REMOVE_BOOTS(null, ArmorSlot.BOOTS, "§cRemover botas", null, false),
        LEATHER_RED_BOOTS(Material.LEATHER_BOOTS, ArmorSlot.BOOTS, "§cBotas Vermelhas", Color.RED, false),
        LEATHER_BLUE_BOOTS(Material.LEATHER_BOOTS, ArmorSlot.BOOTS, "§9Botas Azuis", Color.BLUE, false),
        LEATHER_GREEN_BOOTS(Material.LEATHER_BOOTS, ArmorSlot.BOOTS, "§aBotas Verdes", Color.LIME, false),
        CHAINMAIL_BOOTS(Material.CHAINMAIL_BOOTS, ArmorSlot.BOOTS, "§7Botas de Malha", null, false),
        IRON_BOOTS(Material.IRON_BOOTS, ArmorSlot.BOOTS, "§fBotas de Ferro", null, false),
        GOLDEN_BOOTS(Material.GOLDEN_BOOTS, ArmorSlot.BOOTS, "§eBotas de Ouro", null, true),
        DIAMOND_BOOTS(Material.DIAMOND_BOOTS, ArmorSlot.BOOTS, "§bBotas de Diamante", null, true),
        NETHERITE_BOOTS(Material.NETHERITE_BOOTS, ArmorSlot.BOOTS, "§8Botas de Netherita", null, true);

        final Material material;
        final ArmorSlot slot;
        final String name;
        final Color leatherColor;
        final boolean vip;

        Armor(Material material, ArmorSlot slot, String name, Color leatherColor, boolean vip) {
            this.material = material;
            this.slot = slot;
            this.name = name;
            this.leatherColor = leatherColor;
            this.vip = vip;
        }

        /** A {@code REMOVE_*} entry that clears its slot rather than equipping. */
        boolean isRemove() {
            return material == null;
        }
    }

    /** Marks an inventory we own so clicks in it are handled, not blocked generically. */
    private record MenuHolder(String type) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    /** Start the cosmetic/pet tasks. Called once from the plugin's onEnable. */
    public void start() {
        removeOrphanPets(); // clear any pets left over from a previous run/crash
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickCosmetics, 10L, 6L);
        // Every tick: smooth interpolation needs the high refresh rate to look fluid.
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::followPets, 10L, 1L);
    }

    /** Remove all spawned pets (called on disable so none are orphaned). */
    public void shutdown() {
        for (Entity e : activePet.values()) {
            if (e != null && !e.isDead()) {
                e.remove();
            }
        }
        activePet.clear();
        removeOrphanPets();
    }

    private void removeOrphanPets() {
        for (org.bukkit.World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getScoreboardTags().contains(PET_TAG)) {
                    e.remove();
                }
            }
        }
    }

    private void tickCosmetics() {
        tick++;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            Cosmetic c = activeCosmetic.get(p.getUniqueId());
            if (c == null || c.particle == null) {
                continue;
            }
            // Safety: revoke a VIP cosmetic if the player lost the permission.
            if (c.vip && !p.hasPermission(VIP_PERM)) {
                activeCosmetic.remove(p.getUniqueId());
                continue;
            }
            switch (c.style) {
                case TRAIL -> spawn(p, c, p.getLocation().add(0, 0.15, 0), 8, 0.3, 0.1, 0.3);
                case WINGS -> renderWings(p, c);
                case HALO -> renderHalo(p, c);
            }
        }
    }

    private void renderWings(Player p, Cosmetic c) {
        Location base = p.getLocation();
        Vector dir = base.getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-4) {
            dir = new Vector(0, 0, 1);
        }
        dir.normalize();
        Vector right = new Vector(-dir.getZ(), 0, dir.getX()).normalize();
        Location anchor = base.clone().add(0, 1.0, 0).add(dir.clone().multiply(-0.3));
        for (double[] pt : WING_POINTS) {
            for (int sign = -1; sign <= 1; sign += 2) {
                Location loc = anchor.clone()
                        .add(right.clone().multiply(pt[0] * sign))
                        .add(0, pt[1], 0);
                spawn(p, c, loc, 1, 0, 0, 0);
            }
        }
    }

    private void renderHalo(Player p, Cosmetic c) {
        Location centre = p.getLocation().add(0, 2.25, 0);
        int points = 14;
        double radius = 0.5;
        double spin = (tick % 40) / 40.0 * Math.PI * 2;
        for (int i = 0; i < points; i++) {
            double a = spin + (Math.PI * 2 * i / points);
            Location loc = centre.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius);
            spawn(p, c, loc, 1, 0, 0, 0);
        }
    }

    /** Spawn the cosmetic's particle, using coloured dust when a colour is set. */
    private void spawn(Player p, Cosmetic c, Location loc, int count, double ox, double oy, double oz) {
        if (c.particle == Particle.DUST) {
            Color col = c.color == null ? Color.WHITE : c.color;
            if (c == Cosmetic.RAINBOW) {
                col = rainbow();
            }
            p.getWorld().spawnParticle(Particle.DUST, loc, count, ox, oy, oz, 0.0,
                    new Particle.DustOptions(col, 1.3f));
        } else {
            p.getWorld().spawnParticle(c.particle, loc, count, ox, oy, oz, 0.0);
        }
    }

    /** A colour cycling through the hue wheel over time (manual HSV→RGB). */
    private Color rainbow() {
        float h = (tick % 80) / 80.0f * 6f;
        int i = (int) h % 6;
        float f = h - (int) h;
        int v = 255, q = (int) (255 * (1 - f)), t = (int) (255 * f);
        return switch (i) {
            case 0 -> Color.fromRGB(v, t, 0);
            case 1 -> Color.fromRGB(q, v, 0);
            case 2 -> Color.fromRGB(0, v, t);
            case 3 -> Color.fromRGB(0, q, v);
            case 4 -> Color.fromRGB(t, 0, v);
            default -> Color.fromRGB(v, 0, q);
        };
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> { // after inventory load
            giveHotbar(p);
            loadCosmetics(p); // restore the saved cosmetic selection from the backend
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        playersHidden.remove(uuid);
        activeCosmetic.remove(uuid);
        activeHat.remove(uuid);
        activePetType.remove(uuid);
        activeArmor.remove(uuid);
        cosmeticsVersion.remove(uuid);
        Entity pet = activePet.remove(uuid);
        if (pet != null && !pet.isDead()) {
            pet.remove();
        }
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
        p.getInventory().setItem(1, item(Material.BONE, "§6Pets", "§7Escolha um bichinho de estimação"));
        p.getInventory().setItem(3, item(Material.RED_BED, "§aLobbys", "§7Clique para trocar de lobby"));
        p.getInventory().setItem(4, profileHead(p));
        p.getInventory().setItem(5, hideToggleItem(p));
        p.getInventory().setItem(7, item(Material.LEATHER_HELMET, "§bVestuário", "§7Chapéus e armaduras"));
        p.getInventory().setItem(8, item(Material.NETHER_STAR, "§dCosméticos", "§7Personalize o seu visual"));
        p.getInventory().setHeldItemSlot(0);

        // Re-apply hat + armour (the clear() above wiped the armour slots).
        applyAppearance(p);
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

    /** Block interacting with pets (no mounting, leashing, feeding, etc.). */
    @EventHandler
    public void onPetInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked().getScoreboardTags().contains(PET_TAG)) {
            event.setCancelled(true);
        }
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
            case BONE -> { event.setCancelled(true); openPets(p); }
            case RED_BED -> { event.setCancelled(true); openLobbys(p); }
            case PLAYER_HEAD -> { event.setCancelled(true); showProfile(p); }
            case LIME_DYE, GRAY_DYE -> { event.setCancelled(true); toggleHide(p); }
            case LEATHER_HELMET -> { event.setCancelled(true); openWardrobe(p); }
            case NETHER_STAR -> { event.setCancelled(true); openCosmetics(p); }
            default -> { }
        }
    }

    private void openGames(Player p) {
        MenuHolder holder = new MenuHolder("games");
        Inventory inv = Bukkit.createInventory(holder, 27, Component.text("Modos de Jogo"));
        inv.setItem(13, item(Material.FEATHER, "§aParkour", "§7Teste a sua agilidade",
                "§eClique para ir ao início", "§7e pise na placa de ferro"));
        p.openInventory(inv);
    }

    // ── cosmetics ──

    private void openCosmetics(Player p) {
        Cosmetic[] all = Cosmetic.values();
        int size = menuSize(all.length);
        List<Integer> slots = contentSlots(all.length);
        MenuHolder holder = new MenuHolder("cosmetics");
        Inventory inv = Bukkit.createInventory(holder, size, Component.text("Cosméticos"));

        Cosmetic current = activeCosmetic.get(p.getUniqueId());
        boolean isVip = p.hasPermission(VIP_PERM);
        for (int i = 0; i < all.length && i < slots.size(); i++) {
            Cosmetic c = all[i];
            boolean selected = (current == null ? Cosmetic.NONE : current) == c;
            boolean locked = c.vip && !isVip;

            List<String> lore = new ArrayList<>();
            lore.add(describe(c));
            lore.add(" ");
            if (locked) {
                lore.add("§c🔒 Exclusivo VIP");
                lore.add("§7Adquira VIP para usar");
            } else if (selected) {
                lore.add("§a✔ Selecionado");
            } else {
                if (c.vip) {
                    lore.add("§6★ VIP");
                }
                lore.add("§eClique para usar");
            }

            ItemStack it = item(c.icon, c.name, lore.toArray(new String[0]));
            if (selected && c != Cosmetic.NONE) {
                glow(it);
            }
            var meta = it.getItemMeta();
            meta.getPersistentDataContainer().set(cosmeticKey, PersistentDataType.STRING, c.name());
            it.setItemMeta(meta);
            inv.setItem(slots.get(i), it);
        }
        p.openInventory(inv);
    }

    private String describe(Cosmetic c) {
        return switch (c.style) {
            case WINGS -> "§7Asas de partículas nas suas costas";
            case HALO -> "§7Uma auréola sobre a sua cabeça";
            case TRAIL -> c == Cosmetic.NONE ? "§7Desativa o cosmético atual"
                    : "§7Partículas que seguem você";
        };
    }

    private void selectCosmetic(Player p, Cosmetic c) {
        if (c.vip && !p.hasPermission(VIP_PERM)) {
            p.sendActionBar(MM.deserialize("<red>Esse cosmético é exclusivo para VIPs!"));
            return;
        }
        if (c == Cosmetic.NONE) {
            activeCosmetic.remove(p.getUniqueId());
            p.sendActionBar(Component.text("Cosmético removido", NamedTextColor.GRAY));
        } else {
            activeCosmetic.put(p.getUniqueId(), c);
            p.sendActionBar(MM.deserialize("<green>Cosmético ativado!"));
        }
        saveCosmetics(p);
        openCosmetics(p); // refresh selection state
    }

    // ── hats ──

    private void openHats(Player p) {
        Hat[] all = Hat.values();
        int size = menuSize(all.length);
        List<Integer> slots = contentSlots(all.length);
        MenuHolder holder = new MenuHolder("hats");
        Inventory inv = Bukkit.createInventory(holder, size, Component.text("Chapéus"));

        Hat current = activeHat.get(p.getUniqueId());
        boolean isVip = p.hasPermission(VIP_PERM);
        for (int i = 0; i < all.length && i < slots.size(); i++) {
            Hat h = all[i];
            boolean selected = (current == null ? Hat.NONE : current) == h;
            boolean locked = h.vip && !isVip;
            List<String> lore = new ArrayList<>();
            lore.add(h == Hat.NONE ? "§7Remove o chapéu atual" : "§7Use na sua cabeça");
            lore.add(" ");
            if (locked) {
                lore.add("§c🔒 Exclusivo VIP");
                lore.add("§7Adquira VIP para usar");
            } else if (selected) {
                lore.add("§a✔ Selecionado");
            } else {
                if (h.vip) lore.add("§6★ VIP");
                lore.add("§eClique para usar");
            }
            ItemStack it = item(h.material, h.name, lore.toArray(new String[0]));
            if (selected && h != Hat.NONE) glow(it);
            var meta = it.getItemMeta();
            meta.getPersistentDataContainer().set(hatKey, PersistentDataType.STRING, h.name());
            it.setItemMeta(meta);
            inv.setItem(slots.get(i), it);
        }
        inv.setItem(size - 5, backButton()); // bottom-centre: back to the wardrobe hub
        p.openInventory(inv);
    }

    private void selectHat(Player p, Hat h) {
        if (h.vip && !p.hasPermission(VIP_PERM)) {
            p.sendActionBar(MM.deserialize("<red>Esse chapéu é exclusivo para VIPs!"));
            return;
        }
        UUID id = p.getUniqueId();
        if (h == Hat.NONE) {
            activeHat.remove(id);
            p.sendActionBar(Component.text("Chapéu removido", NamedTextColor.GRAY));
        } else {
            activeHat.put(id, h);
            // Hat and armour helmet share the head slot — equipping a hat clears it.
            Map<ArmorSlot, Armor> map = activeArmor.get(id);
            if (map != null) {
                map.remove(ArmorSlot.HELMET);
            }
            p.sendActionBar(MM.deserialize("<green>Chapéu equipado!"));
        }
        applyAppearance(p);
        saveCosmetics(p);
        openHats(p);
    }

    // ── wardrobe (hats + armour) ──

    /** Hub that branches into the hat menu and the four armour-slot menus. */
    private void openWardrobe(Player p) {
        MenuHolder holder = new MenuHolder("wardrobe");
        Inventory inv = Bukkit.createInventory(holder, 27, Component.text("Vestuário"));
        inv.setItem(11, navItem(Material.LEATHER_HELMET, "§bChapéus", "hats", "§7Equipe um chapéu estiloso"));
        inv.setItem(12, navItem(Material.IRON_HELMET, "§fCapacete", "armor:HELMET", "§7Armadura para a cabeça"));
        inv.setItem(13, navItem(Material.IRON_CHESTPLATE, "§fPeitoral", "armor:CHESTPLATE", "§7Armadura para o peito"));
        inv.setItem(14, navItem(Material.IRON_LEGGINGS, "§fCalças", "armor:LEGGINGS", "§7Armadura para as pernas"));
        inv.setItem(15, navItem(Material.IRON_BOOTS, "§fBotas", "armor:BOOTS", "§7Armadura para os pés"));
        p.openInventory(inv);
    }

    private ItemStack navItem(Material mat, String name, String target, String desc) {
        ItemStack it = item(mat, name, desc, " ", "§eClique para abrir");
        it.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);
        var meta = it.getItemMeta();
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(navKey, PersistentDataType.STRING, target);
        it.setItemMeta(meta);
        return it;
    }

    // ── armour ──

    private void openArmorSlotMenu(Player p, ArmorSlot slot) {
        List<Armor> pieces = new ArrayList<>();
        for (Armor a : Armor.values()) {
            if (a.slot == slot) {
                pieces.add(a);
            }
        }
        int size = menuSize(pieces.size());
        List<Integer> slots = contentSlots(pieces.size());
        MenuHolder holder = new MenuHolder("armor");
        Inventory inv = Bukkit.createInventory(holder, size, Component.text("Armadura · " + slotName(slot)));

        Armor current = armorOf(p.getUniqueId(), slot);
        boolean isVip = p.hasPermission(VIP_PERM);
        for (int i = 0; i < pieces.size() && i < slots.size(); i++) {
            Armor a = pieces.get(i);
            boolean selected = current == a;
            boolean locked = a.vip && !isVip;
            List<String> lore = new ArrayList<>();
            lore.add(a.isRemove() ? "§7Remove a peça atual" : "§7Vista no seu corpo");
            lore.add(" ");
            if (locked) {
                lore.add("§c🔒 Exclusivo VIP");
                lore.add("§7Adquira VIP para usar");
            } else if (selected) {
                lore.add("§a✔ Selecionado");
            } else {
                if (a.vip) lore.add("§6★ VIP");
                lore.add("§eClique para usar");
            }
            ItemStack it = armorIcon(a, lore.toArray(new String[0]));
            if (selected && !a.isRemove()) glow(it);
            var meta = it.getItemMeta();
            meta.getPersistentDataContainer().set(armorKey, PersistentDataType.STRING, a.name());
            it.setItemMeta(meta);
            inv.setItem(slots.get(i), it);
        }
        inv.setItem(size - 5, backButton()); // bottom-centre: back to the wardrobe hub
        p.openInventory(inv);
    }

    private void selectArmor(Player p, Armor a) {
        if (a.vip && !p.hasPermission(VIP_PERM)) {
            p.sendActionBar(MM.deserialize("<red>Essa armadura é exclusiva para VIPs!"));
            return;
        }
        UUID id = p.getUniqueId();
        Map<ArmorSlot, Armor> map = activeArmor.computeIfAbsent(id, k -> new EnumMap<>(ArmorSlot.class));
        if (a.isRemove()) {
            map.remove(a.slot);
            p.sendActionBar(Component.text("Peça removida", NamedTextColor.GRAY));
        } else {
            map.put(a.slot, a);
            if (a.slot == ArmorSlot.HELMET) {
                activeHat.remove(id); // armour helmet and hat share the head slot
            }
            p.sendActionBar(MM.deserialize("<green>Armadura equipada!"));
        }
        applyAppearance(p);
        saveCosmetics(p);
        openArmorSlotMenu(p, a.slot);
    }

    /** The display icon for an armour piece (dyed leather + hidden attributes). */
    private ItemStack armorIcon(Armor a, String... lore) {
        if (a.isRemove()) {
            return item(Material.BARRIER, a.name, lore);
        }
        ItemStack it = item(a.material, a.name, lore);
        var meta = it.getItemMeta();
        if (a.leatherColor != null && meta instanceof LeatherArmorMeta lm) {
            lm.setColor(a.leatherColor);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    /** The actual wearable item placed in the armour slot. */
    private ItemStack wearable(Armor a) {
        ItemStack it = new ItemStack(a.material);
        var meta = it.getItemMeta();
        if (a.leatherColor != null && meta instanceof LeatherArmorMeta lm) {
            lm.setColor(a.leatherColor);
        }
        meta.displayName(Component.text(a.name).decoration(TextDecoration.ITALIC, false));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
        meta.setUnbreakable(true);
        it.setItemMeta(meta);
        return it;
    }

    /** Re-apply the helmet/chest/legs/boots from the player's active hat + armour. */
    private void applyAppearance(Player p) {
        UUID id = p.getUniqueId();
        Map<ArmorSlot, Armor> map = activeArmor.get(id);
        var inv = p.getInventory();
        inv.setChestplate(armorPieceOrNull(map, ArmorSlot.CHESTPLATE));
        inv.setLeggings(armorPieceOrNull(map, ArmorSlot.LEGGINGS));
        inv.setBoots(armorPieceOrNull(map, ArmorSlot.BOOTS));
        // Helmet: the hat wins when present, otherwise the armour helmet.
        Hat hat = activeHat.get(id);
        if (hat != null && hat != Hat.NONE) {
            inv.setHelmet(new ItemStack(hat.material));
        } else {
            inv.setHelmet(armorPieceOrNull(map, ArmorSlot.HELMET));
        }
    }

    private ItemStack armorPieceOrNull(Map<ArmorSlot, Armor> map, ArmorSlot slot) {
        if (map == null) {
            return null;
        }
        Armor a = map.get(slot);
        return (a == null || a.isRemove()) ? null : wearable(a);
    }

    private Armor armorOf(UUID id, ArmorSlot slot) {
        Map<ArmorSlot, Armor> map = activeArmor.get(id);
        return map == null ? null : map.get(slot);
    }

    private static String slotName(ArmorSlot slot) {
        return switch (slot) {
            case HELMET -> "Capacete";
            case CHESTPLATE -> "Peitoral";
            case LEGGINGS -> "Calças";
            case BOOTS -> "Botas";
        };
    }

    // ── pets ──

    private void openPets(Player p) {
        Pet[] all = Pet.values();
        int size = menuSize(all.length);
        List<Integer> slots = contentSlots(all.length);
        MenuHolder holder = new MenuHolder("pets");
        Inventory inv = Bukkit.createInventory(holder, size, Component.text("Pets"));

        Pet current = activePetType.get(p.getUniqueId());
        boolean isVip = p.hasPermission(VIP_PERM);
        for (int i = 0; i < all.length && i < slots.size(); i++) {
            Pet pet = all[i];
            boolean selected = (current == null ? Pet.NONE : current) == pet;
            boolean locked = pet.vip && !isVip;
            List<String> lore = new ArrayList<>();
            lore.add(pet == Pet.NONE ? "§7Remove o pet atual" : "§7Um bichinho que te segue");
            lore.add(" ");
            if (locked) {
                lore.add("§c🔒 Exclusivo VIP");
                lore.add("§7Adquira VIP para usar");
            } else if (selected) {
                lore.add("§a✔ Selecionado");
            } else {
                if (pet.vip) lore.add("§6★ VIP");
                lore.add("§eClique para usar");
            }
            ItemStack it = item(pet.icon, pet.name, lore.toArray(new String[0]));
            if (selected && pet != Pet.NONE) glow(it);
            var meta = it.getItemMeta();
            meta.getPersistentDataContainer().set(petKey, PersistentDataType.STRING, pet.name());
            it.setItemMeta(meta);
            inv.setItem(slots.get(i), it);
        }
        p.openInventory(inv);
    }

    private void selectPet(Player p, Pet pet) {
        if (pet.vip && !p.hasPermission(VIP_PERM)) {
            p.sendActionBar(MM.deserialize("<red>Esse pet é exclusivo para VIPs!"));
            return;
        }
        Entity old = activePet.remove(p.getUniqueId());
        if (old != null && !old.isDead()) {
            old.remove();
        }
        activePetType.remove(p.getUniqueId());
        if (pet == Pet.NONE) {
            p.sendActionBar(Component.text("Pet removido", NamedTextColor.GRAY));
        } else {
            Entity e = spawnPet(p, pet);
            if (e != null) {
                activePet.put(p.getUniqueId(), e);
                activePetType.put(p.getUniqueId(), pet);
                p.sendActionBar(MM.deserialize("<green>Pet ativado!"));
            }
        }
        saveCosmetics(p);
        openPets(p);
    }

    private Entity spawnPet(Player p, Pet pet) {
        try {
            Entity e = p.getWorld().spawnEntity(p.getLocation(), pet.type);
            e.addScoreboardTag(PET_TAG);
            if (e instanceof Mob mob) {
                mob.setAI(false);
                mob.setAware(false);
                mob.setSilent(true);
                mob.setCollidable(false);
                mob.setRemoveWhenFarAway(false);
                mob.setPersistent(true);
            }
            // No gravity: movement is driven entirely by our per-tick interpolation,
            // so the pet never drifts/falls between teleports (and aerial pets float).
            e.setGravity(false);
            if (e instanceof LivingEntity le) {
                le.setInvulnerable(true);
            }
            if (pet.baby && e instanceof Ageable age) {
                age.setBaby();
            }
            return e;
        } catch (Exception ex) {
            plugin.getLogger().warning("Falha ao criar pet " + pet + ": " + ex.getMessage());
            return null;
        }
    }

    /**
     * Move each pet toward a point just behind/beside its owner. Instead of the old
     * teleport-when-far jump, the pet eases in every tick (speed scales with the
     * distance, so it accelerates when the owner runs and settles smoothly when
     * close), turns to face the owner with a smoothed yaw, and does a gentle idle
     * bob plus the occasional heart particle when it's hanging out next to them.
     */
    private void followPets() {
        petTick++;
        for (Map.Entry<UUID, Entity> entry : activePet.entrySet()) {
            UUID id = entry.getKey();
            Player owner = plugin.getServer().getPlayer(id);
            Entity pet = entry.getValue();
            if (owner == null || pet == null || pet.isDead()) {
                continue;
            }
            Location ol = owner.getLocation();
            Location pl = pet.getLocation();
            Location target = petTarget(owner, ol, id);

            // Different world or owner teleported far away → instant catch-up.
            if (pet.getWorld() != ol.getWorld() || pl.distanceSquared(target) > 64.0) {
                target.setYaw(yawTo(target, ol));
                pet.teleport(target);
                continue;
            }

            double dist = pl.distance(target);
            // Ease-in factor proportional to distance, clamped so it neither
            // overshoots nor crawls.
            double factor = Math.max(0.12, Math.min(0.55, dist * 0.35));
            double nx = pl.getX() + (target.getX() - pl.getX()) * factor;
            double ny = pl.getY() + (target.getY() - pl.getY()) * factor;
            double nz = pl.getZ() + (target.getZ() - pl.getZ()) * factor;

            boolean settled = dist < 0.6;
            if (settled) {
                // Bob in place, out of phase per pet so multiple pets don't sync.
                double phase = (petTick + Math.abs(id.hashCode()) % 40) * 0.25;
                ny = target.getY() + Math.max(0.0, Math.sin(phase) * 0.12);
            }

            Location next = new Location(pet.getWorld(), nx, ny, nz);
            next.setYaw(lerpAngle(pl.getYaw(), yawTo(next, ol), 0.35f));
            next.setPitch(0f);
            pet.teleport(next);

            if (settled && petTick % 30 == 0) {
                pet.getWorld().spawnParticle(Particle.HEART,
                        pet.getLocation().add(0, 0.6, 0), 1, 0.1, 0.1, 0.1, 0.0);
            }
        }
    }

    /** The point a pet should sit at: behind and to the side of its owner. */
    private Location petTarget(Player owner, Location ol, UUID id) {
        Vector dir = ol.getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-4) {
            dir = new Vector(0, 0, 1);
        }
        dir.normalize();
        Vector side = new Vector(-dir.getZ(), 0, dir.getX()).multiply(0.6);
        Location target = ol.clone().add(dir.multiply(-1.2)).add(side);
        target.setY(ol.getY());
        Pet type = activePetType.get(id);
        if (type != null && type.flying) {
            target.add(0, 1.1, 0); // aerial pets hover at head height
        }
        return target;
    }

    /** Yaw (degrees) pointing from {@code from} toward {@code to}, ignoring pitch. */
    private static float yawTo(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) {
            return from.getYaw();
        }
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Interpolate an angle along the shortest arc, so the turn never spins 359°. */
    private static float lerpAngle(float from, float to, float t) {
        float diff = ((to - from + 540f) % 360f) - 180f;
        return from + diff * t;
    }

    // ── persistence (cosmetic preferences, cross-session) ──

    /**
     * Load the player's saved cosmetic selection from the backend (reusing the
     * inventory blob store under {@link #COSMETICS_TYPE}) and apply it. Runs the
     * HTTP call off-thread, then applies on the main thread.
     */
    private void loadCosmetics(Player p) {
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            InventoryData data;
            try {
                data = crystal.backend().getInventory(id.toString(), COSMETICS_TYPE);
            } catch (Exception e) {
                return; // backend down → fall back to a clean session
            }
            cosmeticsVersion.put(id, data.version());
            if (data.isEmpty()) {
                return;
            }
            final JsonNode node;
            try {
                node = Json.MAPPER.readTree(data.content());
            } catch (Exception e) {
                plugin.getLogger().warning("Cosméticos inválidos para " + id + ": " + e.getMessage());
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> applyLoaded(p, node));
        });
    }

    private void applyLoaded(Player p, JsonNode node) {
        if (!p.isOnline()) {
            return;
        }
        UUID id = p.getUniqueId();
        boolean isVip = p.hasPermission(VIP_PERM);

        String cos = node.path("cosmetic").asText(null);
        if (cos != null) {
            try {
                Cosmetic c = Cosmetic.valueOf(cos);
                if (c != Cosmetic.NONE && (!c.vip || isVip)) {
                    activeCosmetic.put(id, c);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        String hat = node.path("hat").asText(null);
        if (hat != null) {
            try {
                Hat h = Hat.valueOf(hat);
                if (h != Hat.NONE && (!h.vip || isVip)) {
                    activeHat.put(id, h);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        JsonNode armor = node.path("armor");
        if (armor.isObject()) {
            Map<ArmorSlot, Armor> map = new EnumMap<>(ArmorSlot.class);
            armor.fieldNames().forEachRemaining(slotName -> {
                try {
                    ArmorSlot slot = ArmorSlot.valueOf(slotName);
                    Armor a = Armor.valueOf(armor.path(slotName).asText());
                    if (!a.isRemove() && a.slot == slot && (!a.vip || isVip)) {
                        map.put(slot, a);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            });
            if (!map.isEmpty()) {
                activeArmor.put(id, map);
            }
        }

        String pet = node.path("pet").asText(null);
        if (pet != null) {
            try {
                Pet pt = Pet.valueOf(pet);
                if (pt != Pet.NONE && (!pt.vip || isVip)) {
                    Entity old = activePet.remove(id);
                    if (old != null && !old.isDead()) {
                        old.remove();
                    }
                    Entity e = spawnPet(p, pt);
                    if (e != null) {
                        activePet.put(id, e);
                        activePetType.put(id, pt);
                    }
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        applyAppearance(p); // re-apply restored hat/armour visuals
    }

    /** Serialise the player's current selection and save it (off-thread). */
    private void saveCosmetics(Player p) {
        UUID id = p.getUniqueId();
        ObjectNode root = Json.MAPPER.createObjectNode();
        Cosmetic c = activeCosmetic.get(id);
        if (c != null && c != Cosmetic.NONE) {
            root.put("cosmetic", c.name());
        }
        Hat h = activeHat.get(id);
        if (h != null && h != Hat.NONE) {
            root.put("hat", h.name());
        }
        Pet pet = activePetType.get(id);
        if (pet != null && pet != Pet.NONE) {
            root.put("pet", pet.name());
        }
        Map<ArmorSlot, Armor> armor = activeArmor.get(id);
        if (armor != null && !armor.isEmpty()) {
            ObjectNode an = root.putObject("armor");
            for (Map.Entry<ArmorSlot, Armor> e : armor.entrySet()) {
                if (!e.getValue().isRemove()) {
                    an.put(e.getKey().name(), e.getValue().name());
                }
            }
        }
        final String content = root.toString();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int version = cosmeticsVersion.getOrDefault(id, 0);
            try {
                cosmeticsVersion.put(id, crystal.backend().saveInventory(id.toString(), COSMETICS_TYPE, content, version));
            } catch (BackendHttpClient.BackendException ex) {
                // Version drifted (another save raced us) → refetch and retry once.
                try {
                    InventoryData fresh = crystal.backend().getInventory(id.toString(), COSMETICS_TYPE);
                    cosmeticsVersion.put(id,
                            crystal.backend().saveInventory(id.toString(), COSMETICS_TYPE, content, fresh.version()));
                } catch (Exception ignored) {
                }
            } catch (Exception ignored) {
            }
        });
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
        int size = menuSize(Math.max(lobbies.size(), 1));
        List<Integer> slots = contentSlots(lobbies.size());
        MenuHolder holder = new MenuHolder("lobbys");
        Inventory inv = Bukkit.createInventory(holder, size, Component.text("Lobbys"));

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
            case "hats" -> {
                var pdc = clicked.getItemMeta().getPersistentDataContainer();
                if (pdc.has(navKey, PersistentDataType.STRING)) {
                    openWardrobe(p);
                    return;
                }
                String id = pdc.get(hatKey, PersistentDataType.STRING);
                if (id != null) {
                    try {
                        selectHat(p, Hat.valueOf(id));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            case "wardrobe" -> {
                String target = clicked.getItemMeta().getPersistentDataContainer()
                        .get(navKey, PersistentDataType.STRING);
                if (target != null) {
                    if (target.equals("hats")) {
                        openHats(p);
                    } else if (target.startsWith("armor:")) {
                        try {
                            openArmorSlotMenu(p, ArmorSlot.valueOf(target.substring("armor:".length())));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
            }
            case "armor" -> {
                var pdc = clicked.getItemMeta().getPersistentDataContainer();
                if (pdc.has(navKey, PersistentDataType.STRING)) {
                    openWardrobe(p);
                    return;
                }
                String id = pdc.get(armorKey, PersistentDataType.STRING);
                if (id != null) {
                    try {
                        selectArmor(p, Armor.valueOf(id));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            case "pets" -> {
                String id = clicked.getItemMeta().getPersistentDataContainer()
                        .get(petKey, PersistentDataType.STRING);
                if (id != null) {
                    try {
                        selectPet(p, Pet.valueOf(id));
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
        p.getInventory().setItem(5, hideToggleItem(p));
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

    /**
     * Centered content slots — no filler panes. Content is centered both
     * vertically (a blank row above/below it when it fits) and horizontally
     * (each row centered across the full 9 columns). Empty slots stay air for a
     * clean, modern look.
     */
    private List<Integer> contentSlots(int count) {
        int contentRows = Math.max(1, (int) Math.ceil(count / 9.0));
        int totalRows = Math.min(6, contentRows + 2);
        int topPad = (totalRows - contentRows) / 2;
        List<Integer> slots = new ArrayList<>();
        int remaining = count;
        for (int r = 0; r < contentRows; r++) {
            int inRow = Math.min(9, remaining);
            int startCol = (9 - inRow) / 2;
            for (int c = 0; c < inRow; c++) {
                slots.add((topPad + r) * 9 + startCol + c);
            }
            remaining -= inRow;
        }
        return slots;
    }

    /** Inventory size (multiple of 9) for a centered list of {@code count} items. */
    private int menuSize(int count) {
        int contentRows = Math.max(1, (int) Math.ceil(count / 9.0));
        return Math.min(6, contentRows + 2) * 9;
    }

    /** A "← back" arrow that returns to the wardrobe hub. */
    private ItemStack backButton() {
        ItemStack it = item(Material.ARROW, "§e← Voltar", "§7Voltar ao vestuário");
        var meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(navKey, PersistentDataType.STRING, "wardrobe");
        it.setItemMeta(meta);
        return it;
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
