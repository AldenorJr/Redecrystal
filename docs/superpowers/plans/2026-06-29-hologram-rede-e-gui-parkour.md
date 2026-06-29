# Holograma de rede (`/hologram`) + texto do seletor de Parkour — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Melhorar o texto do item Parkour no menu do lobby e criar um plugin genérico `crystal-hologram` com o comando `/hologram` que renderiza hologramas de apresentação replicados a todos os servidores via config central + hot-reload.

**Architecture:** Novo módulo Paper `crystal-hologram` (espelha `crystal-tag`). O estado vive na chave de config `holograms` do backend; o comando faz read-modify-write assíncrono e o `putConfig` dispara `config-updated` no Kafka, que cada servidor consome para re-renderizar `TextDisplay`s. Limpeza por scoreboard tag (espelha `crystal-parkour/ParkourHologram`).

**Tech Stack:** Java 21, Paper API 1.21.1, Maven (shade), crystal-core SDK (ConfigProvider/BackendHttpClient/Kafka), Adventure (`LegacyComponentSerializer`).

## Global Constraints

- Texto de jogador em **PT**; código e comentários em **inglês**.
- **Nunca bloquear a main thread:** HTTP/`putConfig` em `runTaskAsynchronously`; voltar com `runTask` antes de tocar entidades/API do jogo.
- DTO é `record`; serviço/utilitário é `final`. Constantes em vez de literais mágicos.
- Backend falha-aberto: erro nunca derruba o servidor (lista vazia como fallback).
- Limpar o que cria (TextDisplay) no disable/reload, via tag.
- Paper API: `1.21.1-R0.1-SNAPSHOT`; `api-version: '1.21'`.
- Plugins Bukkit **não têm harness de teste unitário** neste repo (só `crystal-core`). Verificação = compile (`mvn -pl plugins/<m> -am compile`), `make plugins`, e validação **in-game** recriando o container.
- Pacote do novo módulo é **plano** (`com.redecrystal.hologram`, sem sub-pacotes) para coesão com classes package-private. Só `CrystalHologramPlugin` é `public` (exigência do Bukkit).

---

### Task 1: Texto do seletor de Parkour

**Files:**
- Modify: `plugins/crystal-lobby/src/main/java/com/redecrystal/lobby/listener/LobbyHotbar.java:558-559`

**Interfaces:**
- Consumes: helper existente `item(Material, String name, String... lore)`.
- Produces: nada (mudança isolada de strings).

- [ ] **Step 1: Trocar as strings do item Parkour**

Substituir as linhas 558-559 (a chamada `inv.setItem(... item(Material.FEATHER, ...))`) por:

```java
        inv.setItem(bodySlots(1).get(0), item(Material.FEATHER, "§a✦ Parkour",
                "§7Desafie sua agilidade e seus",
                "§7reflexos em um percurso cheio",
                "§7de obstáculos e checkpoints.",
                "",
                "§eClique para começar — pise",
                "§ena placa de ferro no início!"));
```

(A string vazia `""` vira uma linha em branco na lore; o helper `item(...)` já trata cada vararg como uma linha.)

- [ ] **Step 2: Compilar o módulo**

Run: `mvn -pl plugins/crystal-lobby -am compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add plugins/crystal-lobby/src/main/java/com/redecrystal/lobby/listener/LobbyHotbar.java
git commit -m "feat(lobby): texto mais descritivo no seletor de parkour"
```

- [ ] **Step 4: Validação in-game (depois do build geral)**

Após `make plugins` e recriar o lobby (`docker compose up -d --force-recreate --no-deps lobby-01`), abrir o menu "Modos de Jogo" e conferir o novo texto/linha em branco no item Parkour. O clique deve continuar abrindo o parkour.

---

### Task 2: Scaffold do módulo `crystal-hologram`

**Files:**
- Create: `plugins/crystal-hologram/pom.xml`
- Create: `plugins/crystal-hologram/src/main/resources/plugin.yml`
- Create: `plugins/crystal-hologram/src/main/java/com/redecrystal/hologram/CrystalHologramPlugin.java`
- Modify: `plugins/pom.xml` (lista `<modules>`)

