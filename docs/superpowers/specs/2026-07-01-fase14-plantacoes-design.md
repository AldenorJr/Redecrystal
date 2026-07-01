# Design — Fase 14: Plantações (caps por cultura por terreno)

Data: 2026-07-01
Branch base: `feat/login-autocomplete-e-troca-senha`

> Sétima fase do jogo **RankUP** sobre a rede RedeCrystal. Sobre a fundação dos
> **terrenos** (Fase 12 — SWM/load-save/sticky) e do **terreno avançado** (Fase 13
> — expansão/membros/permissões/upgrades), a Fase 14 adiciona a **produção
> agrícola**: cada terreno tem um **teto (cap) por cultura**; plantar até o cap
> bloqueia acima; upgrades elevam os caps; **colher paga Money** (recompensa
> aditiva, escalada pelo multiplicador de prestígio). Arquitetura de alto nível em
> [`2026-07-01-rankup-arquitetura-design.md`](2026-07-01-rankup-arquitetura-design.md)
> (§2 `crystal-plantation`, §4 `plot_plantations`, §6.3, §8, §10 Fase 14) — este
> spec detalha só o que a Fase 14 entrega. Texto de jogador em PT;
> identificadores/módulos/tópicos/chaves em inglês.

## 1. Problema

Depois que o terreno sabe se expandir, receber membros e comprar upgrades (Fase
13), falta o **loop econômico** que dá sentido ao terreno: **produzir**. O jogador
planta culturas no seu terreno, **colhe** e converte em Money — mas sem limite a
plantação vira uma fazenda infinita que trivializa a economia e detona a
performance do servidor `terrenos` (milhares de blocos de cultura por chunk). O
brief pede um **teto por cultura por terreno** (`plot_plantations`): quantos
blocos de cada cultura o terreno comporta, elevável por **upgrade**.

As culturas são as do master §4:
`cactus`, `cana`, `bambu`, `wheat`, `carrot`, `potato`, `melon`, `pumpkin`,
`cacau`, `beetroot`, `nether_wart`.

Três exigências específicas moldam o design:

1. **Contagem barata.** Saber "quantos `wheat` há neste terreno" **não pode** custar
   um scan de chunk a cada plantio — o servidor `terrenos` já carrega Slime worlds
   inteiras (§9 do master). A contagem tem de ser **incremental** (mantida em
   plantio/quebra), não recalculada.
2. **Cap = base + upgrade.** O teto de cada cultura é `base` (config central,
   editável a quente) **mais** o bônus do **tier** de um upgrade de terreno
   (`plot_upgrades`, Fase 13). Nada de cap fixo em código.
3. **Colheita paga, respeitando prestígio.** A recompensa por colher é um **delta
   aditivo** de Money (master §4.2), com o **valor por cultura** vindo da config e
   **escalado pelo multiplicador de prestígio** (Fase 9) — o mesmo campo que Fases
   11/15 lêem para escalar ganhos.

A Fase 14 **reaproveita** muita coisa pronta: o write-path **aditivo** da economia
(`EconomyService.addMoney`, Fase 8) para a recompensa; o ciclo de vida
**single-writer** do terreno (lock/lease/version, Fase 12 §9) que torna a contagem
in-memory autoritativa por sessão; o cache `plot:{plotId}` e o tópico
`plot-updated` (Fase 13) para tiers de upgrade e permissões `plant`/`harvest`; e o
padrão de **facade por feature** do SDK (`crystal.economy()`/`crystal.plots()`).

## 2. Objetivos e não-objetivos

**Objetivos**
- Contexto `plantation` no `rankup-service` (`api/application/domain`): tabela
  `plot_plantations` (master §4), migração `V6__rankup_plantations.sql`, endpoints
  `/api/plantation/**` (GET conta+cap, PUT flush das contas).
- Catálogo `plantation` na **config central** (base cap + valor de Money por
  cultura + definição do upgrade de cap), hot-reload como `chat`/`rank`.
- `cap(crop) = base(config) + bonusPerTier × tier(plot_upgrades)` — computado, não
  armazenado como fonte de verdade.
- Plugin `crystal-plantation` (servidor `terrenos`): contagem **incremental** por
  plantio/quebra (sem scan), **enforcement do cap no plantio** (place acima do cap
  → cancela + mensagem PT), **recompensa aditiva na colheita** (valor×multiplicador,
  em batelada → `economy`), painel de plantações na GUI `/terreno`.
