# Design — Fase 9: Ranks + Prestige (config-driven)

Data: 2026-07-01
Branch base: `feat/login-autocomplete-e-troca-senha`

> Segunda fase do jogo **RankUP** sobre a rede RedeCrystal. Sobre a fundação da
> **Fase 8** (`rankup-service` + contexto `economy`), adiciona os contextos
> **`rank`** (progressão TERRA…INFINITO) e **`prestige`** (reset + multiplicador),
> ambos **dirigidos por config central** (ladder editável a quente, como
> `chat`/`parkour`), mais os plugins de jogador `crystal-rank` (`/rankup`) e
> `crystal-prestige` (`/prestigio`). Arquitetura de alto nível em
> [`2026-07-01-rankup-arquitetura-design.md`](2026-07-01-rankup-arquitetura-design.md)
> (§1.1, §4, §5, §6.2/§6.3, §8, §10) — este spec detalha só o que a Fase 9 entrega.
> Texto de jogador em PT; identificadores/módulos/tópicos/chaves em inglês.

## 1. Problema

Depois da economia (Fase 8), o RankUP precisa da **progressão de jogo**: uma
**ladder de ranks** que o jogador sobe **gastando Money** — TERRA, PEDRA, COBRE,
FERRO, CARVÃO, LAPIS, REDSTONE, OURO, ESMERALDA, DIAMANTE, QUARTZO, OBSIDIANA,
RUBI, SAFIRA, TOPÁZIO, TITÂNIO, PLATINA, NETHERITA, DRAGÃO, LENDÁRIO, MÍTICO,
CELESTIAL, DIVINO, SUPREMO  — e, ao chegar no topo, um **prestígio** que
zera os ranks em troca de um **multiplicador permanente** e recompensas. Hoje não
há onde guardar o rank/prestígio de um jogador, nem como cobrar por uma promoção,
nem comando de jogador para comprar.

Três exigências específicas moldam o design:

1. **Rank de jogo ≠ cargo de rede** (master §1.1). O `[OURO]` do RankUP **não pode
   colidir** com o `[VIP]`/`[ADMIN]` da rede, que continua resolvido por permissão
   no `CargoResolver`/`chat.roles` e é o dono do **nametag**. Um jogador tem os
   dois ao mesmo tempo.
2. **Catálogo na config, não em tabela.** A ladder (nome/preço/prefixo/permissões/
   reward de cada degrau) vive na **config central** (`ConfigProvider`, key `rank`),
   editável a quente via `PUT /api/config/{key}` → `config-updated`, exatamente como
   `chat`/`parkour`. A tabela guarda só a **posição** do jogador.
3. **Compra atômica, sem gasto duplo.** Comprar rank é um **débito** (não pode
   debitar duas vezes) **seguido** de avanço de posição. As duas escritas têm de ser
   atômicas: se o avanço falhar, o débito volta.

A Fase 9 **reaproveita a Fase 8**: o mesmo `rankup-service`, o write-path de
**débito condicional** (`debit` → 422) da economia, e o padrão de facade do SDK
(`crystal.economy()`, `EconomyClient`, `InsufficientFundsException`). Como `rank` e
`economy` vivem **no mesmo serviço**, a cobrança da promoção é uma **chamada
in-process** ao `EconomyService`, não um round-trip HTTP do serviço para si mesmo.

## 2. Objetivos e não-objetivos

**Objetivos**
- Contexto `rank` no `rankup-service` (`api/application/domain`): tabela
  `player_rank`, migração `V2__rankup_rank.sql`, endpoints `/api/rank/**` do master
  §6.2, promoção atômica (débito in-process + avanço com optimistic-lock).
- Contexto `prestige`: tabela `player_prestige`, `V3__rankup_prestige.sql`,
  endpoints `/api/prestige/**` do master §6.3, gate no **último rank** da ladder.
- Catálogo `rank` (25 degraus) e config `prestige` na **config central**,
  hot-reload como `chat`/`parkour`. Documentar a forma JSON.
- Write-through Redis `rankup:{uuid}` (rankId/rankOrder/prestige/multiplier) e
  publicação Kafka `rank-updated`/`prestige-updated`.
- Infra: tópicos novos, rotas `/api/rank/**` e `/api/prestige/**` no gateway.
- SDK `crystal-core`: `RankData`/`PrestigeData`, `RankClient`/`PrestigeClient`,
  `crystal.ranks()`/`crystal.prestige()`, constantes de tópico/chave.