**Interfaces:**
- Produces: módulo Maven `crystal-hologram`; classe `CrystalHologramPlugin extends JavaPlugin` (bootstrap mínimo do SDK — wiring completo na Task 6).

- [ ] **Step 1: Registrar o módulo no parent pom**

Em `plugins/pom.xml`, dentro de `<modules>`, adicionar a linha após `<module>crystal-skin</module>`:

```xml
        <module>crystal-hologram</module>
```

- [ ] **Step 2: Criar `plugins/crystal-hologram/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.redecrystal</groupId>
        <artifactId>plugins-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>crystal-hologram</artifactId>
    <name>Crystal Hologram</name>
    <description>Hologramas de rede (/hologram) replicados via config central + hot-reload.</description>

    <dependencies>
        <dependency>
            <groupId>com.redecrystal</groupId>
            <artifactId>crystal-core</artifactId>
        </dependency>
        <dependency>
            <groupId>io.papermc.paper</groupId>
            <artifactId>paper-api</artifactId>
        </dependency>
    </dependencies>

    <build>
        <finalName>${project.artifactId}</finalName>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
            </plugin>
        </plugins>
    </build>

</project>
```

- [ ] **Step 3: Criar `plugins/crystal-hologram/src/main/resources/plugin.yml`**

```yaml
name: CrystalHologram
version: 0.1.0
main: com.redecrystal.hologram.CrystalHologramPlugin
api-version: '1.21'
author: RedeCrystal
description: Hologramas de rede (/hologram), driven by central config.
commands:
  hologram:
    description: Gerenciar hologramas de rede (admin)
    usage: /hologram set <id> <texto> | move <id> | remove <id> | list
permissions:
  crystal.hologram.admin:
    description: Gerenciar hologramas de rede
    default: op
```

- [ ] **Step 4: Criar `CrystalHologramPlugin` (bootstrap mínimo)**

`plugins/crystal-hologram/src/main/java/com/redecrystal/hologram/CrystalHologramPlugin.java`:

```java
package com.redecrystal.hologram;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Network hologram plugin. Boots the SDK; full rendering + command wiring is
 * added in a later task. Holograms are driven by the central {@code holograms}
 * config so the same set appears on every server running this plugin.
 */
public final class CrystalHologramPlugin extends JavaPlugin {

    private CrystalCore crystal;

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());
        getLogger().info("CrystalHologram enabled.");
    }

    @Override
    public void onDisable() {
        if (crystal != null) {
            crystal.close();
        }
    }
}
```

- [ ] **Step 5: Compilar o módulo novo**

Run: `mvn -pl plugins/crystal-hologram -am compile`
Expected: `BUILD SUCCESS` (resolve crystal-core + paper-api).

- [ ] **Step 6: Commit**

```bash
git add plugins/pom.xml plugins/crystal-hologram
git commit -m "feat(hologram): scaffold do modulo crystal-hologram"
```

---

### Task 3: `HologramDef` (DTO) + `HologramStore` (persistência)

**Files:**
- Create: `plugins/crystal-hologram/src/main/java/com/redecrystal/hologram/HologramDef.java`
- Create: `plugins/crystal-hologram/src/main/java/com/redecrystal/hologram/HologramStore.java`

**Interfaces:**
- Consumes: `CrystalCore.configProvider().get(key)` → `RemoteConfig.value(field)`; `CrystalCore.backend().putConfig(String, Map<String,Object>)`.
- Produces:
  - `record HologramDef(String id, String world, double x, double y, double z, List<String> lines)`
  - `HologramStore(CrystalCore crystal)`, com `static final String CONFIG_KEY = "holograms"`, e métodos `List<HologramDef> all()`, `void put(HologramDef def)`, `boolean remove(String id)`.

- [ ] **Step 1: Criar `HologramDef`**

```java
package com.redecrystal.hologram;

import java.util.List;

/** A network hologram: an id, a world + position, and its (un-coloured) lines. */
record HologramDef(String id, String world, double x, double y, double z, List<String> lines) {
}
```

- [ ] **Step 2: Criar `HologramStore`**

