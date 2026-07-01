# Design — Fase 10: Hub `spawn` (fleet) + TAB global + chat global + scoreboard

Data: 2026-07-01
Branch base: `feat/login-autocomplete-e-troca-senha`

> Terceira fase do jogo **RankUP** sobre a rede RedeCrystal. Sobre a economia
> (Fase 8) e os ranks/prestígio (Fase 9), a Fase 10 dá ao jogo o seu **hub**: um
> novo tipo de servidor **`spawn`** escalável (fleet, como o lobby), o plugin
> **`crystal-spawn`** (scoreboard + navegação GUI-first), a **generalização do
> roteamento** do proxy (`LobbyRouter` → `FleetRouter` por tipo), o **portal
> lobby→spawn**, a **TAB global** (`crystal-tab` lendo a hash Redis `tab:rankup`
> escrita nas Fases 8/9) e o **chat global** com o formato `[Spawn] [TERRA] Steve:
> …`. Arquitetura de alto nível em
> [`2026-07-01-rankup-arquitetura-design.md`](2026-07-01-rankup-arquitetura-design.md)
> (§2, §5, §7.1/§7.2, §8, §10 Fase 10) — este spec detalha só o que a Fase 10
> entrega. Texto de jogador em PT; identificadores/módulos/tópicos/chaves em
> inglês.

## 1. Problema

Depois de existirem economia e ranks (Fases 8/9), o RankUP ainda **não tem casa
própria**: `crystal-economy`/`crystal-rank`/`crystal-prestige` estão montados no
`lobby-01` só para verificação, e não há um servidor de jogo onde o jogador
"entre no RankUP". Faltam quatro coisas para o hub existir e a rede parecer uma
rede só:

1. **Um servidor `spawn`** — hub do jogo, **escalável horizontalmente** como o
   fleet de lobby (várias instâncias, roteamento least-loaded, descoberta pela
   registry), com mundo VOID + build de spawn (schematic), onde moram os plugins
   do jogo (economy/rank/prestige) + a navegação para mina/arena/terrenos.
2. **Roteamento por tipo** — o `LobbyRouter` do `crystal-bungee` só conhece
   `lobby`; precisa virar um **`FleetRouter` por tipo** para também balancear
   `spawn` (e, nas próximas fases, `mina`), mais um **portal lobby→spawn** que
   dispare esse roteamento a partir de dentro do jogo.
3. **Scoreboard do hub** — uma sidebar mostrando **rank, prestígio, money,
   tokens, online e servidor**, lida do **estado quente no Redis** (as chaves
   `rankup:{uuid}`/`economy:{uuid}` escritas nas Fases 8/9), atualizada no tick
   — **não** consumindo Kafka por jogador.
4. **TAB e chat globais de verdade** — hoje a TAB (`crystal-tab`) lista só os
   jogadores **do próprio servidor** e o chat (`crystal-chat`) já cruza a rede
   mas com o formato `<cargo> Nome: msg`. A Fase 10 faz a TAB listar **todos os
   jogadores da rede** (com nick/rank/prestígio/money/servidor, lidos de
   `tab:rankup`) e dá ao chat o formato `[Spawn] [TERRA] Steve: msg` — **tag de
   servidor + prefixo de rank de jogo + cargo de rede coexistindo**.

Três exigências de arquitetura moldam o design: (a) **rank de jogo ≠ cargo de
rede** (master §1.1 / Fase 9) — o `[TERRA]` do RankUP e o `[VIP]` da rede
aparecem **juntos**, resolvidos por fontes diferentes; (b) **estado quente lido
do Redis no tick, não Kafka por jogador** (master §5/§8) — scoreboard e TAB leem
hashes Redis 1×/tick, como o `crystal-tag` lê `tag:overrides`; (c) **fail-open** —
Redis/HTTP fora nunca derruba o hub.

## 2. Objetivos e não-objetivos

**Objetivos**
- Novo tipo de servidor **`spawn`** como fleet (âncora `&spawn-env` no
  `docker-compose.yml`, `spawn-01`/`spawn-02`, `SERVER_TYPE=spawn`), mundo VOID +
  schematic `WORLD_SPAWN` via `crystal-worldinit`, montando os plugins do jogo
  (`crystal-economy`/`crystal-rank`/`crystal-prestige`) + o novo `crystal-spawn`.
- Plugin **`crystal-spawn`** (GUI-first): sidebar scoreboard lendo
  `rankup:{uuid}`/`economy:{uuid}` no tick (~1–2 s); hotbar travada + itens/NPCs de
  navegação (mina/arena/terrenos) e um **seletor de minas** (GUI); **portal
  lobby→spawn** (item no hub do lobby).
- **`crystal-bungee`**: generalizar `LobbyRouter` → **`FleetRouter` por tipo**
  (least-loaded para `spawn`/`mina`, como o lobby), com seleção de spawn e um
  canal de roteamento sob demanda para o portal.
- **TAB global**: `crystal-tab` lê a hash Redis **`tab:rankup`** (escrita nas
  Fases 8/9) 1×/tick e renderiza **todos** os jogadores da rede com
  nick/rank/prestígio/money/servidor. **Sem plugin novo.**
