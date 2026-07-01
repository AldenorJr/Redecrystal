# Design — Fase 15: Arena PvP (instância única, cap ~200)

Data: 2026-07-01
Branch base: `feat/login-autocomplete-e-troca-senha`

> Fase do jogo **RankUP** sobre a rede RedeCrystal. Adiciona o **quarto tipo de
> servidor** — `arena` — como **instância única** (não fleet), alcançada por
> **conexão direta** pelo `crystal-bungee` com **cap ~200** ("arena cheia" quando
> lotada), e liga o **contexto `stat`** (`player_rankup_stats`) ao jogo: um **kill**
> dá **Money aditivo** (config-driven, escalado pelo multiplicador de prestígio) e
> incrementa `pvp_kills`/`pvp_deaths`. Arquitetura de alto nível em
> [`2026-07-01-rankup-arquitetura-design.md`](2026-07-01-rankup-arquitetura-design.md)
> (§2, §4, §6.3, §7.2, §10) — este spec detalha só o que a Fase 15 entrega. Texto de
> jogador em PT; identificadores/módulos/tópicos/chaves em inglês.

## 1. Problema

O RankUP precisa de uma **arena de PvP**: um lugar onde os jogadores lutam, ganham
**Money** por abate e acumulam **estatísticas** de kills/deaths que sobrevivem a
reconexão. Três lacunas concretas:

1. **Não existe o tipo de servidor `arena`.** Ao contrário de spawn/mina (fleets
   least-loaded da Fase 10) e terrenos (sticky da Fase 12), a arena é uma **única
   instância** (master §7.2): todos os jogadores lutam no **mesmo mundo**, até um
   **teto ~200**. Passado o teto, novas entradas são **negadas** com "arena cheia".
   O `crystal-bungee` hoje só sabe rotear least-loaded para o lobby — não tem
   conexão-direta-com-cap.

2. **O contexto `stat` (`player_rankup_stats`) ainda não é exercido para PvP.** A
   tabela guarda `blocks_mined`/`ores_mined`/`pvp_kills`/`pvp_deaths` (master §4); as
   duas primeiras colunas são alimentadas pela mineração (Fase 11), mas
   `pvp_kills`/`pvp_deaths` **nunca são escritas**. Falta o endpoint aditivo
   (`POST /api/stats/{uuid}`) ser usado, o facade no SDK e os listeners de PvP.

3. **Não há produtor real de Money por kill.** A economia (Fase 8) tem o write-path
   **aditivo** (`money+:delta`), mas ninguém o dispara num abate. A recompensa tem de
   ser **config-driven** e **escalada pelo multiplicador de prestígio** (Fase 9,
   master §9) — o mesmo `multiplier` que minas/plantações lêem.

A Fase 15 **reaproveita** intensamente: o `rankup-service` e o contexto `economy`
(Fase 8, write-path aditivo), o `EconomyClient`/`crystal.economy()` (Fase 8), o
`multiplier` de prestígio gravado em `rankup:{uuid}` (Fase 9), o `FleetRouter` do
`crystal-bungee` (Fase 10) e o `crystal-inventory` opt-in por tipo (já existe). Não
reconstrói economia nem descoberta de servidor.

## 2. Objetivos e não-objetivos

**Objetivos**
- **Tipo de servidor `arena`** — instância única (`replicas=1`, cap ~200), serviço
  `arena-01` no compose (`SERVER_TYPE=arena`, **sem** âncora de fleet).
- **Conexão direta + cap** no `crystal-bungee`: ramo `arena` do `FleetRouter` (Fase
  10) que conecta ao único `arena` online e **nega quando lotado** com a mensagem PT
  "arena cheia".
- **Contexto `stat`** no `rankup-service` (`api/application/domain`): endpoints
  `GET`/`POST /api/stats/{uuid}` (aditivo), exercendo `pvp_kills`/`pvp_deaths`. A
  tabela `player_rankup_stats` é **criada pela Fase 11** (dona do contexto `stat`); a
  Fase 15 **reusa** — ver §3 e §10.