```java
package com.redecrystal.hologram;

import com.redecrystal.core.CrystalCore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads/writes the network hologram list in the central {@code holograms} config.
 * Read is fail-open (empty on a backend blip); writes do a read-modify-write of the
 * whole list and {@code putConfig} it back, which hot-reloads every server.
 *
 * <p>All methods are synchronous (they may hit the backend) — callers schedule them
 * off the main thread.
 */
final class HologramStore {

    static final String CONFIG_KEY = "holograms";
    private static final String ITEMS = "items";

    private final CrystalCore crystal;

    HologramStore(CrystalCore crystal) {
        this.crystal = crystal;
    }

    /** Current holograms; empty if the config is missing or the backend is down. */
    List<HologramDef> all() {
        Object raw = crystal.configProvider().get(CONFIG_KEY).value(ITEMS);
        List<HologramDef> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    HologramDef def = fromMap(m);
                    if (def != null) {
                        out.add(def);
                    }
                }
            }
        }
        return out;
    }

    /** Create or replace by id (case-insensitive), persisting the full list. */
    void put(HologramDef def) {
        List<HologramDef> current = all();
        current.removeIf(d -> d.id().equalsIgnoreCase(def.id()));
        current.add(def);
        save(current);
    }

    /** Remove by id; true if something was removed. */
    boolean remove(String id) {
        List<HologramDef> current = all();
        boolean removed = current.removeIf(d -> d.id().equalsIgnoreCase(id));
        if (removed) {
            save(current);
        }
        return removed;
    }

    private void save(List<HologramDef> defs) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (HologramDef d : defs) {
            items.add(toMap(d));
        }
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ITEMS, items);
        crystal.backend().putConfig(CONFIG_KEY, cfg);
    }

    private static HologramDef fromMap(Map<?, ?> m) {
        Object id = m.get("id");
        Object world = m.get("world");
        if (id == null || world == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        if (m.get("lines") instanceof List<?> raw) {
            for (Object l : raw) {
                lines.add(String.valueOf(l));
            }
        }
        return new HologramDef(String.valueOf(id), String.valueOf(world),
                num(m.get("x")), num(m.get("y")), num(m.get("z")), lines);
    }

    private static Map<String, Object> toMap(HologramDef d) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", d.id());
        m.put("world", d.world());
        m.put("x", d.x());
        m.put("y", d.y());
        m.put("z", d.z());
        m.put("lines", d.lines());
        return m;
    }

    private static double num(Object o) {
        return (o instanceof Number n) ? n.doubleValue() : 0.0;
    }
}
```

- [ ] **Step 3: Compilar**

Run: `mvn -pl plugins/crystal-hologram -am compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add plugins/crystal-hologram/src/main/java/com/redecrystal/hologram/HologramDef.java plugins/crystal-hologram/src/main/java/com/redecrystal/hologram/HologramStore.java
git commit -m "feat(hologram): DTO e store da config holograms"
```

---

### Task 4: `HologramRenderer` (TextDisplay + cleanup)

**Files:**
- Create: `plugins/crystal-hologram/src/main/java/com/redecrystal/hologram/HologramRenderer.java`

**Interfaces:**
- Consumes: `HologramDef` (id/world/x/y/z/lines).
- Produces: `HologramRenderer(JavaPlugin plugin)`, com `static final String HOLO_TAG = "crystal_holo"`, métodos `void render(List<HologramDef> defs)` e `void clearAll()`. **`render` deve rodar na main thread.**

- [ ] **Step 1: Criar `HologramRenderer`**