- Plugins `crystal-rank` (GUI `/rankup`) e `crystal-prestige` (GUI `/prestigio`),
  GUI-first, montados em `lobby-01` só para verificação da Fase 9.

**Não-objetivos** (ficam para fases seguintes do master §10)
- NPC de `/rankup` via Citizens — polish da **Fase 16**; a Fase 9 é GUI-first por
  comando (`/rankup`, `/prestigio`), sem NPC.
- Scoreboard/HUD/TAB com rank/prestígio e consumo de `rank-updated` no jogo — **Fase
  10** (spawn + `tab:rankup`). A Fase 9 já grava `rankup:{uuid}`/`tab:rankup`, mas o
  leitor (scoreboard/TAB) chega na Fase 10.
- Grant de **grupo LuckPerms** como efeito do rank — opcional, empurrado para a Fase
  16 (ver §3 e §4.7). A Fase 9 concede as `permissions[]` do rank como
  **PermissionAttachment** transiente, server-local.
- Servidores de jogo novos (spawn/mina/…): não existem ainda; `crystal-rank`/
  `crystal-prestige` vão em `lobby-01` só para exercer as GUIs (a partir da Fase 10
  migram para spawn).
- Produtores reais de Money (mineração/colheita/kill) — Fases 11/14/15; a Fase 9
  usa `/eco give` (Fase 8) para ter saldo e testar a compra.

## 3. Decisões de design

| Tema | Decisão |
|------|---------|
| **Prefixo de rank × cargo de rede** | O `[OURO]` **não** vira nametag. O nametag continua do `crystal-tag`/`CargoResolver` (cargo de rede). O prefixo do rank é **config-only**, lido de `rankup:{uuid}` + catálogo `rank`, e exibido na **GUI `/rankup`**, no scoreboard (Fase 10) e no holo (Fase 16). Rank e cargo coexistem (`[VIP] [OURO]`). |
| **Permissões do rank** | Concedidas como **efeito server-local** via **Bukkit `PermissionAttachment`** (transiente), reaplicado no join e no `rank-updated`, a partir de `permissions[]` do degrau. **Sem** escrita externa. Grant de grupo **LuckPerms** fica opcional/Fase 16 (LuckPerms hoje só é `depend`, sem uso da API — ver §4.7). |
| **Catálogo em config, não em tabela** | Ladder e prestígio vivem na config central (keys `rank` e `prestige`), hot-reload por `config-updated` como `chat`. `player_rank`/`player_prestige` guardam só a posição. (Master §4/§8.) |
| **Débito da promoção: in-process** | `rank` e `economy` estão no **mesmo** `rankup-service` → `RankService.promote` chama o **bean** `EconomyService.debit(...)` diretamente. Nada de HTTP do serviço para si. Fronteira preservada (contextos separados), sem round-trip. |
| **Atomicidade da compra** | `promote` é **`@Transactional`**: `debit` condicional (422 se falta Money) **e** avanço de `rank_order` com optimistic-lock (409 se `version` velha) commitam/rollback **juntos**. 409 no avanço → **estorna** o débito. Eventos (`money-updated`/`rank-updated`) e write-through só **após** o commit. |
| **Gate do prestígio** | `POST /api/prestige/{uuid}` exige `rank_order == último da ladder` (INFINITO). Fora do topo → **422**. Ao prestigiar: reset para TERRA/order 0, `prestige+1`, `multiplier += incremento` da config, reward; emite `prestige-updated` **e** `rank-updated` (o rank voltou a TERRA). |
| **Preço autoritativo no servidor** | O plugin manda só o `uuid`; o **`rankup-service` lê o preço do próximo degrau da config** (não confia no cliente). Catálogo lido do Redis compartilhado `config:rank` (write-through do `ConfigService`), com fallback HTTP a `GET /api/config/rank` no miss frio (ver §4.6). |
| **Facade SDK por feature** | `RankClient`/`PrestigeClient` + `crystal.ranks()`/`crystal.prestige()`, seguindo o **primeiro** facade (Fase 8: `EconomyClient`/`crystal.economy()`). Delegam à `BackendHttpClient` (reusa retry/auth). |
| **Config-driven na GUI** | `crystal-rank` lê a ladder via `ConfigProvider.get("rank")` + `onChange` (como `crystal-chat` lê `chat`) → GUI hot-reload sem restart. |
| **Montagem na Fase 9** | `crystal-rank` e `crystal-prestige` no **`lobby-01`** só para verificação (como `crystal-economy` na Fase 8); migram para spawn na Fase 10. |