- **Kill reward**: abate → `EconomyService`/`addMoney` **aditivo**, valor da config
  `arena` **escalado pelo `multiplier`** do algoz + incremento `pvp_kills` (algoz) /
  `pvp_deaths` (vítima).
- **SDK `crystal-core`**: `StatData`, `StatClient`, `crystal.stats()`, constante de
  chave/endpoint; reuso do `EconomyClient`/`PrestigeClient` existentes.
- **Plugin leve `crystal-arena`** (GUI-first): listeners de PvP (kill reward,
  morte/respawn), stub de seletor de kit / visão de stats por GUI. Reuso de
  `crystal-economy` (HUD/saldo) e `crystal-inventory` (loadout namespaced) na arena.
- **Rota** `/api/stats/**` no gateway; serviço `arena-01` no compose.

**Não-objetivos** (ficam para fases seguintes do master §10)
- Sistema completo de **kits** (categorias, cooldown, loja de kits) — a Fase 15
  entrega só um **kit padrão** + stub de seletor; kits ricos são polish da **Fase
  16**.
- **NPCs (Citizens)**, holos de ranking de kills, animações — **Fase 16**.
- Scoreboard/HUD dedicado de arena e **broadcast de killstreak** — reusa o HUD de
  economia (Fase 10); broadcasts ficam para a Fase 16.
- **Leaderboard de kills** (`leaderboard:pvp-kills`) — o master §8 não o prevê nesta
  fase; a Fase 15 grava só as stats no Postgres (via aditivo) sem sorted set novo.
- Regras de mundo da arena (regiões/kill-zones/anti-camping) e **matchmaking** —
  arena é **um mundo aberto único**, sem filas.

## 3. Decisões de design

