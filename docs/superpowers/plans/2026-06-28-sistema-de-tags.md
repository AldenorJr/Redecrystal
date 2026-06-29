# Sistema de Tags — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mostrar a tag (cargo) acima da cabeça do jogador e dar a admins um comando `/tag` (GUI) para selecionar/atribuir uma tag de teste e editar/criar/excluir definições de cargo.

**Architecture:** Um novo plugin `crystal-tag` renderiza o nametag (prefixo na linha do nome via scoreboard team) e hospeda o comando `/tag` + GUIs. A tag de teste é um *override* por jogador guardado num hash Redis `tag:overrides` que sobrescreve a resolução por permissão em **todos** os pontos onde a tag aparece (nametag, tab, chat, sidebar, `/profile`). A edição de cargos reaproveita o endpoint de escrita de config já existente (`PUT /api/config/chat`), que dispara hot-reload em toda a rede. Sem mudanças no backend.

**Tech Stack:** Java 21, Paper API 1.21, Maven (reactor `plugins/`), Lettuce/Redis, LuckPerms (permissões), Adventure/MiniMessage, JUnit 5.

## Global Constraints

- **Idioma:** código e comentários em **inglês**; texto de jogador e docs em **PT**. (CLAUDE.md / CODING_STANDARDS)
- **Nunca bloquear a main thread do Bukkit:** HTTP em `runTaskAsynchronously`, voltar com `runTask` antes de tocar a API do jogo. (Chamadas Redis pontuais síncronas são toleradas pelo codebase — seguir o padrão existente do `crystal-tab`.)
- **Fail-open:** falha de Redis/backend nunca derruba o servidor; trata como "sem override" / loga e segue.
- **Texto de jogador nunca é re-parseado como entrada de outro jogador** — prefixos editados são strings de config, renderizados via MiniMessage/legacy só na exibição.
- **DTO é `record`**, utilitário é `final` com construtor privado, conjunto fechado é `enum`. Constantes em vez de literais mágicos.
- **Escreva código que se pareça com o vizinho** — copiar seções (`// ── nome ──`), item builders e layout de GUI do `crystal-lobby`.
- **Permissão de admin:** `crystal.tag.admin`, `default: op` (convenção `crystal.<área>.admin`).
- **Java/Paper:** `java.version=21`, `api-version: '1.21'`, paper-api `1.21.1-R0.1-SNAPSHOT` (do parent pom).
- Validação em jogo: rebuild → **recriar** o container (`docker compose up -d --force-recreate --no-deps lobby-01 …`).

---

### Task 1: Override no Redis — ops de hash + `TagOverrides` (crystal-core)

**Files:**
- Modify: `plugins/crystal-core/src/main/java/com/redecrystal/core/redis/RedisClient.java`
- Create: `plugins/crystal-core/src/main/java/com/redecrystal/core/cargo/TagOverrides.java`

**Interfaces:**
- Produces:
  - `RedisClient.hset(String key, String field, String value)`, `String hget(String key, String field)`, `void hdel(String key, String field)`, `Map<String,String> hgetAll(String key)`
  - `TagOverrides.KEY` (`"tag:overrides"`), `TagOverrides.read(RedisClient, UUID) -> String`, `TagOverrides.set(RedisClient, UUID, String)`, `TagOverrides.clear(RedisClient, UUID)`

> Sem teste unitário: são wrappers finos sobre Lettuce (o codebase não testa `RedisClient`; coberto pela validação em jogo).

- [ ] **Step 1: Adicionar as ops de hash ao `RedisClient`**

No `RedisClient.java`, logo após o bloco `// ── generic set ops (e.g. tells_disabled) ──` (depois do método `sismember`), inserir:

```java
    // ── generic hash ops (e.g. tag:overrides) ──
    public void hset(String key, String field, String value) { sync.hset(key, field, value); }
    public String hget(String key, String field)             { return sync.hget(key, field); }
    public void hdel(String key, String field)               { sync.hdel(key, field); }
    public Map<String, String> hgetAll(String key)           { return sync.hgetall(key); }
```

O import `java.util.Map` já existe no arquivo (usado em outras assinaturas). Confirme; se faltar, adicione `import java.util.Map;`.

- [ ] **Step 2: Criar `TagOverrides`**

```java
package com.redecrystal.core.cargo;

import com.redecrystal.core.redis.RedisClient;
import java.util.UUID;

/**
 * Per-player tag override (the admin "test" tag), stored in the Redis hash
 * {@value #KEY}: field = player UUID, value = cargo id. When present, the override
 * wins over the permission-based cargo everywhere the tag is shown (nametag, tab,
 * chat, sidebar, profile). Reads fail open: a Redis blip means "no override".
 */
public final class TagOverrides {

    public static final String KEY = "tag:overrides";

    private TagOverrides() {
    }

    /** The override cargo id for a player, or {@code null} if none / on failure. */
    public static String read(RedisClient redis, UUID uuid) {
        try {
            return redis.hget(KEY, uuid.toString());
        } catch (Exception e) {
            return null;
        }
    }

    public static void set(RedisClient redis, UUID uuid, String cargoId) {
        redis.hset(KEY, uuid.toString(), cargoId);
    }

    public static void clear(RedisClient redis, UUID uuid) {
        redis.hdel(KEY, uuid.toString());
    }
}
```