## 4. Arquitetura

### 4.1 Catálogo na config central — keys `rank` e `prestige`

Duas configs novas, geridas pelo `ConfigService` existente do `core-service`
(`GET`/`PUT /api/config/{key}` → cache Redis `config:{key}` + `config-updated`),
sem código de backend novo para configs. Ambas são **hot-reload** para o plugin
(`ConfigProvider.onChange`) e **read-through** para o serviço (§4.6).

**Key `rank`** — a ladder. Cada degrau: `id`, `order` (0 = TERRA, inicial),
`name` (PT, para GUI), `price` (Money; TERRA = 0, nunca comprado), `prefix`
(MiniMessage/legacy, config-only), `permissions[]` (nós concedidos como efeito),
`reward` (`tokens` + `commands[]` executados no console ao promover):

```json
{
  "rungs": [
    { "id": "TERRA",  "order": 0, "name": "Terra",  "price": 0,
      "prefix": "<gray>[TERRA]</gray>",  "permissions": [], "reward": { "tokens": 0, "commands": [] } },
    { "id": "PEDRA",  "order": 1, "name": "Pedra",  "price": 5000,
      "prefix": "<white>[PEDRA]</white>", "permissions": ["rankup.rank.pedra"],
      "reward": { "tokens": 5, "commands": ["broadcast %player% subiu para Pedra!"] } },
    { "id": "OURO",   "order": 7, "name": "Ouro",   "price": 250000,
      "prefix": "<gold>[OURO]</gold>",   "permissions": ["rankup.rank.ouro", "rankup.mine.ouro"],
      "reward": { "tokens": 50, "commands": [] } }
  ]
}
```

O plugin resolve o degrau atual/próximo por `order`; o `price` cobrado é o do
**degrau alvo** (`order == atual+1`).

**Key `prestige`** — parâmetros do prestígio (separado de `rank` para editar
independente; um key por conceito, como `chat`/`parkour`):

```json
{
  "multiplierIncrement": 0.100,
  "maxPrestige": 100,
  "reward": { "tokens": 500, "commands": ["broadcast %player% atingiu o Prestígio %prestige%!"] }
}
```

`multiplierIncrement` soma ao `multiplier` (d1.000) a cada prestígio;
`multiplier` é o campo que Fases 11/14/15 lêem para escalar ganhos.

### 4.2 Contexto `rank` (`api/application/domain`)

**`domain/RankEntity`** (`@Table("player_rank")`): `playerUuid` (PK), `rankId`
(String, d`TERRA`), `rankOrder` (int, d0), `version` (int, d0), `updatedAt`.
Construtor de criação zera em TERRA/0. Método `promoteTo(rankId, order)` (bump de
`version` + `updatedAt`), no estilo de `InventoryEntity.update`.

**`domain/RankRepository`** (`JpaRepository<RankEntity, UUID>`) — avanço atômico
com optimistic-lock **manual** (compara `version`, como o `InventoryService`, não
`@Version` JPA), no molde do `@Modifying @Query` introduzido na Fase 8:

```java
@Modifying(clearAutomatically = true)
@Query("UPDATE RankEntity r SET r.rankId = :rankId, r.rankOrder = :order, "
     + "r.version = r.version + 1, r.updatedAt = CURRENT_TIMESTAMP "
     + "WHERE r.playerUuid = :uuid AND r.version = :expectedVersion")
int advance(UUID uuid, String rankId, int order, int expectedVersion);  // 0 linhas = 409
```

**`application/RankCatalog`** — lê a config `rank` do Redis compartilhado
`config:rank` (StringRedisTemplate, como o `ConfigService` faz cache), desserializa
os `rungs`, expõe `current(order)`/`next(order)`/`all()`. Fail-open com fallback
HTTP no miss frio (§4.6).

**`application/RankService`** (Redis + `EconomyService` in-process + `EventPublisher`):