| Tema | Decisão |
|------|---------|
| **Instância única vs fleet** | Arena **não** é fleet: `replicas=1`, **sem** âncora YAML `&arena-env`. O `crystal-bungee` **conecta direto** ao único `arena` online (não há "menos cheio" — só há um), aplicando **cap**. spawn/mina continuam least-loaded; terrenos sticky. (Master §7.2.) |
| **Enforcement do cap** | O teto vem do **`maxPlayers` já registrado** por `arena-01` na registry (o `NetworkServer` já expõe `maxPlayers`/`onlinePlayers` — **sem campo novo**). Ao rotear/conectar, se `onlinePlayers >= maxPlayers` → **nega** com "§cA arena está cheia. Tente novamente em instantes." A checagem também vive num gate `ServerPreConnectEvent` (como o gate de login), para barrar `/server arena` direto. |
| **Gatilho da entrada** | O **portal/NPC** que leva à arena mora no `crystal-spawn` (Fase 10/16); a Fase 15 expõe `FleetRouter.routeToArena(player)` e o gate de cap. Para **verificação** desta fase usa-se `/server arena` (jogador já autenticado). |
| **Dono de `player_rankup_stats`** | A tabela do contexto `stat` é **criada uma única vez pela Fase 11 (Minas)** — a primeira a tocar `stat` (`blocks_mined`/`ores_mined`), com o **schema completo das 4 colunas** (incl. `pvp_kills`/`pvp_deaths`). A Fase 15 **reusa** e só passa a **escrever** `pvp_kills`/`pvp_deaths`. **Nenhuma migração nova** de tabela na Fase 15. (Reconciliação em §10.) |
| **Endpoint de stats** | `GET /api/stats/{uuid}` (leitura) e `POST /api/stats/{uuid}` **aditivo** (`{blocksMined?,oresMined?,pvpKills?,pvpDeaths?}`, campos ausentes = +0) — write-path **aditivo** do master §4.2 (sem versão, sem 409), igual ao `addMoney` da Fase 8. |
| **Kill reward aditivo** | Abate → `addMoney` **aditivo** (`source="arena-kill"`), **não** débito: dar dinheiro é inofensivo a corrida (master §4.2). Valor = `config.arena.killReward` **× `multiplier`** do algoz. |
| **De onde vem o `multiplier`** | Lido do **Redis `rankup:{uuid}`** (hash gravado por write-through na Fase 9) — **sem HTTP por kill**. Miss/Redis fora → **fail-open `1.000`** (paga a base). Alternativa descartada: `crystal.prestige().get` por abate (round-trip a cada morte). |
| **Reward: quem calcula** | O **plugin `crystal-arena`** calcula `base × multiplier` e chama `addMoney` (aditivo). Mantém o backend genérico (o `EconomyService` não conhece "arena"); espelha o padrão "produtor no plugin dá flush aditivo" do master §4.2. |
| **Inventário: jogador leva o dele, dropa na morte, renasce vazio — DECIDIDO** | **Não** há kit padrão. O jogador **entra com o próprio inventário** (o que ele equipou/trouxe). Ao morrer: **dropa todos os itens** (`keepInventory=false`, loot cai no chão para quem catar), aparece a **tela de morte**, e no **respawn nasce no spawn do mundo da arena com o inventário vazio** — a perda do loot é o risco. O `crystal-inventory` opt-in (`arena.syncInventory=true`) mantém o inventário-arena **namespaced** e persistente entre sessões (o que ele trouxe fica salvo até morrer). O **respawn NÃO re-entrega kit**. |
| **Plugin novo `crystal-arena`** | Um plugin **leve, arena-only**, para os listeners de PvP (kill reward/morte/respawn) e a GUI stub. **Não** dobra em `crystal-economy` (que roda em **todo** tipo e deve ficar economia-only). Espelha "um plugin por tipo" (`crystal-spawn`/`crystal-mine`/`crystal-plot`). Diverge do master §2.1/§10, que não listou plugin de arena — ver §10. |
| **Sem tópico Kafka novo** | O master §5 **não** prevê tópico de PvP; `stat` é **consumidor** (de `money-updated`), não produtor. O kill reward reusa `money-updated` (emitido pela economia). Stats vão por **HTTP aditivo**. **Nenhum tópico novo** na Fase 15. |
| **Facade SDK por feature** | `StatClient` + `crystal.stats()` é o **4º facade** (após `economy()`/`ranks()`/`prestige()` das Fases 8/9), delegando à `BackendHttpClient`. |
| **Montagem** | `crystal-arena` + `crystal-economy` + `crystal-inventory` (+ tab/tag/chat/skin como nos demais) montados em **`arena-01`**. |

## 4. Arquitetura

### 4.1 Contexto `stat` (`api/application/domain`) — reuso da tabela da Fase 11

A tabela `player_rankup_stats` (`player_uuid` PK · `blocks_mined`/`ores_mined`/
`pvp_kills`/`pvp_deaths` BIGINT d0 · `updated_at`) é **criada pela Fase 11** (dona do
contexto `stat`). A Fase 15 adiciona o **serviço/endpoint** e passa a escrever as
colunas de PvP. Se, por ordenação de entrega, a Fase 15 chegar **antes** do trabalho
de `stat` da Fase 11, a migração `V<n>__rankup_stats.sql` (schema completo das 4
colunas) migra para o plano da Fase 15 — mas **uma única migração** cria a tabela,
nunca duas (§10).

**`domain/StatEntity`** (`@Table("player_rankup_stats")`): `playerUuid` (PK),
`blocksMined`, `oresMined`, `pvpKills`, `pvpDeaths` (long, d0), `updatedAt`.
Construtor de criação zera tudo.

**`domain/StatRepository`** (`JpaRepository<StatEntity, UUID>`) — write-path
**aditivo** (`@Modifying @Query`, molde do `addMoney` da Fase 8), um só UPDATE que
soma os deltas (ausente = 0), atômico, **sem versão**:

```java
@Modifying(clearAutomatically = true)
@Query("UPDATE StatEntity s SET s.blocksMined = s.blocksMined + :blocks, "
     + "s.oresMined = s.oresMined + :ores, s.pvpKills = s.pvpKills + :kills, "
     + "s.pvpDeaths = s.pvpDeaths + :deaths, s.updatedAt = CURRENT_TIMESTAMP "
     + "WHERE s.playerUuid = :uuid")
int addStats(UUID uuid, long blocks, long ores, long kills, long deaths);
```

**`application/StatService`** (no molde do `EconomyService`, sem Redis obrigatório —
stats não são lidas em loop de tick):

| Método | Comportamento |
|--------|---------------|
| `get(uuid)` | `findById` → cria zerado no `ensure` se ausente. |
| `ensure(uuid)` | cria linha zerada se ausente; retorna. |
| `add(uuid, blocks, ores, kills, deaths)` | `ensure` → `repository.addStats(...)` → re-`findById` (valor mutado, `clearAutomatically`) → retorna. |

**`api/StatController`** (`@RequestMapping("/api/stats")`, controller fino):

| Método/Path | Body → Resposta |
|---|---|
| `GET /{uuid}` | → `StatResponse{uuid,blocksMined,oresMined,pvpKills,pvpDeaths}` (cria zerado no ensure) |
| `POST /{uuid}` | `{blocksMined?,oresMined?,pvpKills?,pvpDeaths?}` (nulos = 0) → aditivo → `StatResponse` |

### 4.2 Kill reward (config `arena` + multiplicador de prestígio)

Config central **key `arena`** (via `PUT /api/config/{key}`, hot-reload como
`chat`/`parkour`; é **dado**, não código):

```json
{
  "killReward": 100
}
```

> **Sem kit** (decisão §3): o jogador leva o próprio inventário. A config `arena`
> guarda só o `killReward` (e o `syncInventory`/spawn se necessário). Uma futura
> loja de gear/kits pagos é polish da Fase 16.

O `crystal-arena` lê `ConfigProvider.get("arena")` + `onChange` (hot-reload). No
abate calcula `reward = round(killReward × multiplier)`, com `multiplier` lido de
`rankup:{uuid}` do algoz (Redis, escrito pela Fase 9; fail-open `1.000`), e chama
`crystal.economy().addMoney(killer, reward, "arena-kill")` **off-thread**. Isso
dispara o write-through de `economy:{uuid}` + `money-updated` (a economia já faz),
que o HUD (Fase 10) reflete.

### 4.3 `crystal-bungee` — ramo `arena` do `FleetRouter` + cap

O `FleetRouter` (generalização do `LobbyRouter` na Fase 10) já **descobre** todos os
tipos pela registry (registra `arena-01` no Velocity) e roteia por tipo. A Fase 15
adiciona o **ramo `arena`** (instância única, master §7.2):

- `routeToArena(Player)` — pega o **único** `arena` online (`listServers("arena")`,
  primeiro `isOnline`); se **nenhum** online → mensagem "arena indisponível"; se
  `onlinePlayers >= maxPlayers` → **nega** com "§cA arena está cheia…"; senão
  `createConnectionRequest` direto (sem least-loaded, sem `pending` smoothing).
- **Gate de cap** no `ServerPreConnectEvent` (ao lado do gate de login em
  `ConnectionRoutingListener`, ou num `ArenaConnectListener` coeso): se o alvo é o
  `arena` e está lotado, `denied()` + mensagem — barra `/server arena` direto além do
  `routeToArena`. Como o `LobbyRouter`, cai fail-open (registry fora → não bloqueia
  indevidamente; erro logado).

### 4.4 SDK `crystal-core`

- `http/StatData` (`record`): `StatData(String uuid, long blocksMined, long oresMined,
  long pvpKills, long pvpDeaths)`.
- Métodos na `BackendHttpClient` (estilo economy, via `send`): `getStats`
  (allowNotFound → zerado) e `addStats(uuid, blocks, ores, kills, deaths)`.