- [ ] **Step 3: Compilar o módulo**

Run: `mvn -q -pl plugins/crystal-core -am compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add plugins/crystal-core/src/main/java/com/redecrystal/core/redis/RedisClient.java \
        plugins/crystal-core/src/main/java/com/redecrystal/core/cargo/TagOverrides.java
git commit -m "feat(core): hash ops no RedisClient + TagOverrides (override de tag)"
```

---

### Task 2: `CargoResolver` com override + teste (crystal-core)

**Files:**
- Modify: `plugins/crystal-core/src/main/java/com/redecrystal/core/cargo/CargoResolver.java`
- Test: `plugins/crystal-core/src/test/java/com/redecrystal/core/cargo/CargoResolverTest.java`

**Interfaces:**
- Consumes: `RemoteConfig` (`com.redecrystal.core.http.RemoteConfig`), `CargoResolver.Cargo(String id, String prefix, String nameColor, int weight)`
- Produces: `CargoResolver.resolve(RemoteConfig chatConfig, String overrideId, Predicate<String> hasPermission) -> Cargo` (override vence se nomear um cargo definido; senão cai na resolução por permissão). O overload existente `resolve(RemoteConfig, Predicate)` é mantido e delega com `overrideId = null`.

- [ ] **Step 1: Escrever o teste que falha**

Criar `plugins/crystal-core/src/test/java/com/redecrystal/core/cargo/CargoResolverTest.java`:

```java
package com.redecrystal.core.cargo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.redecrystal.core.cargo.CargoResolver.Cargo;
import com.redecrystal.core.http.RemoteConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class CargoResolverTest {

    private static RemoteConfig chatConfig() {
        Map<String, Object> vip = new LinkedHashMap<>();
        vip.put("permission", "tag.vip");
        vip.put("weight", 10);
        vip.put("prefix", "<gold>[VIP]");
        vip.put("nameColor", "<gold>");
        Map<String, Object> ceo = new LinkedHashMap<>();
        ceo.put("permission", "tag.ceo");
        ceo.put("weight", 100);
        ceo.put("prefix", "<red>[CEO]");
        ceo.put("nameColor", "<red>");
        Map<String, Object> roles = new LinkedHashMap<>();
        roles.put("vip", vip);
        roles.put("ceo", ceo);
        return new RemoteConfig("chat", 1, Map.of("roles", roles));
    }

    private static Predicate<String> has(String... perms) {
        Set<String> set = Set.of(perms);
        return set::contains;
    }

    @Test
    void resolvesHighestWeightByPermission() {
        Cargo c = CargoResolver.resolve(chatConfig(), has("tag.vip", "tag.ceo"));
        assertEquals("ceo", c.id());
    }

    @Test
    void noPermissionResolvesToNull() {
        assertNull(CargoResolver.resolve(chatConfig(), has()));
    }

    @Test
    void overrideWinsEvenWithoutPermission() {
        Cargo c = CargoResolver.resolve(chatConfig(), "vip", has());
        assertEquals("vip", c.id());
        assertEquals("<gold>[VIP]", c.prefix());
    }

    @Test
    void unknownOverrideFallsBackToPermission() {
        Cargo c = CargoResolver.resolve(chatConfig(), "ghost", has("tag.vip"));
        assertEquals("vip", c.id());
    }

    @Test
    void blankOverrideFallsBackToPermission() {
        Cargo c = CargoResolver.resolve(chatConfig(), "", has("tag.vip"));
        assertEquals("vip", c.id());
    }
}
```

- [ ] **Step 2: Rodar o teste e ver falhar**

Run: `mvn -q -pl plugins/crystal-core test -Dtest=CargoResolverTest`
Expected: FALHA na compilação/execução — o overload `resolve(RemoteConfig, String, Predicate)` ainda não existe (`overrideWinsEvenWithoutPermission` não compila).

- [ ] **Step 3: Implementar o overload com override**

Substituir o corpo do `CargoResolver` (do método `resolve` em diante) por:

```java
    /**
     * @param chatConfig    the {@code chat} RemoteConfig (carries {@code roles})
     * @param hasPermission predicate over permission nodes
     * @return the highest-weight matching cargo, or {@code null} if none match
     */
    public static Cargo resolve(RemoteConfig chatConfig, Predicate<String> hasPermission) {
        return resolve(chatConfig, null, hasPermission);
    }

    /**
     * Same as {@link #resolve(RemoteConfig, Predicate)} but an {@code overrideId}
     * (admin "test" tag) wins when it names a defined cargo — regardless of the
     * player's permissions. A {@code null}/blank/unknown override falls back to the
     * permission-based resolution.
     */
    @SuppressWarnings("unchecked")
    public static Cargo resolve(RemoteConfig chatConfig, String overrideId, Predicate<String> hasPermission) {
        if (chatConfig == null || !(chatConfig.value("roles") instanceof Map<?, ?> rolesMap)) {
            return null;
        }
        List<Cargo> roles = new ArrayList<>();
        Map<String, String> permissions = new HashMap<>();
        for (Map.Entry<?, ?> entry : rolesMap.entrySet()) {
            String id = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> data)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) data;
            String permission = m.get("permission") == null ? "tag." + id : String.valueOf(m.get("permission"));
            int weight = m.get("weight") instanceof Number n ? n.intValue() : 0;
            String prefix = m.get("prefix") == null ? "" : String.valueOf(m.get("prefix"));
            String nameColor = m.get("nameColor") == null ? "" : String.valueOf(m.get("nameColor"));
            roles.add(new Cargo(id, prefix, nameColor, weight));
            permissions.put(id, permission);
        }
        // An admin-set override wins, if it names a defined cargo.
        if (overrideId != null && !overrideId.isBlank()) {
            for (Cargo c : roles) {
                if (c.id().equals(overrideId)) {
                    return c;
                }
            }
        }
        roles.sort(Comparator.comparingInt(Cargo::weight).reversed());
        for (Cargo c : roles) {
            if (hasPermission.test("tag." + c.id()) || hasPermission.test(permissions.get(c.id()))) {
                return c;
            }
        }
        return null;
    }
}
```

Isso remove o antigo método auxiliar `permissionOf(...)` (agora a permissão vem do mapa `permissions`). Atualizar os imports do topo do arquivo para:

```java
import com.redecrystal.core.http.RemoteConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
```

- [ ] **Step 4: Rodar o teste e ver passar**

Run: `mvn -q -pl plugins/crystal-core test -Dtest=CargoResolverTest`
Expected: PASS (5 testes verdes).

- [ ] **Step 5: Commit**

```bash
git add plugins/crystal-core/src/main/java/com/redecrystal/core/cargo/CargoResolver.java \
        plugins/crystal-core/src/test/java/com/redecrystal/core/cargo/CargoResolverTest.java
git commit -m "feat(core): CargoResolver com override de tag (+ teste)"
```

---

### Task 3: Scaffold do módulo `crystal-tag`

**Files:**
- Create: `plugins/crystal-tag/pom.xml`
- Create: `plugins/crystal-tag/src/main/resources/plugin.yml`
- Create: `plugins/crystal-tag/src/main/java/com/redecrystal/tag/CrystalTagPlugin.java`
- Modify: `plugins/pom.xml` (registrar o módulo)

**Interfaces:**
- Consumes: `CrystalCore.bootstrap(CrystalConfig.fromEnv())`, `crystal.configProvider().preload("chat")`, `crystal.close()`
- Produces: plugin Paper `CrystalTag` que dá boot e fica pronto para receber listeners/comandos nas tasks seguintes.

- [ ] **Step 1: Criar o `pom.xml` do módulo** (espelha `crystal-tab`)

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

    <artifactId>crystal-tag</artifactId>
    <name>Crystal Tag</name>
    <description>Nametag acima da cabeça + comando /tag (selecionar/atribuir/editar cargos), admin.</description>

    <dependencies>
        <dependency>
            <groupId>com.redecrystal</groupId>
            <artifactId>crystal-core</artifactId>
        </dependency>
        <dependency>
            <groupId>io.papermc.paper</groupId>
            <artifactId>paper-api</artifactId>
        </dependency>
        <dependency>
            <groupId>net.luckperms</groupId>
            <artifactId>api</artifactId>
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

- [ ] **Step 2: Criar o `plugin.yml`**

```yaml
name: CrystalTag
version: 0.1.0
main: com.redecrystal.tag.CrystalTagPlugin
api-version: '1.21'
author: RedeCrystal
description: Nametag acima da cabeça e comando /tag (admin), driven by central config.
depend: [LuckPerms]
commands:
  tag:
    description: Selecionar, atribuir e editar tags (admin)
    usage: /tag [jogador|editar]
permissions:
  crystal.tag.admin:
    description: Selecionar, atribuir e editar tags
    default: op
```

- [ ] **Step 3: Criar `CrystalTagPlugin` (boot mínimo)**

```java
package com.redecrystal.tag;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Tag plugin. Boots the SDK and (in later tasks) renders the in-world nametag
 * (cargo prefix above the head) and serves the admin {@code /tag} command + GUIs.
 * The cargo definitions live in the shared {@code chat} config (hot-reloaded).
 */
public final class CrystalTagPlugin extends JavaPlugin {

    private CrystalCore crystal;

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());
        crystal.configProvider().preload("chat"); // cargo/role config (shared)
        getLogger().info("CrystalTag enabled.");
    }

    @Override
    public void onDisable() {
        if (crystal != null) {
            crystal.close();
        }
    }
}
```