- SDK `crystal-core`: `PlantationData`, `PlantationClient`, `crystal.plantations()`.
- Infra: rota `/api/plantation/**` no gateway. **Sem tópico Kafka novo** (§3).
- Interação com as permissões `can_plant`/`can_harvest` da Fase 13.

**Não-objetivos** (ficam para fases seguintes do master §10)
- NPC/holograma da plantação (loja de sementes, holo de produção) — polish da
  **Fase 16**.
- Boosts de produção por Tokens (multiplicador temporário) — **Fase 16**.
- Culturas fora da lista do master (árvores, abóbora gigante, etc.).
- Auto-colheita/coletores automáticos — não previstos no brief.
- Leaderboard de produção — o master §8 não o prevê; a Fase 14 não cria sorted set.

## 3. Decisões de design

| Tema | Decisão |
|------|---------|
| **Contagem incremental, não scan** | O `planted_count` é mantido **em memória** no servidor `terrenos` que hospeda o terreno: `+1` no `BlockPlaceEvent` da cultura, `−1` na remoção do bloco **contado**. **Nunca** há scan periódico de chunk. Carregado do backend no load do terreno; o enforcement lê a memória (0 round-trip por plantio). |
| **Autoridade da conta** | Durante a sessão, a **memória do servidor sticky é autoritativa** — o §9 (lock NX + lease + version) garante **um único writer** por terreno, então não há corrida entre instâncias. **Postgres `plot_plantations` é o registro durável** (snapshot), atualizado **write-behind** (flush periódico + no save/unload do terreno, junto do ciclo §9). **Redis não guarda conta** (não é lido em loop cross-instância; só o dono muta). |
| **Cap computado (base+tier)** | `cap(crop) = plantation.crops[crop].baseCap + plantation.capUpgrade.bonusPerTier × tier`, onde `tier` vem de `plot_upgrades` (upgrade `plantation_cap`, catálogo `plot_upgrades` da Fase 13). O **`cap` do master §4 não é fonte de verdade** — é derivado; ver §10. |
| **Unidade contada** | 1 conta = **1 bloco de cultura plantado pelo jogador** (o bloco do `BlockPlaceEvent`). Segmentos de **crescimento** (cana/cactus/bambu que sobem, `BlockGrowEvent`) **não** contam (não há place) — quebrar um segmento crescido é **no-op** para a conta, mas **paga** colheita (§4.4). |
| **Colheita: dropa item → vender na loja — DECIDIDO** | Diferente da mineração (Money direto), colher uma cultura **dropa o item** no inventário do jogador (comportamento vanilla mantido). O Money vem da **venda**: um caminho de venda (GUI `/vender` / sell-all na Fase 14; **loja-NPC rica na Fase 16**) converte os itens de cultura em Money = `value(crop) × multiplier`, com `value` da config `plantation` e o `multiplier` de prestígio (`rankup:{uuid}`, fail-open 1.0) aplicado **no momento da venda**, no plugin. A colheita **decrementa** o `planted_count` (bloco base removido); a economia entra na **venda**, não na quebra. |
| **Plantio livre até o cap — DECIDIDO** | Plantar **não consome semente**: o jogador planta livremente até o teto da cultura; o limitador é o `cap` + o tempo de crescimento. Sem loja de sementes nesta fase. |
| **Permissões plant/harvest (Fase 13)** | O `BlockPlaceEvent` só conta/enforce **depois** de checar `can_plant`; o `BlockBreakEvent` só paga/decrementa **depois** de checar `can_harvest` — ambos lidos do cache `plot:{plotId}` (Fase 13). Proteção genérica de build/break continua no `crystal-plot`; `crystal-plantation` cuida **só** de plantio/colheita, compondo por `EventPriority` (§4.5). |
| **`plantation` é contexto próprio** | Mantém a fronteira do master §2.2 (`plot_plantations` pertence a `plantation`). Para computar o cap, lê `plot_upgrades` (contexto `plot`) **in-process** (mesmo `rankup-service`, sem HTTP para si), como `RankService`↔`EconomyService` na Fase 9. Alternativa (dobrar em `plot`) descartada: mantém o master e isola escrita de produção. |
| **Flush absoluto, guardado pelo lease** | O flush é `PUT /api/plantation/{plotId}` com as contas **absolutas** (seguro porque single-writer). Só o servidor **dono do lease** do terreno (§9) faz o flush; snapshot durável = ponto de recuperação em crash (perda ≤ janela desde o último flush, como o save de segurança do §9). |
| **Sem tópico Kafka novo** | Conta é local single-writer (ninguém reage cross-instância); colheita já emite `money-updated` (economy); mudança de cap chega por `plot-updated` (Fase 13, tier de upgrade) + `config-updated` (base/valor). `crystal-plantation` **consome** esses dois e invalida `plot:{plotId}`; **não** cria tópico. |
| **Anti-drift refinado (explosão/água/pistão) — DECIDIDO** | (a) **Explosões canceladas** dentro do terreno (TNT/creeper não destroem cultura nem build). (b) **Espalhamento de água/lava cancelado** (`BlockFromToEvent` — sem fluxo/expansão de líquido no terreno, evita griefing e auto-farm por água). (c) **Pistões limitados por terreno**: **1 pistão por padrão**, aumentável **+1 por compra em Money** (upgrade `piston_limit`, catálogo `plot_upgrades` da Fase 13); colocar um pistão além do limite é **cancelado** ("§cLimite de pistões atingido — compre mais no /terreno."). Pistões dentro do limite **funcionam** (redstone controlada). Isso mantém a conta exata sem proibir redstone de vez. |
| **Reconciliação sob demanda, não periódica** | Onde o cancelamento (acima) não cobrir, **clamp** `[0, cap]` impede conta absurda e um `/plantacao reconciliar` (admin) faz **um** scan do terreno carregado para re-sincronizar — o único scan do sistema, e **opt-in**. |
| **Montagem na Fase 14** | `crystal-plantation` roda no fleet `terrenos` (junto de `crystal-plot`), como a Fase 12/13 estabeleceu. |