- **Chat global**: `crystal-chat` no formato `[Spawn] [TERRA] Steve: msg` — tag
  de servidor + prefixo de rank de jogo (de `rankup:{uuid}`/catálogo `rank`) +
  cargo de rede ainda honrado, sobre o `player-chat` existente com `server`/
  `rankId` no envelope.
- **Presença**: chave Redis **`player-server:{uuid}`** com o servidor atual, usada
  no TAB e no chat.

**Não-objetivos** (ficam para fases seguintes do master §10)
- Fleet `mina`/`crystal-mine` (Fase 11), terrenos (12/13), plantações (14), arena
  (15). O `FleetRouter` **já nasce** genérico e o seletor de minas do
  `crystal-spawn` **já existe como GUI**, mas ele roteia para um fleet `mina` que
  só passa a existir na Fase 11 (até lá, "em breve").
- NPCs via Citizens, holos de topo, PlaceholderAPI, cosméticos/Tokens — polish da
  **Fase 16**. A Fase 10 usa **itens de hotbar** (e, opcionalmente, NPCs simples
  já suportados) para navegação; sem Citizens.
- Produtores reais de Money (mineração/kill/colheita) — Fases 11/14/15. O
  scoreboard **exibe** money/tokens; quem os **gera** vem depois.
- Mudanças no `rankup-service`/SDK de backend: a Fase 10 é **só de plugins +
  infra** (compose). `tab:rankup`, `rankup:{uuid}`, `economy:{uuid}` já são
  **escritos** nas Fases 8/9 — a Fase 10 é o **leitor**. Um único ajuste de
  escrita é discutido em §4.6 e §10 (campo `server`/`nick` de `tab:rankup`).

## 3. Decisões de design

| Tema | Decisão |
|------|---------|
| **Estado quente: Redis no tick, não Kafka por jogador** | Scoreboard e TAB leem hashes Redis 1×/tick (`rankup:{uuid}`, `economy:{uuid}`, `tab:rankup`, `player-server:{uuid}`), como o `crystal-tag` lê `tag:overrides` e a `LobbyScoreboard` lê `onlineCount`. **Não** há consumidor Kafka por jogador para HUD/placar. Os tópicos `money-updated`/`rank-updated`/`prestige-updated` (Fases 8/9) continuam existindo como **fatos** para reações pontuais (broadcast/animação), fora do laço de render. |
| **Scoreboard: 1 chamada Redis por jogador por tick** | `crystal-spawn` espelha a `LobbyScoreboard` (board por jogador, times por linha, refresh em timer). Para o saldo/rank de **cada** jogador, um `hgetAll rankup:{uuid}` + `hgetAll economy:{uuid}` por tick — leitura só do **próprio** jogador (não da rede toda), no molde do `TagOverrides.read`. Miss/Redis fora → 0/TERRA (fail-open). |
| **TAB agregada por _modo de jogo_ (não por instância) — DECIDIDO** | A TAB é **agrupada por modo**: no lobby lista **todos** os jogadores em qualquer instância de lobby; no RankUP lista **todos** os jogadores em qualquer instância RankUP (spawn/mina/arena/terrenos), **independente da instância**. Modo = grupo de `SERVER_TYPE` (mapa config `tab.groups`: `lobby={lobby}`, `rankup={spawn,mina,arena,terrenos}`). Um hash Redis **por grupo** `tab:<group>` (field=uuid → json). O `crystal-tab` lê `tab:<seuGrupo>` 1×/tick e injeta entradas para jogadores do **mesmo modo** em **outras** instâncias (ver §4.5 e §10). |
| **Entrada da TAB escrita pelo servidor atual do jogador — DECIDIDO** | Cada servidor de jogo escreve a entrada do próprio jogador em `tab:<group>` (join + refresh periódico, remove no quit), montando `{nick,cargo,rankId,prestige,money,server}`: o **cargo** é resolvido **localmente** (permissão/`chat.roles`/`tag:overrides`) e `rank/money` vêm de `rankup:{uuid}`/`economy:{uuid}` (Redis). Isso resolve o **cargo de jogadores remotos** (vem gravado na entrada) e o **servidor defasado** (a entrada é reescrita a cada presença), sem depender de write-through das Fases 8/9. |
| **TAB do RankUP: CARGO + NICK + SIGLA do clã — DECIDIDO** | Na **TAB do RankUP**, cada linha mostra **cargo de rede** + **nick** + **sigla do clã** (3 letras, Fase 17): `[VIP] Steve [CLA]` — cargo como prefixo, nick, e a **sigla** como sufixo. **Não** entra o rank de jogo (esse fica no **scoreboard**/sidebar e no **chat do RankUP**). Sem clã → sem sigla. A `sigla` vem do `clanTag` da entrada `tab:rankup` (escrita pelo servidor atual do jogador, lendo `clan-of:{uuid}`+`clan:{clanId}`). No **lobby**, a TAB mantém só cargo + nick. (Rank ≠ cargo, master §1.1.) |
| **Chat: cargo sempre; rank só no modo RankUP — DECIDIDO** | `crystal-chat` acrescenta `rankId` (lido de `rankup:{uuid}` do remetente) ao envelope `player-chat` (que já carrega `server` e `prefix` do cargo). No **modo RankUP** o consumidor compõe `<cargo> <prefixo do rank> Nome: msg` (cargo **e** rank); no **lobby**, só `<cargo> Nome: msg`. O prefixo do rank vem do catálogo `rank` (config). Formato **config-driven** por grupo (`chatFormat`/`chatFormat.rankup` com placeholder `<rank_prefix>`). |
| **`FleetRouter` genérico por tipo** | `LobbyRouter` (hardcode `"lobby"` + `name.startsWith("lobby")`) vira `FleetRouter` parametrizado por **`type`** (e prefixo de nome). O `crystal-bungee` instancia **um por tipo** (`lobby`, `spawn`; `mina` na Fase 11). Cada um mantém seu `pending`/`waitingFor…`/discovery — mesma lógica least-loaded, `pending` smoothing e drop de instância que saiu da registry. |
| **Portal lobby→spawn: roteamento sob demanda no proxy** | O login continua roteando o jogador ao **lobby** (fluxo da Fase 0–7 intacto). Do hub, um **item/portal** publica um plugin-message num canal novo `crystal:route` com o tipo alvo (`spawn`); um listener no proxy chama `FleetRouter(spawn).route(player)` (least-loaded). Espelha o padrão do `AUTH_CHANNEL` (plugin-message backend→proxy) já usado no login. **Não** se nomeia instância no cliente (o proxy escolhe), preservando o balanceamento. |
| **`player-server:{uuid}` como presença** | Chave string nova (master §8) com o `serverId` atual do jogador, escrita no join de cada servidor de jogo e no roteamento (TTL ~30 s, refresh no heartbeat). Fonte da coluna "servidor" da TAB/chat quando `tab:rankup.server` estiver defasada (ver §4.6). |
| **Plugins que migram do lobby para o spawn** | `crystal-economy`, `crystal-rank`, `crystal-prestige` deixam de ser montados no `lobby-01` (onde estavam "só para verificação" nas Fases 8/9) e passam a ser montados no fleet **`spawn`** (`&spawn-env`), junto do novo `crystal-spawn`. `crystal-tab`/`crystal-chat`/`crystal-tag` continuam em **todos** os servidores de jogo (lobby e spawn). |
| **Reuso do `crystal-worldinit` para o mundo do spawn** | O spawn usa o **mesmo** `crystal-worldinit` (VOID + paste do schematic via `CRYSTAL_WORLD_SCHEMATIC`), com um schematic `WORLD_SPAWN` próprio — sem código novo de mundo, como o lobby. |
| **GUI-first (README)** | Navegação e seletor de minas começam por **GUI/itens de hotbar**, não por comando cru. Hotbar travada (cancelar `InventoryClickEvent`/drop/move) para os itens de navegação, no molde das GUIs do lobby (`MenuHolder`). |

