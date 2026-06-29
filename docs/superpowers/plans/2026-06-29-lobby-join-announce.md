# Aviso de entrada no lobby (VIP/staff, com tag) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Suprimir as mensagens vanilla de entrada/saída no lobby e, só para quem tem `crystal.lobby.joinannounce`, anunciar no chat local `[Cargo] Nome entrou no lobby!`.

**Architecture:** Tudo no `crystal-lobby`. `PlayerJoinListener` (já recebe `CrystalCore`) suprime o join vanilla e, se o jogador tem a permissão, resolve o cargo pelo mesmo caminho do `LobbyScoreboard` (`TagOverrides.read` + `CargoResolver.resolve` sobre a config `chat`) e dá `getServer().sendMessage(...)` local. `PlayerQuitListener` suprime o quit vanilla. A permissão é declarada no `plugin.yml`.

**Tech Stack:** Java 21, Paper 1.21, Maven, crystal-core (`CargoResolver`/`TagOverrides`), Adventure MiniMessage.

## Global Constraints

- Texto do jogador em **PT**; código/comentários em **inglês**.
- Constantes em vez de literais (node de permissão, sufixo da mensagem).
- Resolução de cargo na main thread, **espelhando** `LobbyScoreboard`/`ProfileCommand`/`NametagService` (um `hget` por entrada; evento único, não loop). "Código que se parece com o vizinho."
- Não bloquear desnecessariamente: nada de loops/HTTP novos; só o `hget` já padrão do projeto.
- Sem testes unitários para cola Bukkit — gate por task = compilar; verificação funcional em jogo.
- Build do módulo: `mvn -pl plugins/crystal-lobby -am compile`.

## File Structure

- **Modify** `plugins/crystal-lobby/src/main/java/com/redecrystal/lobby/listener/PlayerJoinListener.java` — suprime join vanilla; anuncia entrada para quem tem a permissão.
- **Modify** `plugins/crystal-lobby/src/main/java/com/redecrystal/lobby/listener/PlayerQuitListener.java` — suprime quit vanilla.
- **Modify** `plugins/crystal-lobby/src/main/resources/plugin.yml` — declara `crystal.lobby.joinannounce`.

(`PlayerJoinListener` e `PlayerQuitListener` já recebem `CrystalCore` no construtor — sem mudança de construtor nem de registro no `CrystalLobbyPlugin`.)

---

## Task 1: Suprimir vanilla + anunciar entrada de VIP/staff

**Files:**
- Modify: `plugins/crystal-lobby/src/main/java/com/redecrystal/lobby/listener/PlayerJoinListener.java`
- Modify: `plugins/crystal-lobby/src/main/java/com/redecrystal/lobby/listener/PlayerQuitListener.java`
- Modify: `plugins/crystal-lobby/src/main/resources/plugin.yml`

**Interfaces:**
- Consome: `crystal.redis()`, `crystal.configProvider().get("chat")`, `CargoResolver.resolve(RemoteConfig, String, Predicate<String>)` → `Cargo(String id, String prefix, String nameColor, int weight)` ou null; `TagOverrides.read(RedisClient, UUID)`.
- Produz: nenhuma API nova.

- [ ] **Step 1: Declarar a permissão no `plugin.yml`**

No bloco `permissions:`, adicione (após `crystal.maintenance`):

```yaml
  crystal.lobby.joinannounce:
    description: Anuncia no chat do lobby quando este jogador entra
    default: op
```

- [ ] **Step 2: Suprimir o quit vanilla no `PlayerQuitListener`**

Em `onQuit`, no início do método (antes de mexer no Redis), adicione:

```java
        event.quitMessage(null); // lobby chat is curated; no vanilla leave spam
```

- [ ] **Step 3: Imports no `PlayerJoinListener`**

Garanta no topo (alguns já existem — `CrystalCore`, `Component`, `NamedTextColor`):

```java
import com.redecrystal.core.cargo.CargoResolver;
import com.redecrystal.core.cargo.TagOverrides;
import net.kyori.adventure.text.minimessage.MiniMessage;
import java.util.UUID;
```

- [ ] **Step 4: Constantes no `PlayerJoinListener`**

Após o campo `FLY_PERM` (linha 23), adicione:

```java
    private static final String JOIN_ANNOUNCE_PERM = "crystal.lobby.joinannounce";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    /** Trailing text of the join line, in PT, rendered grey. */
    private static final String JOIN_SUFFIX = "<gray> entrou no lobby!";
```

- [ ] **Step 5: Suprimir o join vanilla e chamar o anúncio**

Em `onJoin`, logo no início (após `Player player = event.getPlayer();`, antes do bloco de sessão), adicione a supressão do join vanilla para todos:

```java
        event.joinMessage(null); // lobby chat is curated; only VIP/staff are announced below
```

E ao FINAL do método `onJoin` (depois de `plugin.sendToSpawn(player);`), adicione:

```java
        announceJoin(player);
```

- [ ] **Step 6: Implementar `announceJoin`**

Adicione o método privado ao `PlayerJoinListener` (após `onJoin`):

```java
    /**
     * Broadcast "[Cargo] Name entrou no lobby!" on THIS lobby — only for players
     * with the announce permission (VIP/staff). The cargo prefix/name colour come
     * from the shared {@code chat} config, resolved the same way as the sidebar.
     */
    private void announceJoin(Player player) {
        if (!player.hasPermission(JOIN_ANNOUNCE_PERM)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        String overrideId = TagOverrides.read(crystal.redis(), uuid);
        CargoResolver.Cargo cargo = CargoResolver.resolve(
                crystal.configProvider().get("chat"), overrideId, player::hasPermission);

        Component name = MM.deserialize((cargo == null ? "<white>" : cargo.nameColor()) + player.getName());
        Component suffix = MM.deserialize(JOIN_SUFFIX);
        Component line = cargo == null
                ? name.append(suffix)
                : MM.deserialize(cargo.prefix()).append(Component.text(" ")).append(name).append(suffix);

        plugin.getServer().sendMessage(line);
    }
```

Nota: `player.getName()` é seguro inserir no MiniMessage (nomes Minecraft são `[A-Za-z0-9_]`). `cargo.prefix()`/`cargo.nameColor()` já são strings MiniMessage (ex. `<red>[Diretor]`, `<red>`).

- [ ] **Step 7: Compilar**

Run: `mvn -pl plugins/crystal-lobby -am compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Commit**

```bash
git add plugins/crystal-lobby/src/main/java/com/redecrystal/lobby/listener/PlayerJoinListener.java \
        plugins/crystal-lobby/src/main/java/com/redecrystal/lobby/listener/PlayerQuitListener.java \
        plugins/crystal-lobby/src/main/resources/plugin.yml
git commit -m "feat(lobby): aviso de entrada para VIP/staff com tag; suprime msg vanilla de entrada/saída"
```

---

## Task 2: Build + redeploy + verificação

**Files:** nenhum (build + deploy + verificação manual).

- [ ] **Step 1: Build dos jars**

Run: `make plugins`
Expected: `BUILD SUCCESS`; `crystal-lobby.jar` atualizado.

- [ ] **Step 2: Recriar os lobbies**

Run: `docker compose up -d --force-recreate --no-deps lobby-01 lobby-02 lobby-03`
Expected: sobem; logs sem erro; `CrystalLobby` carregado.

- [ ] **Step 3: Verificação em jogo (critérios de sucesso da spec)**

1. Jogador **sem** `crystal.lobby.joinannounce` entra → **nenhuma** mensagem (nem vanilla, nem custom).
2. Conceda a permissão a uma conta com cargo (ex.: Diretor) e entre → `[Diretor] Nome entrou no lobby!` aparece no chat daquele lobby.
3. Qualquer jogador sai → **nenhuma** mensagem de saída.
4. Conta com a permissão mas **sem** cargo → `Nome entrou no lobby!` (sem prefixo), sem erro.
5. O aviso aparece só na instância onde a pessoa entrou (entre em lobby-01 e confirme que lobby-02 não mostra).

- [ ] **Step 4: Commit final (se houver ajustes)**

---

## Self-Review

**Spec coverage:**
- Suprime join+quit vanilla para todos → Task 1 Steps 2 e 5. ✓
- Anúncio só com `crystal.lobby.joinannounce` → Task 1 Step 6 (guard). ✓
- Permissão declarada (`default: op`) → Task 1 Step 1. ✓
- Formato `[Cargo] Nome entrou no lobby!`, cargo via padrão do scoreboard → Step 6. ✓
- Fallback sem cargo (sem prefixo, nome branco) → Step 6 (ramo `cargo == null`). ✓
- Broadcast local → `plugin.getServer().sendMessage(line)`. ✓
- Sem saída custom, sem Kafka, sem config → respeitado. ✓
- Critérios 1-5 → Task 2 Step 3. ✓

**Placeholder scan:** nenhum TBD/TODO; todo passo com código mostra o código.

**Type consistency:** `CargoResolver.Cargo` com `prefix()`/`nameColor()`; `TagOverrides.read(RedisClient, UUID)`; `MM`/`JOIN_ANNOUNCE_PERM`/`JOIN_SUFFIX` constantes usadas de forma consistente. `announceJoin(Player)` chamado no fim de `onJoin`. ✓