## 4. Arquitetura

### 4.1 Catálogo na config central — key `plantation`

Config nova, gerida pelo `ConfigService` existente (`GET`/`PUT /api/config/{key}`
→ cache `config:{key}` + `config-updated`), **hot-reload** para o plugin
(`ConfigProvider.onChange`) e **read-through** para o serviço (como o `rank` da
Fase 9, §4.6). Cada cultura tem `baseCap` (teto sem upgrade) e `value` (Money por
bloco colhido); `capUpgrade` amarra o upgrade de `plot_upgrades` que eleva os caps:

```json
{
  "crops": {
    "wheat":       { "baseCap": 64,  "value": 3 },
    "carrot":      { "baseCap": 64,  "value": 3 },
    "potato":      { "baseCap": 64,  "value": 3 },
    "beetroot":    { "baseCap": 64,  "value": 3 },
    "cactus":      { "baseCap": 128, "value": 2 },
    "cana":        { "baseCap": 128, "value": 2 },
    "bambu":       { "baseCap": 128, "value": 2 },
    "melon":       { "baseCap": 96,  "value": 4 },
    "pumpkin":     { "baseCap": 96,  "value": 4 },
    "cacau":       { "baseCap": 64,  "value": 5 },
    "nether_wart": { "baseCap": 64,  "value": 6 }
  },
  "capUpgrade": { "upgradeId": "plantation_cap", "bonusPerTier": 32 }
}
```

`cap(crop, tier) = crops[crop].baseCap + capUpgrade.bonusPerTier × tier`. O
`plantation_cap` é um item do catálogo `plot_upgrades` (Fase 13, config central);
comprar tier eleva **todas** as culturas do terreno (bônus por tier). Editar caps
ou valores é `PUT /api/config/plantation` → propaga por `config-updated`.

**Mapeamento cultura→`Material`** (no plugin, registro estático): `wheat`→`WHEAT`,
`carrot`→`CARROTS`, `potato`→`POTATOES`, `beetroot`→`BEETROOTS`,
`nether_wart`→`NETHER_WART`, `cacau`→`COCOA`, `cactus`→`CACTUS`,
`cana`→`SUGAR_CANE`, `bambu`→`BAMBOO`, `melon`→`MELON_STEM` (colhe `MELON`),
`pumpkin`→`PUMPKIN_STEM` (colhe `PUMPKIN`). O bloco **contado** é o plantado (base/
caule); o bloco **colhível** que paga inclui o fruto (`MELON`/`PUMPKIN`) e os
segmentos de cana/cactus/bambu.

### 4.2 Contexto `plantation` (`api/application/domain`)