- [ ] **Step 4: Registrar o módulo no reactor**

Em `plugins/pom.xml`, no bloco `<modules>`, adicionar após `<module>crystal-tab</module>`:

```xml
        <module>crystal-tag</module>
```

- [ ] **Step 5: Compilar o módulo**

Run: `mvn -q -pl plugins/crystal-tag -am compile`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add plugins/crystal-tag/pom.xml \
        plugins/crystal-tag/src/main/resources/plugin.yml \
        plugins/crystal-tag/src/main/java/com/redecrystal/tag/CrystalTagPlugin.java \
        plugins/pom.xml
git commit -m "feat(tag): scaffold do plugin crystal-tag"
```

---

### Task 4: `NametagService` — tag acima da cabeça

**Files:**
- Create: `plugins/crystal-tag/src/main/java/com/redecrystal/tag/listener/NametagService.java`
- Modify: `plugins/crystal-tag/src/main/java/com/redecrystal/tag/CrystalTagPlugin.java`

**Interfaces:**
- Consumes: `CargoResolver.resolve(RemoteConfig, String, Predicate)` (Task 2), `TagOverrides.KEY` + `RedisClient.hgetAll` (Task 1), `crystal.configProvider().get("chat")`, `crystal.redis()`
- Produces: `NametagService(JavaPlugin, CrystalCore)` com `start()`; renderiza o prefixo do cargo efetivo (override > permissão) acima do nome de cada jogador, no scoreboard atual de cada espectador.

- [ ] **Step 1: Criar `NametagService`**

```java
package com.redecrystal.tag.listener;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.cargo.CargoResolver;
import com.redecrystal.core.cargo.TagOverrides;
import com.redecrystal.core.http.RemoteConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Renders each player's cargo tag above their head. Minecraft shows the nametag
 * from a scoreboard team's prefix, and the lobby gives every player their own
 * scoreboard, so we apply one cargo team per cargo onto each viewer's current
 * board ({@code player.getScoreboard()}) and put each target in their cargo's
 * team. Team names ({@code ct_*}) don't collide with the lobby sidebar's
 * ({@code line0..6}). Updates are guarded so unchanged tags send no packets.
 */
public final class NametagService implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String CHAT_CONFIG_KEY = "chat";
    private static final String TEAM_PREFIX = "ct_";
    private static final String DEFAULT_TEAM = "ct_default";
    private static final int MAX_TEAM_NAME = 16;
    private static final long REFRESH_TICKS = 40L;

    private final JavaPlugin plugin;
    private final CrystalCore crystal;

    public NametagService(JavaPlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
    }

    /** A target's resolved tag, computed once per tick and applied to every board. */
    private record Resolved(String teamName, Component prefix, NamedTextColor color) { }

    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refresh, REFRESH_TICKS, REFRESH_TICKS);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Apply shortly after join, once the lobby has (re)built the player's board.
        plugin.getServer().getScheduler().runTaskLater(plugin, this::refresh, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        String entry = event.getPlayer().getName();
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (viewer.equals(event.getPlayer())) {
                continue;
            }
            Team team = viewer.getScoreboard().getEntryTeam(entry);
            if (team != null) {
                team.removeEntry(entry);
            }
        }
    }

    private void refresh() {
        Map<String, String> overrides;
        try {
            overrides = crystal.redis().hgetAll(TagOverrides.KEY);
        } catch (Exception e) {
            overrides = Map.of(); // Redis down → permission-based tags only
        }
        RemoteConfig chat = crystal.configProvider().get(CHAT_CONFIG_KEY);

        List<Player> players = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        Map<UUID, Resolved> resolved = new HashMap<>();
        for (Player target : players) {
            String overrideId = overrides.get(target.getUniqueId().toString());
            resolved.put(target.getUniqueId(), resolve(chat, overrideId, target));
        }
        for (Player viewer : players) {
            Scoreboard board = viewer.getScoreboard();
            for (Player target : players) {
                try {
                    apply(board, target.getName(), resolved.get(target.getUniqueId()));
                } catch (Exception e) {
                    plugin.getLogger().warning("Nametag falhou para " + target.getName() + ": " + e);
                }
            }
        }
    }

    private Resolved resolve(RemoteConfig chat, String overrideId, Player target) {
        CargoResolver.Cargo cargo = CargoResolver.resolve(chat, overrideId, target::hasPermission);
        if (cargo == null) {
            return new Resolved(DEFAULT_TEAM, Component.empty(), null);
        }
        Component prefix = cargo.prefix().isEmpty()
                ? Component.empty()
                : parse(cargo.prefix()).append(Component.space());
        return new Resolved(teamName(cargo.id()), prefix, nearest(cargo.nameColor()));
    }

    /** Ensure {@code entry} sits in its cargo team with the right prefix/color. */
    private void apply(Scoreboard board, String entry, Resolved r) {
        Team team = board.getTeam(r.teamName());
        if (team == null) {
            team = board.registerNewTeam(r.teamName());
        }
        if (!team.prefix().equals(r.prefix())) {
            team.prefix(r.prefix());
        }
        if (r.color() != null && team.color() != r.color()) {
            team.color(r.color());
        }
        Team current = board.getEntryTeam(entry);
        if (current != team) {
            team.addEntry(entry); // moves the entry out of any previous team
        }
    }

    private static String teamName(String cargoId) {
        String name = TEAM_PREFIX + cargoId;
        return name.length() <= MAX_TEAM_NAME ? name : name.substring(0, MAX_TEAM_NAME);
    }

    /** Best-effort vanilla colour for the name line (the team only accepts one
     *  {@link NamedTextColor}; hex is mapped to the nearest). */
    private static NamedTextColor nearest(String nameColor) {
        if (nameColor == null || nameColor.isBlank()) {
            return null;
        }
        TextColor col = parse(nameColor + "_").color();
        return col == null ? null : NamedTextColor.nearestTo(col);
    }

    /** Parse a MiniMessage string, falling back to legacy '&' codes if present. */
    private static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        if (raw.indexOf('&') >= 0 || raw.indexOf('§') >= 0) {
            return LEGACY.deserialize(raw.replace('§', '&'));
        }
        return MM.deserialize(raw);
    }
}
```

- [ ] **Step 2: Registrar o serviço no plugin**

No `CrystalTagPlugin`, adicionar imports e registrar no `onEnable` (após o `preload`):

```java
import com.redecrystal.tag.listener.NametagService;
import org.bukkit.plugin.PluginManager;
```

```java
        NametagService nametags = new NametagService(this, crystal);
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(nametags, this);
        nametags.start();
