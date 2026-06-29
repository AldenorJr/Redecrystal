# Spawn do Login + Modo Edição — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permitir que a staff defina, em jogo, o spawn do servidor de login, usando um modo edição individual (`/login manutencao`) que destrava o membro da staff e um comando (`/login setspawn`) que grava o spawn na config remota, hot-reloadada para toda a rede.

**Architecture:** Tudo vive em `crystal-login`. Os subcomandos admin são roteados dentro do `CommandFilterListener` (interceptação `PlayerCommandPreprocessEvent`), pois a staff conectada ao login está não-autenticada e congelada — não há comando Bukkit registrado para `/login`. O plugin guarda um `Set<UUID> editing` em memória e um `volatile Location spawn` populado de `login.spawn` (hot-reload via `configProvider().onChange`). O `LoginGuard` libera quem está em edição; o `PlayerJoinListener` teleporta o jogador congelado ao spawn configurado.

**Tech Stack:** Java 21, Paper API 1.21, Maven, `crystal-core` SDK (configProvider/backend HTTP/Kafka), Adventure MiniMessage.

## Global Constraints

- **Idioma:** código e comentários em **inglês**; texto de jogador em **PT**. (CLAUDE.md / CODING_STANDARDS)
- **Nunca bloquear a main thread do Bukkit:** chamadas HTTP (`crystal.backend()`) em `runTaskAsynchronously`. (CLAUDE.md)
- **Serviço/utilitário é `final`; DTO é `record`; constantes em vez de literais mágicos.** (CODING_STANDARDS)
- **Escreva código que se pareça com o vizinho** — copiar idioma/seções/densidade de comentário dos arquivos ao lado (`LobbyCommand`, `CrystalLobbyPlugin`). (CLAUDE.md)
- **Permissão admin:** `crystal.login.admin` (default: op).
- **Chave de config remota:** `login`. Campo do spawn: `spawn` (Map `x/y/z/yaw/pitch`).
- **Sem testes unitários para cola Bukkit** — o repo só testa lógica pura em `crystal-core`. Gate automatizado por task = compilar; verificação funcional = em jogo (rebuild + recriar container), conforme CLAUDE.md.
- **Build de módulo:** `mvn -pl plugins/crystal-login -am compile`.

---

## File Structure

- **Modify** `plugins/crystal-login/src/main/java/com/redecrystal/login/CrystalLoginPlugin.java`
  — estado `editing` + `spawn`; subscrição `onChange("login")`; métodos `isEditing`, `getLoginSpawn`, `applyLoginSpawn`, `toggleEditMode`, `setLoginSpawn`; remoção do `editing` no `clearLocalState`.
- **Modify** `plugins/crystal-login/src/main/java/com/redecrystal/login/listener/CommandFilterListener.java`
  — roteamento dos subcomandos admin `manutencao`/`setspawn` dentro do case `/login`.
- **Modify** `plugins/crystal-login/src/main/java/com/redecrystal/login/listener/PlayerJoinListener.java`
  — teleporta ao spawn de login configurado após congelar.
- **Modify** `plugins/crystal-login/src/main/java/com/redecrystal/login/listener/LoginGuard.java`
  — `locked()` considera `isEditing`; `onMove` libera quem edita.

Sem mudança em `plugin.yml` (nada novo a registrar). `PlayerQuitListener` já chama `clearLocalState`, que passará a limpar `editing` — sem edição no arquivo do listener.

---

## Task 1: Estado e aplicação do spawn no plugin

Adiciona ao `CrystalLoginPlugin` o estado em memória (`editing`, `spawn`), a leitura/hot-reload de `login.spawn`, e os getters/helpers que as demais tasks consomem. Nenhum comportamento de jogador muda ainda (join não teleporta até a Task 3), mas o plugin já compila e carrega a config.

**Files:**
- Modify: `plugins/crystal-login/src/main/java/com/redecrystal/login/CrystalLoginPlugin.java`

**Interfaces:**
- Consumes: `crystal.configProvider().get("login")` → `RemoteConfig` (`.config()` → `Map<String,Object>`, `.value("spawn")` → `Object`); `crystal.configProvider().onChange(String, Consumer<RemoteConfig>)`.
- Produces (usados pelas tasks 2–4):
  - `public boolean isEditing(UUID uuid)`
  - `public Location getLoginSpawn()` — retorna clone ou `null` se não configurado
  - `public void applyLoginSpawn(Player player)` — teleporta ao spawn de login se existir
  - `private Set<UUID> editing` (acessível via `isEditing`)

- [ ] **Step 1: Adicionar imports e campos de estado**

No topo de `CrystalLoginPlugin.java`, garanta os imports (alguns já existem):

```java
import java.util.HashMap;
import org.bukkit.Location;
import org.bukkit.World;
```