**`domain/PlantationEntity`** (`@Table("plot_plantations")`): PK composta
(`plotId` UUID, `crop` VARCHAR(16)), `plantedCount` (int, d0). Sem `version`: a
escrita é single-writer (lease do §9), o snapshot é absoluto. Método
`setCount(count)` (clamp ≥ 0), no estilo enxuto do `InventoryEntity.update`.

**`domain/PlantationRepository`** (`JpaRepository<PlantationEntity, …>`):
`findByPlotId(plotId)` (lista as culturas do terreno); upsert em lote no flush
(`saveAll`). Chave composta via `@IdClass`/`@EmbeddedId` (`PlotCropId`).

**`application/PlantationCatalog`** — lê `config:plantation` do Redis compartilhado
(`StringRedisTemplate`, como o `RankCatalog` da Fase 9), desserializa
`crops`/`capUpgrade`, expõe `baseCap(crop)`/`value(crop)`/`bonusPerTier()`.
Fail-open com fallback HTTP `GET /api/config/plantation` no miss frio.

**`application/PlantationService`** (in-process com `plot` p/ tiers de upgrade):

| Método | Comportamento |
|--------|---------------|
| `get(plotId)` | `findByPlotId` → para cada cultura, `plantedCount` + `cap` computado (`catalog.baseCap + catalog.bonusPerTier × tierDe(plotId,"plantation_cap")`, tier lido in-process do `PlotUpgradeService`/`plot_upgrades`). Culturas sem linha entram com `count=0`. |
| `save(plotId, counts)` | flush **absoluto**: `saveAll` das contas (clamp `[0, cap]`). Retorna `get(plotId)` (contas + caps atuais). Idempotente; seguro por single-writer. |

Sem endpoint de "ajustar cap": o cap é derivado (config + tier), nunca setado
diretamente.

**`api/PlantationController`** (`@RequestMapping("/api/plantation")`, controller
fino; DTOs `record` aninhados):

| Método/Path | Body → Resposta |
|---|---|
| `GET /{plotId}` | → `PlantationResponse{plotId, crops:[{crop,plantedCount,cap}]}` |
| `PUT /{plotId}` | `{crops:[{crop,plantedCount}]}` → flush absoluto → `PlantationResponse` |

### 4.3 `shared/` do `rankup-service` — sem extensão

A Fase 14 **não** adiciona tópico nem exceção nova ao `shared/`: reusa o
`ApiExceptionHandler` (404 se terreno inexistente) e o `EventPublisher` (não emite
tópico próprio). O `KafkaTopics` do backend **não** muda.

### 4.4 Plugin `crystal-plantation` (fleet `terrenos`, GUI-first)

Espelha `crystal-plot` (`pom.xml` com shade do `crystal-core` + paper-api;
`Crystal…Plugin` só boot+registro; `listener/` + `commands/` + `menu/`). Lê o
catálogo de `ConfigProvider.get("plantation")` + `onChange` (hot-reload). O estado
vivo é um mapa por terreno **carregado**:

```
plotCounts : Map<UUID plotId, Map<String crop, int count>>   // in-memory, single-writer
plotPistons : Map<UUID plotId, int placedPistons>            // p/ o limite de pistões (§3)
```
(Sem `harvestBuffer`: a colheita **dropa item**; o Money entra na **venda**, §4.4b.)

**Ciclo de vida (piggyback no §9):**
- **Load do terreno** (`plot-loaded`/entrada do dono, evento do `crystal-plot`):
  `crystal.plantations().get(plotId)` **off-thread** → popula `plotCounts[plotId]`.
- **Flush de segurança** a cada ~3–5min (mesma cadência do save §9) e **no
  save/unload** do terreno: `crystal.plantations().save(plotId, counts)`
  off-thread. Flush do `harvestBuffer` no mesmo tick e no `quit`.
- **Unload**: remove `plotCounts[plotId]` após o flush final.

**`listener/PlantationListener`** (coeso, estado por-terreno compartilhado, como o
`ParkourListener`):

- `onPlace(BlockPlaceEvent, priority=HIGH, ignoreCancelled=true)`: se o bloco é
  cultura e está num terreno; checa `can_plant` (cache `plot:{plotId}`); computa
  `cap(crop)`; se `count >= cap` → `event.setCancelled(true)` + mensagem
  `§cLimite de {cultura} atingido neste terreno ({cap}).`; senão `count++`.