```java
package com.redecrystal.hologram;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Spawns one {@link TextDisplay} per network hologram. Everything is tagged so a
 * crash/reload never leaves a duplicate behind (mirrors the parkour holograms and
 * the lobby pets). Holograms whose world is absent on this server are skipped, so
 * the same config runs safely on any server type.
 */
final class HologramRenderer {

    static final String HOLO_TAG = "crystal_holo";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final JavaPlugin plugin;
    private final List<TextDisplay> displays = new ArrayList<>();

    HologramRenderer(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Clear everything and respawn from the given defs. Must run on the main thread. */
    void render(List<HologramDef> defs) {
        clearAll();
        for (HologramDef def : defs) {
            World w = plugin.getServer().getWorld(def.world());
            if (w == null) {
                plugin.getLogger().warning("Hologram '" + def.id()
                        + "' skipped: world '" + def.world() + "' not on this server.");
                continue;
            }
            spawn(new Location(w, def.x(), def.y(), def.z()), text(def.lines()));
        }
    }

    /** Remove every hologram (tracked + any orphan from a previous run). */
    void clearAll() {
        for (TextDisplay d : displays) {
            if (d != null && !d.isDead()) {
                d.remove();
            }
        }
        displays.clear();
        removeOrphans();
    }

    private void removeOrphans() {
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getScoreboardTags().contains(HOLO_TAG)) {
                    e.remove();
                }
            }
        }
    }

    private void spawn(Location at, Component text) {
        World w = at.getWorld();
        if (w == null) {
            return;
        }
        displays.add(w.spawn(at, TextDisplay.class, td -> {
            td.addScoreboardTag(HOLO_TAG);
            td.setPersistent(false); // respawned on enable / hot-reload; never saved
            td.setBillboard(Display.Billboard.CENTER);
            td.setSeeThrough(true);
            td.text(text);
        }));
    }

    /** Join the lines (legacy '&' colours) into one multi-line component. */
    private static Component text(List<String> lines) {
        Component out = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out = out.append(Component.newline());
            }
            out = out.append(LEGACY.deserialize(lines.get(i)));
        }
        return out;
    }
}
```

- [ ] **Step 2: Compilar**

Run: `mvn -pl plugins/crystal-hologram -am compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add plugins/crystal-hologram/src/main/java/com/redecrystal/hologram/HologramRenderer.java
git commit -m "feat(hologram): renderer de TextDisplay com cleanup por tag"
```

---

### Task 5: `HologramCommand` (set/move/remove/list)

**Files:**
- Create: `plugins/crystal-hologram/src/main/java/com/redecrystal/hologram/HologramCommand.java`

**Interfaces:**
- Consumes: `HologramDef`; `HologramStore` (`all()`, `put(def)`, `remove(id)`); `JavaPlugin` (scheduler); `CrystalCore` (não usado diretamente além do store, mas mantido na assinatura para simetria).
- Produces: `HologramCommand(JavaPlugin plugin, CrystalCore crystal, HologramStore store) implements CommandExecutor`.

- [ ] **Step 1: Criar `HologramCommand`**

```java
package com.redecrystal.hologram;

import com.redecrystal.core.CrystalCore;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * {@code /hologram set|move|remove|list} — admin command to manage the network
 * holograms. Writes go to the central {@code holograms} config off the main thread;
 * the resulting {@code config-updated} event hot-reloads every server.
 */
final class HologramCommand implements CommandExecutor {

    private static final String ADMIN_PERM = "crystal.hologram.admin";
    /** Regex matching the literal two-char sequence {@code \n} typed by the admin. */
    private static final String LINE_SPLIT = "\\\\n";

    private final JavaPlugin plugin;
    private final CrystalCore crystal;
    private final HologramStore store;

    HologramCommand(JavaPlugin plugin, CrystalCore crystal, HologramStore store) {
        this.plugin = plugin;
        this.crystal = crystal;
        this.store = store;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ADMIN_PERM)) {
            sender.sendMessage(Component.text("Sem permissão.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "set" -> handleSet(sender, args);
            case "move" -> handleMove(sender, args);
            case "remove" -> handleRemove(sender, args);
            case "list" -> handleList(sender);
            default -> usage(sender);
        }
        return true;
    }

    private void handleSet(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Apenas jogadores.", NamedTextColor.RED));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("/hologram set <id> <texto>", NamedTextColor.GRAY));
            return;
        }
        String id = args[1];
        String joined = String.join(" ", List.of(args).subList(2, args.length));
        List<String> lines = List.of(joined.split(LINE_SPLIT, -1));
        Location loc = player.getLocation();
        HologramDef def = new HologramDef(id, loc.getWorld().getName(),
                round(loc.getX()), round(loc.getY()), round(loc.getZ()), lines);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            store.put(def);
            player.sendMessage(Component.text("Holograma '" + id + "' definido para a rede.", NamedTextColor.GREEN));
        });
    }

    private void handleMove(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Apenas jogadores.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("/hologram move <id>", NamedTextColor.GRAY));
            return;
        }
        String id = args[1];
        Location loc = player.getLocation();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            HologramDef existing = find(id);
            if (existing == null) {
                player.sendMessage(Component.text("Holograma '" + id + "' não existe.", NamedTextColor.RED));
                return;
            }
            HologramDef moved = new HologramDef(existing.id(), loc.getWorld().getName(),
                    round(loc.getX()), round(loc.getY()), round(loc.getZ()), existing.lines());
            store.put(moved);
            player.sendMessage(Component.text("Holograma '" + id + "' movido.", NamedTextColor.GREEN));
        });
    }

    private void handleRemove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("/hologram remove <id>", NamedTextColor.GRAY));
            return;
        }
        String id = args[1];
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean removed = store.remove(id);
            sender.sendMessage(removed
                    ? Component.text("Holograma '" + id + "' removido.", NamedTextColor.GREEN)
                    : Component.text("Holograma '" + id + "' não existe.", NamedTextColor.RED));
        });
    }

    private void handleList(CommandSender sender) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<HologramDef> all = store.all();
            if (all.isEmpty()) {
                sender.sendMessage(Component.text("Nenhum holograma na rede.", NamedTextColor.GRAY));
                return;
            }
            sender.sendMessage(Component.text("Hologramas (" + all.size() + "):", NamedTextColor.AQUA));
            for (HologramDef d : all) {
                sender.sendMessage(Component.text(" • " + d.id() + " (" + d.world() + ")", NamedTextColor.GRAY));
            }
        });
    }

    private HologramDef find(String id) {
        for (HologramDef d : store.all()) {
            if (d.id().equalsIgnoreCase(id)) {
                return d;
            }
        }
        return null;
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(Component.text(
                "/hologram set <id> <texto> | move <id> | remove <id> | list", NamedTextColor.GRAY));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
```