## 4. Arquitetura

### 4.1 Novo tipo de servidor `spawn` (fleet)

No `docker-compose.yml`, uma **âncora `&spawn-env`** espelhando `&lobby-env`
(TYPE/VERSION/MEMORY, VOID `LEVEL_TYPE=FLAT` + `GENERATOR_SETTINGS` de void,
`CRYSTAL_WORLD_SCHEMATIC`/`CRYSTAL_WORLD_PASTE`, `SERVER_ID`/`SERVER_TYPE=spawn`/
`SERVER_HOST`/`SERVER_PORT`), com `depends_on: *mc-deps`. Instâncias `spawn-01`/
`spawn-02` usam `<<: *spawn-env` variando só `SERVER_ID`/`SERVER_HOST` (exatamente
como `lobby-02`/`lobby-03`). Volumes montam os jars do jogo:

```
environment: &spawn-env
  <<: *lobby-env base (TYPE/VERSION/MEMORY/void)
  CRYSTAL_WORLD_SCHEMATIC: /schematics/world-spawn.schematic
  SERVER_TYPE: spawn
volumes:
  - ./plugins/crystal-spawn/target/crystal-spawn.jar:/plugins/crystal-spawn.jar:ro
  - ./plugins/crystal-economy/target/crystal-economy.jar:/plugins/crystal-economy.jar:ro
  - ./plugins/crystal-rank/target/crystal-rank.jar:/plugins/crystal-rank.jar:ro
  - ./plugins/crystal-prestige/target/crystal-prestige.jar:/plugins/crystal-prestige.jar:ro
  - ./plugins/crystal-tab|chat|tag|worldinit|... (como o lobby)
  - ./world/WORLD_SPAWN/world-spawn.schematic:/schematics/world-spawn.schematic:ro
  - FastAsyncWorldEdit + LuckPerms (EXTERNAL_PLUGINS, como o lobby)
```

Registry/descoberta e heartbeat vêm de graça do `crystal-core` (o servidor se
registra por `SERVER_TYPE`; `crystal.startHeartbeat` já roda). Nenhuma mudança no
proxy para **adicionar/remover** uma instância — o `FleetRouter` a descobre pela
registry (§4.3), como o lobby.