- `onBreak(BlockBreakEvent, priority=HIGH, ignoreCancelled=true)`: se cultura num
  terreno; checa `can_harvest`; o bloco **dropa normalmente** (item vai pro
  inventário — a venda paga depois, §4.4b); se o bloco é um **contado** (base
  plantada) → `count--` (clamp ≥ 0). **Nada de Money aqui.**
- **Proteção anti-drift (decisão §3):**
  - `onExplode` (`EntityExplodeEvent`/`BlockExplodeEvent`) → **cancela** dentro do
    terreno (sem destruir cultura/build).
  - `onFromTo` (`BlockFromToEvent`) → **cancela** espalhamento de água/lava no
    terreno.
  - `onPistonPlace` (`BlockPlaceEvent` de `PISTON`/`STICKY_PISTON`): conta os
    pistões do terreno (`plotPistons`); se `placed >= pistonLimit(plotId)` →
    **cancela** + "§cLimite de pistões atingido — compre mais no /terreno.".
    `pistonLimit = 1 + tier(plot_upgrades["piston_limit"])`. Remover um pistão
    decrementa. Pistões **dentro** do limite operam normalmente.

**`menu/PlantationMenu`** (aberto pela GUI `/terreno` da Fase 13 ou por
`/plantacao`): busca `crystal.plantations().get(plotId)` off-thread; um item por
cultura mostrando `count/cap` e o `value`; item do upgrade `plantation_cap`
(tier atual + custo do próximo, via Fase 13). Cliques cancelados; comprar tier
delega ao fluxo de upgrade da Fase 13 (`plot().upgrade(...)`).

**`commands/PlantationCommand`** (`/plantacao`): abre o `PlantationMenu`;
`/plantacao reconciliar` (`crystal.plantation.admin`, default op) faz **um** scan
do terreno carregado, recomputa as contas e chama `save` — a reconciliação
opt-in.

#### 4.4b Venda das culturas (`crystal-plantation`)

Como a colheita **dropa item** (decisão §3), o Money vem de um **caminho de
venda**. A Fase 14 entrega uma venda **funcional** (a loja-NPC rica é da Fase 16):

- **`menu/SellMenu`** (aberto por `/vender` ou por um botão "Vender" no
  `PlantationMenu`): varre o inventário do jogador, identifica itens de cultura
  (mapa cultura→`Material`, §4.1), calcula `total = Σ (qtd × value(crop)) ×
  multiplier` (`value` da config `plantation`, `multiplier` de `rankup:{uuid}`,
  fail-open 1.0), **remove os itens** e credita via `economy().addMoney(uuid,
  total, "plantation-sell")` **off-thread** (aditivo, Fase 8 → `money-updated`).
  GUI-first; um "vender tudo" cobre o caso comum.
- **`commands/SellCommand`** (`/vender`): atalho para o `SellMenu`/vender-tudo.
- A venda funciona em **qualquer** servidor (é inventário + HTTP), não exige o
  terreno montado. O `value` e o `multiplier` são resolvidos **na venda**, então
  prestigiar antes de vender aumenta o ganho.

### 4.5 Composição com o `crystal-plot` (Fase 13)

Ambos rodam no `terrenos` e lêem o mesmo cache `plot:{plotId}`. O `crystal-plot`
mantém a proteção **genérica** (build/break por membro, limites do terreno) em
`EventPriority.NORMAL`; o `crystal-plantation` roda em `HIGH` com
`ignoreCancelled=true` — se o plot já cancelou (jogador sem `can_build`/fora do
terreno), o plantation nem processa. Assim `plant`/`harvest` (específicos de
cultura) compõem com build/break sem duplo-cancelamento. A permissão de plantio é
`can_plant`; a de colheita, `can_harvest` (colunas de `plot_settings`, Fase 13).

### 4.6 Gateway

`api-gateway/application.yml` ganha uma rota (mesmo formato das existentes):

```yaml
- id: rankup-plantation
  uri: lb://rankup-service
  predicates:
    - Path=/api/plantation/**
```

Sem mudança no `ServiceTokenAuthFilter` (já guarda todo `/api/**`).

### 4.7 SDK `crystal-core`

- `http/PlantationData` (`record`): `PlantationData(String plotId,
  List<CropCount> crops)`, com `CropCount(String crop, int plantedCount, int cap)`.