| Método | Comportamento |
|--------|---------------|
| `get(uuid)` | `findById` → cria TERRA/0 no `ensure` se ausente. |
| `catalog()` | `RankCatalog.all()` (para `GET /api/rank`). |
| `promote(uuid)` | **`@Transactional`**: `ensure` → lê `next = catalog.next(current.order)` (nenhum → 409/`ConflictException` "já no topo", ou 422 dedicado); `economyService.debit(uuid, next.price)` → 0 linhas = `InsufficientFundsException` (**422**, rollback); `repository.advance(uuid, next.id, next.order, current.version)` == 0 → `ConflictException` (**409**, rollback, estorna débito). Após commit: write-through `rankup:{uuid}` + `EventPublisher` `money-updated` **e** `rank-updated`. Reward (`tokens`+`commands`) aplicado pós-commit (tokens via `economyService.addTokens`; `commands` **não** rodam no backend — vão no payload do `rank-updated` para o plugin executar no console, server-local). |

`cache(e)` (fail-open, `try/catch` como `EconomyService.cache`): `HSET
rankup:{uuid} rankId/rankOrder` + `EXPIRE 10min`; e `HSET tab:rankup <uuid> <json>`
(escrito agora, consumido na Fase 10). Falha de Redis → só `log.warn`.

**`api/RankController`** (`@RequestMapping("/api/rank")`, controller fino):

| Método/Path | Body → Resposta |
|---|---|
| `GET /` | catálogo da config → `List<RankRungResponse>` |
| `GET /{uuid}` | `RankResponse{uuid,rankId,rankOrder,version}` (cria TERRA no ensure) |
| `POST /{uuid}/promote` | (sem body) → `RankResponse` novo; **422** sem Money, **409** concorrência/topo |

### 4.3 Contexto `prestige` (`api/application/domain`)

**`domain/PrestigeEntity`** (`@Table("player_prestige")`): `playerUuid` (PK),
`prestige` (int, d0), `multiplier` (`NUMERIC(6,3)`/`BigDecimal`, d1.000), `version`
(int, d0), `updatedAt`. Método `bump(increment)` (prestige+1, multiplier+=inc,
bump version).

**`application/PrestigeService`** (`@Transactional`, chama `RankService`/
`RankRepository` in-process para o reset + `EconomyService.addTokens` para reward):

| Método | Comportamento |
|--------|---------------|
| `get(uuid)` | `findById` → cria d0/1.000 no ensure. |
| `promote(uuid)` | **`@Transactional`**: carrega rank atual; se `rank_order != último da ladder` → `InsufficientFundsException`-análogo? **não** — usa uma exceção 422 semântica (ver §6). No topo: `rankRepository.advance(uuid, "TERRA", 0, rank.version)` (reset; 409 se stale) + `prestige.bump(config.multiplierIncrement)` (respeita `maxPrestige`; save com optimistic-lock → 409). Pós-commit: write-through `rankup:{uuid}` (prestige/multiplier/rankId/rankOrder) + `prestige-updated` **e** `rank-updated`; reward (tokens + commands no payload). |

**`api/PrestigeController`** (`@RequestMapping("/api/prestige")`):

| Método/Path | Body → Resposta |
|---|---|
| `GET /{uuid}` | `PrestigeResponse{uuid,prestige,multiplier,version}` |
| `POST /{uuid}/promote` | (sem body) → reset+bump; **422** se não está no último rank; **409** concorrência |

> Nota: o master §6.3 lista `POST /api/prestige/{uuid}`. Padronizamos com o rank
> usando `POST /api/prestige/{uuid}/promote` (mais explícito; a rota do gateway
> cobre `/api/prestige/**` de qualquer forma). Registrar em §9 como divergência
> menor de path.

### 4.4 `shared/` do `rankup-service` (Fase 8) — extensões

Reusa o `shared/` que a Fase 8 criou no `rankup-service`:
- `shared/messaging/KafkaTopics`: `+ RANK_UPDATED="rank-updated"`,
  `PRESTIGE_UPDATED="prestige-updated"`.
- `shared/web/ApiExceptionHandler`: já ganhou o mapeamento **422**
  (`InsufficientFundsException`) e **409** (`ConflictException`) na Fase 8 — a Fase 9
  só reusa. Para o gate de prestígio, uma exceção 422 semântica (§6).

### 4.5 Gateway

`api-gateway/application.yml` ganha duas rotas (mesmo formato das existentes):

```yaml
- id: rankup-rank
  uri: lb://rankup-service
  predicates:
    - Path=/api/rank/**
- id: rankup-prestige
  uri: lb://rankup-service
  predicates:
    - Path=/api/prestige/**
```