- `http/StatClient`: facade fino sobre a `BackendHttpClient`, exposto por
  `crystal.stats()` (`new StatClient(backend)` no `CrystalCore`), 4º facade após
  economy/ranks/prestige. Reusa `EconomyClient`/`PrestigeClient` já existentes (kill
  reward e leitura de multiplier).
- **Sem** tópico Kafka novo em `KafkaTopics` (§3).

### 4.5 Plugin `crystal-arena` (Paper, roda só na arena, GUI-first)

Espelha `crystal-economy`/`crystal-profile` (`pom.xml` com shade do `crystal-core` +
paper-api; `CrystalArenaPlugin` só boot+registro; `listener/` + `commands/` + `gui/`;
`plugin.yml` `api-version: '1.21'`). Registrar em `plugins/pom.xml`.

- `listener/ArenaCombatListener` (`PlayerDeathEvent`): **dropa os itens** da vítima
  (`setKeepInventory(false)`, `setKeepLevel(false)` — o loot cai no chão, vanilla);
  resolve algoz (`getKiller()`); off-thread lê o `multiplier` de `rankup:{uuid}` do
  algoz → `addMoney(killer, base×mult, "arena-kill")` **e** `addStats(killer,0,0,1,0)`;
  `addStats(victim,0,0,0,1)`. Sem algoz (queda/lava) → só `pvp_deaths` da vítima. Cada
  chamada é async, fail-open (falha logada, PvP nunca trava).
- `listener/ArenaSpawnListener` (`PlayerRespawnEvent`/`PlayerJoinEvent`): no respawn
  **teleporta ao spawn do mundo da arena** com o **inventário vazio** (NÃO re-entrega
  kit — decisão §3); a tela de morte é a vanilla. No primeiro join, o jogador chega
  com o inventário-arena que trouxe (sync namespaced).
- `gui/ArenaMenu` (GUI-first, espelha `BalanceMenu`): visão de **stats** do jogador
  (kills/deaths via `crystal.stats().get(uuid)` off-thread) + **stub de seletor de
  kit**; abre na main thread, cliques cancelados.
- `commands/ArenaCommand` (`/arena`): abre a `ArenaMenu`.

### 4.6 Inventário na arena (jogador leva o dele; sem kit)

`crystal-inventory` já sincroniza **por tipo** quando `<type>.syncInventory = true`
(ver `CrystalInventoryPlugin`/`InventorySyncListener`). A Fase 15 **liga** o flag para
`arena` (`arena.syncInventory = true`) — o inventário da arena é **namespaced**
(`arena`), isolado de spawn/lobby, e **persiste o que o jogador trouxe** entre
sessões. **Não** há kit padrão: o jogador **entra com o próprio inventário-arena** e,
ao morrer, **dropa tudo** e **renasce vazio** (§3/§4.5) — o sync salva o estado vazio
pós-morte. De onde o jogador tira gear para lutar (loja de gear, trazer de outro
servidor) é uma questão de **economia de itens** — a loja de gear pago fica para a
**Fase 16**; na Fase 15 ele usa o que trouxer.

### 4.7 Gateway + Compose

`api-gateway/application.yml` ganha a rota (mesmo formato das existentes):

```yaml
- id: rankup-stats
  uri: lb://rankup-service
  predicates:
    - Path=/api/stats/**
```

Sem mudança no `ServiceTokenAuthFilter` (já guarda todo `/api/**`).

`docker-compose.yml` ganha **`arena-01`** — **instância única**, `SERVER_TYPE=arena`,
**sem** âncora de fleet (ao contrário de `&lobby-env`), montando os jars
`crystal-arena` + `crystal-economy` + `crystal-inventory` (+ tab/tag/chat/skin como
nos lobbies), `depends_on: *mc-deps`, mundo VOID/FLAT (arena PvP tem mundo próprio —
`world/WORLD_ARENA`), `max-players` ~200 (env → `maxPlayers` registrado). K8s
(master §7.3): `Deployment replicas=1`.

## 5. Fluxo de dados