```

- [ ] **Step 3: Compilar**

Run: `mvn -q -pl plugins/crystal-tag -am compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Validar em jogo (manual)**

Rebuild + recriar o container do lobby:
```bash
mvn -q -pl plugins/crystal-tag -am package
docker compose up -d --force-recreate --no-deps lobby-01
```
Entrar com um jogador que tenha permissão de um cargo (ex.: `tag.vip`) e confirmar o `[VIP]` acima da cabeça. Sem cargo → nome sem prefixo.

- [ ] **Step 5: Commit**

```bash
git add plugins/crystal-tag/src/main/java/com/redecrystal/tag/listener/NametagService.java \
        plugins/crystal-tag/src/main/java/com/redecrystal/tag/CrystalTagPlugin.java
git commit -m "feat(tag): nametag do cargo acima da cabeça (scoreboard team)"
```

---

### Task 5: GUI seletora + comando `/tag` e `/tag <jogador>`

**Files:**
- Create: `plugins/crystal-tag/src/main/java/com/redecrystal/tag/menu/Menus.java`
- Create: `plugins/crystal-tag/src/main/java/com/redecrystal/tag/menu/TagSelectorMenu.java`
- Create: `plugins/crystal-tag/src/main/java/com/redecrystal/tag/command/TagCommand.java`
- Modify: `plugins/crystal-tag/src/main/java/com/redecrystal/tag/CrystalTagPlugin.java`

**Interfaces:**
- Consumes: `TagOverrides.set/clear/read` + `RedisClient` (Task 1), `crystal.configProvider().get("chat")`, `CargoResolver` não é necessário aqui (lista todos os cargos diretamente do config).
- Produces:
  - `Menus.MenuHolder(String type, UUID target, String targetName)` (InventoryHolder) + helpers estáticos de GUI (`item`, `glow`, `framedSize`, `bodySlots`, `barCenter`, `barLeft`, `parse`).
  - `TagSelectorMenu(JavaPlugin, CrystalCore)` com `open(Player admin, UUID target, String targetName)` e `Listener` de clique (tipo `"tag:select"`).
  - `TagCommand(CrystalCore, TagSelectorMenu, TagEditorMenu)` — na Task 6 ganha o editor; nesta task aceita `null` para o editor e trata `/tag editar` com mensagem "em breve" **não** — ver Step 4 (dispatch só do seletor; `editar` será ligado na Task 6).

- [ ] **Step 1: Criar `Menus` (helpers de GUI compartilhados)**

Copia o padrão do `crystal-lobby` (item builders, layout em bordas). Cabeçalho do arquivo:

```java
package com.redecrystal.tag.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

/** Shared GUI helpers + holder for the tag menus (mirrors the lobby's bordered
 *  3-row layout: empty top row, content in columns 1–7, a control row at the
 *  bottom). All player-facing text is PT. */
public final class Menus {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Menus() {
    }

    /** Marks an inventory we own. {@code target}/{@code targetName} carry the
     *  player a selector is editing (null for the editor menus). */
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

    /** Parse MiniMessage, falling back to legacy '&' codes if present. */
    public static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        if (raw.indexOf('&') >= 0 || raw.indexOf('§') >= 0) {
            return LEGACY.deserialize(raw.replace('§', '&'));
        }
        return MM.deserialize(raw);
    }
}
```

