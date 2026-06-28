# Lobby GUIs, Navigation, Fly & Protection — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add GUI-driven profile + lobby selector menus to the lobby, auto-fly for permitted players, a 200-block boundary, and block-interaction blocking — across `crystal-lobby` and `crystal-login`.

**Architecture:** Pure Bukkit/Paper event listeners and inventory GUIs in `crystal-lobby` and `crystal-login`. Cross-server moves use the Velocity-supported `BungeeCord` plugin-messaging `Connect` subchannel. The lobby selector is built on demand from `crystal.backend().listServers("lobby")`, so it reflects the live registry with no static config.

**Tech Stack:** Java 21, Paper 1.21 API, Maven (multi-module reactor), `crystal-core` SDK (`CrystalCore` facade → `backend()`, `config()`).

## Global Constraints

- Java **21**, Paper API **1.21** (`api-version: '1.21'`).
- Plugins talk to the backend **only** through the `crystal-core` SDK (`crystal.backend()` / `crystal.config()`), never to services directly.
- Permission node for flight is **`crystal.fly`**, default **`op`**, in BOTH plugins.
- Boundary radius is **200 blocks horizontal** (x/z only, ignore Y); compare with `distanceSquared` against `200.0 * 200.0 = 40000.0`.
- These Minecraft plugins have **no unit-test harness** (consistent with the rest of the repo). The per-task gate is a **successful Maven build** plus the documented manual verification. Do not invent a fake test framework.
- Build a single plugin module with: `mvn -B -pl plugins/<module> -am package -DskipTests` (the `-am` flag also rebuilds the `crystal-core` dependency). Expected tail: `BUILD SUCCESS`.
- Color codes use the legacy `§` style already used across `LobbyHotbar` (e.g. `§b`, `§7`). Keep `decoration(TextDecoration.ITALIC, false)` on every component, matching existing code.

---

## File Structure

**`crystal-lobby`**
- `LobbyProtection.java` (modify) — add 200-block boundary to `onMove`; add block-break/place/interact cancels.
- `CrystalLobbyPlugin.java` (modify) — auto-fly on join; register outgoing `BungeeCord` channel.
- `LobbyHotbar.java` (modify) — caveira opens a Profile GUI; new slot-3 "Lobbys" item opens the lobby selector GUI; `Connect` plugin message; compass relabel to "Modos de Jogo".
- `src/main/resources/plugin.yml` (modify) — declare `crystal.fly`.

**`crystal-login`**
- `LoginProtection.java` (create) — 200-block boundary + block-interaction cancels.
- `CrystalLoginPlugin.java` (modify) — register `LoginProtection`; auto-fly on join.
- `src/main/resources/plugin.yml` (modify) — declare `crystal.fly`.

---

## Task 1: Lobby protection — boundary + block blocking

**Files:**
- Modify: `plugins/crystal-lobby/src/main/java/com/redecrystal/lobby/LobbyProtection.java`

**Interfaces:**
- Consumes: `CrystalLobbyPlugin.getSpawn()` → `Location` (clone, may be null), `getVoidY()` → `double`.
- Produces: nothing new consumed by later tasks.

- [ ] **Step 1: Replace `onMove` and add block-interaction handlers**

Replace the entire body of `LobbyProtection.java` with:

```java
package com.redecrystal.lobby;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Action;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Lobby protection: no damage, no hunger, no death, no block interaction, and a
 * 200-block boundary. Falling into the void or wandering past the boundary
 * teleports the player back to the configured lobby spawn instead of killing
 * them. The spawn + void threshold come from {@link CrystalLobbyPlugin}
 * (central config, hot-reloaded).
 */
public final class LobbyProtection implements Listener {

    /** Horizontal leash radius from spawn, squared (200 blocks). */
    private static final double MAX_RADIUS_SQ = 200.0 * 200.0;

    private final CrystalLobbyPlugin plugin;

    public LobbyProtection(CrystalLobbyPlugin plugin) {
        this.plugin = plugin;
    }

    /** Cancel ALL damage to players (fall, fire, drowning, PvP, …). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            event.setCancelled(true);
        }
    }

    /** No hunger in the lobby. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        event.setCancelled(true);
        if (event.getEntity() instanceof Player player) {
            player.setFoodLevel(20);
            player.setSaturation(20f);
        }
    }

    /**
     * Void rescue + boundary. Only runs when the player crosses a block
     * boundary (cheap). Below the void threshold OR past the 200-block
     * horizontal radius → back to spawn (no death).
     */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        Location spawn = plugin.getSpawn();
        if (spawn == null) {
            return;
        }
        if (to.getY() < plugin.getVoidY()) {
            event.getPlayer().teleport(spawn);
            return;
        }
        if (spawn.getWorld() != null && spawn.getWorld().equals(to.getWorld())) {
            double dx = to.getX() - spawn.getX();
            double dz = to.getZ() - spawn.getZ();
            if (dx * dx + dz * dz > MAX_RADIUS_SQ) {
                event.getPlayer().teleport(spawn);
            }
        }
    }

    /** Belt-and-suspenders: if a death ever slips through, respawn at the lobby. */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Location spawn = plugin.getSpawn();
        if (spawn != null) {
            event.setRespawnLocation(spawn);
        }
    }

    // ── block interaction is fully blocked in the lobby ──

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        event.setCancelled(true);
    }

    /** Cancel any interaction with a block (buttons, doors, chests, plates…). */
    @EventHandler
    public void onInteractBlock(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) {
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                || event.getAction() == Action.LEFT_CLICK_BLOCK
                || event.getAction() == Action.PHYSICAL) {
            event.setCancelled(true);
        }
    }
}
```