**Entrar na arena (conexão direta + cap)**
```
/server arena (ou portal do spawn) → FleetRouter.routeToArena / gate ServerPreConnect
  arena online?  não → "arena indisponível"
  onlinePlayers >= maxPlayers?  sim → denied() + "§cA arena está cheia…"
  senão → createConnectionRequest(arena-01)  (direto, sem least-loaded)
```

**Abate (drop + kill reward + stats + respawn vazio)**
```
PlayerDeathEvent (algoz A mata vítima V) → ArenaCombatListener:
  setKeepInventory(false) → itens de V DROPAM no chão (loot); tela de morte vanilla
  (off-thread):
    mult = rankup:{A}.multiplier  (Redis; fail-open 1.000)
    crystal.economy().addMoney(A, round(config.killReward × mult), "arena-kill")
        → EconomyService.addMoney (UPDATE aditivo) → economy:{A} write-through + money-updated
    crystal.stats().addStats(A, 0,0, 1,0)   (aditivo pvp_kills)
    crystal.stats().addStats(V, 0,0, 0,1)   (aditivo pvp_deaths)
    (sem algoz → só o addStats de V)
PlayerRespawnEvent (V) → ArenaSpawnListener:
  teleporta ao spawn do mundo da arena; inventário VAZIO (sem kit); sync salva o estado
```

**Regra Kafka × Redis (master §5/§8):** `money-updated` (do reward) é **fato** que o
HUD (Fase 10) reflete pelo Redis `economy:{uuid}`; as stats vão por **HTTP aditivo**
ao Postgres (não são lidas em loop de tick). Nenhuma escrita de PvP passa por Kafka.

## 6. Concorrência e tratamento de erros (fail-open, padrão do projeto)

- **Stats aditivas** (`pvp_kills`/`pvp_deaths`/`blocks`/`ores`): UPDATE atômico no
  banco, **sem versão, sem 409**; incrementos concorrentes somam corretamente.
- **Kill reward** (`addMoney`): aditivo atômico (Fase 8) — dar Money não corre risco
  de gasto duplo; sem 422/409.
- **Cap da arena**: checado por `onlinePlayers` da registry; **race benigna** — se
  dois entram no mesmo instante perto do teto, o excedente entra e o próximo tick de
  heartbeat corrige (cap "~200", não hard-limit transacional). Registry fora →
  fail-open (não bloqueia; erro logado), como o `LobbyRouter`.
- **Redis fora** (leitura do `multiplier`): fail-open `1.000` → paga a base; nunca
  trava o PvP.
- **HTTP fora** (plugin, kill reward/stats): `BackendException` off-thread, logado; o
  abate continua no jogo (recompensa/stat daquele kill é perdida — janela mínima,
  aceitável; batelada opcional reduz chamadas se o volume subir).
- **Kafka fora**: `EventPublisher` engole a falha (padrão do core) — o `addMoney`
  conclui.
- **Config `arena` ausente/corrompida**: `killReward` cai num default (ex.: 0 ou
  constante), kit vazio; sem crash.

## 7. Propagação entre servidores

- **Money/stats** mudam Postgres na hora; o saldo também vai a `economy:{uuid}`
  (Redis compartilhado), então o HUD (Fase 10) em qualquer servidor vê o novo saldo
  no próximo tick (~1–2s), sem pub/sub por jogador. Stats são lidas sob demanda
  (`GET /api/stats/{uuid}` na `ArenaMenu`), não em loop.
- **Multiplicador de prestígio** é escrito por write-through na Fase 9
  (`rankup:{uuid}`); a arena o **lê** — coerente na rede sem estado local.
- **Cap** é derivado da registry (heartbeats), compartilhada; o proxy vê a lotação
  atual sem config estática.
- **Edição da config `arena`** (`PUT /api/config/arena`) propaga por `config-updated`
  (Kafka, já instantâneo): `crystal-arena` recarrega `killReward`/kit — hot-reload sem
  restart.

## 8. Testes