- [ ] **Step 2: Criar `TagSelectorMenu`**

```java
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
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getItemMeta() == null) {
            return;
        }
        String cargoId = clicked.getItemMeta().getPersistentDataContainer()
                .get(cargoKey, PersistentDataType.STRING);
        if (cargoId == null) {
            return;
        }
        Player admin = (Player) event.getWhoClicked();
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
```

- [ ] **Step 3: Criar `TagCommand`**

```java
package com.redecrystal.tag.command;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.tag.menu.TagEditorMenu;
import com.redecrystal.tag.menu.TagSelectorMenu;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /tag} — admin-only. No args opens the selector for yourself;
 * {@code /tag <jogador>} targets another player; {@code /tag editar} opens the
 * cargo editor. Gated by {@code crystal.tag.admin}.
 */
public final class TagCommand implements CommandExecutor {

    private static final String ADMIN_PERM = "crystal.tag.admin";

    private final TagSelectorMenu selector;
    private final TagEditorMenu editor;

    public TagCommand(CrystalCore crystal, TagSelectorMenu selector, TagEditorMenu editor) {
        this.selector = selector;
        this.editor = editor;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player admin)) {
            sender.sendMessage("Apenas jogadores.");
            return true;
        }
        if (!admin.hasPermission(ADMIN_PERM)) {
            admin.sendMessage(Component.text("Você não tem permissão.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            selector.open(admin, admin.getUniqueId(), admin.getName());
            return true;
        }
        if (args[0].equalsIgnoreCase("editar")) {
            editor.open(admin);
            return true;
        }
        String targetName = args[0];
        Player online = Bukkit.getPlayerExact(targetName);
        UUID target = online != null ? online.getUniqueId() : offlineUuid(targetName);
        selector.open(admin, target, online != null ? online.getName() : targetName);
        return true;
    }

    /** Offline-mode UUID derived from a name (matches the player's own UUID). */
    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
```

> `TagEditorMenu` é criado na Task 6. Para esta task compilar isoladamente, **faça a Task 6 em seguida sem testar em jogo entre elas**, OU crie já um `TagEditorMenu` stub mínimo (classe vazia com `open(Player)`) — o passo de fiação abaixo importa as duas. Recomendado: implementar Task 5 e Task 6 juntas e só então recompilar/validar. O Step 5 abaixo registra ambos.

- [ ] **Step 4: Stub temporário do editor (para compilar a Task 5 isolada)**

Criar `plugins/crystal-tag/src/main/java/com/redecrystal/tag/menu/TagEditorMenu.java` (será substituído na Task 6):

```java
package com.redecrystal.tag.menu;

import org.bukkit.entity.Player;

/** Placeholder; full editor implemented in Task 6. */
public final class TagEditorMenu {
    public void open(Player admin) {
        admin.sendMessage(net.kyori.adventure.text.Component.text("Editor em construção."));
    }
}
```

- [ ] **Step 5: Fiar comando + seletor no plugin**

No `CrystalTagPlugin.onEnable`, após registrar o `NametagService`:

```java
import com.redecrystal.tag.command.TagCommand;
import com.redecrystal.tag.menu.TagEditorMenu;
import com.redecrystal.tag.menu.TagSelectorMenu;
```

```java
        TagSelectorMenu selector = new TagSelectorMenu(this, crystal);
        TagEditorMenu editor = new TagEditorMenu(); // full wiring in Task 6
        pm.registerEvents(selector, this);
        getCommand("tag").setExecutor(new TagCommand(crystal, selector, editor));
```

- [ ] **Step 6: Compilar**

Run: `mvn -q -pl plugins/crystal-tag -am compile`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add plugins/crystal-tag/src/main/java/com/redecrystal/tag/menu/Menus.java \
        plugins/crystal-tag/src/main/java/com/redecrystal/tag/menu/TagSelectorMenu.java \
        plugins/crystal-tag/src/main/java/com/redecrystal/tag/menu/TagEditorMenu.java \
        plugins/crystal-tag/src/main/java/com/redecrystal/tag/command/TagCommand.java \
        plugins/crystal-tag/src/main/java/com/redecrystal/tag/CrystalTagPlugin.java