> Note: `onInteractBlock` only cancels when a **block** was clicked. The hotbar item menus in `LobbyHotbar.onInteract` open programmatically and are unaffected.

- [ ] **Step 2: Build the lobby module**

Run: `mvn -B -pl plugins/crystal-lobby -am package -DskipTests`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add plugins/crystal-lobby/src/main/java/com/redecrystal/lobby/LobbyProtection.java
git commit -m "feat(lobby): 200-block boundary and block-interaction blocking"
```

---

## Task 2: Lobby auto-fly + permission

**Files:**
- Modify: `plugins/crystal-lobby/src/main/java/com/redecrystal/lobby/CrystalLobbyPlugin.java:110-124` (the `onJoin` handler)
- Modify: `plugins/crystal-lobby/src/main/resources/plugin.yml`

**Interfaces:**
- Consumes: nothing new.
- Produces: permission `crystal.fly` (also used by Task 5's login plugin, declared separately there).

- [ ] **Step 1: Enable flight on join for permitted players**

In `CrystalLobbyPlugin.java`, find the `onJoin` method. After this existing line:

```java
        player.setFireTicks(0);
        sendToSpawn(player);
```

change it to:

```java
        player.setFireTicks(0);
        if (player.hasPermission("crystal.fly")) {
            player.setAllowFlight(true);
            player.setFlying(true);
        }
        sendToSpawn(player);
```

- [ ] **Step 2: Declare the permission in `plugin.yml`**

In `plugins/crystal-lobby/src/main/resources/plugin.yml`, under the existing `permissions:` block, add `crystal.fly` so the block reads:

```yaml
permissions:
  crystal.lobby.admin:
    description: Manage the lobby (set spawn)
    default: op
  crystal.fly:
    description: Fly freely around the lobby and login
    default: op
```

- [ ] **Step 3: Build the lobby module**

Run: `mvn -B -pl plugins/crystal-lobby -am package -DskipTests`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add plugins/crystal-lobby/src/main/java/com/redecrystal/lobby/CrystalLobbyPlugin.java plugins/crystal-lobby/src/main/resources/plugin.yml
git commit -m "feat(lobby): auto-fly on join for crystal.fly permission"
```

---

## Task 3: Profile GUI (caveira)

**Files:**
- Modify: `plugins/crystal-lobby/src/main/java/com/redecrystal/lobby/LobbyHotbar.java` (the `showProfile` method)

**Interfaces:**
- Consumes: `crystal.backend().getProfile(String uuid)` → `ProfileData` (`rank()`/`level()`/`coins()`); existing `MenuHolder` record; existing `named(...)` helper.
- Produces: a `MenuHolder("profile")` GUI (read-only — clicks already cancelled by `onClick`).

- [ ] **Step 1: Replace `showProfile` to open a GUI instead of chat**

In `LobbyHotbar.java`, replace the entire `showProfile` method with these two methods:

```java
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
```

> The `NamedTextColor` import may now be unused; if the compiler warns, leave it — it is still used elsewhere in the file (`toggleHide`, `showProfile` previously). Verify with the build.

- [ ] **Step 2: Build the lobby module**