**Unitário backend** (`StatServiceTest`, JUnit 5 + Mockito, mockando `StatRepository`,
no molde do `EconomyServiceTest` da Fase 8):
1. `add` chama `repository.addStats(...)` com os deltas certos e devolve o valor
   re-lido.
2. `add` só de `pvpKills=1` não altera `blocks/ores/deaths` (deltas 0).
3. `get` de jogador ausente → `ensure` cria zerado.

**Manual (curl + em jogo, CLAUDE.md)** contra o gateway com `Authorization: Bearer
<BACKEND_SERVICE_TOKEN>`:
- **curl** (determinístico): `GET /api/stats/{uuid}` cria zerado; `POST /api/stats/{uuid}
  {"pvpKills":1}` incrementa e `GET` confirma persistência; `POST` só com
  `{"pvpDeaths":2}` soma sem tocar kills.
- **em jogo** (subir `arena-01`, montar `crystal-arena`+`crystal-economy`+
  `crystal-inventory`, **recriar** o container):
  1. `/server arena` conecta direto; kit padrão entregue.
  2. Dois jogadores lutam; um kill dá **Money** ao algoz (`/saldo` sobe conforme
     `killReward × multiplier`) e incrementa `pvp_kills`/`pvp_deaths` (`/arena` mostra);
     **reconectar** preserva as stats.
  3. **Cap**: registrar `arena-01` com `maxPlayers` baixo (ex.: 2) para teste; a 3ª
     entrada é negada com "§cA arena está cheia…".
  4. **Hot-reload**: `PUT /api/config/arena` mudando `killReward` → o próximo kill usa
     o novo valor **sem restart**.

## 9. Arquivos afetados (resumo)

**Backend `backend/rankup-service/`** (módulo já existe desde a Fase 8):
- **NOVO** `stat/{api,application,domain}/…` (`StatController`, `StatService`,
  `StatEntity`, `StatRepository`).
- **Migração `player_rankup_stats`**: **de posse da Fase 11** (não criada aqui); a
  Fase 15 só a reusa. (Fallback de ordenação em §10.)
- `backend/api-gateway/src/main/resources/application.yml` (+ rota `stats`).

**SDK `plugins/crystal-core`:**
- **NOVO** `http/StatData`, `http/StatClient`; `http/BackendHttpClient` (+ `getStats`,
  `addStats`); `CrystalCore` (+ `stats()`).

**Plugins:**
- **NOVO** `plugins/crystal-arena/` (`pom.xml`, `plugin.yml`, `CrystalArenaPlugin`,
  `listener/{ArenaCombatListener,ArenaSpawnListener}`, `gui/ArenaMenu`,
  `commands/ArenaCommand`).
- `plugins/pom.xml` (+ módulo `crystal-arena`).
- `plugins/crystal-bungee` — `FleetRouter` (+ ramo `arena`: `routeToArena` + cap),
  `ConnectionRoutingListener` ou `ArenaConnectListener` (+ gate de cap no
  `ServerPreConnectEvent`).

**Infra/Compose:**
- `docker-compose.yml` (+ serviço **`arena-01`**, `SERVER_TYPE=arena`, `replicas=1`,
  `max-players` ~200, jars `crystal-arena`/`crystal-economy`/`crystal-inventory`;
  volume/mundo `WORLD_ARENA`).
- Config `arena` (dado, via `PUT /api/config/arena`): `killReward`, `kit`,
  `syncInventory: true` — seed via curl (§8), não arquivo de código.

## 10. Divergências do código atual (a reconciliar)

> **Decisões do dono (2026-07-01), refletidas em §3/§4.5/§4.6:** PvP **de loot**, não
> de kit — o jogador **leva o próprio inventário**, **dropa tudo ao morrer**
> (`keepInventory=false`), vê a tela de morte e **renasce no spawn da arena com o
> inventário vazio**. **Sem kit padrão** e **sem penalidade de Money** (a perda é o
> loot). Ponta a costurar na **Fase 16**: uma **loja de gear pago** para o jogador se
> equipar antes de lutar (senão só usa o que trouxer de fora).