### 4.2 Plugin `crystal-spawn` (Paper, roda no fleet `spawn`)

Espelha `crystal-lobby` (`pom.xml` com shade do `crystal-core` + paper-api;
`CrystalSpawnPlugin` só boot+registro; `listener/`, `commands/`, `gui/`). Boot:
`CrystalCore.bootstrap(CrystalConfig.fromEnv())`, registra o scoreboard, os
listeners de navegação e (opcional) o portal.

**`listener/SpawnScoreboard`** — sidebar por jogador, **espelhando a
`LobbyScoreboard`** (board novo no join, um `Team line0..lineN` por linha,
refresh em `runTaskTimer` ~20–40 ticks). A diferença é a **fonte das linhas**:
lê o estado quente do Redis do **próprio** jogador no tick, não só `onlineCount`:

```
lines(p):
  rankup  = crystal.redis().hgetAll("rankup:"  + uuid)   // rankId, rankOrder, prestige, multiplier
  economy = crystal.redis().hgetAll("economy:" + uuid)   // money, tokens
  online  = crystal.redis().onlineCount()
  server  = crystal.config().serverId()
  → Rank: <prefixo do rank via catálogo `rank`> · Prestígio: n
    Money: m · Tokens: t · Online: k · Servidor: spawn-XX
```

O **prefixo do rank** vem do catálogo `rank` (config central, `ConfigProvider.
get("rank")` + `onChange`, como o `crystal-chat` lê `chat`): `rankId` → degrau →
`prefix`. Miss de Redis / hash vazia → `TERRA`/0/0 (fail-open, `try/catch` como o
`crystal-tag`). Uma leitura Redis por jogador por tick — sem N chamadas na main
thread além do laço de online (idêntico ao padrão da `LobbyScoreboard`).

**`listener/NavigationListener` + `gui/`** — hotbar travada com itens de
navegação (mina/arena/terrenos) dados no join; clique/drop/move cancelados
(`InventoryClickEvent`/`PlayerDropItemEvent`), no molde das GUIs do lobby. Itens:
- **Mina** → abre `gui/MineSelectorMenu` (seletor de minas GUI-first; lista as
  minas do catálogo `mine` da config quando existir — Fase 11 — e roteia via
  `crystal:route` `mina`; até lá "em breve").
- **Arena** → `crystal:route` `arena` (Fase 15; "em breve").
- **Terrenos** → `crystal:route` `terrenos` (Fase 12; "em breve").
- **`/rankup`/`/prestigio`/`/saldo`** já vêm dos plugins migrados (itens podem
  abrir essas GUIs diretamente).

**`commands/SpawnCommand`** (`/spawn`) — teleporta o jogador ao spawn do mundo
(build pastado) e (re)dá a hotbar de navegação.

### 4.3 `crystal-bungee`: `LobbyRouter` → `FleetRouter` por tipo

O `LobbyRouter` atual embute o tipo (`LOBBY_TYPE = "lobby"`) e o prefixo
(`name.startsWith("lobby")`). Generalização mínima, preservando a lógica:

```java
public final class FleetRouter {
    private final String type;           // "lobby", "spawn", "mina"
    private final ProxyServer proxy; private final CrystalCore crystal; private final Logger logger;
    private final Map<String, AtomicInteger> pending = new ConcurrentHashMap<>();
    private final Set<UUID> waiting = ConcurrentHashMap.newKeySet();

    public void sync()            { /* listServers(type) + register/unregister, name.startsWith(type) */ }
    public void route(Player p)   { /* pick least-loaded (onlinePlayers + pending), park se vazio */ }
    private Optional<NetworkServer> pick() { /* min por onlinePlayers()+pending, isOnline() */ }
    // drain/removeWaiting idênticos ao LobbyRouter
}
```

O `CrystalBungeePlugin` instancia **um `FleetRouter` por tipo** (`lobby`,
`spawn`), agenda `sync()` de cada um a cada 10 s e revalida em
`SERVER_STARTED`/`SERVER_STOPPED` (como hoje). O `ConnectionRoutingListener`
continua chamando `router(lobby).route(...)` após o auth handshake (fluxo de
login intacto). Para o **portal**, um novo canal:

**`listener/RouteRequestListener`** — registra o canal `crystal:route`; ao
receber um plugin-message de um `ServerConnection` (backend) com o tipo alvo,
chama `router(type).route(player)`. Espelha o `AUTH_CHANNEL`
(`ConnectionRoutingListener`): consome a mensagem, exige origem backend, nunca
confia no cliente. O gate `onServerPreConnect` já **permite** um jogador
autenticado alcançar qualquer servidor registrado (só nega não-autenticados), então
rotear para um `spawn` recém-descoberto passa sem mudança no gate.

`spawn`/`mina` = least-loaded (como o lobby). `arena` (Fase 15) = instância única
(conecta direto); `terrenos` (Fase 12) = sticky por `plot-server:{uuid}` — ambos
fora do escopo da Fase 10, mas o `FleetRouter` já deixa o encaixe pronto.