Sem mudança no `ServiceTokenAuthFilter` (já guarda todo `/api/**`).

### 4.6 Leitura do catálogo pelo serviço (config → rankup-service)

A promoção precisa do preço **autoritativo** (não do cliente). O catálogo `rank`
está no `core-service`. Para não acoplar via HTTP a cada compra, o `rankup-service`
lê o catálogo do **Redis compartilhado** `config:rank` (mesma chave que o
`ConfigService.upsert`/`get` do core escreve por write-through) via
`StringRedisTemplate`, desserializando os `rungs`. No **miss frio** (config nunca
lida/gravada ainda), faz um `GET /api/config/rank` de fallback pelo gateway com o
service token, aquecendo o cache. Read-through, fail-open. É o **primeiro consumidor
de config fora do core-service** — registrar em §9.

### 4.7 SDK `crystal-core`

- `messaging/KafkaTopics`: `+ RANK_UPDATED`, `PRESTIGE_UPDATED` (e na lista `ALL`,
  para os consumidores broadcast da Fase 10).
- `http/RankData` (`record`): `RankData(String uuid, String rankId, int rankOrder, int version)`.
- `http/PrestigeData` (`record`): `PrestigeData(String uuid, int prestige, double multiplier, int version)`.
- `http/RankRung` (`record`): degrau do catálogo (`id, order, name, price, prefix,
  List<String> permissions`) para a GUI/`GET /api/rank`.
- Métodos na `BackendHttpClient` (estilo profile/economy, via `send`): `getRank`
  (allowNotFound→null), `rankCatalog()`, `promoteRank(uuid)`, `getPrestige`,
  `promotePrestige(uuid)`. `promote*` propagam **422**
  (`InsufficientFundsException`, já introduzida na Fase 8) e **409**
  (`BackendException.statusCode()==409`).
- `http/RankClient` e `http/PrestigeClient`: facades finos sobre a
  `BackendHttpClient`, expondo a chave `RANKUP_KEY_PREFIX="rankup:"` e os campos do
  hash (`rankId`,`rankOrder`,`prestige`,`multiplier`). `CrystalCore` ganha
  `ranks()`/`prestige()` (`new RankClient(backend)` / `new PrestigeClient(backend)`),
  seguindo o `economy()` da Fase 8.

### 4.8 Plugin `crystal-rank` (`/rankup`, GUI-first)

Espelha `crystal-profile`/`crystal-parkour` (`pom.xml` com shade do `crystal-core` +
paper-api; `Crystal…Plugin` só boot+registro; `commands/` + `gui/` + `listener/`).
Lê a ladder de `ConfigProvider.get("rank")` + `onChange` (hot-reload da GUI).

- `gui/RankUpMenu` (espelha `ParkourTopMenu`): busca `crystal.ranks().get(uuid)` e o
  saldo `crystal.economy().get(uuid)` **off-thread**; monta itens (rank atual;
  próximo rank + preço; ladder completa) e **abre na main thread**; cliques
  cancelados exceto o botão "comprar próximo".
- `commands/RankUpCommand` (`/rankup`): abre a `RankUpMenu`.
- Compra: ao clicar "comprar", chama `crystal.ranks().promote(uuid)` **off-thread**;
  `InsufficientFundsException` → "§cVocê não tem Money suficiente."; 409/topo →
  "§eVocê já está no rank máximo." ou "§cTente novamente."; sucesso → feedback de
  progresso + reabre a GUI no novo rank.
- `listener/RankEffectListener`: no `PlayerJoinEvent` e ao consumir `rank-updated`
  (do próprio jogador), lê o rank de `rankup:{uuid}`/`RankClient`, resolve o degrau
  no catálogo e **aplica as `permissions[]`** via `PermissionAttachment` transiente;
  executa os `commands` do reward no console; **não** toca o nametag (é do
  `crystal-tag`). `onQuit`: remove o attachment.

### 4.9 Plugin `crystal-prestige` (`/prestigio`, GUI-first)

Espelha `crystal-rank`. Lê `prestige` config via `ConfigProvider`.

- `gui/PrestigeMenu`: busca `crystal.prestige().get(uuid)` + `crystal.ranks().get(uuid)`
  off-thread; **só habilita** o botão "prestigiar" quando `rankOrder == último da
  ladder`; senão mostra o progresso restante até o topo. Confirmação antes de
  resetar.