- **Depende das Fases 8, 9, 10 (ainda não construídas).** A Fase 15 assume: da **Fase
  8** o `rankup-service` + `economy` (write-path aditivo `addMoney`,
  `EconomyClient`/`crystal.economy()`, mapeamento 422/409 no handler); da **Fase 9** o
  `multiplier` de prestígio gravado por write-through em `rankup:{uuid}`; da **Fase
  10** o `FleetRouter` (generalização do `LobbyRouter`) que **descobre a arena** e o
  padrão de reuso de `crystal-inventory` opt-in nos servidores de jogo. **Só começa
  depois dessas mergeadas.**
- **Dono de `player_rankup_stats` (crítico — evitar duas migrações).** O master §4
  lista `player_rankup_stats (stat)` separado de `mine_stats (mine)`; o roadmap
  atribui à **Fase 11** tocar `mine/stat` (blocks/ores). **Decisão: a Fase 11 (Minas)
  cria a tabela `player_rankup_stats` uma única vez, com o schema completo das 4
  colunas** (incl. `pvp_kills`/`pvp_deaths`, ainda não usadas lá). A Fase 15 **reusa**
  e só passa a escrever as colunas de PvP — **sem migração de tabela**. Se, por
  ordenação de entrega, a Fase 15 for implementada antes do trabalho de `stat` da Fase
  11, a migração (`V<n>__rankup_stats.sql`, número definido quando a fase dona landa)
  **migra para o plano da Fase 15**, mas **uma só** fase a cria. A Fase 11 alimenta
  `blocks_mined`/`ores_mined`; a Fase 15 alimenta `pvp_kills`/`pvp_deaths` — mesma
  tabela, contextos coexistindo, sem colisão de DDL.
- **Plugin `crystal-arena` novo (o master não o listou).** O master §2.1 enumera 7
  plugins **sem** arena, e o §10 Fase 15 fala só em `crystal-bungee` + **reuso** de
  `crystal-economy`/`crystal-inventory`. A Fase 15 **adiciona** um plugin leve
  `crystal-arena` para os listeners de PvP (kill/morte/respawn) e a GUI stub — dobrar
  isso em `crystal-economy` (que roda em **todo** tipo e deve ficar economia-only)
  violaria responsabilidade única. Espelha "um plugin por tipo de servidor"
  (`crystal-spawn`/`crystal-mine`/`crystal-plot`). **Registrar como 8º plugin.**
- **`crystal.stats()` é o 4º facade por feature.** Nasce da infra de facade da Fase 8
  (`economy()`); segue `ranks()`/`prestige()` (Fase 9). Delega à `BackendHttpClient`.
- **Sem tópico Kafka novo.** O master §5 não prevê tópico de PvP; `stat` é
  **consumidor** de `money-updated`, não produtor. A Fase 15 reusa `money-updated`
  (reward) e faz stats por HTTP aditivo — **nenhuma** entrada nova em
  `KafkaTopics`/`create-topics.sh`. (Se uma fase futura quiser reagir a kills —
  killstreak/achievements — aí entra um `player-killed`; fora do escopo.)
- **Cap por `maxPlayers` da registry (sem campo novo).** O `NetworkServer` já expõe
  `maxPlayers`/`onlinePlayers`; a Fase 15 reusa para o teto ~200 em vez de introduzir
  config/campo de cap dedicado. O cap é "~200" (correção por heartbeat), não um
  hard-limit transacional — coerente com o "cap ~200" do master §7.2.
- **`FleetRouter` ainda é `LobbyRouter` no branch.** Hoje só existe `LobbyRouter`
  (lobby-only). A generalização para `FleetRouter` por tipo é da **Fase 10**; a Fase
  15 assume-a e só **acrescenta o ramo `arena`** (conexão direta + cap). Se a Fase 10
  ainda não tiver landado, o ramo arena é implementado sobre o `LobbyRouter`
  generalizado como parte do pré-requisito.