### 4.4 Chat global — formato `[Spawn] [TERRA] Steve: msg`

`crystal-chat` já publica no `player-chat` com `server`/`prefix`(cargo)/`nameColor`
e já **consome** o tópico para renderizar em toda a rede (`ChatService.broadcast`).
Duas adições:

1. **Emissão (`ChatListener`)** — além do cargo de rede (já resolvido no servidor
   do remetente, que é o único que lê as permissões dele), acrescenta ao envelope
   o **`rankId`** do jogador, lido de `rankup:{uuid}` (Redis, fail-open → `TERRA`).
   O envelope passa a ter `server`, `prefix` (cargo), `nameColor`, `rankId`.
2. **Render (`ChatService.broadcast`)** — o consumidor compõe por **modo do
   remetente** (derivado do `server`/tipo do envelope):
   - **cargo de rede** `[VIP]` — o `prefix` já existente do envelope (pode ser
     vazio); **sempre** presente, em qualquer modo.
   - **prefixo do rank** `[OURO]` — resolvido do `rankId` pelo catálogo `rank`
     (config), **só no modo RankUP** (spawn/mina/arena/terrenos). No lobby o rank
     **não** aparece no chat.

   Dois formatos config-driven (hot-reload como hoje): `chatFormat` (lobby/padrão)
   `"<prefix> <player_name><gray>:</gray> <message>"` e `chatFormat.rankup`
   `"<prefix> <rank_prefix> <player_name><gray>:</gray> <message>"`. Peças vazias
   colapsam. Decisão do dono: **cargo sempre; rank só no RankUP** (§3). Tag de
   servidor/modo fica opcional (não pedida) — pode entrar via `<server_tag>` se
   quiser depois.

`crystal-chat` passa a ler também o catálogo `rank` (`ConfigProvider.
preload("rank")` + `onChange`) para resolver o prefixo — mesmo padrão do `chat`.

### 4.5 TAB global — `crystal-tab` lê `tab:rankup`

Hoje o `CrystalTabPlugin.refresh()` roda 1×/tick, lê `onlineCount` (rede) e
`tag:overrides` (`hgetAll`), e **para cada jogador local** (`getOnlinePlayers()`)
renderiza header/footer + o nome com o cargo de rede. Ele **não** lê `tab:rankup`
e **só** lista jogadores do próprio servidor.

A Fase 10 reescreve o `refresh()` em torno de **grupos por modo** (§3):
- **Escrita da própria entrada.** Cada servidor de jogo escreve a entrada do seu
  jogador em `tab:<group>` (join + refresh no tick, `hdel` no quit): `{nick,cargo,
  clanTag,rankId,prestige,money,server}`. O **cargo** é resolvido **localmente**
  (`chat.roles`+`tag:overrides`, único servidor que lê as permissões dele); a
  **clanTag** (sigla de 3 letras, Fase 17) vem de `clan-of:{uuid}`+`clan:{clanId}`;
  `rank/money` vêm de `rankup:{uuid}`/`economy:{uuid}` (Redis). `group` = mapa
  `tab.groups` a partir do `SERVER_TYPE`.
- **Leitura do grupo.** `hgetAll(tab:<seuGrupo>)` 1×/tick → todos os jogadores do
  **mesmo modo** (em qualquer instância). No **RankUP** renderiza `[cargo] nick
  [sigla]` (**cargo + nick + sigla do clã**, §3); no **lobby**, `[cargo] nick`. O
  **rank de jogo não entra na TAB** (fica no scoreboard); `prestige/money` podem ir
  como colunas auxiliares se desejado.

**Entradas para jogadores remotos.** O Paper `playerListName(...)` só edita a
entrada de um jogador **realmente conectado a este servidor**. Listar jogadores de
**outras instâncias do mesmo modo** exige **injetar entradas sintéticas** (via
pacotes/`PlayerInfoUpdate` ou biblioteca de tablist) — capacidade que o
`crystal-tab` **não** tem hoje (ver §10). O escopo agora é **por modo** (não a rede
toda), mas a injeção cross-instância continua necessária dentro do modo. **Decisão
de implementação (pacotes próprios vs lib de tablist) fica para o plano.**

### 4.6 Presença `player-server:{uuid}` e a coluna "servidor"

`player-server:{uuid}` (string, TTL ~30 s) é escrita por **cada servidor de jogo**
no `PlayerJoinEvent` (e refrescada no heartbeat) com o próprio `serverId` — chave
nova (master §8), inexistente hoje. Uso na Fase 10:
- **TAB**: coluna "servidor" de cada jogador. `tab:rankup.server` é escrito
  **write-through nas mutações** de rank/economy (Fases 8/9), então fica **defasado**
  quando o jogador só **troca de servidor** sem mutar saldo. Decisão: a coluna
  "servidor" da TAB usa **`player-server:{uuid}`** (verdade de presença) e cai em
  `tab:rankup.server` só como fallback.
- **Chat**: o `server` já vem no envelope do `player-chat` (servidor de origem da
  mensagem) — não depende de `player-server`. A presença serve para a TAB e para
  futuras features (mensagem direcionada, "onde está fulano").