git commit -m "feat(tag): GUI seletora + comando /tag e /tag <jogador> (override)"
```

---

### Task 6: GUI de edição `/tag editar` (editar/criar/excluir cargos)

**Files:**
- Replace: `plugins/crystal-tag/src/main/java/com/redecrystal/tag/menu/TagEditorMenu.java` (substitui o stub da Task 5)

**Interfaces:**
- Consumes: `crystal.backend().getConfig("chat")` + `crystal.backend().putConfig("chat", Map)` (já existem em `BackendHttpClient`), `Menus.*`
- Produces: `TagEditorMenu(JavaPlugin, CrystalCore)` com `open(Player)`; `Listener` de clique + fluxo de digitar-no-chat para editar `prefix`/`nameColor`/`weight`, criar e excluir cargos. Cada alteração faz `putConfig("chat", …)` (hot-reload em toda a rede).

> Atenção: a Task 5 instanciou `new TagEditorMenu()`. Esta task muda o construtor para `TagEditorMenu(JavaPlugin, CrystalCore)`; ajuste a linha de fiação no `CrystalTagPlugin` (Step 4) e registre o listener.

- [ ] **Step 1: Substituir `TagEditorMenu` pela versão completa**

```java
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
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getItemMeta() == null) {
            return;
        }
        Player admin = (Player) event.getWhoClicked();
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
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    admin.sendMessage(Component.text("Cargo '" + cargoId + "' excluído.", NamedTextColor.GREEN));
                    if (admin.isOnline()) {
                        open(admin);
                    }
                });
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
```

- [ ] **Step 2: Atualizar a fiação no plugin**

No `CrystalTagPlugin.onEnable`, trocar a linha do editor stub por:

```java
        TagEditorMenu editor = new TagEditorMenu(this, crystal);
        pm.registerEvents(editor, this);
```

(O `TagCommand` continua recebendo `selector` e `editor`.)

- [ ] **Step 3: Compilar**

Run: `mvn -q -pl plugins/crystal-tag -am compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Validar em jogo (manual)**

```bash
mvn -q -pl plugins/crystal-tag -am package
docker compose up -d --force-recreate --no-deps lobby-01
```
- `/tag editar` → clicar num cargo → editar prefixo (digitar no chat) → confirmar que a tag muda no chat/tab/nametag sem restart.
- Criar um cargo novo, depois excluí-lo; confirmar que some das listas.

- [ ] **Step 5: Commit**

```bash
git add plugins/crystal-tag/src/main/java/com/redecrystal/tag/menu/TagEditorMenu.java \
        plugins/crystal-tag/src/main/java/com/redecrystal/tag/CrystalTagPlugin.java
git commit -m "feat(tag): GUI /tag editar — editar/criar/excluir cargos via putConfig"
```

---

### Task 7: Consumidores honram o override (chat, tab, sidebar, profile)

**Files:**
- Modify: `plugins/crystal-chat/src/main/java/com/redecrystal/chat/CrystalChatPlugin.java`
- Modify: `plugins/crystal-tab/src/main/java/com/redecrystal/tab/CrystalTabPlugin.java`
- Modify: `plugins/crystal-lobby/src/main/java/com/redecrystal/lobby/listener/LobbyScoreboard.java`
- Modify: `plugins/crystal-lobby/src/main/java/com/redecrystal/lobby/listener/LobbyHotbar.java`
- Modify: `plugins/crystal-profile/src/main/java/com/redecrystal/profile/CrystalProfilePlugin.java`

**Interfaces:**
- Consumes: `TagOverrides` + `RedisClient.hget/hgetAll` (Task 1), `CargoResolver.resolve(RemoteConfig, String, Predicate)` (Task 2)
- Produces: o override de teste passa a aparecer no chat, na tab, na sidebar do lobby, no GUI de perfil e no `/profile`.

- [ ] **Step 1: chat — resolver com override**

Em `CrystalChatPlugin`, adicionar import `import com.redecrystal.core.cargo.TagOverrides;` e `import java.util.UUID;` (se faltar). Adicionar um overload de `resolveRole` e um leitor de override:

```java
    /** Highest-weight role, but an admin override (by cargo id) wins. */
    private Role resolveRole(Player player, String overrideId) {
        if (overrideId != null && !overrideId.isBlank()) {
            for (Role role : roles) {
                if (role.id().equals(overrideId)) {
                    return role;
                }
            }
        }
        return resolveRole(player);
    }
```

No `onChat`, trocar a resolução do cargo por:

```java
        String overrideId = TagOverrides.read(crystal.redis(), event.getPlayer().getUniqueId());
        Role role = resolveRole(event.getPlayer(), overrideId);
```

- [ ] **Step 2: tab — override por tick (1 hgetAll) + no join**

Em `CrystalTabPlugin`, adicionar `import com.redecrystal.core.cargo.TagOverrides;` e `import java.util.Map;` (já existe). Adicionar o overload de `resolveRole`:

```java
    private Role resolveRole(Player player, String overrideId) {
        if (overrideId != null && !overrideId.isBlank()) {
            for (Role role : roles) {
                if (role.id().equals(overrideId)) {
                    return role;
                }
            }
        }
        return resolveRole(player);
    }
```

Trocar `refresh()`:

```java
    private void refresh() {
        int online = (int) crystal.redis().onlineCount();
        Map<String, String> overrides;
        try {
            overrides = crystal.redis().hgetAll(TagOverrides.KEY);
        } catch (Exception e) {
            overrides = Map.of();
        }
        for (Player p : getServer().getOnlinePlayers()) {
            try {
                renderTab(p, online, overrides.get(p.getUniqueId().toString()));
            } catch (Exception e) {
                getLogger().warning("Tab render failed for " + p.getName() + ": " + e);
            }
        }
    }
```