- `commands/PrestigeCommand` (`/prestigio`): abre a `PrestigeMenu`.
- Confirmar: `crystal.prestige().promote(uuid)` off-thread; 422 (não está no topo) →
  "§cVocê precisa chegar em INFINITO antes de prestigiar."; sucesso → mensagem de
  reset + novo multiplicador.

### 4.10 `plugin.yml` (exemplo `crystal-rank`)

```yaml
name: CrystalRank
main: com.redecrystal.rank.CrystalRankPlugin
api-version: '1.21'
author: RedeCrystal
description: Ranks RankUP (TERRA…INFINITO) — GUI /rankup, config-driven.
commands:
  rankup:
    description: Abrir a GUI de ranks e comprar o próximo
    usage: /rankup
```

`crystal-prestige` análogo (`prestigio`). Sem `depend: [LuckPerms]` na Fase 9 (não
usa a API; permissões via Bukkit). Registrar ambos em `plugins/pom.xml`
(`<modules>`).

## 5. Fluxo de dados

**Comprar rank (`/rankup`)**
```
/rankup → RankUpMenu (off-thread: ranks().get + economy().get) → abre GUI (main)
  clique "comprar" → ranks().promote(uuid) → POST /api/rank/{uuid}/promote
   → RankService.promote  @Transactional:
       next = RankCatalog.next(current.order)         (preço autoritativo da config)
       EconomyService.debit(uuid, next.price)  → 0 linhas = 422 (rollback, nada muda)
       RankRepository.advance(uuid,next.id,next.order,current.version) → 0 linhas = 409 (rollback, estorna)
   → commit → write-through rankup:{uuid} + tab:rankup
   → EventPublisher money-updated {uuid,money,delta,source="rankup"}  (key=uuid)
                     rank-updated  {uuid,rankId,rankOrder,reward:{tokens,commands}}
  ← RankResponse → plugin reabre GUI; RankEffectListener aplica permissions[]+commands
```

**Prestigiar (`/prestigio`, no topo)**
```
/prestigio → PrestigeMenu (habilita só se rankOrder==último) → confirmar
  → prestige().promote(uuid) → POST /api/prestige/{uuid}/promote
   → PrestigeService.promote @Transactional:
       rank_order != último → 422 (nada muda)
       RankRepository.advance(uuid,"TERRA",0,rank.version)   (reset; 409 se stale)
       prestige.bump(config.multiplierIncrement)             (409 se stale)
   → commit → write-through rankup:{uuid}
   → prestige-updated {uuid,prestige,multiplier,reward} + rank-updated {uuid,"TERRA",0}
```

**Regra Kafka × Redis (master §5/§8):** `rank-updated`/`prestige-updated` são
**fatos** (Fase 10+ reage: scoreboard/TAB/broadcast). O estado quente lido em loop
(rank no scoreboard/HUD) virá do **Redis** `rankup:{uuid}`, não do Kafka por
jogador. Toda mutação é **write-through** (Postgres → Redis → Kafka).

## 6. Tratamento de erros (fail-open, padrão do projeto)

- **422 (sem Money)**: `debit`==0 → `InsufficientFundsException` (Fase 8) →
  rollback da transação (rank **não** avança) → plugin mostra "Money insuficiente".
- **422 (fora do topo, prestígio)**: gate em `PrestigeService` — exceção 422
  semântica (nova subclasse leve de `BackendException`/`ResponseStatusException`
  mapeada a 422 no `ApiExceptionHandler`, ou reuso pragmático de
  `InsufficientFundsException`; **decisão de nomenclatura**, ver §9). Plugin: "chegue
  em INFINITO primeiro".
- **409 (concorrência/topo)**: `advance`==0 (version velha **ou** já no último rank)
  → `ConflictException` → rollback total (estorna débito) → plugin: "tente
  novamente"/"rank máximo".
- **Redis fora**: write-through de `rankup:{uuid}` e leitura do catálogo são
  `try/catch` → `log.warn`; catálogo cai no fallback HTTP; leitura de rank do plugin
  cai no HTTP `GET /api/rank/{uuid}`. Nada quebra.
- **Kafka fora**: `EventPublisher` engole a falha (padrão do core) — a compra
  conclui; o efeito de permissão/prefixo é reaplicado no próximo join de qualquer
  forma.
