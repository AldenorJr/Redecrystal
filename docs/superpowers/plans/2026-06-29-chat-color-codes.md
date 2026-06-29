# Cores no chat (códigos `&`, só com permissão) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Jogadores com `crystal.chat.color` podem colorir a própria mensagem com códigos legacy `&0-&f` (ex.: `&6NANDINHA`). Formatos (`&k/&l/&m/&n/&o/&r`) e MiniMessage do jogador ficam desabilitados. Quem não tem a permissão: mensagem em texto puro (atual).

**Architecture:** A permissão é resolvida na captura (`ChatListener`, único ponto com o `Player`) e carregada como flag `allowColors` no payload Kafka. O consumer (`CrystalChatPlugin`) lê o flag e repassa ao `ChatService.broadcast(..., allowColors)`, que renderiza a mensagem via um helper `colorsOnly(...)` (traduz só `&0-&f`; remove formatos e `§`; nunca usa MiniMessage).

**Tech Stack:** Java 21, Paper 1.21, Maven, crystal-core (Kafka), Adventure (`LegacyComponentSerializer`, MiniMessage).

## Global Constraints

- Texto do jogador em **PT**; código/comentários em **inglês**.
- Constantes nomeadas (node de permissão, regex de formatos) em vez de literais.
- Segurança: a mensagem do jogador **nunca** passa por MiniMessage/`parse()` — só `colorsOnly`.
- Atualizar **todos** os call sites de `broadcast(...)` para a nova assinatura.
- Sem testes unitários para cola Bukkit — gate por task = compilar; verificação em jogo.
- Build do módulo: `mvn -pl plugins/crystal-chat -am compile`.

## File Structure

- **Modify** `plugins/crystal-chat/.../listener/ChatListener.java` — resolve `allowColors`, adiciona ao payload.
- **Modify** `plugins/crystal-chat/.../CrystalChatPlugin.java` — lê `allowColors`, repassa ao broadcast.
- **Modify** `plugins/crystal-chat/.../ChatService.java` — `broadcast(..., boolean allowColors)` + helper `colorsOnly`.
- **Modify** `plugins/crystal-chat/src/main/resources/plugin.yml` — declara `crystal.chat.color`.

---

## Task 1: Cor por permissão no chat

**Files:**
- Modify: `plugins/crystal-chat/src/main/java/com/redecrystal/chat/listener/ChatListener.java`
- Modify: `plugins/crystal-chat/src/main/java/com/redecrystal/chat/CrystalChatPlugin.java`
- Modify: `plugins/crystal-chat/src/main/java/com/redecrystal/chat/ChatService.java`
- Modify: `plugins/crystal-chat/src/main/resources/plugin.yml`

**Interfaces:**
- `ChatService.broadcast(String server, String player, String message, String prefix, String nameColor, boolean allowColors)` — nova assinatura (era sem `allowColors`).

- [ ] **Step 1: `ChatListener` — resolver e carregar `allowColors`**

Adicione a constante após os campos (antes de `onChat`):

```java
    private static final String COLOR_PERM = "crystal.chat.color";
```

Em `onChat`, antes do `crystal.kafka().publish(...)`, calcule o flag:

```java
        boolean allowColors = event.getPlayer().hasPermission(COLOR_PERM);
```

E adicione o par ao `Map.of(...)` do publish (após `"nameColor", tag.nameColor()`):

```java
                "nameColor", tag.nameColor(),
                "allowColors", String.valueOf(allowColors)));
```

(O `Map.of` passa a ter 7 pares — dentro do limite de 10.)

- [ ] **Step 2: `CrystalChatPlugin` — ler o flag e repassar**

No consumer do `PLAYER_CHAT`, no ramo `else` (global), após `String nameColor = event.get("nameColor");` adicione:

```java
                boolean allowColors = "true".equals(event.get("allowColors"));
```

E troque a chamada de broadcast para incluir o flag:

```java
                if (player != null && message != null) {
                    getServer().getScheduler().runTask(this,
                            () -> chat.broadcast(server, player, message, prefix, nameColor, allowColors));
                }
```

- [ ] **Step 3: `ChatService` — assinatura + helper `colorsOnly`**

Adicione a constante de regex junto às outras constantes (após `DEFAULT_FORMAT`):

```java
    /** Legacy format/reset codes (&k &l &m &n &o &r) — stripped from player messages. */
    private static final String FORMAT_CODES = "(?i)&[k-or]";
```

Substitua o método `broadcast` por:

```java
    /** Render and broadcast a global chat line on this server. */
    public void broadcast(String server, String player, String message, String prefix,
                          String nameColor, boolean allowColors) {
        Component messageComponent = allowColors ? colorsOnly(message) : Component.text(message);
        Component line = MINI.deserialize(
                chatFormat,
                Placeholder.component("prefix", parse(prefix)),
                Placeholder.component("player_name", parse((nameColor == null ? "" : nameColor) + player)),
                Placeholder.unparsed("player", player),
                Placeholder.unparsed("server", server == null ? "" : server),
                Placeholder.component("message", messageComponent));
        plugin.getServer().sendMessage(line);
    }

    /**
     * Translate ONLY legacy '&' colour codes (&0-&f) in a player's message: strip
     * the section sign and the format/reset codes first, and never interpret
     * MiniMessage (so players can't inject &lt;click&gt;/&lt;hover&gt;/rainbow).
     */
    private static Component colorsOnly(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        String stripped = raw.replace("§", "").replaceAll(FORMAT_CODES, "");
        return LegacyComponentSerializer.legacyAmpersand().deserialize(stripped);
    }
```

(`parse(...)` continua existindo e é usado só para prefix/player_name, que são confiáveis.)

- [ ] **Step 4: `plugin.yml` — declarar a permissão**

Ao final do arquivo, adicione o bloco:

```yaml
permissions:
  crystal.chat.color:
    description: Usar códigos de cor (&) na mensagem de chat
    default: op
```

- [ ] **Step 5: Conferir call sites e compilar**

Grep para garantir que o único call site de `broadcast(` foi atualizado (consumer):

Run: `grep -rn "\.broadcast(" plugins/crystal-chat/src` → deve mostrar só a chamada do consumer (com 6 args agora) e a definição.
Run: `mvn -pl plugins/crystal-chat -am compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add plugins/crystal-chat/src/main/java/com/redecrystal/chat/listener/ChatListener.java \
        plugins/crystal-chat/src/main/java/com/redecrystal/chat/CrystalChatPlugin.java \
        plugins/crystal-chat/src/main/java/com/redecrystal/chat/ChatService.java \
        plugins/crystal-chat/src/main/resources/plugin.yml
git commit -m "feat(chat): cores & na mensagem só com permissão crystal.chat.color (sem formatos/MiniMessage)"
```

---

## Task 2: Build + redeploy + verificação

**Files:** nenhum (build + deploy + verificação manual).

- [ ] **Step 1: Build**

Run: `make plugins`
Expected: `BUILD SUCCESS`; `crystal-chat.jar` atualizado.

- [ ] **Step 2: Recriar os servidores que rodam o chat (lobbies)**

Run: `docker compose up -d --force-recreate --no-deps lobby-01 lobby-02 lobby-03`
Expected: sobem; logs mostram `CrystalChat enabled`, sem erro.

- [ ] **Step 3: Verificação em jogo (critérios da spec)**

1. Conta com `crystal.chat.color` (op, ou `/lp group vip permission set crystal.chat.color true`) envia `&6NANDINHA` → "NANDINHA" dourado.
2. `&6&nNANDINHA` → dourado, **sem** sublinhado.
3. `<rainbow>oi` ou `<click:run_command:/x>oi` → aparece literal, sem efeito.
4. Conta **sem** a permissão envia `&6oi` → texto puro `&6oi` (sem cor).
5. Mensagem normal sem códigos continua igual para todos.

- [ ] **Step 4: Commit final (se houver ajustes)**

---

## Self-Review

**Spec coverage:**
- Permissão `crystal.chat.color` resolvida na captura + flag no Kafka → Task 1 Steps 1-2. ✓
- `broadcast` usa `colorsOnly` quando permitido, senão texto puro → Step 3. ✓
- `colorsOnly` traduz só `&0-&f`, remove formatos (`(?i)&[k-or]`) e `§`, sem MiniMessage → Step 3. ✓
- Permissão declarada (`default: op`) → Step 4. ✓
- Todos os call sites de broadcast atualizados → Step 5 (grep). ✓
- `/tell` intacto; sem config; sem formatos → respeitado. ✓
- Critérios 1-5 → Task 2 Step 3. ✓

**Placeholder scan:** sem TBD/TODO; todo passo com código mostra o código.

**Type consistency:** `broadcast(String,String,String,String,String,boolean)` chamado no consumer com o `allowColors`; `colorsOnly(String)`; constantes `COLOR_PERM`/`FORMAT_CODES`. `Map.of` com 7 pares (≤10). ✓