- [ ] **Step 2: Compilar**

Run: `mvn -pl plugins/crystal-hologram -am compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add plugins/crystal-hologram/src/main/java/com/redecrystal/hologram/HologramCommand.java
git commit -m "feat(hologram): comando /hologram set|move|remove|list"
```

---

### Task 6: Wiring final em `CrystalHologramPlugin`

**Files:**
- Modify: `plugins/crystal-hologram/src/main/java/com/redecrystal/hologram/CrystalHologramPlugin.java`

**Interfaces:**
- Consumes: `HologramStore(crystal)`, `HologramRenderer(this)`, `HologramCommand(this, crystal, store)`, `HologramStore.CONFIG_KEY`.
- Produces: plugin completo (render no enable + hot-reload + comando + cleanup no disable).

- [ ] **Step 1: Substituir o conteúdo de `CrystalHologramPlugin`**

```java
package com.redecrystal.hologram;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Network hologram plugin. Boots the SDK, renders the holograms from the central
 * {@code holograms} config, and hot-reloads them when the config changes — so the
 * same holograms appear on every server running this plugin, surviving restarts.
 */
public final class CrystalHologramPlugin extends JavaPlugin {

    private CrystalCore crystal;
    private HologramRenderer renderer;

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());
        crystal.configProvider().preload(HologramStore.CONFIG_KEY);
        HologramStore store = new HologramStore(crystal);
        this.renderer = new HologramRenderer(this);
        // Defer the first render one tick so all worlds are loaded before we spawn.
        getServer().getScheduler().runTask(this, () -> renderer.render(store.all()));
        // Hot-reload: the Kafka callback is off-thread, so bounce to the main thread.
        crystal.configProvider().onChange(HologramStore.CONFIG_KEY, cfg ->
                getServer().getScheduler().runTask(this, () -> renderer.render(store.all())));
        getCommand("hologram").setExecutor(new HologramCommand(this, crystal, store));
        getLogger().info("CrystalHologram enabled.");
    }

    @Override
    public void onDisable() {
        if (renderer != null) {
            renderer.clearAll();
        }
        if (crystal != null) {
            crystal.close();
        }
    }
}
```

- [ ] **Step 2: Compilar e empacotar o jar shaded**

Run: `mvn -pl plugins/crystal-hologram -am package`
Expected: `BUILD SUCCESS` e `plugins/crystal-hologram/target/crystal-hologram.jar` existe.

- [ ] **Step 3: Commit**