- Métodos na `BackendHttpClient` (estilo economy/plot, via `send`):
  `getPlantations(plotId)` (allowNotFound → lista vazia), `putPlantations(plotId,
  counts)`.
- `http/PlantationClient`: facade fino sobre a `BackendHttpClient`. `CrystalCore`
  ganha `plantations()` (`new PlantationClient(backend)`), seguindo o `economy()`
  da Fase 8 e o `plots()` da Fase 12. **Sem** tópico/chave nova no SDK.

## 5. Fluxo de dados

**Plantar (enforcement do cap, sem round-trip)**
```
BlockPlaceEvent (cultura, dentro do terreno)
  → can_plant? (cache plot:{plotId})  não → deixa o crystal-plot negar
  → cap = baseCap(crop) + bonusPerTier × tier   (config + plot_upgrades, tudo já em cache)
  → count(crop) >= cap ? cancela + "§cLimite atingido"  :  count(crop)++
  (memória; flush write-behind no save/unload do terreno → PUT /api/plantation/{plotId})
```

**Colher (dropa item) + vender (paga Money)**
```
BlockBreakEvent (cultura colhível, dentro do terreno)
  → can_harvest? (cache plot:{plotId})
  → bloco dropa item (vanilla)              // sem Money aqui
  → se bloco contado (base plantada): count(crop)--  (clamp ≥ 0)

/vender (ou botão Vender)  → SellMenu:
  total = Σ (qtd item × value(crop)) × multiplier   (value config, multiplier rankup:{uuid})
  remove itens do inventário → economy().addMoney(uuid, total, "plantation-sell") → money-updated
```

**Load / flush do terreno (piggyback no §9)**
```
plot-loaded → plantations().get(plotId) → popula plotCounts[plotId]
save de segurança/unload (§9) → plantations().save(plotId, counts)  (PUT absoluto, single-writer)
```

**Regra Kafka × Redis (master §5/§8):** a Fase 14 **não** adiciona tópico. A conta
é estado quente **local** (single-writer sticky), lido da memória no place/break —
nem Kafka nem Redis por plantio. A colheita reusa `money-updated` (economy). Cap
muda por `plot-updated` (Fase 13) + `config-updated`, que invalidam `plot:{plotId}`
e o catálogo.

## 6. Tratamento de erros (fail-open, padrão do projeto)

- **Backend fora no load**: `plantations().get` falha → `plotCounts[plotId]` fica
  vazio (contas 0) e o enforcement usa o cap cheio; o flush no unload persiste o
  que houver. Nada trava a main thread (chamada async). *Aceitável*: single-writer
  garante que o próximo load lê o snapshot correto.
- **Backend fora no flush**: retry no próximo ciclo; a memória continua
  autoritativa. Perda máxima = janela desde o último flush bem-sucedido (paridade
  com o save de segurança do §9).
- **Redis fora** (cache `plot:{plotId}`/`rankup:{uuid}`): leitura de tier/permissão
  cai no fallback (cap base, permissão via HTTP do `crystal-plot`); `multiplier`
  fail-open a `1.0`. `log.warn`, nada quebra.
- **Config ausente/corrompida**: `PlantationCatalog` fail-open → cultura sem
  `baseCap` é tratada como **não plantável** (cap 0 → sem produção), sem crash; GUI
  mostra "catálogo indisponível".
- **Conta negativa/estourada**: clamp `[0, cap]` em memória e no flush — drift
  nunca vira conta absurda nem cap negativo.
- **Terreno não carregado**: place/break num plot sem entrada em `plotCounts`
  (só ocorre fora do ciclo normal) → recarrega sob demanda ou ignora o
  enforcement (fail-open), logando `warn`.

## 7. Propagação entre servidores

- **Conta** é **local** ao servidor que hospeda o terreno (sticky, §7.2 do master):
  só o dono/membros ativos estão naquela instância, então não há leitura
  cross-instância de conta viva. Ao migrar de instância (após unload+save), o
  próximo servidor carrega o snapshot do Postgres — coerente sem estado externo.
- **Recompensa de colheita** muda `economy:{uuid}` + emite `money-updated` (write-
  through da Fase 8): o HUD/scoreboard (Fase 10) reflete no próximo tick.
- **Cap** propaga por `plot-updated` (compra de tier, Fase 13) e `config-updated`
  (edição de `plantation`): `crystal-plantation` invalida `plot:{plotId}`/catálogo
  e recomputa o cap — **hot-reload sem restart**, como `chat`/`rank`.