Logo após o campo `attempts` (linha ~54), adicione:

```java
    private final Set<UUID> editing = ConcurrentHashMap.newKeySet();
    private volatile Location loginSpawn;
```

- [ ] **Step 2: Carregar o spawn no onEnable e assinar hot-reload**

Em `onEnable()`, depois da linha `this.loginTimeoutSeconds = …` (linha ~61), adicione:

```java
        applyLoginSpawnConfig(crystal.configProvider().get(CONFIG_KEY));
        crystal.configProvider().onChange(CONFIG_KEY, updated -> {
            applyLoginSpawnConfig(updated);
            getLogger().info("Hot-reloaded login spawn: " + describe(loginSpawn));
        });
```

- [ ] **Step 3: Implementar parsing/aplicação do spawn (copiando o padrão do lobby)**

Adicione estes métodos privados/públicos ao plugin (perto da seção `// ── join`):

```java
    // ── login spawn (config-driven; set live with /login setspawn) ──

    /** Parse the spawn from the {@code login} config; null when unset. */
    private void applyLoginSpawnConfig(RemoteConfig cfg) {
        this.loginSpawn = parseSpawn(cfg.value("spawn"));
    }

    private Location parseSpawn(Object raw) {
        World world = getServer().getWorlds().isEmpty() ? null : getServer().getWorlds().get(0);
        if (world == null || !(raw instanceof Map<?, ?> m) || m.get("x") == null) {
            return null;
        }
        return new Location(world, num(m.get("x")), num(m.get("y")), num(m.get("z")),
                (float) num(m.get("yaw")), (float) num(m.get("pitch")));
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    /** The configured login spawn, or null when none is set. */
    public Location getLoginSpawn() {
        return loginSpawn == null ? null : loginSpawn.clone();
    }

    /** Teleport the player to the configured login spawn, if any. */
    public void applyLoginSpawn(Player player) {
        Location s = getLoginSpawn();
        if (s != null) {
            player.teleport(s);
        }
    }

    public boolean isEditing(UUID uuid) {
        return editing.contains(uuid);
    }

    private static String describe(Location l) {
        return l == null ? "unset"
                : (Math.round(l.getX()) + "," + Math.round(l.getY()) + "," + Math.round(l.getZ()));
    }
```

Nota: `RemoteConfig` precisa estar importado — adicione `import com.redecrystal.core.http.RemoteConfig;` se ainda não estiver.

- [ ] **Step 4: Limpar `editing` no quit**

Em `clearLocalState(UUID uuid)` (linha ~205), adicione junto das outras limpezas:

```java
        editing.remove(uuid);
```

- [ ] **Step 5: Compilar**

Run: `mvn -pl plugins/crystal-login -am compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add plugins/crystal-login/src/main/java/com/redecrystal/login/CrystalLoginPlugin.java
git commit -m "feat(login): estado de edição + spawn de login config-driven com hot-reload"
```

---

## Task 2: Modo edição e setspawn (lógica no plugin)

Adiciona os dois comportamentos admin como métodos do plugin: `toggleEditMode` (liga/desliga o destravamento individual) e `setLoginSpawn` (grava a posição na config remota). A Task 4 chama estes métodos a partir do interceptador.

**Files:**
- Modify: `plugins/crystal-login/src/main/java/com/redecrystal/login/CrystalLoginPlugin.java`

**Interfaces:**
- Consumes: `editing`, `loginTimeoutSeconds`, `scheduleLoginTimeout(Player)`, `cancelTimeout(UUID)` (já existe, privado), `applyLoginSpawn(Player)`, `crystal.backend().putConfig(String, Map)`, `crystal.configProvider().get(CONFIG_KEY)`.
- Produces (usados pela Task 4):
  - `public void toggleEditMode(Player player)`
  - `public void setLoginSpawn(Player player)`

- [ ] **Step 1: Imports para gamemode/efeitos**

Garanta no topo:

```java
import org.bukkit.GameMode;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
```

- [ ] **Step 2: Implementar `toggleEditMode`**

Adicione na seção do login spawn (após `applyLoginSpawn`):

```java
    // ── staff edit mode (/login manutencao): unfreeze one staff member to fly + set spawn ──

    /** Toggle the caller's individual edit mode: unfreeze to fly, or re-freeze. */
    public void toggleEditMode(Player player) {
        UUID uuid = player.getUniqueId();
        if (editing.remove(uuid)) {
            // Leaving edit mode: restore the frozen login state.
            freeze(player);
            applyLoginSpawn(player);
            scheduleLoginTimeout(player);
            send(player, "<#b14aed>» <white>Modo edição <red>OFF<white>.");
            return;
        }
        // Entering edit mode: free movement, cancel the kick timer, drop blindness.
        editing.add(uuid);
        cancelTimeout(uuid);
        player.clearActivePotionEffects();
        player.setGameMode(GameMode.CREATIVE);
        player.setAllowFlight(true);
        player.setFlying(true);
        send(player, "<#b14aed>» <white>Modo edição <green>ON<white>. Voe até o local e use <#b14aed>/login setspawn<white>.");
    }

    /** Re-apply the frozen login state (mirror of PlayerJoinListener). */
    private void freeze(Player player) {
        player.setGameMode(GameMode.ADVENTURE);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setFlying(false);
        player.setAllowFlight(false);
        player.getInventory().clear();
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                PotionEffect.INFINITE_DURATION, 0, false, false, false));
    }
```

