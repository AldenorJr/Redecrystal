# Design — Fase 11: Minas (fleet `mina`)

Data: 2026-07-01
Branch base: `feat/login-autocomplete-e-troca-senha`

> Terceira fase de jogo do **RankUP** sobre a rede RedeCrystal. Sobre a economia
> (**Fase 8**: `rankup-service` + contexto `economy`, write-path aditivo), os ranks
> e o prestígio (**Fase 9**: catálogo config hot-reload + `multiplier`) e o hub
> `spawn` + `FleetRouter` (**Fase 10**), a Fase 11 entrega o **fleet `mina`** e o
> plugin **`crystal-mine`**: uma mina config-driven que **auto-regenera**, dá
> **Money por bloco** (batelada aditiva, escalada pelo `multiplier` do prestígio) e
> mostra um **holograma de % + contagem de reset**. Arquitetura de alto nível em
> [`2026-07-01-rankup-arquitetura-design.md`](2026-07-01-rankup-arquitetura-design.md)
> (§2, §4, §5, §6.3, §7, §8, §10) — este spec detalha só o que a Fase 11 entrega.
> Texto de jogador em PT; identificadores/módulos/tópicos/chaves em inglês.

## 1. Problema

Depois da economia e da progressão, o RankUP precisa do seu **loop de ganho
central**: um jogador entra numa **mina**, quebra blocos, recebe **Money** e sobe de
rank com esse Money (Fase 9). Falta tudo disso:

1. **Não há servidor de mina.** O fleet `mina` (master §7) ainda não existe no
   compose; só há `login`/`lobby`. Precisa nascer como fleet escalável idêntico ao
   do lobby (âncora YAML, N instâncias, descoberta pela registry).
2. **Não há mina.** Nenhum plugin define uma **região minerável**, sua **composição
   de blocos** (percentuais por material), o **preço/reward** de cada bloco, nem a
   **regeneração** quando ela esvazia.
3. **Ninguém produz Money real ainda.** As Fases 8/9 entregaram os write-paths e o
   `/eco give` admin, mas o **primeiro produtor de Money de jogo** (minerar) não
   existe — é o que fecha o ciclo minerar → Money → `/rankup`.

Três exigências específicas moldam o design:

- **Regen não pode travar a main thread** (CLAUDE.md: "nunca bloqueie a main thread
  do Bukkit"). Reencher uma região de dezenas de milhares de blocos com
  `setType` bloco-a-bloco no tick congela o servidor. Precisa de um **bulk block-set
  eficiente fora da main thread** — a rede já monta **FastAsyncWorldEdit (FAWE)** em
  `EXTERNAL_PLUGINS/` e já usa a **API headless do WorldEdit** no `crystal-worldinit`
  (paste de schematic sem jogador). É o caminho natural.
- **Recompensa por bloco é um delta ADITIVO em batelada.** Emitir um HTTP por bloco
  quebrado inundaria o gateway. Acumula-se por jogador e faz-se **flush periódico /
  no quit / na troca de servidor** — exatamente o write-path aditivo do master §4.2
  (`EconomyService.addMoney`), com uma **janela de perda em crash** que precisa ser
  pequena e explícita.
- **O ganho escala com o prestígio.** O `multiplier` (Fase 9, hash `rankup:{uuid}`)
  multiplica o reward por bloco. Fase 11 é a **primeira consumidora** desse campo.

A Fase 11 **reaproveita**: o `rankup-service` (Fases 8/9), o write-path aditivo da
economia (`POST /api/economy/{uuid}/money`), o padrão de **catálogo em config
hot-reload** da Fase 9 (`ConfigProvider.get`/`onChange`, como `parkour`/`chat`), o
padrão de **holograma auto-limpante** do `crystal-parkour`/`crystal-hologram`, o
padrão de **fleet do lobby** (master §7.1) e o `FleetRouter` da Fase 10.

## 2. Objetivos e não-objetivos

**Objetivos**
- **Fleet `mina`** no compose: âncora `&mina-env`, `mina-01`/`mina-02`,
  `SERVER_TYPE=mina`, mundo VOID + schematic `WORLD_MINA` (plataforma/estrutura em
  volta), montando os jars de jogo + **FAWE**. Idêntico ao fleet do lobby: instâncias
  intercambiáveis, descobertas pela registry (sem mudar o proxy p/ escalar).
- Plugin **`crystal-mine`** (roda em `mina`):
  - **Catálogo `mine` na config central** (key `mine`, hot-reload): região(ões),
    composição (blocos + %), reward por bloco, intervalo/percentual de reset,
    acesso por permissão de rank. Uma ou mais minas por instância (`mineId`).
  - **Regeneração** eficiente **fora da main thread** (FAWE `EditSession` +
    `RandomPattern` sobre `CuboidRegion`), disparada por **timer**, por
    **percentual minerado** e por **comando admin** (`/mina reset`).
  - **Holograma** por mina (% restante + contagem para o próximo reset) + **animação
    de reset** (título/contagem regressiva); **NPC deferido** para a Fase 16.
  - **Recompensa por bloco → economy** como **delta aditivo em batelada** (acumula,
    flush periódico/no quit/no disable), escalada pelo `multiplier` do prestígio.
- Contexto **`mine` (leve)** no `rankup-service`: tabela `mine_stats`
  (`blocks_mined` por jogador por mina), endpoint de catálogo/estado e de batelada
  de stats (master §6.3).
- Write-through Redis **`mine:{server}:{mineId}`** (hash `{percentRemaining,
  resetAt}`) lido pelo holograma; `leaderboard:blocks-mined` no flush.
- Tópico Kafka **`mine-reset`** (produzido pelo `crystal-mine`, consumido pela
  própria instância p/ holo/animação).
- SDK `crystal-core`: `MineData`/`MineRung`, `MineClient`, `crystal.mines()`,
  constantes de tópico/chave.
- Infra: tópico `mine-reset`, rota `/api/mine/**` no gateway.

**Não-objetivos** (ficam para fases seguintes do master §10)
- **NPC de mina** (Citizens) — **Fase 16** (polish). Fase 11 é holograma + comando +
  seletor GUI (Fase 10 no spawn); o "NPC de mina" fica como stub.
- **Seletor de minas no spawn** — é da **Fase 10** (`crystal-spawn`); a Fase 11
  assume-o pronto e só garante que a mina existe do lado `mina`.
- **HUD/scoreboard de blocos minerados** e consumo de `money-updated` no jogo — Fase
  10 (spawn). O `leaderboard:blocks-mined` é gravado, mas o holo de topo (ranking) é
  Fase 16.
- **Terrenos, plantações, arena, homes** — Fases 12–15.
- **Reset como schematic/undo do WorldEdit** — a mina não é um build restaurado de
  arquivo; é um **preenchimento aleatório por composição** (`RandomPattern`). O
  schematic `WORLD_MINA` é só a estrutura ao redor (parede/plataforma), colada uma
  vez pelo `crystal-worldinit`, fora da região minerável. **Asset:** já existe no
  repo em **`world/WORLD_MINA/world-mina.schem`** (originalmente `prison-mine-demo.schem`);
  falta definir, na config `mine`, a(s) **bounding box(es)** da(s) região(ões)
  minerável(is) e a `composition[]` de cada mina.

## 3. Decisões de design

| Tema | Decisão |
|------|---------|
| **Fleet `mina`** | Mesma receita do lobby (master §7.1): âncora `&mina-env`, `mina-01`/`mina-02`, `depends_on: *mc-deps`, mundo VOID + `CRYSTAL_WORLD_SCHEMATIC=WORLD_MINA`. Instâncias **idênticas** e intercambiáveis; cada `mineId` é uma região do mundo replicada em toda instância (o eixo `server` da chave Redis distingue as instâncias). `FleetRouter` (Fase 10) roteia `mina` **least-loaded**. |
| **Regen: preenche pela % de composição de cada mina — DECIDIDO** | O reset **calcula e faz o set a partir da porcentagem de cada bloco** da mina: a `composition[].percent` do catálogo (key `mine`) vira um `RandomPattern` ponderado (`STONE 60% + IRON_ORE 30% + GOLD_ORE 10%`), aplicado sobre a `CuboidRegion` inteira. Cada mina tem a sua composição/percentuais; mudar a config muda a mistura no próximo reset (hot-reload). |
| **Regen: FAWE headless, off-main-thread** | Reuso do caminho já provado no `crystal-worldinit`: `WorldEdit.getInstance().newEditSessionBuilder().world(BukkitAdapter.adapt(world))…`. O reset faz `edit.setBlocks(CuboidRegion, RandomPattern)` (o `RandomPattern` acima). FAWE processa em bulk e **fora da main thread** (o `EditSession` roda numa `runTaskAsynchronously`); ao terminar, volta com `runTask` para tocar holo/estado/evento. **Sem** `Block.setType` bloco-a-bloco no tick. Fallback de scheduler em pedaços se o headless async não servir (§3, validar no plano com teste de TPS). |
| **Bloco minerado: sem drop, Money direto — DECIDIDO** | Estilo prison/RankUP: ao quebrar um bloco dentro da região, **não** há drop de item (`event.setDropItems(false)`, sem XP), o bloco vira ar até o próximo reset, e o jogador recebe **Money direto** (`reward_base × multiplier`, batelada). Sem clutter de inventário; não há venda de minério (a "loja" da Fase 16 é para cosmético/boost via Tokens, não para vender blocos). |
| **Fallback de regen (se FAWE headless falhar)** | Se a API async do FAWE não for utilizável neste setup (ver §10), cai-se num **scheduler em pedaços**: N blocos por tick via `runTaskTimer` até cobrir a região, mantendo o TPS. Mais lento, mas nunca trava. Decisão preferida: **FAWE**; fallback documentado, não default. |
| **Reward aditivo em batelada** | Cada bloco quebrado acumula `reward × multiplier` num mapa `pending:{uuid}` em memória (`ConcurrentHashMap`). **Flush** = um `POST /api/economy/{uuid}/money {delta,source="mine"}` (write-path aditivo, Fase 8) por jogador, disparado: (a) por **timer** (~30 s), (b) no **quit** (cobre troca de servidor pelo proxy — sair da `mina` dispara `PlayerQuitEvent`), (c) no **`onDisable`** do plugin. **Nunca** um HTTP por bloco. |
| **Janela de perda em crash** | Se a instância cai entre flushes, perde-se **só o acumulado desde o último flush** (≤ intervalo de flush, ex. 30 s de mineração). Aceitável para um delta aditivo de jogo (não é saldo debitado); o intervalo é config e pode encurtar. Documentado em §6/§10. |
| **Multiplicador de prestígio** | O reward por bloco é `reward_base × multiplier`, com `multiplier` lido do hash `rankup:{uuid}` (Fase 9, campo `multiplier`, d1.000), cacheado por jogador no join/`rank-updated`, **fail-open a 1.0** se ausente. Fase 11 é a **primeira consumidora** do `multiplier`. |
| **Catálogo em config, não em tabela** | A composição/percentuais/reward/reset das minas vivem na **config central** (key `mine`), hot-reload por `config-updated` como `parkour`/`rank` (master §4/§8). A tabela `mine_stats` guarda só **quanto cada jogador minerou** (leaderboard/estatística), não o catálogo. |
| **Reset: timer E percentual E manual** | Três gatilhos: **timer** (`resetIntervalSeconds` da config), **percentual minerado** (`resetAtPercentMined`, ex. 80% → reenche antes de esvaziar de vez) e **admin** (`/mina reset [mineId]`). Todos convergem no mesmo `regen(mineId)`. |
| **Contagem de % sem scan de chunk** | O % restante é derivado de **contadores em memória**: `total` (nº de blocos da região, calculado uma vez no reset) e `mined` (incrementado no `BlockBreakEvent` dentro da região). `percentRemaining = 100 × (total − mined) / total`. **Sem** varrer a região a cada tick. |
| **Holograma: display próprio dinâmico** | O % + contagem mudam a cada segundo — não cabem no `crystal-hologram` (holos **estáticos** config-driven por tipo de servidor). O `crystal-mine` possui o seu **`MineHologram`** (um `TextDisplay` marcado, `persistent=false`, limpo no disable), espelhando o `ParkourHologram` que já atualiza o rótulo de chegada ao vivo. |
| **`mine-reset` via Kafka** | O reset emite `mine-reset {mineId,server,resetAt}` (key = `mineId:server`). Mesmo sendo consumido na **própria instância** (holo/animação), passa pelo bus por consistência do modelo (master §5) e observabilidade; o plugin publica via `KafkaClient` (crystal-core) e reage localmente. |
| **NPC deferido** | "NPC de mina" (Citizens) é polish da **Fase 16** (montar Citizens em `EXTERNAL_PLUGINS/` como FAWE/LuckPerms). Fase 11 entrega holo + comando; o ponto do NPC fica como stub. |
| **Facade SDK por feature** | `MineClient` + `crystal.mines()`, seguindo `economy()`/`ranks()`/`prestige()` das Fases 8/9. Delega à `BackendHttpClient` (reusa retry/auth). |
| **`crystal-mine` sem shade do WorldEdit** | Como o `crystal-worldinit`: `worldedit-bukkit` é dependência **provided** (fornecida pelo FAWE em runtime), **não** shaded. O SDK `crystal-core` continua shaded normalmente. |

## 4. Arquitetura

### 4.1 Fleet `mina` no compose (padrão do lobby)

Espelha o bloco `lobby-01/02/03` do `docker-compose.yml` (âncoras `&mc-deps` e
`&lobby-env`). Novo bloco com âncora `&mina-env`:

```
mina-01:
  image: itzg/minecraft-server:java21
  depends_on: *mc-deps
  environment: &mina-env
    TYPE: PAPER
    VERSION: "1.21.1"
    LEVEL_TYPE: FLAT
    GENERATOR_SETTINGS: '{"biome":"minecraft:the_void","layers":[{"block":"minecraft:air","height":1}]}'
    CRYSTAL_WORLD_SCHEMATIC: /schematics/world-mina.schem   # estrutura ao redor, não a região minerável
    SERVER_ID: mina-01
    SERVER_TYPE: mina
    SERVER_HOST: mina-01
    SERVER_PORT: 25565
  volumes:
    - mina01data:/data
    - ./plugins/crystal-mine/target/crystal-mine.jar:/plugins/crystal-mine.jar:ro
    - ./plugins/crystal-economy/target/crystal-economy.jar:/plugins/crystal-economy.jar:ro
    - ./plugins/crystal-rank/target/crystal-rank.jar:/plugins/crystal-rank.jar:ro
    - ./plugins/crystal-chat|tab|tag|hologram|inventory|profile/... (compartilhados)
    - ./EXTERNAL_PLUGINS/FastAsyncWorldEdit-Paper-2.15.2.jar:/plugins/FastAsyncWorldEdit.jar:ro
    - ./plugins/crystal-worldinit/target/crystal-worldinit.jar:/plugins/crystal-worldinit.jar:ro
    - ./world/WORLD_MINA/world-mina.schem:/schematics/world-mina.schem:ro
mina-02:  { <<: *mina-env, SERVER_ID: mina-02, SERVER_HOST: mina-02 }
```

Cada instância roda `crystal-economy`/`crystal-rank` (o jogador vê saldo/rank e faz
`/rankup` na mina) + os compartilhados (chat/tab/tag/hologram). O `crystal-mine` é o
único novo. Economia/inventário/ranks/stats/chat/tab são **compartilhados** via
gateway+Redis (nada por-instância).

### 4.2 Catálogo na config central — key `mine`

Config nova, gerida pelo `ConfigService` existente (`GET`/`PUT /api/config/mine` →
cache `config:mine` + `config-updated`), sem backend novo. Hot-reload para o plugin
(`ConfigProvider.onChange`), como `parkour`/`rank`:

```json
{
  "mines": [
    {
      "id": "iron",
      "world": "world",
      "region": { "min": {"x":-20,"y":40,"z":-20}, "max": {"x":20,"y":60,"z":20} },
      "hologram": { "x": 0.5, "y": 63, "z": 0.5 },
      "requiredPermission": "rankup.mine.iron",
      "resetIntervalSeconds": 600,
      "resetAtPercentMined": 80,
      "composition": [
        { "block": "STONE",     "percent": 60, "reward": 2 },
        { "block": "IRON_ORE",  "percent": 30, "reward": 8 },
        { "block": "GOLD_ORE",  "percent": 10, "reward": 20 }
      ]
    }
  ]
}
```

`percent` da composição é a **fração de preenchimento** (soma ~100) traduzida num
`RandomPattern`; `reward` é o Money base por bloco daquele material (antes do
`multiplier`). `requiredPermission` casa com as `permissions[]` do degrau de rank
(Fase 9, ex. `rankup.mine.ouro`) — controla acesso/quebra dentro da região.

Uma **`Mine`** imutável (mirror do `ParkourCourse.fromConfig`) parseia cada entrada:
região (`CuboidRegion`/bounding box), composição, `hologram`, thresholds. Uma
**`MineCatalog`** guarda `Map<mineId, Mine>` reconstruído no boot e em cada
`onChange`.

### 4.3 Plugin `crystal-mine` (Paper, roda em `mina`)

Espelha `crystal-parkour` (boot só registra; `commands/`, `listener/`, holo próprio;
lê config via `ConfigProvider.get("mine")` + `onChange`). Classe
`CrystalMinePlugin` (onEnable/onDisable) + peças:

- **`Mine` / `MineCatalog`** (§4.2) — modelo config-driven imutável, hot-reload.
- **`MineRegen`** — dado um `mineId`: monta o `RandomPattern` a partir da composição,
  agenda um `runTaskAsynchronously` que abre um `EditSession` FAWE headless
  (`newEditSessionBuilder().world(adapt(world)).maxBlocks(-1)`), `setBlocks(region,
  pattern)`, `flushQueue`, e volta com `runTask` para: zerar o contador `mined`,
  recomputar `total`, atualizar `mine:{server}:{mineId}`, refrescar o holo e emitir
  `mine-reset`. Fallback em pedaços (§3) se o headless não servir.
- **`MineListener`** (coeso, à moda do `ParkourListener`):
  - `onBlockBreak`: se o bloco está dentro de uma região de mina — checa
    `requiredPermission` (sem permissão → cancela + aviso), **suprime o drop**
    (`setDropItems(false)`, `setExpToDrop(0)` — sem item no chão/inventário),
    **incrementa `mined`**, acumula `reward × multiplier(uuid)` em `pending:{uuid}`
    (**Money direto**, decisão §3), e dá `blocks_mined++` para a batelada de stats.
    Dispara `regen` se `percentMined ≥ resetAtPercentMined`. O bloco fica ar até o
    reset.
  - `onQuit`: **flush** imediato do jogador (economia + stats), remove estado.
- **`MineHologram`** (mirror `ParkourHologram`) — um `TextDisplay` por mina
  (`persistent=false`, tag `crystal_mine_holo`, limpo no disable/reload, remove
  órfãos), com **barra de %** + **contagem para o próximo reset**, atualizado num
  `runTaskTimer` (~1 s) a partir dos contadores locais / `mine:{server}:{mineId}`.
- **`RewardBatcher`** — `Map<UUID,Long> pending` + `Map<UUID,Long> minedStats`;
  `flush(uuid)` faz `crystal.economy().addMoney(uuid, delta, "mine")` +
  `crystal.mines().addMined(mineId, uuid, blocks)` **off-thread**;
  `flushAll()` no `runTaskTimer` (~30 s) e no `onDisable`. `multiplier(uuid)` lido de
  `rankup:{uuid}` (cache local, refresh no join/`rank-updated`), fail-open 1.0.
- **`MineCommand`** (`/mina`): `/mina` abre uma GUI simples (mira/lista de minas por
  acesso — GUI-first); `/mina reset [mineId]` (admin `crystal.mine.admin`, default
  op) força o regen; `/mina tp <mineId>` teleporta. (O seletor rico é do spawn, Fase
  10; aqui é o mínimo local.)

### 4.4 Backend `rankup-service` — contexto `mine` (leve) + stats

Novo contexto `mine` (`api/application/domain`), espelhando `economy`/`rank`:

- **`domain/MineStatEntity`** (`@Table("mine_stats")`, PK composta
  `(mine_id, player_uuid)`): `blocksMined` (BIGINT d0), `updatedAt`. Migração
  `V4__rankup_mine.sql`.
- **`domain/MineStatRepository`** — `@Modifying @Query` aditivo (à moda do
  `EconomyRepository.addMoney` da Fase 8): `UPDATE … SET blocks_mined = blocks_mined
  + :blocks …` (upsert/ensure antes). Sem versão/409 (aditivo puro).
- **`application/MineService`** — `addMined(mineId, uuid, blocks)` (ensure + aditivo +
  write-through `leaderboard:blocks-mined` via `leaderboardAdd`, fail-open);
  `catalog()`/`state(mineId)` opcionais (o catálogo autoritativo é a config `mine`,
  como o `rank`).

Endpoints (master §6.3), controller fino:

| Método/Path | Body → Resposta |
|---|---|
| `GET /api/mine` | catálogo (lido da config `mine`) → `List<MineRungResponse>` |
| `GET /api/mine/{mineId}` | estado leve (`mine:{server}:{mineId}` / stats agregada) |
| `POST /api/mine/{mineId}/mined` | `{uuid, blocks}` → aditivo em `mine_stats` (batelada) |

> A **recompensa de Money** **não** passa por um endpoint de mina: é o
> `POST /api/economy/{uuid}/money` aditivo da Fase 8 (reuso do write-path). O
> contexto `mine` guarda só **estatística** de blocos.

### 4.5 Gateway

`api-gateway/application.yml` ganha a rota (mesmo formato das Fases 8/9):

```yaml
- id: rankup-mine
  uri: lb://rankup-service
  predicates:
    - Path=/api/mine/**
```

Sem mudança no `ServiceTokenAuthFilter` (já guarda todo `/api/**`).

### 4.6 SDK `crystal-core`

- `messaging/KafkaTopics`: `+ MINE_RESET="mine-reset"` (e na lista `ALL`).
- `http/MineData` (`record`): estado leve da mina (`mineId, server, percentRemaining,
  resetAt`).
- `http/MineRung` (`record`): entrada do catálogo (`id, world, requiredPermission,
  resetIntervalSeconds, resetAtPercentMined, List<Composition>`), para GUI/`GET /api/mine`.
- Métodos na `BackendHttpClient` (via `send`, estilo economy/rank): `mineCatalog()`,
  `getMine(mineId)` (allowNotFound→null), `addMined(mineId, uuid, blocks)`.
- `http/MineClient`: facade fino sobre a `BackendHttpClient`, expondo a chave
  `MINE_KEY_PREFIX="mine:"` (`mine:{server}:{mineId}`), os campos do hash
  (`percentRemaining`,`resetAt`) e `BLOCKS_MINED_LEADERBOARD="blocks-mined"`.
  `CrystalCore` ganha `mines()` (`new MineClient(backend)`), seguindo `economy()`/
  `ranks()`/`prestige()`.

### 4.7 Redis `mine:{server}:{mineId}`

Hash `{percentRemaining, resetAt}`, TTL ~60 s, **write-through** pelo `crystal-mine`
(no reset e a cada recount do timer do holo). Lido pelo `MineHologram` da própria
instância (e, na Fase 16, por um holo de topo). `server` = `SERVER_ID` (ex.
`mina-01`), então instâncias diferentes do fleet têm chaves distintas mesmo com o
mesmo `mineId`. Falha de Redis é **fail-open** (holo cai nos contadores locais).

## 5. Fluxo de dados

**Minerar (delta aditivo em batelada)**
```
BlockBreakEvent dentro da região  → MineListener
  checa requiredPermission (sem → cancela)
  mined++              (contador local; recomputa percentRemaining)
  pending[uuid] += reward(block) × multiplier(uuid)     (acumula em memória)
  minedStats[uuid]++
  se percentMined ≥ resetAtPercentMined → MineRegen.regen(mineId)
  [a cada ~1s] MineHologram lê contadores → barra % + countdown; write-through mine:{server}:{mineId}

Flush (timer ~30s | quit | onDisable):
  POST /api/economy/{uuid}/money {delta=pending[uuid], source="mine"}   (aditivo, Fase 8)
  POST /api/mine/{mineId}/mined  {uuid, blocks=minedStats[uuid]}         (aditivo)
  → write-through economy:{uuid} + leaderboard:money + leaderboard:blocks-mined
  → money-updated (Kafka; HUD/broadcast reagem na Fase 10)
```

**Reset (timer | percentual | `/mina reset`)**
```
gatilho → MineRegen.regen(mineId)
  runTaskAsynchronously:
    EditSession FAWE headless: setBlocks(CuboidRegion, RandomPattern(composição)); flushQueue
  runTask (volta à main):
    total = nº de blocos da região; mined = 0
    write-through mine:{server}:{mineId} {percentRemaining=100, resetAt=now+interval}
    MineHologram.refresh()  +  animação (título/contagem)
  KafkaClient.publish mine-reset {mineId, server, resetAt}   (key = mineId:server)
    → consumido na própria instância p/ animação/observabilidade
```

**Regra Kafka × Redis (master §5/§8):** `mine-reset`/`money-updated` são **fatos**
(reações: animação, HUD, broadcast, leaderboard). O estado quente lido em loop (a
barra de %) vem do **Redis** `mine:{server}:{mineId}` (e dos contadores locais), não
do Kafka por bloco. Toda escrita de estado é **write-through**.

## 6. Concorrência e janelas de perda

- **Reward aditivo**: `POST …/money` é o write-path atômico `money=money+:delta` da
  Fase 8 — sem versão, sem 409. A batelada só reduz o **número** de chamadas; a soma
  final é a mesma. Idempotência não é exigida (é acumulador local, uma chamada por
  flush).
- **Janela de perda (crash)**: o `pending`/`minedStats` vive em memória; um crash
  entre flushes perde o acumulado desde o último flush (**≤ intervalo de flush**,
  ex. 30 s). É delta de ganho de jogo (não saldo debitado) → aceitável; o intervalo é
  config e pode encurtar. **Não** se persiste `pending` em disco nesta fase
  (over-engineering p/ um ganho aditivo); documentado em §10.
- **Regen concorrente**: um flag `regenerating` por `mineId` impede dois resets
  sobrepostos (timer + percentual disparando juntos); o segundo é no-op enquanto o
  `EditSession` async não terminou.
- **Contagem de `mined` entre instâncias**: cada instância conta a **sua** região
  (chave Redis por `server`); não há estado de % compartilhado — correto, pois cada
  `mina-XX` tem a sua cópia física da mina.

## 7. Tratamento de erros (fail-open, padrão do projeto)

- **FAWE indisponível/headless falha**: log + fallback do scheduler em pedaços (§3);
  a mina ainda regenera, só mais devagar. Nunca trava a main thread.
- **HTTP fora (flush)**: `addMoney`/`addMined` são off-thread; falha → `log.warn` e o
  `pending` **é mantido** para o próximo flush (não zera antes do 200). Perda só no
  crash (§6).
- **Redis fora**: write-through de `mine:{…}` e leitura de `multiplier` são
  `try/catch` → `log.warn`; holo cai nos contadores locais; `multiplier` cai a 1.0.
- **Kafka fora**: `KafkaClient.publish` engole a falha (padrão do core) — o reset
  conclui; a animação local roda de qualquer forma.
- **Config `mine` ausente/corrompida**: `MineCatalog` fail-open → sem minas → sem
  quebra recompensada, holo "mina indisponível"; sem crash.
- **Bloco fora de qualquer região**: ignorado (mineração normal do mundo, sem
  reward).

## 8. Propagação entre servidores e performance da regen

- **Ganho de Money / stats**: mudam Postgres + Redis compartilhado no flush; qualquer
  servidor que leia `economy:{uuid}`/leaderboards vê no próximo tick (Fase 10), sem
  pub/sub por jogador.
- **`multiplier` do prestígio**: escrito por Fase 9 no `rankup:{uuid}`; a mina o lê
  compartilhado — prestigiar no spawn reflete no ganho da mina sem estado local.
- **Regen off-main-thread**: o `EditSession` FAWE roda em `runTaskAsynchronously`; o
  único trabalho na main é recomputar contadores + tocar o holo (barato). Alvo: reset
  de uma região de dezenas de milhares de blocos **sem queda perceptível de TPS**.
- **Edição do catálogo** (`PUT /api/config/mine`): propaga por `config-updated`
  (Kafka, instantâneo) → `crystal-mine` recarrega `MineCatalog` e reconstrói holos —
  **hot-reload sem restart**, como `parkour`/`rank`.

## 9. Testes

**Unitário backend** (`MineServiceTest`, JUnit 5 + Mockito, mockando repo + Redis +
`EventPublisher`, no molde do `EconomyServiceTest` da Fase 8):
1. `addMined` chama o `@Modifying` aditivo e grava `leaderboard:blocks-mined`.
2. `addMined` com Redis fora → **fail-open** (persiste no Postgres, só `log.warn`).

**Unitário plugin** (JVM puro, sem Bukkit, onde couber):
3. `RandomPattern`/composição: os percentuais somam e mapeiam os materiais certos.
4. `RewardBatcher`: acumula `reward × multiplier`, e `flush` só zera após "200".

**Manual (curl + em jogo, CLAUDE.md)** contra o gateway com `Bearer
<BACKEND_SERVICE_TOKEN>`:
- **curl**: `PUT /api/config/mine` com o catálogo de exemplo; `GET /api/mine`
  confirma; `POST /api/mine/iron/mined {uuid,blocks}` incrementa e aparece em
  `leaderboard:blocks-mined`.
- **em jogo** (subir o fleet `mina`, **recriar** os containers `--force-recreate
  --no-deps mina-01 mina-02`): entrar via spawn/`FleetRouter`; minerar dá Money
  (checar `/saldo` da Fase 8) e **persiste após reconectar** (flush no quit);
  esvaziar até `resetAtPercentMined` → **reset automático** com animação; esperar o
  **timer** → reset; `/mina reset` → reset manual; a **barra de %** do holo
  acompanha via `mine:{server}:{mineId}`; prestigiar (Fase 9) e conferir que o
  **reward escala** com o `multiplier`; matar o container e reconectar → perda **só**
  da janela desde o último flush.

## 10. Divergências do código atual (a reconciliar)

- **Depende das Fases 8, 9 e 10 — nenhuma construída ainda.** Nem
  `backend/rankup-service/`, nem `plugins/crystal-economy|rank|prestige/`, nem o
  fleet `spawn`/`FleetRouter` existem no branch. A Fase 11 assume: o `rankup-service`
  com `shared/{messaging,web}` (Fase 8); o write-path aditivo `addMoney` e o facade
  `crystal.economy()` (Fase 8); o hash `rankup:{uuid}` com `multiplier` (Fase 9); e o
  `FleetRouter` + seletor de minas no spawn (Fase 10). **A Fase 11 só começa depois
  dessas mergeadas.**
- **`FleetRouter` ainda não existe — hoje é `LobbyRouter` (lobby-only).** O
  `plugins/crystal-bungee/.../LobbyRouter.java` roteia só lobby. O master §7.2 e a
  Fase 10 generalizam-no para `FleetRouter` por tipo (roteia `mina` least-loaded). Se
  a Fase 10 atrasar, o acesso à mina precisa de um roteamento provisório — a Fase 11
  **não** reescreve o router (é da Fase 10).
- **Fase 10 ainda não tem spec/plan.** Existem specs/plans só até a Fase 9
  (`2026-07-01-fase9-…`). O "seletor de minas no spawn" e o `tab:rankup`/scoreboard
  de que a mina depende para exibição vêm da Fase 10, **ainda não desenhada** — a
  Fase 11 pressupõe-na.
- **FAWE headless off-main-thread: a confirmar no plano.** O `crystal-worldinit` usa
  a **API síncrona** do WorldEdit (`Operations.complete`) dentro de um
  `runTaskLater` na **main thread** (paste único no boot, tolerável). A Fase 11
  precisa de `setBlocks`/`RandomPattern` **em task async** sem tocar a main. O FAWE
  suporta `EditSession` fora da main (é o seu propósito), mas **isto ainda não é
  exercido neste repositório** — validar no plano com um teste de TPS; se o headless
  async não servir, cai no **fallback do scheduler em pedaços** (§3). É a decisão de
  maior risco técnico da fase.
- **`crystal-hologram` é estático; a mina precisa de holo dinâmico.** O
  `crystal-hologram` renderiza holos **config-driven imutáveis** por tipo de
  servidor (`HologramDef`/`HologramRenderer`). O `%`/countdown mudam ao vivo — a Fase
  11 usa um **`MineHologram` próprio** (mirror do `ParkourHologram`, que já atualiza o
  rótulo ao vivo). Não é uma extensão do `crystal-hologram`.
- **Primeiro produtor de Money e primeira consumidora do `multiplier`.** As Fases 8/9
  entregaram os write-paths mas **nenhum produtor real** de Money (só `/eco give`
  admin) nem leitor do `multiplier`. A Fase 11 é o primeiro dos dois — se o campo
  `multiplier` no `rankup:{uuid}` divergir do previsto na Fase 9, ajustar aqui.
- **`mine_stats` PK composta.** É a **primeira tabela de PK composta** do
  `rankup_db` (`economy`/`rank`/`prestige` são PK simples por `uuid`). Confirmar o
  padrão de `@IdClass`/`@EmbeddedId` no plano (o master §4 lista `PK(mine_id,
  player_uuid)`).
- **`mine-reset` produzido por um plugin.** Até aqui os eventos de domínio saem do
  **backend** (`EventPublisher`); `mine-reset` sai do **plugin** (`KafkaClient` do
  crystal-core, como o `player-chat`). Consistente com o master (produtor =
  `crystal-mine`), mas é o primeiro evento de jogo emitido do lado do plugin —
  registrar.
- **`pending` não persistido (janela de perda aceita).** Diferente dos terrenos
  (Fase 12, "nunca perder progresso"), o reward de mineração aceita perder a janela
  desde o último flush num crash. É uma **decisão de trade-off** (delta aditivo de
  ganho, não saldo debitado); se inaceitável, encurtar o intervalo ou persistir o
  `pending` — fora do escopo da Fase 11.
```