## 8. Testes

**Unitário backend** (`PlantationServiceTest`, JUnit 5 + Mockito, mockando
`PlantationRepository` + `PlotUpgradeService` + `StringRedisTemplate`, no molde do
`RankServiceTest` da Fase 9):
1. `get` computa `cap = baseCap + bonusPerTier × tier` (tier 0 → só base; tier 2 →
   base + 2×bonus).
2. `get` retorna `count=0` para cultura sem linha.
3. `save` faz clamp `[0, cap]` (conta negativa → 0; acima do cap → cap).
4. `PlantationCatalog` desserializa `config:plantation` do Redis e resolve
   `baseCap`/`value`.

**Unitário plugin** (JVM puro, sem Bukkit, onde a lógica é isolável): o cálculo de
cap e a decisão de enforcement (`count >= cap → cancelar`) e o clamp da conta.

**Manual (curl + em jogo, CLAUDE.md)** contra o gateway com `Authorization: Bearer
<BACKEND_SERVICE_TOKEN>`:
- **curl** (determinístico): `PUT /api/config/plantation` com o catálogo de
  exemplo; `GET /api/plantation/{plotId}` (terreno da Fase 12) confirma caps;
  `PUT /api/plantation/{plotId}` seta contas; `GET` confirma clamp.
- **em jogo** (montar `crystal-plantation` no `terrenos-01`, **recriar** o
  container): entrar no terreno, plantar `wheat` até `baseCap` → o próximo place é
  **cancelado** com a mensagem; comprar tier de `plantation_cap` (GUI Fase 13) →
  cap sobe, plantio libera; colher paga Money (`/saldo`) proporcional ao `value` e
  ao multiplicador de prestígio; **reconectar** (ou reiniciar o `terrenos-01`) e a
  conta persiste (≤ janela do último flush); **hot-reload**:
  `PUT /api/config/plantation` mudando um `baseCap` → o cap na GUI muda **sem
  restart**.

## 9. Arquivos afetados (resumo)

**Backend `backend/rankup-service/`** (o módulo já existe desde a Fase 8):
- **NOVO** `plantation/{api,application,domain}/…` (`PlantationController`,
  `PlantationService`, `PlantationCatalog`, `PlantationEntity`, `PlotCropId`,
  `PlantationRepository`).
- **NOVO** `db/migration/V6__rankup_plantations.sql` (`plot_plantations` PK
  `(plot_id, crop)`, `planted_count INT d0`).
- **NOVO** teste `PlantationServiceTest`.
- `backend/api-gateway/src/main/resources/application.yml` (+ rota
  `/api/plantation/**`).
- *(Sem mudança em `KafkaTopics`/`create-topics.sh` — nenhum tópico novo.)*

**SDK `plugins/crystal-core`:**
- **NOVO** `http/PlantationData`, `http/PlantationClient`; `http/BackendHttpClient`
  (+ `getPlantations`/`putPlantations`); `CrystalCore` (+ `plantations()`).

**Plugins:**
- **NOVO** `plugins/crystal-plantation/` (`pom.xml`, `plugin.yml`,
  `CrystalPlantationPlugin`, `listener/PlantationListener`,
  `menu/PlantationMenu`, `commands/PlantationCommand`).
- `plugins/pom.xml` (+ 1 módulo).
- *(Integração com `crystal-plot`: leitura do cache `plot:{plotId}` e do fluxo de
  upgrade da Fase 13 — sem alterar o `crystal-plot`.)*

**Infra/config de dados:** o catálogo `plantation` é **dado** (via
`PUT /api/config/plantation`), não arquivo de código — seed inicial via curl (§8).

**Compose:** `docker-compose.yml` monta o jar `crystal-plantation` no
`terrenos-01/-02` (volumes, como `crystal-plot` na Fase 12), **recriar** o
container.

## 10. Divergências do código atual / master (a reconciliar)

