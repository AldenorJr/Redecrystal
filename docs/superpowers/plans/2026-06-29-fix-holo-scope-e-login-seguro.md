# Correções: escopo de holograma por tipo + `/login manutencao` seguro — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** (1) Escopar hologramas por `serverType` (holograma de login só em login, de lobby só em lobby) — hoje vazam para todos porque login e lobbies compartilham o mundo `world`. (2) Tornar `/login manutencao` seguro exigindo a senha (verificada no backend) antes de entrar no modo edição — hoje basta ter a permissão op, que em offline-mode é spoofável pelo nick.

**Architecture:** Holograma: cada `HologramDef` guarda o `type` (serverType de onde foi criado); cada servidor só renderiza defs cujo `type` == `crystal.config().serverType()`. Login: `/login manutencao <senha>` chama `crystal.backend().login(uuid,name,false,senha)` (mesma verificação do auth normal) numa task async, descartando o token — sem sessão, sem JWT, sem roteamento; só destrava se a senha confere. `setspawn` passa a exigir estar em modo edição.

**Tech Stack:** Java 21, Paper 1.21, Maven, crystal-core SDK. Sinal de tipo: `crystal.config().serverType()` (precedente: crystal-inventory).

## Global Constraints

- Código/comentários em **inglês**; texto de jogador em **PT**.
- Nunca bloquear a main thread: verificação de senha / HTTP em `runTaskAsynchronously`; tocar entidades/efeitos só na main thread.
- DTO é `record`; serviço/utilitário `final`; constantes em vez de literais.
- Escrever código que se pareça com o vizinho (`authenticate`/`setLoginSpawn`/`onAuthError`).
- Sem testes unitários para cola Bukkit — gate por task = compilar; verificação funcional em jogo.
- Build: `mvn -pl plugins/crystal-hologram -am compile` e `mvn -pl plugins/crystal-login -am compile`.

---

## Task 1: Escopo de holograma por `serverType`

Adiciona o campo `type` ao holograma e filtra a renderização por tipo de servidor. Touch em todos os arquivos do módulo `crystal-hologram`.

**Files:**
- Modify: `plugins/crystal-hologram/src/main/java/com/redecrystal/hologram/HologramDef.java`
- Modify: `plugins/crystal-hologram/src/main/java/com/redecrystal/hologram/HologramStore.java`
- Modify: `plugins/crystal-hologram/src/main/java/com/redecrystal/hologram/HologramRenderer.java`
- Modify: `plugins/crystal-hologram/src/main/java/com/redecrystal/hologram/HologramCommand.java`
- Modify: `plugins/crystal-hologram/src/main/java/com/redecrystal/hologram/CrystalHologramPlugin.java`

**Interfaces:**
- `record HologramDef(String id, String type, String world, double x, double y, double z, List<String> lines)` — `type` é o serverType ("login"/"lobby") de onde foi criado; pode ser `null` em entradas legadas.
- `HologramRenderer(JavaPlugin plugin, String serverType)` — `render` só desenha defs cujo `type` == `serverType`.

- [ ] **Step 1: HologramDef ganha `type`**

Substitua o corpo do record:

```java
package com.redecrystal.hologram;

import java.util.List;

/**
 * A network hologram: an id, the server {@code type} it belongs to (only servers
 * of that type render it), a world + position, and its (un-coloured) lines.
 */
record HologramDef(String id, String type, String world, double x, double y, double z, List<String> lines) {
}
```

- [ ] **Step 2: HologramStore serializa `type`**

Em `fromMap`, leia o `type` (aceitando ausência → null) e passe ao construtor:

```java
    private static HologramDef fromMap(Map<?, ?> m) {
        Object id = m.get("id");
        Object world = m.get("world");
        if (id == null || world == null) {
            return null;
        }
        Object type = m.get("type");
        List<String> lines = new ArrayList<>();
        if (m.get("lines") instanceof List<?> raw) {
            for (Object l : raw) {
                lines.add(String.valueOf(l));
            }
        }
        return new HologramDef(String.valueOf(id), type == null ? null : String.valueOf(type),
                String.valueOf(world), num(m.get("x")), num(m.get("y")), num(m.get("z")), lines);
    }
```

Em `toMap`, grave o `type` quando presente (logo após o `id`):

```java
    private static Map<String, Object> toMap(HologramDef d) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", d.id());
        if (d.type() != null) {
            m.put("type", d.type());
        }
        m.put("world", d.world());
        m.put("x", d.x());
        m.put("y", d.y());
        m.put("z", d.z());
        m.put("lines", d.lines());
        return m;
    }
```

- [ ] **Step 3: HologramRenderer filtra por tipo**

Adicione o campo `serverType`, receba-o no construtor, e filtre no `render`:

```java
    private final JavaPlugin plugin;
    private final String serverType;
    private final List<TextDisplay> displays = new ArrayList<>();

    HologramRenderer(JavaPlugin plugin, String serverType) {
        this.plugin = plugin;
        this.serverType = serverType;
    }

    /** Clear everything and respawn the defs scoped to THIS server type. Main thread. */
    void render(List<HologramDef> defs) {
        clearAll();
        for (HologramDef def : defs) {
            if (!serverType.equals(def.type())) {
                continue; // belongs to another server type (or legacy/untyped) — not ours
            }
            World w = plugin.getServer().getWorld(def.world());
            if (w == null) {
                plugin.getLogger().warning("Hologram '" + def.id()
                        + "' skipped: world '" + def.world() + "' not on this server.");
                continue;
            }
            spawn(new Location(w, def.x(), def.y(), def.z()), text(def.lines()));
        }
    }
```

(O resto da classe — `clearAll`, `removeOrphans`, `spawn`, `text` — fica igual.)

- [ ] **Step 4: HologramCommand grava/preserva o `type`**

Em `handleSet`, construa o def com o serverType atual:

```java
        HologramDef def = new HologramDef(id, crystal.config().serverType(), loc.getWorld().getName(),
                round(loc.getX()), round(loc.getY()), round(loc.getZ()), lines);
```

Em `handleMove`, preserve o `type` do existente:

```java
            HologramDef moved = new HologramDef(existing.id(), existing.type(), worldName, x, y, z, existing.lines());
```

Em `handleList`, mostre o tipo:

```java
            for (HologramDef d : all) {
                sender.sendMessage(Component.text(" • " + d.id() + " (" + d.type() + " @ " + d.world() + ")", NamedTextColor.GRAY));
            }
```

- [ ] **Step 5: CrystalHologramPlugin passa o serverType ao renderer**

```java
        this.renderer = new HologramRenderer(this, crystal.config().serverType());
```

- [ ] **Step 6: Compilar**

Run: `mvn -pl plugins/crystal-hologram -am compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add plugins/crystal-hologram/src/main/java/com/redecrystal/hologram/
git commit -m "fix(hologram): escopo por tipo de servidor (login/lobby não vazam entre si)"
```

---

## Task 2: `/login manutencao <senha>` seguro

Exige a senha (verificada no backend, sem rotear) para entrar no modo edição; `setspawn` exige estar editando. Fecha o spoofing de nick op em offline-mode.

**Files:**
- Modify: `plugins/crystal-login/src/main/java/com/redecrystal/login/CrystalLoginPlugin.java`
- Modify: `plugins/crystal-login/src/main/java/com/redecrystal/login/listener/CommandFilterListener.java`

**Interfaces:**
- `public void enterEditMode(Player player, String password)` — verifica a senha async; só destrava se confere.
- `public void exitEditMode(Player player)` — re-congela e reexibe o prompt.
- (`setLoginSpawn(Player)` inalterado; o gate de "estar editando" vive no listener.)

- [ ] **Step 1: Substituir `toggleEditMode` por `enterEditMode` + `exitEditMode`**

Em `CrystalLoginPlugin.java`, substitua o método `toggleEditMode(Player player)` (linhas ~149-169) por:

```java
    /**
     * Enter edit mode after verifying the caller's password against the backend.
     * The login server is offline-mode, so the permission alone is keyed to a
     * spoofable username — we never unfreeze a staff member who can't prove the
     * account. Verification reuses the normal login call but discards the token:
     * no session, no JWT, no proxy routing.
     */
    public void enterEditMode(Player player, String password) {
        UUID uuid = player.getUniqueId();
        if (isEditing(uuid)) {
            return; // already editing
        }
        String name = player.getName();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                crystal.backend().login(uuid.toString(), name, false, password); // throws on bad credentials
                getServer().getScheduler().runTask(this, () -> applyEditMode(player));
            } catch (BackendException e) {
                send(player, "<red>Senha incorreta. Use <white>/login manutencao <senha><red>.");
            }
        });
    }

    /** Unfreeze the staff member after a verified password (main thread). */
    private void applyEditMode(Player player) {
        if (!player.isOnline()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        editing.add(uuid);
        cancelTimeout(uuid);
        player.clearActivePotionEffects();
        player.setGameMode(GameMode.CREATIVE);
        player.setAllowFlight(true);
        player.setFlying(true);
        send(player, "<#b14aed>» <white>Modo edição <green>ON<white>. Voe até o local e use <#b14aed>/login setspawn<white>.");
    }

    /** Leave edit mode: restore the frozen login state and re-show the auth prompt. */
    public void exitEditMode(Player player) {
        if (!editing.remove(player.getUniqueId())) {
            return; // not editing
        }
        freeze(player);
        applyLoginSpawn(player);
        scheduleLoginTimeout(player);
        resolveAndPrompt(player);
        send(player, "<#b14aed>» <white>Modo edição <red>OFF<white>.");
    }
```