- [ ] **Step 3: Implementar `setLoginSpawn` (HTTP off-thread, padrão LobbyCommand)**

Adicione em seguida:

```java
    /** Persist the caller's current location as the login spawn for the whole network. */
    public void setLoginSpawn(Player player) {
        Location loc = player.getLocation();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Map<String, Object> cfg = new HashMap<>(crystal.configProvider().get(CONFIG_KEY).config());
                cfg.put("spawn", Map.of(
                        "x", round(loc.getX()), "y", round(loc.getY()), "z", round(loc.getZ()),
                        "yaw", round(loc.getYaw()), "pitch", round(loc.getPitch())));
                crystal.backend().putConfig(CONFIG_KEY, cfg);
                send(player, "<green>Spawn do login definido para todos os servidores de login.");
            } catch (Exception e) {
                send(player, "<red>Falha ao salvar o spawn. Tente novamente.");
                getLogger().warning("setLoginSpawn failed for " + player.getName() + ": " + e.getMessage());
            }
        });
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
```

Nota: `send(...)` é chamado de uma async task; como ele só faz `player.sendMessage`, mantém o mesmo comportamento do `LobbyCommand` (que também envia mensagem de dentro da async task). Seguir o padrão do vizinho.

- [ ] **Step 4: Compilar**

Run: `mvn -pl plugins/crystal-login -am compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add plugins/crystal-login/src/main/java/com/redecrystal/login/CrystalLoginPlugin.java
git commit -m "feat(login): toggleEditMode e setLoginSpawn (grava login.spawn no backend)"
```

---

## Task 3: Teleportar ao spawn no join + liberar quem edita no LoginGuard

Conecta o spawn ao fluxo de entrada (jogador cai congelado **no** spawn configurado) e impede que as travas do `LoginGuard` atinjam a staff em modo edição.

**Files:**
- Modify: `plugins/crystal-login/src/main/java/com/redecrystal/login/listener/PlayerJoinListener.java`
- Modify: `plugins/crystal-login/src/main/java/com/redecrystal/login/listener/LoginGuard.java`

**Interfaces:**
- Consumes: `plugin.applyLoginSpawn(Player)` (Task 1), `plugin.isEditing(UUID)` (Task 1).
- Produces: nenhuma nova API.

- [ ] **Step 1: Teleportar ao spawn no PlayerJoinListener**

Em `PlayerJoinListener.onJoin`, depois do bloco que aplica a cegueira (linha ~37, após `player.addPotionEffect(...)`) e **antes** de `plugin.scheduleLoginTimeout(player)`, adicione:

```java
        // Land the frozen player on the configured login spawn (no-op if unset).
        plugin.applyLoginSpawn(player);
```

- [ ] **Step 2: `locked()` considera edição no LoginGuard**

Em `LoginGuard`, substitua o helper `locked` (linha ~49):

```java
    private boolean locked(Player p) {
        return !plugin.isAuthenticated(p.getUniqueId()) && !plugin.isEditing(p.getUniqueId());
    }
```

- [ ] **Step 3: `onMove` libera quem está em edição**

Em `LoginGuard.onMove` (linha ~78), logo no início do método (após pegar `from`/`to`, antes do check de leash), adicione um early-return para quem edita. Substitua o começo do método:

```java
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!locked(event.getPlayer())) {
            return; // staff in edit mode (or authenticated) flies freely
        }
        Location from = event.getFrom();
        Location to = event.getTo();
```

(o restante do método permanece igual).

- [ ] **Step 4: Compilar**

Run: `mvn -pl plugins/crystal-login -am compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add plugins/crystal-login/src/main/java/com/redecrystal/login/listener/PlayerJoinListener.java \
        plugins/crystal-login/src/main/java/com/redecrystal/login/listener/LoginGuard.java
git commit -m "feat(login): cai no spawn configurado ao entrar; libera staff em modo edição"
```

---

## Task 4: Roteamento dos subcomandos admin no CommandFilterListener

Liga os comandos digitados (`/login manutencao`, `/login setspawn`) à lógica do plugin, com checagem de permissão antes de tratar `args[0]` como senha.