- **HTTP fora (plugin)**: `BackendException` de transporte → "indisponível", nunca
  trava a main thread (chamada é async).
- **Config ausente/corrompida**: `RankCatalog` fail-open → catálogo vazio →
  `next()` nulo → promoção responde 409/erro claro, sem crash; GUI mostra "ladder
  indisponível".

## 7. Propagação entre servidores

- **Compra/prestígio** mudam Postgres + `rankup:{uuid}` (Redis compartilhado) na
  hora; qualquer servidor que leia `rankup:{uuid}` (scoreboard/TAB da Fase 10) vê o
  novo rank no seu próximo tick (~1–2s), sem pub/sub por jogador. O `rank-updated`
  (Kafka) é o gatilho para reações (broadcast/animação) nas fases seguintes.
- **Efeito de permissão/prefixo** é **server-local** (PermissionAttachment); cada
  servidor onde o jogador entra reaplica lendo `rankup:{uuid}`/catálogo — coerente
  em toda a rede sem estado externo.
- **Edição da ladder** (`PUT /api/config/rank`) propaga por `config-updated`
  (Kafka, já instantâneo): plugins recarregam a GUI e o serviço relê `config:rank` —
  **hot-reload sem restart**, como `chat`/`parkour`.

## 8. Testes

**Unitário backend** (`RankServiceTest`/`PrestigeServiceTest`, JUnit 5 + Mockito,
mockando repos + `EconomyService` + `StringRedisTemplate` + `EventPublisher`, no
molde do `EconomyServiceTest` da Fase 8):
1. `promote` com Money → `debit` retorna 1, `advance` retorna 1 → rank sobe um
   degrau, `rank-updated` emitido.
2. `promote` sem Money → `debit` retorna 0 → 422 e rank **não** avança (rollback).
3. `promote` com `version` velha → `advance` retorna 0 → 409 e **débito estornado**
   (nada persistido).
4. `promote` no último degrau → `next()` nulo → erro claro (não avança).
5. `PrestigeService.promote` **fora** do topo → 422, sem reset.
6. `PrestigeService.promote` **no** topo → rank reseta para TERRA/0, `prestige+1`,
   `multiplier += increment`, `prestige-updated`+`rank-updated`.
7. `RankCatalog` desserializa `config:rank` do Redis e resolve `next(order)`.

**Manual (curl + em jogo, CLAUDE.md)** contra o gateway com `Authorization: Bearer
<BACKEND_SERVICE_TOKEN>`:
- **curl** (determinístico): `PUT /api/config/rank` com a ladder de exemplo;
  `GET /api/rank` confirma o catálogo; `GET /api/rank/{uuid}` cria TERRA;
  `POST /api/rank/{uuid}/promote` sem Money → **422**; `POST /api/economy/{uuid}/money`
  (Fase 8) dá Money e `promote` → 200 sobe um rank; `GET` confirma; `rank-updated`
  no Kafka UI. `POST /api/prestige/{uuid}/promote` fora do topo → **422**.
- **em jogo** (montar `crystal-rank`+`crystal-prestige` no `lobby-01`, **recriar** o
  container): `/eco give <você> 1000000`; `/rankup` abre a GUI, comprar deduz Money e
  sobe o rank (prefixo/permissão aplicados); `/rankup` sem saldo → "Money
  insuficiente"; subir até INFINITO → `/prestigio` habilita, confirmar reseta para
  TERRA e sobe o multiplicador. **Hot-reload**: `PUT /api/config/rank` mudando um
  preço → a GUI reflete **sem restart**.

## 9. Arquivos afetados (resumo)

**Backend `backend/rankup-service/`** (o módulo já existe desde a Fase 8):
- **NOVO** `rank/{api,application,domain}/…` (`RankController`, `RankService`,
  `RankCatalog`, `RankEntity`, `RankRepository`), `prestige/{api,application,domain}/…`.
- `shared/messaging/KafkaTopics` (+ `RANK_UPDATED`, `PRESTIGE_UPDATED`).
- **NOVO** `db/migration/V2__rankup_rank.sql`, `V3__rankup_prestige.sql`.
- **NOVO** testes `RankServiceTest`, `PrestigeServiceTest`.
- `backend/api-gateway/src/main/resources/application.yml` (+ rotas `rank`/`prestige`).
- `infra/kafka/create-topics.sh` (+ `rank-updated`, `prestige-updated`).