Quem **escreve** `player-server` e o campo `server`/`nick` de `tab:rankup` é o
ponto de contato com o backend/plugins das Fases 8/9 (ver §10): idealmente cada
servidor de jogo escreve `player-server` no join (plugin-side, fail-open), sem
tocar o `rankup-service`.

## 5. Fluxo de dados

**Entrar no spawn (portal do hub)**
```
hub (lobby) → clique no item "Spawn" → plugin-message crystal:route "spawn" (backend→proxy)
  → RouteRequestListener → FleetRouter(spawn).route(player)
      pick() = spawn menos cheio (onlinePlayers + pending)  |  nenhum online → park + retry no sync
  → createConnectionRequest(spawn-XX)  (gate já permite: jogador autenticado)
  → no join do spawn: SET player-server:{uuid}=spawn-XX EX 30s; dá hotbar; monta scoreboard
```

**Scoreboard do spawn (tick ~1–2 s)**
```
timer → para cada jogador local:
  hgetAll rankup:{uuid}  → rankId/rankOrder/prestige/multiplier   (miss → TERRA/0)
  hgetAll economy:{uuid} → money/tokens                           (miss → 0/0)
  onlineCount (rede) + serverId
  rankId → prefixo via catálogo `rank` (config)
  → atualiza as linhas do board (team.prefix), flicker-free
```

**Chat global com formato novo**
```
Steve digita no spawn-01 → ChatListener:
  resolve cargo de rede (perm local) + lê rankId de rankup:{uuid}
  publish player-chat {scope:global, server:spawn-01, player:Steve, prefix:<cargo>, rankId:OURO, ...}
todos os servidores consomem → ChatService.broadcast:
  server_tag = label(spawn-01) = "[Spawn]"
  rank_prefix = catálogo rank[OURO].prefix = "[OURO]"
  linha = "[Spawn] [OURO] [VIP] Steve: msg"   (peças vazias colapsam)
```

**TAB global (tick)**
```
refresh → hgetAll tab:rankup (rede) + hgetAll tag:overrides (locais)
  header/footer (MiniMessage) + online (rede)
  para cada uuid em tab:rankup: nick + rank_prefix(rankId) + prestígio/money +
    servidor(player-server:{uuid} ?: tab:rankup.server)
  entradas remotas injetadas (pacotes/lib); locais via playerListName (cargo de rede resolvível)
```

**Regra Kafka × Redis (master §5/§8).** Scoreboard e TAB **não** consomem Kafka
por jogador — leem `rankup:{uuid}`/`economy:{uuid}`/`tab:rankup` no próprio tick.
`money/rank/prestige-updated` seguem como **fatos** (reações pontuais das Fases
8/9), fora do laço de render. Toda escrita que muda TAB/placar é **write-through**
(Postgres → Redis → Kafka) nas Fases 8/9; a Fase 10 só **lê**.

## 6. Tratamento de erros (fail-open, padrão do projeto)

- **Redis fora** (scoreboard/TAB): `hgetAll` em `try/catch` → mapa vazio → linhas
  neutras (`TERRA`/0/0), como o `crystal-tab` já trata `tag:overrides`. Render por
  jogador em `try/catch` com `warning`, nunca derruba o servidor (padrão do tab).
- **`rankup:{uuid}`/`economy:{uuid}` ausente** (jogador nunca mutou saldo/rank):
  trata como TERRA/0/0 — não é erro.
- **Catálogo `rank` ausente/corrompido** (config): prefixo do rank cai em `[rankId]`
  cru ou vazio; sem crash (fail-open como a `RankCatalog` da Fase 9).
- **Nenhum spawn online** (portal): `FleetRouter.route` **parqueia** o jogador
  (action bar "Procurando…") e drena no próximo `sync`/`SERVER_STARTED` — idêntico
  ao `waitingForLobby` do lobby. O jogador permanece no hub.
- **Plugin-message forjado** (canal `crystal:route`): só aceito de
  `ServerConnection` (backend), nunca de cliente — espelha o `AUTH_CHANNEL`.
- **`player-server:{uuid}` expirado/ausente**: coluna "servidor" da TAB cai em
  `tab:rankup.server`; se ambos faltarem, "—". Sem crash.
- **Kafka fora** (chat): `player-chat` não é entregue naquele instante; a operação
  local (o jogador vê a própria mensagem? — hoje o evento é **cancelado** e a
  entrega é 100 % via tópico) — registrar como risco: se o Kafka cair, o chat
  global para. Comportamento **idêntico ao atual** (a Fase 10 não muda o
  transporte), então não é regressão.

## 7. Propagação entre servidores

- **Scoreboard/TAB** refletem mudanças de rank/saldo em **~1–2 s** (o tick de
  leitura do Redis compartilhado), sem pub/sub por jogador — a mutação já foi
  write-through nas Fases 8/9. Trocar de rank em qualquer servidor aparece no
  placar de todos no próximo tick.
- **Chat** cruza a rede pelo `player-chat` (Kafka) já hoje; a Fase 10 só enriquece
  o envelope (`rankId`) e o formato — a propagação instantânea é a mesma.