Run: `mvn -B -pl plugins/crystal-lobby -am package -DskipTests`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add plugins/crystal-lobby/src/main/java/com/redecrystal/lobby/LobbyHotbar.java
git commit -m "feat(lobby): profile head opens a GUI instead of chat"
```

---

## Task 4: Lobby selector GUI + server navigation

**Files:**
- Modify: `plugins/crystal-lobby/src/main/java/com/redecrystal/lobby/LobbyHotbar.java`
- Modify: `plugins/crystal-lobby/src/main/java/com/redecrystal/lobby/CrystalLobbyPlugin.java` (`onEnable`, register outgoing channel)

**Interfaces:**
- Consumes: `crystal.backend().listServers("lobby")` → `List<NetworkServer>` (`serverId()`, `isOnline()`, `onlinePlayers()`, `maxPlayers()`); `crystal.config().serverId()` → `String`; existing `MenuHolder`, `named(...)`, `handleMenuClick`.
- Produces: a `MenuHolder("lobbys")` GUI; `Connect` plugin messages on channel `"BungeeCord"`.

- [ ] **Step 1: Register the outgoing `BungeeCord` channel in the lobby plugin**

In `CrystalLobbyPlugin.java`, inside `onEnable`, immediately after this line:

```java
        getServer().getPluginManager().registerEvents(this, this);
```

add:

```java
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
```

- [ ] **Step 2: Add the NamespacedKey field + imports to `LobbyHotbar`**

In `LobbyHotbar.java`, add these imports alongside the existing imports:

```java
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import com.redecrystal.core.http.NetworkServer;
```

Add a field and initialize it in the constructor. Change the field block + constructor to:

```java
    private final JavaPlugin plugin;
    private final CrystalCore crystal;
    private final Map<UUID, Boolean> playersHidden = new ConcurrentHashMap<>();
    private final NamespacedKey lobbyKey;

    public LobbyHotbar(JavaPlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
        this.lobbyKey = new NamespacedKey(plugin, "lobby-id");
    }
```

- [ ] **Step 3: Add the "Lobbys" hotbar item and relabel the compass**

In `LobbyHotbar.giveHotbar`, replace the item-setup lines so slot 0 is relabeled and slot 3 is added:

```java
        p.getInventory().clear();
        p.getInventory().setItem(0, named(new ItemStack(Material.COMPASS), "§bModos de Jogo", "§7Clique para escolher um modo"));
        p.getInventory().setItem(3, named(new ItemStack(Material.RED_BED), "§aLobbys", "§7Clique para trocar de lobby"));
        p.getInventory().setItem(4, profileHead(p));
        p.getInventory().setItem(7, hideToggleItem(p));
        p.getInventory().setItem(8, named(new ItemStack(Material.NETHER_STAR), "§dCosméticos", "§7Em breve"));
        p.getInventory().setHeldItemSlot(0);
```

- [ ] **Step 4: Route the new item in `onInteract`**

In `LobbyHotbar.onInteract`, add a `RED_BED` case to the `switch`:

```java
        switch (event.getItem().getType()) {
            case COMPASS -> { event.setCancelled(true); openGames(p); }
            case RED_BED -> { event.setCancelled(true); openLobbys(p); }
            case PLAYER_HEAD -> { event.setCancelled(true); showProfile(p); }
            case LIME_DYE, GRAY_DYE -> { event.setCancelled(true); toggleHide(p); }
            case NETHER_STAR -> { event.setCancelled(true); openCosmetics(p); }
            default -> { }
        }
```

- [ ] **Step 5: Add the selector GUI builder + connect helper**

In `LobbyHotbar.java`, add these three methods (e.g. just after `openCosmetics`):

```java
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
```

- [ ] **Step 6: Handle clicks in the selector menu**

In `LobbyHotbar.handleMenuClick`, add a branch for the `"lobbys"` menu. Replace the method with:

```java
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
```

- [ ] **Step 7: Build the lobby module**

Run: `mvn -B -pl plugins/crystal-lobby -am package -DskipTests`
Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Commit**

```bash
git add plugins/crystal-lobby/src/main/java/com/redecrystal/lobby/LobbyHotbar.java plugins/crystal-lobby/src/main/java/com/redecrystal/lobby/CrystalLobbyPlugin.java
git commit -m "feat(lobby): GUI lobby selector with live registry + BungeeCord connect"
```

---

## Task 5: Login protection + auto-fly

**Files:**
- Create: `plugins/crystal-login/src/main/java/com/redecrystal/login/LoginProtection.java`
- Modify: `plugins/crystal-login/src/main/java/com/redecrystal/login/CrystalLoginPlugin.java`
- Modify: `plugins/crystal-login/src/main/resources/plugin.yml`

**Interfaces:**
- Consumes: world spawn via `to.getWorld().getSpawnLocation()`.
- Produces: nothing for later tasks (final task).

- [ ] **Step 1: Create `LoginProtection`**

Create `plugins/crystal-login/src/main/java/com/redecrystal/login/LoginProtection.java`:

```java
package com.redecrystal.login;