**Files:**
- Modify: `plugins/crystal-login/src/main/java/com/redecrystal/login/listener/CommandFilterListener.java`

**Interfaces:**
- Consumes: `plugin.toggleEditMode(Player)` (Task 2), `plugin.setLoginSpawn(Player)` (Task 2).
- Produces: nenhuma nova API.

- [ ] **Step 1: Adicionar constante de permissão**

No topo da classe `CommandFilterListener`, após o campo `MM`:

```java
    private static final String ADMIN_PERM = "crystal.login.admin";
```

- [ ] **Step 2: Rotear admin dentro do case `/login`**

Substitua o case `/login`, `/l` (linhas ~38-44) por:

```java
            case "/login", "/l" -> {
                // Staff subcommands are checked BEFORE treating args as a password,
                // and gated by permission so a normal player can never trigger them
                // (their password just falls through to the auth path below).
                if (parts.length >= 2 && player.hasPermission(ADMIN_PERM)) {
                    switch (parts[1].toLowerCase()) {
                        case "manutencao", "manutenção" -> {
                            plugin.toggleEditMode(player);
                            return;
                        }
                        case "setspawn" -> {
                            plugin.setLoginSpawn(player);
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
git add plugins/crystal-login/src/main/java/com/redecrystal/login/listener/CommandFilterListener.java
git commit -m "feat(login): /login manutencao e /login setspawn (interceptados, gate por permissão)"
```

---

## Task 5: Build do jar + verificação em jogo

Gera o jar shaded e valida o fluxo completo no servidor real, conforme o processo do CLAUDE.md (não há teste unitário para cola Bukkit).

**Files:** nenhum (build + verificação manual).

- [ ] **Step 1: Build dos jars dos plugins**

Run: `make plugins`
Expected: jar shaded de `crystal-login` gerado sem erro.

- [ ] **Step 2: Recriar o container de login**

Run: `docker compose up -d --force-recreate --no-deps login-01`
(Use o nome real do serviço de login no `docker-compose.yml`; ajuste se diferir.)
Expected: container sobe; `docker compose logs login-01` mostra `CrystalLogin enabled`.

- [ ] **Step 3: Verificar em jogo (checklist dos critérios de sucesso da spec)**

Com uma conta **staff** (op / `crystal.login.admin`):
1. Conecte ao login → você cai congelado (cego, sem mover).
2. `/login manutencao` → mensagem "Modo edição ON"; você voa, sem cegueira, e **não** leva kick por timeout (espere >60 s para confirmar).
3. Voe até o ponto desejado → `/login setspawn` → mensagem "Spawn do login definido…".
4. `/login manutencao` de novo → "Modo edição OFF"; volta congelado no spawn.
5. Com uma conta **comum** (ou alt), reconecte ao login → cai congelado **no novo spawn**.
6. Conta comum digitando `/login manutencao` → tratado como senha (erro de senha/registro normal), **sem** efeito admin.

Expected: todos os 6 itens conferem. Se algum falhar, depurar antes de concluir.

- [ ] **Step 4: Commit final (se houver ajustes)**

Caso a verificação exija correções, commit com mensagem descritiva. Se tudo passou de primeira, não há novo commit nesta task.

---

## Self-Review

**Spec coverage:**
- Config `login.spawn` + hot-reload → Task 1. ✓
- Spawn aplicado no join → Task 3 Step 1. ✓
- `/login manutencao` modo edição (toggle, individual; cancela timeout, remove cegueira, creative+fly; off restaura) → Task 2 Step 2 + Task 4 Step 2. ✓
- `/login setspawn` (perm, HTTP async, grava `login.spawn`) → Task 2 Step 3 + Task 4 Step 2. ✓
- Roteamento `/login` com permissão antes de senha → Task 4 Step 2. ✓
- LoginGuard libera quem edita (locked + onMove) → Task 3 Steps 2-3. ✓
- Limpeza de `editing` no quit → Task 1 Step 4. ✓
- Sem mudança em proxy/manutenção de rede; sem GUI; sem persistir edição; sem edição de blocos → respeitado (nenhuma task toca nisso). ✓
- Critérios de sucesso 1-6 → Task 5 Step 3. ✓

**Placeholder scan:** nenhum TBD/TODO; todo passo com código mostra o código. O único "ajuste se diferir" (nome do serviço docker) é instrução de ambiente real, não placeholder de lógica.

**Type consistency:** `toggleEditMode(Player)`, `setLoginSpawn(Player)`, `isEditing(UUID)`, `getLoginSpawn()`, `applyLoginSpawn(Player)` usados de forma idêntica entre as tasks. `CONFIG_KEY = "login"` reutilizado. `freeze(Player)` espelha o `PlayerJoinListener`. `round`/`num`/`describe` privados e estáticos. OK.