- **Presença** (`player-server:{uuid}`) é escrita no join de cada servidor e lida
  no tick da TAB — um jogador que troca de servidor aparece no novo servidor em
  ≤ (TTL/refresh) segundos.
- **Roteamento**: novas instâncias `spawn` entram na registry e o
  `FleetRouter(spawn).sync()` (10 s / `SERVER_STARTED`) as registra no proxy sem
  mudança de config — mesma propriedade do fleet de lobby.
- **Edição de config** (`chatFormat`, catálogo `rank`, `chat.serverTags`) propaga
  por `config-updated` (Kafka) → hot-reload de chat/tab/scoreboard sem restart.

## 8. Testes

**Unitário (JVM puro, sem Bukkit)**
- `FleetRouterTest`: `pick()` escolhe o menos cheio contando `pending`; instância
  fora da registry é dropada; nenhum online → parqueia; mesma cobertura que o
  `LobbyRouter` implícito, agora parametrizada por `type`.
- Composição do chat: dado `server=spawn-01`, `rankId=OURO`, `prefix=[VIP]`,
  monta `[Spawn] [OURO] [VIP] Steve: …`; peças vazias colapsam; `rankId`
  desconhecido → prefixo vazio/cru.
- (Se extraível) resolução do prefixo do rank a partir do catálogo `rank` e do
  label de servidor a partir do tipo/id.

**Manual em jogo (CLAUDE.md)** — `make plugins` / `mvn -pl … package`, **recriar**
os containers (`docker compose up -d --force-recreate --no-deps spawn-01 spawn-02
proxy-01 lobby-01`), observar:
1. **Fleet least-loaded**: com `spawn-01` e `spawn-02` no ar, dois jogadores usam
   o portal do hub → **2/2** (um em cada). Derrubar `spawn-02` → o próximo vai
   para `spawn-01`; subir de novo → rebalanceia.
2. **Scoreboard ~1–2 s**: `/eco give <você> 1000` (Fase 8) num spawn → o saldo na
   sidebar atualiza em ~1–2 s; `/rankup` (Fase 9) sobe o rank e o placar reflete.
3. **Chat cross-server com prefixo**: mensagem de quem está no `spawn-01` aparece
   no `spawn-02` (e no lobby) como `[Spawn] [<rank>] [<cargo?>] Nome: msg`.
4. **TAB global**: a TAB de qualquer servidor lista **todos** os jogadores da rede
   (inclusive os em outro servidor) com nick/rank/prestígio/money/servidor.
5. **Hot-reload**: `PUT /api/config/chat` mudando o `chatFormat`/`serverTags` e
   `PUT /api/config/rank` mudando um prefixo → chat/tab/scoreboard refletem **sem
   restart**.

## 9. Arquivos afetados (resumo)

**NOVO `plugins/crystal-spawn/`** — `pom.xml` (shade do `crystal-core`),
`plugin.yml` (`/spawn`, permissões), `CrystalSpawnPlugin`,
`listener/SpawnScoreboard`, `listener/NavigationListener`,
`commands/SpawnCommand`, `gui/MineSelectorMenu` (+ hotbar de navegação). Registrar
o módulo em `plugins/pom.xml`.

**`plugins/crystal-bungee/`** — `LobbyRouter` → **`FleetRouter`** (parametrizado
por `type`); `CrystalBungeePlugin` instancia um router por tipo (`lobby`,`spawn`)
e agenda os `sync()`; **NOVO** `listener/RouteRequestListener` (canal
`crystal:route`); `ConnectionRoutingListener` passa a chamar `router(lobby)`.

**`plugins/crystal-tab/`** — `CrystalTabPlugin.refresh()` lê `tab:rankup`
(`hgetAll`) + catálogo `rank`; `TabRenderer` compõe rank_prefix/prestígio/money/
servidor; **injeção de entradas remotas** (pacotes/lib — ver §10).

**`plugins/crystal-chat/`** — `ChatListener` acrescenta `rankId` ao envelope;
`ChatService` compõe `[server_tag] [rank_prefix] [cargo] Nome` (novo `chatFormat`
+ `serverTags`), lê catálogo `rank` via `ConfigProvider`.

**`plugins/crystal-core/`** (mínimo) — constante(s) de chave nova(s)
(`player-server:` / `PlayerPresence`), se centralizadas como `TagOverrides`;
possível helper de leitura de `tab:rankup`. **Sem** mudança de backend/SDK HTTP.

**Infra** — `docker-compose.yml`: âncora `&spawn-env`, `spawn-01`/`spawn-02`
(montando `crystal-spawn`/`crystal-economy`/`crystal-rank`/`crystal-prestige` +
tab/chat/tag/worldinit), **remover** os volumes de economy/rank/prestige do
`lobby-01`; `world/WORLD_SPAWN/…schematic` (asset novo). **Sem** tópicos/chaves
novas no `create-topics.sh` (a Fase 10 lê o que as Fases 8/9 escrevem;
`player-server` é Redis, não Kafka).

## 10. Divergências do código atual (a reconciliar)