Trocar `onJoin`:

```java
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        int online = (int) crystal.redis().onlineCount();
        String overrideId = TagOverrides.read(crystal.redis(), event.getPlayer().getUniqueId());
        renderTab(event.getPlayer(), online, overrideId);
    }
```

Trocar a assinatura de `renderTab` para receber o override:

```java
    private void renderTab(Player player, int online, String overrideId) {
        String h = maintenance ? maintenanceHeader : header;
        String f = maintenance ? maintenanceFooter : footer;
        Role role = resolveRole(player, overrideId);
        String prefix = role == null ? "" : role.prefix();
        String nameColor = role == null ? "" : role.nameColor();
        renderer.apply(player, h, f, online, maxPlayers, prefixInTab, prefix, nameColor);
    }
```

- [ ] **Step 3: sidebar do lobby — override por jogador**

Em `LobbyScoreboard`, adicionar `import com.redecrystal.core.cargo.TagOverrides;`. Na função `lines(Player p)`, trocar a resolução do cargo por:

```java
        String overrideId = TagOverrides.read(crystal.redis(), p.getUniqueId());
        CargoResolver.Cargo cargo = CargoResolver.resolve(
                crystal.configProvider().get("chat"), overrideId, p::hasPermission);
```

- [ ] **Step 4: GUI de perfil do lobby — override**

Em `LobbyHotbar`, adicionar `import com.redecrystal.core.cargo.TagOverrides;`. Em `resolveCargo(Player p)`, trocar por:

```java
    private Component resolveCargo(Player p) {
        String overrideId = TagOverrides.read(crystal.redis(), p.getUniqueId());
        CargoResolver.Cargo c = CargoResolver.resolve(
                crystal.configProvider().get("chat"), overrideId, p::hasPermission);
        String prefix = c == null ? "<gray>[MEMBRO]" : c.prefix();
        return line(prefix);
    }
```

- [ ] **Step 5: `/profile` — override**

Em `CrystalProfilePlugin`, adicionar `import com.redecrystal.core.cargo.TagOverrides;`. No `onCommand` (dentro do `runTaskAsynchronously`), trocar a resolução por:

```java
            String overrideId = TagOverrides.read(crystal.redis(), player.getUniqueId());
            CargoResolver.Cargo cargo = CargoResolver.resolve(
                    crystal.configProvider().get("chat"), overrideId, player::hasPermission);
```

- [ ] **Step 6: Compilar os módulos tocados**

Run:
```bash
mvn -q -pl plugins/crystal-chat,plugins/crystal-tab,plugins/crystal-lobby,plugins/crystal-profile -am compile
```
Expected: BUILD SUCCESS.

- [ ] **Step 7: Validar em jogo (manual, fim-a-fim)**

```bash
mvn -q -pl plugins/crystal-tag,plugins/crystal-chat,plugins/crystal-tab,plugins/crystal-lobby,plugins/crystal-profile -am package
docker compose up -d --force-recreate --no-deps lobby-01
```
Roteiro: `/tag` → selecionar um cargo → confirmar a tag aparecendo **simultaneamente** no nametag (acima da cabeça), no tab, no chat (mandar uma mensagem), na sidebar e no `/profile` (+ GUI de perfil). Depois "Limpar override" → tudo volta ao cargo por permissão.

- [ ] **Step 8: Commit**

```bash
git add plugins/crystal-chat plugins/crystal-tab plugins/crystal-lobby plugins/crystal-profile
git commit -m "feat(tags): honrar override de tag no chat, tab, sidebar e perfil"
```

---

## Self-Review (preenchido)

**Spec coverage:**
- Nametag acima da cabeça → Task 4. ✅
- Override no Redis (hash, sobrescreve permissão) → Tasks 1–2. ✅
- `/tag` (própria) e `/tag <jogador>` GUI → Task 5. ✅
- `/tag editar` editar prefixo/cor/peso + criar/excluir → Task 6. ✅
- Override em todos os lugares (nametag, tab, chat, sidebar, /profile) → Tasks 4 e 7. ✅
- Admin-only `crystal.tag.admin` → Tasks 3 (plugin.yml) e 5 (checagem). ✅
- Backend sem mudanças (reaproveita `putConfig`) → Task 6. ✅
- Novo módulo no reactor → Task 3. ✅

**Placeholder scan:** sem TBD/TODO; o único stub é intencional e explicitamente substituído (TagEditorMenu na Task 5 → Task 6), com a nota de fiação.

**Type consistency:** `CargoResolver.resolve(RemoteConfig, String, Predicate)` usado igual em Tasks 4 e 7. `TagOverrides.read/set/clear(RedisClient, UUID[, String])` e `RedisClient.hget/hgetAll/hset/hdel` consistentes entre Tasks 1, 4, 5, 7. `Menus.MenuHolder(String,UUID,String)` e helpers usados igual em Tasks 5 e 6. `Role.id()` existe nos records de chat e tab (confirmado nos arquivos atuais).