> **Decisões do dono (2026-07-01), refletidas em §3/§4.4:** (1) **Colheita dropa
> item → venda na loja** (não Money direto); a Fase 14 entrega uma **venda
> funcional** (`/vender`/`SellMenu`, `value×multiplier`), e a **loja-NPC rica é a
> Fase 16**. (2) **Plantio livre até o cap** (sem semente). (3) Anti-drift:
> **explosões e espalhamento de água canceladas** no terreno; **pistões limitados
> a 1 por terreno**, +1 por compra em Money (**novo upgrade `piston_limit`** no
> catálogo `plot_upgrades` da Fase 13). Consequências a reconciliar: o
> `plot_upgrades` da Fase 13 ganha o item `piston_limit`; o `crystal-plantation`
> passa a contar pistões por terreno; e existe um caminho de venda antes do NPC da
> Fase 16.


- **Depende das Fases 12 e 13, ainda não construídas.** No branch **não** existem
  `backend/rankup-service/`, `plugins/crystal-plot/`, `plugins/crystal-economy/`,
  nem os contextos `plot`/`economy` — tudo da Fase 8 em diante está **desenhado,
  não implementado**. A Fase 14 assume, das anteriores: o ciclo §9 do terreno
  (lock/lease/version, single-writer), o cache `plot:{plotId}` + tópico
  `plot-updated`, as colunas `can_plant`/`can_harvest` de `plot_settings`, o
  catálogo `plot_upgrades` com um item de cap, o `EconomyService.addMoney`
  (aditivo, Fase 8) e o `multiplier` de `rankup:{uuid}` (Fase 9). **A Fase 14 só
  começa depois de 12/13 mergeadas.** Migração **V6** pressupõe que 12/13 usaram
  V4/V5.
- **`cap` do master §4 não é fonte de verdade.** O master lista `plot_plantations`
  com coluna `cap INT`. A Fase 14 **computa** o cap (`base` config + `bonusPerTier
  × tier`), não o persiste — para caps mudarem por hot-reload de config **e** por
  upgrade sem migrar linhas. A coluna `cap` do master é **derivada**; a Fase 14 a
  **omite** da tabela (guarda só `planted_count`). Reconciliar no master §4.
- **Sem tópico Kafka de plantação.** O master §5 não lista um tópico de plantação e
  a Fase 14 confirma que **não precisa**: conta é single-writer local; colheita
  reusa `money-updated`; cap muda por `plot-updated`/`config-updated`. Registrar que
  a produção **não** vira fato de domínio Kafka (ao contrário de rank/prestige).
- **Drift de contagem (pistão/explosão/água/fade/pisoteio).** A contagem
  incremental pressupõe que **todo** desaparecimento de um bloco contado passe por
  um evento que o plugin observa. Culturas somem sem `BlockBreakEvent` do jogador:
  pistão empurrando, TNT/creeper, água/lava fluindo sobre a plantação, `BlockFade`,
  pisoteio de farmland, mob comendo. A Fase 14 **fica segura cancelando** esses
  vetores dentro do terreno (§4.4) — o terreno é um mundo VOID controlado com
  proteção de plot, então cancelar liquid-flow/pistão/explosão sobre cultura é
  coerente com a proteção existente e mantém a conta **exata**. Onde cancelar não
  couber, o **clamp `[0, cap]`** impede conta absurda e o `/plantacao reconciliar`
  (scan único, opt-in) re-sincroniza. **Nenhum** scan periódico.
- **Crescimento vertical (cana/cactus/bambu) × unidade contada.** Esses crescem em
  altura sem `BlockPlaceEvent`. A Fase 14 conta **só o bloco plantado pelo jogador**
  (base); segmentos crescidos **não** contam mas **pagam** colheita ao serem
  quebrados. Assim o cap limita **plantas**, não altura, e a colheita por segmento
  continua rendendo — comportamento pretendido, registrado para não confundir com
  "cap = blocos totais".
- **Multiplicador de prestígio aplicado no plugin.** O master §4 diz que o
  `multiplier` "é lido para escalar ganhos" sem dizer **onde**. A Fase 14 aplica no
  **plugin** (lê `rankup:{uuid}`, multiplica antes do `addMoney` aditivo), como a
  recompensa das minas (Fase 11), evitando acoplar o contexto `plantation` ao
  `prestige`. Alternativa (backend escalar no `addMoney`) descartada: quebraria a
  fronteira e o write-path aditivo puro do §4.2.
- **`crystal.plantations()` é mais um facade por feature.** Segue `economy()`
  (Fase 8)/`plots()` (Fase 12); nenhuma infra nova de facade — só o client fino
  sobre a `BackendHttpClient`. Registrado por consistência com as divergências das
  Fases 8/9.