import org.bukkit.Location;
import org.bukkit.event.Action;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Login-server guard: cancels all block interaction and pulls players back to
 * the world spawn if they wander beyond a 200-block (horizontal) radius. The
 * login world is a void canvas with its spawn set by crystal-worldinit.
 */
public final class LoginProtection implements Listener {

    /** Horizontal leash radius from spawn, squared (200 blocks). */
    private static final double MAX_RADIUS_SQ = 200.0 * 200.0;

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        if (to.getWorld() == null) {
            return;
        }
        Location spawn = to.getWorld().getSpawnLocation();
        double dx = to.getX() - spawn.getX();
        double dz = to.getZ() - spawn.getZ();
        if (dx * dx + dz * dz > MAX_RADIUS_SQ) {
            event.getPlayer().teleport(spawn);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        event.setCancelled(true);
    }

    @EventHandler
    public void onInteractBlock(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) {
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                || event.getAction() == Action.LEFT_CLICK_BLOCK
                || event.getAction() == Action.PHYSICAL) {
            event.setCancelled(true);
        }
    }
}
```

- [ ] **Step 2: Register the listener and enable flight on join**

In `CrystalLoginPlugin.java`, in `onEnable`, after this existing line:

```java
        getServer().getPluginManager().registerEvents(this, this);
```

add:

```java
        getServer().getPluginManager().registerEvents(new LoginProtection(), this);
```

Then in the `onJoin` method, after this existing line:

```java
        String uuid = player.getUniqueId().toString();
```

add:

```java
        if (player.hasPermission("crystal.fly")) {
            player.setAllowFlight(true);
            player.setFlying(true);
        }
```

- [ ] **Step 3: Declare the permission in `plugin.yml`**

Replace `plugins/crystal-login/src/main/resources/plugin.yml` with:

```yaml
name: CrystalLogin
version: 0.1.0
main: com.redecrystal.login.CrystalLoginPlugin
api-version: '1.21'
author: RedeCrystal
description: Session/auth gateway that forwards players to a lobby.
permissions:
  crystal.fly:
    description: Fly freely around the lobby and login
    default: op
```

- [ ] **Step 4: Build the login module**

Run: `mvn -B -pl plugins/crystal-login -am package -DskipTests`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add plugins/crystal-login/src/main/java/com/redecrystal/login/LoginProtection.java plugins/crystal-login/src/main/java/com/redecrystal/login/CrystalLoginPlugin.java plugins/crystal-login/src/main/resources/plugin.yml
git commit -m "feat(login): 200-block boundary, block blocking, and auto-fly"
```

---

## Manual Verification (after all tasks, stack up)

Build all plugin jars and bring the servers up:

```bash
make plugins
docker compose up -d proxy-01 login-01 lobby-01 lobby-02 lobby-03
```

Connect a 1.21.x client and verify:

- [ ] Right-click the **caveira** → a "Perfil" GUI opens showing the player head with Rank / Level / Coins.
- [ ] Right-click the **cama (Lobbys)** → a "Lobbys" GUI lists every online lobby; the current one is the emerald block marked "Você está aqui" and is not clickable; clicking another lobby connects you there.
- [ ] Start a new `lobby-03` (if not already up) → reopening the selector shows it; stop a lobby → reopening no longer shows it.
- [ ] The **bússola (Modos de Jogo)** still opens the games menu → Parkour.
- [ ] A player with `crystal.fly` (e.g. op) joins **already flying** on both login and lobby.
- [ ] Walking/flying past ~200 blocks (x/z) from spawn teleports back to spawn, on both login and lobby.
- [ ] Breaking, placing, or right-clicking blocks (buttons/doors/chests) does nothing on both login and lobby.

---

## Self-Review notes

- **Spec coverage:** Profile GUI (Task 3), lobby selector + dynamic registry + plugin messaging (Task 4), compass stays as game modes (Task 4 relabel), auto-fly lobby+login (Tasks 2 & 5), 200-block boundary lobby+login (Tasks 1 & 5), block-interaction blocking lobby+login (Tasks 1 & 5). All spec sections covered.
- **Type consistency:** `MenuHolder` types used: `"games"` (existing), `"profile"` (Task 3), `"lobbys"` (Task 4) — consistent between builder and `handleMenuClick`. `NamespacedKey lobbyKey` set in `openLobbyMenu` and read in `handleMenuClick`. `connectTo` channel `"BungeeCord"` matches the registered outgoing channel in `CrystalLobbyPlugin`.
- **Permission node:** `crystal.fly` declared in both `plugin.yml` files and checked with the identical string in both join handlers.
```