```bash
git add plugins/crystal-hologram/src/main/java/com/redecrystal/hologram/CrystalHologramPlugin.java
git commit -m "feat(hologram): render no enable, hot-reload e registro do comando"
```

---

### Task 7: Deploy (docker-compose) + validação in-game

**Files:**
- Modify: `docker-compose.yml` (serviços `login-01`, `lobby-01`, `lobby-02`, `lobby-03`)

**Interfaces:**
- Consumes: jar `plugins/crystal-hologram/target/crystal-hologram.jar` (gerado por `make plugins`).

- [ ] **Step 1: Montar o jar nos serviços**

Em `docker-compose.yml`, logo **após** cada linha existente
`- ./plugins/crystal-tag/target/crystal-tag.jar:/plugins/crystal-tag.jar:ro`
(há 4 ocorrências: login-01 ~linha 220, lobby-01 ~265, lobby-02 ~290, lobby-03 ~315), adicionar:

```yaml
      - ./plugins/crystal-hologram/target/crystal-hologram.jar:/plugins/crystal-hologram.jar:ro
```

- [ ] **Step 2: Build de todos os plugins**

Run: `make plugins`
Expected: `BUILD SUCCESS`; `crystal-hologram.jar` e `crystal-lobby.jar` atualizados em `target/`.

- [ ] **Step 3: Recriar containers**

Run: `docker compose up -d --force-recreate --no-deps login-01 lobby-01 lobby-02 lobby-03`
Expected: containers sobem; `docker compose logs lobby-01 | grep CrystalHologram` mostra `CrystalHologram enabled.`

- [ ] **Step 4: Validação in-game**

Entrar num lobby como admin (op) e validar:
1. `/hologram set boasvindas &b&lRede Crystal\n&fBem-vindo ao servidor!` → aparece um holograma de 2 linhas (linha 1 azul/negrito, linha 2 branca) na sua posição. Mensagem verde de confirmação.
2. Trocar de lobby (ex.: lobby-02) → o mesmo holograma está lá (replicação via config).
3. `/hologram list` → lista `boasvindas (world)`.
4. `/hologram move boasvindas` em outra posição → o holograma reaparece no novo lugar em todos os lobbies, mesmo texto.
5. `/hologram remove boasvindas` → some de todos os lobbies.
6. Recriar um lobby e confirmar que um holograma definido reaparece (persistência).
7. Conferir nos logs que não há erro quando um servidor não tem o `world` referenciado (apenas `WARNING ... skipped`).

- [ ] **Step 5: Commit**

```bash
git add docker-compose.yml
git commit -m "chore(compose): monta crystal-hologram no login e nas lobbies"
```

---

## Self-Review

**Spec coverage:**
- Parte 1 (texto parkour) → Task 1. ✔
- Módulo genérico `crystal-hologram` → Tasks 2-6. ✔
- Comando `/hologram` set/move/remove/list + perm → Task 5 + plugin.yml (Task 2). ✔
- Persistência backend `holograms` + read-modify-write async → Task 3 + Task 5. ✔
- Hot-reload via `onChange` na main thread → Task 6. ✔
- Render TextDisplay (billboard/see-through/persist false/tag) + multilinha `\n` + cores `&` → Task 4. ✔
- Guard de world ausente → Task 4 (`render`) + validação Task 7.7. ✔
- Registro no parent pom + deploy compose (login + lobbies) → Task 2 + Task 7. ✔
- Critérios de sucesso 1-6 da spec → cobertos pelas validações das Tasks 1 e 7. ✔

**Placeholder scan:** nenhum TODO/TBD; todo passo de código tem o código completo. ✔

**Type consistency:** `HologramDef(id, world, x, y, z, lines)` usado igual em Store/Renderer/Command; `HologramStore.CONFIG_KEY`/`all()`/`put()`/`remove()` e `HologramRenderer.render()`/`clearAll()`/`HOLO_TAG` consistentes entre tasks. `HologramCommand` construtor `(plugin, crystal, store)` bate com a chamada em Task 6. ✔

**Nota de desvio da spec:** a spec sugeria sub-pacote `command/`; o plano usa pacote plano `com.redecrystal.hologram` para manter as classes package-private coesas (documentado em Global Constraints).