> **Decisões do dono (2026-07-01), já refletidas em §3/§4.4/§4.5:**
> (1) **TAB agregada por modo de jogo**, não por instância nem pela rede toda —
> lobby vê lobby; RankUP vê RankUP (qualquer instância). Hash Redis **por grupo**
> `tab:<group>` (mapa `tab.groups` por `SERVER_TYPE`). (2) **TAB mostra só o
> cargo**; o rank de jogo fica no scoreboard e no chat do RankUP. (3) **Chat**:
> cargo sempre; **rank só no modo RankUP**. (4) A **entrada da TAB é escrita pelo
> servidor atual do jogador** (cargo local + rank/money do Redis) → resolve cargo
> remoto e servidor defasado sem write-through das Fases 8/9. A **injeção
> cross-instância** (pacotes vs lib de tablist) segue como decisão do **plano**.

- **Depende das Fases 8/9, ainda não construídas.** Nem `rankup-service`, nem
  `crystal-economy`/`crystal-rank`/`crystal-prestige`, nem as chaves
  `economy:{uuid}`/`rankup:{uuid}`/`tab:rankup` existem no branch — Fases 8/9 estão
  **desenhadas, não implementadas**. A Fase 10 é o **leitor** dessas chaves e **só
  começa depois** das Fases 8/9 mergeadas. Se os campos de `tab:rankup` divergirem
  do assumido aqui (`nick,rankId,rankOrder,prestige,money,server`), reconciliar.
- **`crystal-tab` hoje NÃO lê hash Redis nem lista a rede.** Ele constrói a TAB a
  partir dos **jogadores locais** (`getOnlinePlayers()`) e do cargo de rede
  (`chat.roles`); só o **online count** é de rede (`onlineCount`). "TAB global
  listando todos" é **capacidade nova**: exige ler `tab:rankup` **e** **injetar
  entradas sintéticas** para jogadores de outros servidores — o `playerListName`
  do Paper só edita a entrada de quem está **conectado a este servidor**. Isso
  provavelmente precisa de manipulação de pacotes (`PlayerInfoUpdate`) ou de uma
  lib de tablist (não presente hoje). **Maior incógnita técnica da fase** — decidir
  no plano se a Fase 10 entrega a lista global completa ou só header/footer/rank
  dos locais + count global, empurrando a injeção remota para um passo seguinte.
- **`player-server:{uuid}` não existe em lugar nenhum.** Chave nova (master §8);
  hoje só há `online_players` (set) e `session:{uuid}`. Definir **quem escreve**
  (cada servidor de jogo no join, plugin-side, fail-open) e o TTL/refresh.
- **`tab:rankup.server` fica defasado.** As Fases 8/9 fazem write-through de
  `tab:rankup` **nas mutações** de rank/economy — não na **troca de servidor**.
  Logo a coluna "servidor" da TAB deve vir de `player-server:{uuid}` (presença),
  não do campo `server` do json. Alternativa: atualizar `tab:rankup.server` no
  join de cada servidor (custo extra de escrita). Decidir no plano.
- **Cargo de rede de jogadores remotos.** O cargo (`[VIP]`) é resolvido por
  **permissão no servidor do jogador**; para um jogador **remoto** a TAB não tem
  como resolvê-lo (sem LuckPerms local). Ou a TAB mostra só o **rank de jogo** para
  remotos, ou o cargo de rede precisa ser **carregado em `tab:rankup`** (campo novo
  escrito pelas Fases 8/9 ou pelo `crystal-tag`). Reconciliar — o master §5 lista
  `tab:rankup` com `{nick,rank,prestige,money,server}`, **sem** cargo de rede.
- **`LobbyRouter` é específico de lobby.** Hardcode `LOBBY_TYPE`/
  `name.startsWith("lobby")`; a generalização para `FleetRouter` por tipo é
  direta, mas o `CrystalBungeePlugin` hoje instancia **um** router e o
  `ConnectionRoutingListener` chama `routeToLobby` diretamente — ambos mudam para o
  modelo multi-router.
- **Não há portal/roteamento sob demanda hoje.** O único roteamento é
  proxy-iniciado após o auth handshake; não existe canal para "me mande ao spawn"
  de dentro do jogo. O canal `crystal:route` é **novo** (espelha o `AUTH_CHANNEL`),
  e o proxy precisa registrá-lo (`getChannelRegistrar().register(...)`), como já
  faz com o `crystal:auth`.
- **`crystal-worldinit` reusado sem mudança**, mas o asset `world/WORLD_SPAWN/…`
  (schematic do spawn) **não existe** — precisa ser produzido (como o
  `hub-lobby-medieval.schematic` do lobby). Marcar como **NOVO** (asset, não
  código).
- **Chat: `chatFormat` atual não tem `<server_tag>`/`<rank_prefix>`.** O
  `DEFAULT_FORMAT` é `"<prefix> <player_name><gray>:</gray> <message>"` (só cargo).
  A Fase 10 amplia o formato e faz o `crystal-chat` **também** ler o catálogo
  `rank` — hoje ele só lê `chat`. Sem isso, o `[TERRA]` não aparece.