**SDK `plugins/crystal-core`:**
- `messaging/KafkaTopics` (+ tópicos), **NOVO** `http/RankData`, `http/PrestigeData`,
  `http/RankRung`, `http/RankClient`, `http/PrestigeClient`; `http/BackendHttpClient`
  (+ métodos rank/prestige); `CrystalCore` (+ `ranks()`/`prestige()`).

**Plugins:**
- **NOVO** `plugins/crystal-rank/` (`pom.xml`, `plugin.yml`, `CrystalRankPlugin`,
  `gui/RankUpMenu`, `commands/RankUpCommand`, `listener/RankEffectListener`).
- **NOVO** `plugins/crystal-prestige/` (`pom.xml`, `plugin.yml`, `CrystalPrestigePlugin`,
  `gui/PrestigeMenu`, `commands/PrestigeCommand`).
- `plugins/pom.xml` (+ 2 módulos).

**Infra/config de dados:** as configs `rank`/`prestige` são **dados** (via
`PUT /api/config/{key}`), não arquivos de código — seed inicial via curl (§8).

**Compose:** `docker-compose.yml` monta os jars `crystal-rank`/`crystal-prestige` no
`lobby-01` (volumes, como `crystal-economy` na Fase 8), **recriar** o container.

## 10. Divergências do código atual (a reconciliar)

- **Depende da Fase 8, ainda não implementada.** Nem `backend/rankup-service/` nem
  `plugins/crystal-economy/` existem no branch — a Fase 8 está **desenhada, não
  construída**. A Fase 9 assume, da Fase 8: o módulo `rankup-service` com
  `shared/{messaging,web}` (incl. mapeamento **422** e `ConflictException` no
  handler), o `EconomyService.debit` (débito condicional), a
  `InsufficientFundsException` no SDK, e o facade `crystal.economy()`. **A Fase 9 só
  começa depois da Fase 8 mergeada.**
- **`crystal.ranks()`/`crystal.prestige()` são o 2º/3º facade por feature.** Hoje o
  `CrystalCore` expõe `backend()`/`configProvider()`/`redis()`/… mas **nenhum**
  facade por feature; o `economy()` (Fase 8) é o primeiro. O master §6.4 fala de
  `crystal.ranks()` como se já houvesse infra de facade — na verdade ela nasce na
  Fase 8. (O master também escreve `crystal.config()`; o código real é
  `configProvider()`.)
- **`rankup-service` como consumidor de config.** O master assume o preço lido "da
  config" no `promote`, mas o `ConfigService`/`ConfigProvider` moram no
  `core-service`/SDK. A Fase 9 introduz o **primeiro consumidor de config no
  `rankup-service`** (read-through de `config:rank` no Redis compartilhado + fallback
  HTTP). Alternativa considerada e descartada: passar o preço do plugin (inseguro,
  spoofável).
- **422 semântico do prestígio.** O gate "não está no topo" não é "saldo
  insuficiente". O handler da Fase 8 mapeia `InsufficientFundsException`→422; a Fase
  9 precisa de um 422 **semanticamente distinto** (ex.: `PreconditionFailedException`
  ou `ResponseStatusException(UNPROCESSABLE_ENTITY)`). **Decisão de nomenclatura a
  fechar no plano** — reusar a exceção da Fase 8 é pragmático mas semanticamente
  impreciso.
- **Path do prestígio.** Master §6.3 lista `POST /api/prestige/{uuid}`; padronizamos
  para `POST /api/prestige/{uuid}/promote` (simetria com rank). A rota do gateway
  (`/api/prestige/**`) cobre ambos.
- **Prefixo do rank não vira nametag.** O master §1.1 admite "conceder grupo
  LuckPerms como efeito"; na prática LuckPerms hoje é só `depend` sem uso da API
  (tudo é `player.hasPermission`). A Fase 9 opta por **prefixo config-only** (GUI/
  scoreboard/holo) + **permissões via `PermissionAttachment`**, deixando o
  grupo-LuckPerms como opção da Fase 16. `crystal-rank`/`crystal-prestige`, por isso,
  **não** declaram `depend: [LuckPerms]`.
- **`tab:rankup` escrito antes do leitor.** A Fase 9 já faz write-through de
  `tab:rankup`/`rankup:{uuid}`, mas o consumidor (scoreboard/TAB) só chega na Fase
  10 — escrita "adiantada" intencional, sem efeito visível na Fase 9.