`freeze(...)` e `setLoginSpawn(...)` permanecem inalterados logo abaixo. `BackendException` já está importado (usado em `authenticate`).

- [ ] **Step 2: Roteamento seguro no CommandFilterListener**

Substitua o bloco do `case "/login", "/l"` (linhas ~39-61) por:

```java
            case "/login", "/l" -> {
                // Staff subcommands are checked BEFORE treating args as a password and
                // gated by permission. Entering edit mode additionally requires the
                // password (offline-mode: the op permission is keyed to a spoofable
                // username), so a non-admin's password just falls through to auth.
                if (player.hasPermission(ADMIN_PERM)) {
                    String sub = parts.length >= 2 ? parts[1].toLowerCase() : "";
                    switch (sub) {
                        case "manutencao", "manutenção" -> {
                            if (plugin.isEditing(player.getUniqueId())) {
                                plugin.exitEditMode(player); // toggle off; no password needed
                            } else if (parts.length >= 3) {
                                plugin.enterEditMode(player, parts[2]); // verify password first
                            } else {
                                send(player, "<red>Uso: /login manutencao <senha>");
                            }
                            return;
                        }
                        case "setspawn" -> {
                            if (plugin.isEditing(player.getUniqueId())) {
                                plugin.setLoginSpawn(player);
                            } else {
                                send(player, "<red>Entre em modo edição primeiro: <white>/login manutencao <senha>");
                            }
                            return;
                        }
                        default -> { /* fall through to password handling */ }
                    }
                }
                if (parts.length < 2) {
                    send(player, "<red>Uso: /login <senha>");
                    return;
                }
                plugin.authenticate(player, parts[1], false);
            }
```

- [ ] **Step 3: Compilar**

Run: `mvn -pl plugins/crystal-login -am compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add plugins/crystal-login/src/main/java/com/redecrystal/login/CrystalLoginPlugin.java \
        plugins/crystal-login/src/main/java/com/redecrystal/login/listener/CommandFilterListener.java
git commit -m "fix(login): /login manutencao exige senha verificada; setspawn exige modo edição"
```

---

## Task 3: Build + redeploy + verificação

**Files:** nenhum (build + deploy + verificação manual).

- [ ] **Step 1: Build dos jars**

Run: `make plugins`
Expected: `BUILD SUCCESS`; `crystal-hologram.jar` e `crystal-login.jar` atualizados.

- [ ] **Step 2: Recriar containers**

Run: `docker compose up -d --force-recreate --no-deps login-01 lobby-01 lobby-02 lobby-03`
Expected: sobem; logs mostram `CrystalLogin enabled` e `CrystalHologram enabled`.

- [ ] **Step 3: Verificação em jogo**

Holograma (escopo por tipo):
1. Num lobby: `/hologram remove boasvindas` (entrada legada sem tipo, se existir) e recrie: `/hologram set teste &aLobby`. Deve aparecer nos lobbies e **não** no login.
2. No login (em modo edição): crie um holograma → aparece só no login.

Login seguro:
3. Entre no login com conta op. `/login manutencao` (sem senha) → "Uso: /login manutencao <senha>". Não destrava.
4. `/login manutencao <senha errada>` → "Senha incorreta". Não destrava.
5. `/login manutencao <senha correta>` → "Modo edição ON"; destrava e voa.
6. `/login setspawn` fora do modo edição → "Entre em modo edição primeiro". Em modo edição → grava o spawn.
7. `/login manutencao` de novo (editando) → "Modo edição OFF"; re-congela + prompt.

- [ ] **Step 4: Commit final (se houver ajustes)**

---

## Self-Review

- Escopo holo por serverType → Task 1 (Def+Store+Renderer+Command+Plugin). ✓
- handleMove preserva type; handleList mostra type → Task 1 Step 4. ✓
- `/login manutencao <senha>` verifica no backend sem rotear → Task 2 Step 1 (`enterEditMode`). ✓
- setspawn exige editar → Task 2 Step 2. ✓
- exitEditMode mantém freeze + prompt (do fix anterior) → Task 2 Step 1. ✓
- Permissão antes da senha preservada; não-admin cai no auth → Task 2 Step 2. ✓
- Placeholder scan: nenhum TBD. Type consistency: `enterEditMode(Player,String)`, `exitEditMode(Player)`, `HologramRenderer(JavaPlugin,String)`, `HologramDef(id,type,world,x,y,z,lines)` usados de forma consistente. ✓
- Legado: hologramas sem `type` não renderizam (filtrados) — Task 3 Step 3.1 recria o de teste. Documentado.
