# Design — Fase 13: Terrenos avançado (expansão + membros + permissões + upgrades + level/produção)

Data: 2026-07-01
Branch base: `feat/login-autocomplete-e-troca-senha`

> Sexta fase do jogo **RankUP** sobre a rede RedeCrystal. Sobre a **fundação SWM
> da Fase 12** (`SERVER_TYPE=terrenos`, custom SlimeLoader → `plot_worlds`, ciclo
> de vida lock/lease/version, sticky routing, `/terreno` GUI básica), a Fase 13
> transforma o terreno num espaço **social e progressivo**: **expandir** a área
> (10→20→30→40, pago em Money), **convidar membros**, dar **permissões por membro**
> (build/break/open-chests/plant/harvest) com **enforcement por evento**, comprar
> **upgrades** de um catálogo em config, e exibir **level/produção/invested_money**
> na GUI. Arquitetura de alto nível em
> [`2026-07-01-rankup-arquitetura-design.md`](2026-07-01-rankup-arquitetura-design.md)
> (§2, §4, §5, §6.3, §8, §10) — este spec detalha só o que a Fase 13 entrega.
> Texto de jogador em PT; identificadores/módulos/tópicos/chaves em inglês.

## 1. Problema

Depois da Fase 12, um terreno já **carrega, salva e não corrompe** — mas é
**solitário e estático**: só o dono entra e constrói, o tamanho é fixo, não há
como convidar um amigo nem limitar o que ele faz, e não há progressão (nível,
upgrades, produção). Falta tudo o que torna o terreno um espaço vivo:

1. **Expandir a área construível.** O terreno nasce 10×10 (Fase 12) e precisa
   crescer 10→20→30→40 **pagando Money** — um **débito** (não pode cobrar duas
   vezes) **seguido** do bump do tamanho, atômicos, e o crescimento tem de
   **refletir na região construível** do Slime world VOID (Fase 12).
2. **Membros + permissões.** O dono convida jogadores como **membros**
   (`plot_members`) e define, **por membro**, o que cada um pode fazer:
   `can_build`, `can_break`, `can_open_chests`, `can_plant`, `can_harvest`
   (`plot_settings`). Um membro **sem `can_break` não pode quebrar bloco**; sem
   `can_build` não pode colocar; e assim por diante.
3. **Upgrades.** Um **catálogo em config** (`plot_upgrades`, hot-reload como a
   ladder de ranks) com tiers compráveis que elevam limites (produção, cap de
   membros, cap de área/plantas) — comprar é um **débito** (422 sem Money).
4. **Level/produção/invested_money na GUI.** O `plots` da Fase 12 já tem as
   colunas `level`, `invested_money`; a Fase 13 as **superfície na GUI** e soma o
   Money gasto em expansão/upgrade em `invested_money` (base do "level" do
   terreno).
5. **Consistência cross-instância.** O fleet `terrenos` é sticky **enquanto o
   mundo está ativo** — mas um membro pode editar permissões de outra instância
   (spawn/lobby), ou o dono e um membro estarem em instâncias diferentes. Uma
   mudança de permissão/membro/upgrade numa instância A tem de ser **honrada** na
   instância B que tem o mundo montado. O master resolve isso com o tópico
   **`plot-updated`** invalidando o cache `plot:{plotId}`.

A Fase 13 **reaproveita** a Fase 12 (o `crystal-plot`, o contexto `plot`, o
`PlotClient`, o cache `plot:{plotId}`, o tópico `plot-loaded`/`plot-saved`,
sticky routing) e a Fase 8 (o write-path de **débito condicional** `debit`→422 do
`EconomyService`, chamado **in-process** como na promoção de rank da Fase 9) e a
Fase 9 (catálogo em config central com hot-reload, para o `plot_upgrades`).

## 2. Objetivos e não-objetivos

**Objetivos**
- Estender o contexto `plot` do `rankup-service` (`api/application/domain`) com
  três tabelas novas — `plot_members`, `plot_settings`, `plot_upgrades` — na
  migração `V5__rankup_plot_members_settings_upgrades.sql`.
- Endpoints do master §6.3: `POST`/`DELETE /api/plot/{plotId}/members[/{uuid}]`,
  `PUT /api/plot/{plotId}/settings/{memberUuid}`, `POST /api/plot/{plotId}/expand`
  (débito→422 + optimistic-lock no `size`→409), `POST /api/plot/{plotId}/upgrade`
  (compra tier, débito→422), `PUT /api/plot/{plotId}/name`. `level`/`produção`/
  `invested_money`/membros/permissões/upgrades **surfaced** no `GET /api/plot/**`.
- Emitir `plot-updated {plotId,kind}` (kind ∈ `member`/`setting`/`upgrade`/
  `expand`/`name`) em **toda** mutação; consumido pelo `crystal-plot` das outras
  instâncias para **invalidar `plot:{plotId}`**.
- Catálogo `plot_upgrades` na **config central** (hot-reload como `rank`).
- `crystal-plot`: GUI `/terreno` completa (info com level/produção/
  invested_money; expandir; add/remover membro; permissões por membro; upgrades;
  renomear) no padrão `MenuHolder`+PDC do projeto.
- `crystal-plot`: **enforcement por evento** — listeners de break/place/chest/
  plant/harvest que **negam** a ação a quem não tem a permissão, lendo o cache
  `plot:{plotId}` (nunca chamada HTTP no evento, na main thread).
- Invalidação cross-instância via `plot-updated` (consumidor Kafka no
  `crystal-plot`).
- SDK `crystal-core`: DTOs `PlotMember`/`PlotSettings`/`PlotUpgrade` e métodos
  novos no `PlotClient` (members/settings/expand/upgrade/name), reusando a
  `BackendHttpClient` (retry/auth) e `InsufficientFundsException` (422).

**Não-objetivos** (ficam para outras fases do master §10)
- **Plantações** (caps por cultura, contagem de plantas, colheita → economy) —
  **Fase 14** (`crystal-plantation`, `plot_plantations`). A Fase 13 só entrega as
  **permissões** `can_plant`/`can_harvest` (enforcement de plantar/colher);
  quem transforma isso em cap/produção de cultura é a Fase 14.
- **Fundação SWM** (load/save/unload, lock/lease/version, sticky, `/terreno`
  básica) — **Fase 12** (pré-requisito; ver §10).
- **Produção passiva de Money por terreno** como produtor real de economia — o
  campo `produção` é **exibido** (derivado de level/upgrades) e usado pela Fase 14
  (plantações); a Fase 13 não roda um tick de geração de Money.
- NPC de `/terreno` (Citizens) e holograma de topo de terrenos — **Fase 16**.
- Home de terreno / teleporte — já entregue na Fase 12 (`/terreno` básica).

## 3. Decisões de design

| Tema | Decisão |
|------|---------|
| **Enforcement por evento, cache-first** | Break/place/chest/plant/harvest são **negados no listener** lendo `plot:{plotId}` (Redis, via `crystal-plot`), **nunca** um round-trip HTTP por evento na main thread. O cache traz o `owner_uuid` + o mapa de permissões por membro; miss frio → **read-through** off-thread popula o cache e, até popular, **fail-safe** (ver §6). O terrenos-XX que montou o mundo já tem esse cache quente do `plot-loaded` (Fase 12). |
| **Owner sempre pode tudo** | O `owner_uuid` (coluna de `plots`, Fase 12) **não** tem linha em `plot_settings`; o enforcement dá bypass total ao dono. `plot_settings` é só para **membros**. Não-membro/não-dono **não** interage (nem quebra, nem abre baú) — o terreno é privado por padrão. |
| **Expansão atômica (débito + size version→409)** | `POST /expand` é **`@Transactional`**: `EconomyService.debit(owner, priceDoNívelAlvo)` in-process → 0 linhas = **422** (rollback); `PlotRepository.expand(plotId, novoSize, expectedVersion)` (optimistic-lock **manual** no `plots.version`, como `InventoryService.save`) → 0 linhas = **409** (rollback, estorna). Preço do próximo nível **autoritativo** no servidor (config `plot_upgrades`/`plot`), não do cliente. Pós-commit: `invested_money += preço`, write-through `plot:{plotId}`, emite `money-updated` + `plot-updated{expand}`. |
| **Tamanho → região construível do Slime VOID** | O `plots.size` (10/20/30/40) é o **lado** de uma área quadrada centrada na origem do Slime world do terreno (Fase 12). A área construível é `[-size/2, +size/2]` em X/Z (bounds inteiros por coordenada de bloco), Y livre dentro do build-height do VOID. **Não** há WorldGuard nem geração de chunk nova: expandir só **aumenta os bounds** que o enforcement de coordenada usa (§4.6). O mundo VOID já é "infinito ar"; crescer não gera terreno, só libera mais blocos para construir. |
| **Upgrades em config, não em tabela** | O **catálogo** de upgrades (id, nome, tiers, preço por tier, efeito) vive na **config central** key `plot_upgrades`, hot-reload por `config-updated` como a ladder `rank` (Fase 9). `plot_upgrades` (tabela) guarda só o **tier possuído** por terreno (`PK(plot_id,upgrade_id)`, `tier`). Comprar lê o preço do **tier alvo** da config (autoritativo). |
| **Compra de upgrade: débito in-process** | Como `economy` e `plot` vivem no **mesmo** `rankup-service`, `PlotService.upgrade` chama o bean `EconomyService.debit(...)` direto (sem HTTP para si), no molde do `RankService.promote` (Fase 9). `@Transactional`: débito→422 **e** upsert do tier commitam juntos. |
| **`plot-updated` invalida `plot:{plotId}`** | Toda mutação (member/setting/upgrade/expand/name) emite `plot-updated{plotId,kind}` (key = `plotId`) **após** o commit. Cada `crystal-plot` consome o tópico e, para o **seu** plot montado, faz `del plot:{plotId}` (invalida) e **re-lê** (read-through) — assim uma edição na instância A é honrada pelo enforcement na instância B em ≤ tempo de propagação Kafka. O próprio produtor também atualiza o cache no write-through; `plot-updated` é para os **outros**. |
| **Membros ≠ sticky, edição de qualquer lugar** | A GUI `/terreno` (membros/permissões/upgrades/rename) funciona em **qualquer** servidor (spawn/lobby/terrenos) — é só HTTP + Kafka, não exige o mundo montado. Só o **enforcement** (listeners) roda onde o mundo está (terrenos-XX). Expansão idem: muta metadados; o efeito visível (mais área) aparece porque os bounds vêm do `plots.size` relido. |
| **Facade SDK reusa PlotClient (Fase 12)** | Sem client novo: o `PlotClient` da Fase 12 ganha os métodos members/settings/expand/upgrade/name; DTOs `PlotMember`/`PlotSettings`/`PlotUpgrade` novos. `expand`/`upgrade` propagam **422** (`InsufficientFundsException`, Fase 8) e **409** (`BackendException.statusCode()==409`). |
| **Montagem na Fase 13** | `crystal-plot` já roda no fleet `terrenos` (Fase 12); a Fase 13 só estende o mesmo plugin. GUI de administração (membros/upgrades) exercível também de um lobby, pois é HTTP puro. |

## 4. Arquitetura

### 4.1 Migração `V5__rankup_plot_members_settings_upgrades.sql`

Três tabelas novas no `rankup_db`, todas do contexto `plot`, todas com FK lógica
para `plots.plot_id` (Fase 12). Segue o master §4:

```
plot_members   PK(plot_id, member_uuid)
  plot_id UUID · member_uuid UUID · added_at TIMESTAMPTZ d now()
plot_settings  PK(plot_id, member_uuid)      -- permissões POR MEMBRO
  plot_id UUID · member_uuid UUID ·
  can_build BOOL d false · can_break BOOL d false · can_open_chests BOOL d false ·
  can_plant BOOL d false · can_harvest BOOL d false
plot_upgrades  PK(plot_id, upgrade_id)        -- tier POSSUÍDO (catálogo em config)
  plot_id UUID · upgrade_id VARCHAR(48) · tier INT d0
```

> `V4` é reservado à Fase 12 (`plots` + `plot_worlds`); a Fase 13 é `V5`
> (numeração Flyway imutável — ver §10 se a Fase 12 usar outro número).

**DECIDIDO (dono):** um membro recém-convidado entra **sem nenhuma permissão**
(`plot_settings` tudo `false`); o dono **libera cada uma** manualmente na GUI de
permissões. Máximo controle — o convite dá acesso ao terreno, não à construção.
Remover membro apaga ambas as linhas.

### 4.2 Contexto `plot` — extensões `domain`

- **`PlotMemberEntity`** (`@Table("plot_members")`, `@IdClass`/chave composta
  `plot_id+member_uuid`, no molde do `InventoryId` do core-service).
- **`PlotSettingsEntity`** (`@Table("plot_settings")`, mesma PK composta): os cinco
  booleanos; método `update(build,break,chests,plant,harvest)`.
- **`PlotUpgradeEntity`** (`@Table("plot_upgrades")`, PK `plot_id+upgrade_id`):
  `tier`; método `setTier(int)`.
- **`PlotEntity`** (Fase 12) ganha, se ainda não tiver: `expand(newSize,
  investedDelta)` (bump `size`, `invested_money += delta`, bump `version`,
  `updated_at`) — optimistic-lock manual no `version`, como `InventoryEntity`.

**Repositórios** (`JpaRepository`): `PlotMemberRepository`
(`findByPlotId(plotId)`, `existsById`, `deleteById`), `PlotSettingsRepository`,
`PlotUpgradeRepository` (`findByPlotId`). `PlotRepository` (Fase 12) ganha o
`@Modifying @Query` de expansão atômica (molde Fase 8/9):

```java
@Modifying(clearAutomatically = true)
@Query("UPDATE PlotEntity p SET p.size = :newSize, "
     + "p.investedMoney = p.investedMoney + :investedDelta, "
     + "p.version = p.version + 1, p.updatedAt = CURRENT_TIMESTAMP "
     + "WHERE p.plotId = :plotId AND p.version = :expectedVersion")
int expand(UUID plotId, int newSize, long investedDelta, int expectedVersion); // 0 = 409
```

### 4.3 Catálogo `plot_upgrades` na config central (key `plot_upgrades`)

Mesma mecânica da ladder `rank` (Fase 9): gerida pelo `ConfigService` do
core-service (`PUT /api/config/plot_upgrades` → cache `config:plot_upgrades` +
`config-updated`), **hot-reload** para o plugin (`ConfigProvider.onChange`) e
**read-through** para o serviço (`config:plot_upgrades` no Redis compartilhado +
fallback HTTP no miss frio, exatamente como o `RankCatalog` da Fase 9). Forma:

```json
{
  "upgrades": [
    { "id": "production", "name": "Produção",
      "tiers": [
        { "tier": 1, "price": 50000,  "productionBonus": 0.10 },
        { "tier": 2, "price": 150000, "productionBonus": 0.25 }
      ] },
    { "id": "members", "name": "Membros",
      "tiers": [
        { "tier": 1, "price": 25000, "memberCap": 5 },
        { "tier": 2, "price": 80000, "memberCap": 10 }
      ] }
  ]
}
```

E a **tabela de preços de expansão** (10→20→30→40) vive na key `plot` (parâmetros
gerais do terreno, separada de `plot_upgrades` como `prestige` é separado de
`rank`): `{ "expansion": { "20": 100000, "30": 400000, "40": 1000000 },
"baseSize": 10 }`. O `PlotCatalog` (novo, molde do `RankCatalog`) desserializa
ambas e expõe `expansionPrice(targetSize)`, `upgrade(id)`, `tierPrice(id,tier)`.

### 4.4 `application/PlotService` (extensões)

Reusa o `PlotService` da Fase 12 (que já faz `get`/`create`/`world` blob). Novos
métodos (Redis via `StringRedisTemplate`, `EconomyService` in-process,
`EventPublisher`, `PlotCatalog`):

| Método | Comportamento |
|--------|---------------|
| `addMember(plotId, memberUuid)` | valida dono existe; respeita `memberCap` (upgrade `members`, senão default da config); upsert `plot_members` + `plot_settings` **default-false** (membro sem permissões até o dono liberar); write-through `plot:{plotId}`; emite `plot-updated{member}`. |
| `removeMember(plotId, memberUuid)` | apaga `plot_members`+`plot_settings`; write-through; `plot-updated{member}`. |
| `setSettings(plotId, memberUuid, flags)` | exige que seja membro; `PlotSettingsEntity.update(...)`; write-through; `plot-updated{setting}`. |
| `expand(plotId)` | **`@Transactional`**: carrega plot; `target = size+10` (≤40, senão 409/"máximo"); `price = catalog.expansionPrice(target)`; `economyService.debit(owner, price)` == 0 → **422** (rollback); `repository.expand(plotId, target, price, plot.version)` == 0 → **409** (rollback, estorna). Pós-commit: write-through `plot:{plotId}`; `money-updated` + `plot-updated{expand}`. |
| `upgrade(plotId, upgradeId)` | **`@Transactional`**: `tier = current+1`; `price = catalog.tierPrice(upgradeId, tier)` (inexistente → 409/"tier máximo"); `debit(owner, price)` == 0 → **422**; upsert `plot_upgrades.tier`; `invested_money += price`. Pós-commit: write-through; `money-updated` + `plot-updated{upgrade}`. |
| `rename(plotId, name)` | valida `name` (≤48, sanitiza); update `display_name`; write-through; `plot-updated{name}`. |

`cache(plot, members, settings, upgrades)` (fail-open, `try/catch` como
`EconomyService.cache` da Fase 8): grava `HSET plot:{plotId}` com
`owner`, `size`, `level`, `invested_money`, `display_name`, e um sub-mapa
serializado de **permissões por membro** (`member:{uuid}` → bitmask/json dos 5
booleanos) e **tiers de upgrade** — em uma leitura o enforcement resolve tudo.
`EXPIRE 5min` (master §8). Falha de Redis → só `log.warn`.

**`produção`/`level` derivados:** o `GET` computa `level` a partir de
`invested_money` (faixas na config `plot`) e `produção` a partir dos tiers de
`plot_upgrades` (`productionBonus` somados) — **não** são colunas mutáveis
próprias; `invested_money` (coluna real) é a fonte. Exposto no `PlotResponse`.

### 4.5 `api/PlotController` (extensões)

`@RequestMapping("/api/plot")` (Fase 12), controller fino, DTOs `record`
aninhados. Novos handlers (master §6.3):

| Método/Path | Body → Resposta |
|---|---|
| `POST /{plotId}/members` | `{memberUuid}` → 200 (`PlotResponse` c/ membros) |
| `DELETE /{plotId}/members/{uuid}` | → 200 |
| `PUT /{plotId}/settings/{memberUuid}` | `{canBuild,canBreak,canOpenChests,canPlant,canHarvest}` → 200 |
| `POST /{plotId}/expand` | (sem body) → `PlotResponse` novo; **422** sem Money, **409** concorrência/máximo |
| `POST /{plotId}/upgrade` | `{upgradeId}` → `PlotResponse`; **422** sem Money, **409** tier máximo/concorrência |
| `PUT /{plotId}/name` | `{name}` → `PlotResponse` |
| `GET /{plotId}` (Fase 12, estendido) | + `level`, `production`, `investedMoney`, `List<PlotMember>`, `List<PlotSettings>`, `List<PlotUpgrade>` |

### 4.6 `crystal-plot` — enforcement por evento (novo `listener/`)

Espelha o `LobbyProtection` (coesão-listener: um só `Listener` com vários
`@EventHandler`, cancela ações). Um **`PlotProtection`** por instância terrenos:

- **Cache local por plot montado.** O `crystal-plot` mantém, para o(s) plot(s)
  ativos nesta instância, um snapshot em memória de `owner` + `size` (bounds) +
  permissões por membro, **hidratado do `plot:{plotId}`** no `plot-loaded` (Fase
  12) e **re-hidratado** ao consumir `plot-updated{plotId}` (§4.7). Assim os
  eventos leem **memória local**, não Redis por evento (o Redis já foi lido 1× na
  hidratação) — mesmo espírito do `crystal-tag` lendo `tag:overrides` 1×/tick.
- **`onBreak` (`BlockBreakEvent`)** — se o jogador é dono → permite; se membro com
  `can_break` → permite; senão `setCancelled(true)` + aviso PT ("§cVocê não tem
  permissão para quebrar aqui."). `ignoreCancelled=true`.
- **`onPlace` (`BlockPlaceEvent`)** — idem `can_build`. **Também** valida os
  **bounds** de `size`: colocar fora de `[-size/2,+size/2]` em X/Z é negado
  ("§cFora da sua área. Expanda o terreno.") — é o **enforcement de coordenada**
  que substitui WorldGuard num VOID (master §9; §10 desta fase).
- **`onChest` (`PlayerInteractEvent` RIGHT_CLICK_BLOCK em container)** — se o
  bloco é baú/barril/shulker etc. e o jogador não é dono nem membro com
  `can_open_chests` → cancela.
- **`onPlant`/`onHarvest`** — a Fase 13 registra os hooks (plantar =
  `BlockPlaceEvent` de cultura; colher = `BlockBreakEvent`/`PlayerHarvestBlockEvent`
  de cultura madura) gated por `can_plant`/`can_harvest`, mas a **contagem/cap por
  cultura** e a **recompensa** ficam para a **Fase 14** (plantação). Aqui é só o
  gate de permissão.

Todos fail-safe: exceção no cálculo → `warning` + **nega** a ação (privado por
padrão), nunca derruba o servidor (padrão do projeto).

### 4.7 `crystal-plot` — GUI `/terreno` (estende a básica da Fase 12)

A GUI da Fase 12 (info/home/rename) ganha telas novas, no padrão `MenuHolder`+PDC
do projeto (`Menus.MenuHolder(type,target,targetName)`, `item`/`glow`/`bodySlots`/
`framedSize`/`barLeft`, cliques cancelados, ação carregada em PDC/`MenuHolder`):

- **Tela principal `/terreno`** — info: nome, tamanho atual + próximo (preço),
  **level**, **produção**, **invested_money**, nº de membros; botões: Expandir,
  Membros, Upgrades, Renomear.
- **Expandir** — mostra `size→size+10` e o preço (config); confirmar → chama
  `crystal.plots().expand(plotId)` **off-thread**; `InsufficientFundsException` →
  "§cMoney insuficiente."; 409/máximo → "§eTamanho máximo." / "§cTente novamente.";
  sucesso → reabre com o novo tamanho.
- **Membros** — lista membros (cabeça do jogador); clicar um abre a tela de
  **permissões** dele (5 toggles: build/break/chests/plant/harvest — clicar
  alterna e chama `PUT /settings/{uuid}` off-thread); botão "Adicionar" pede o
  nome no chat (fluxo `awaiting*`+`AsyncChatEvent` cancelado, como o rename de pet
  do lobby / `/tag editar`), resolve UUID, `POST /members`; botão "Remover".
- **Upgrades** — lista o catálogo (`ConfigProvider.get("plot_upgrades")` +
  `onChange` → hot-reload da GUI, como `crystal-rank` lê `rank`); cada upgrade
  mostra tier atual/próximo + preço; comprar → `crystal.plots().upgrade(plotId,id)`
  off-thread (422/409 tratados).
- **Renomear** — prompt no chat → `PUT /name`.

Todas as buscas de dados (`crystal.plots().get(...)`, saldo) **off-thread**; a
montagem/abertura do inventário na **main thread** (padrão `ParkourTopMenu`).

### 4.8 `crystal-plot` — consumidor `plot-updated` (invalidação cross-instância)

Registra consumo de `plot-updated` (Kafka, via o mesmo mecanismo de consumo dos
outros tópicos do SDK). Ao receber `{plotId,kind}`:
- Se **este** servidor tem o `plotId` montado → `del plot:{plotId}` (invalida) e
  **re-hidrata** o snapshot em memória do `PlotProtection` (§4.6) via read-through
  off-thread. Se não tem o plot montado → ignora (ou só invalida o cache Redis se
  o mantiver). Assim, editar permissões no lobby/spawn (instância A) faz o
  terrenos-XX (instância B) parar de deixar o ex-membro quebrar bloco em ≤
  propagação Kafka. O **produtor** da mutação já atualizou o cache no
  write-through; `plot-updated` cobre as **outras** instâncias (master §5/§8).

### 4.9 SDK `crystal-core`

- `messaging/KafkaTopics`: `PLOT_UPDATED = "plot-updated"` já existe da Fase 12
  (se não, adiciona-se aqui) + na lista `ALL`.
- `http/PlotMember` (`record`: `plotId`, `memberUuid`, `addedAt`),
  `http/PlotSettings` (`record`: `memberUuid` + 5 booleanos),
  `http/PlotUpgrade` (`record`: `upgradeId`, `tier`). `PlotData` (Fase 12) ganha
  `level`, `production`, `investedMoney`, e as três listas no `GET`.
- Métodos novos na `BackendHttpClient` (estilo Fase 12, via `send`):
  `addPlotMember`, `removePlotMember`, `setPlotSettings`, `expandPlot`,
  `upgradePlot`, `renamePlot`. `expandPlot`/`upgradePlot` propagam **422**/**409**.
- `http/PlotClient` (Fase 12) ganha os delegates e as chaves de cache
  (`PLOT_KEY_PREFIX = "plot:"`). `CrystalCore.plots()` já existe (Fase 12).

### 4.10 Gateway

Nenhuma rota nova: a Fase 12 já roteia `Path=/api/plot/**` → `lb://rankup-service`
(cobre members/settings/expand/upgrade/name). `ServiceTokenAuthFilter` inalterado.

## 5. Fluxo de dados

**Expandir (`/terreno` → Expandir)**
```
clique "expandir" → plots().expand(plotId) [off-thread] → POST /api/plot/{plotId}/expand
  → PlotService.expand @Transactional:
      target = size+10 (>40 → 409 "máximo")
      price  = PlotCatalog.expansionPrice(target)        (autoritativo, config)
      EconomyService.debit(owner, price) → 0 linhas = 422 (rollback, nada muda)
      PlotRepository.expand(plotId, target, price, plot.version) → 0 = 409 (rollback, estorna)
  → commit → write-through plot:{plotId} (size/invested_money)
  → money-updated {owner,...} + plot-updated {plotId,"expand"}   (key=plotId)
  ← PlotResponse → GUI reabre; PlotProtection re-hidrata bounds (via plot-updated na própria instância ou write-through local)
```

**Editar permissão de membro (instância A) → honrado na instância B**
```
/terreno → Membros → membro → toggle can_break=false
  → plots().setSettings(plotId, uuid, flags) → PUT /api/plot/{plotId}/settings/{uuid}
  → PlotService.setSettings: update plot_settings → write-through plot:{plotId}
  → plot-updated {plotId,"setting"}   (Kafka, key=plotId)
  ┌─ instância B (terrenos-XX, mundo montado):
  │    consome plot-updated → del plot:{plotId} → re-hidrata PlotProtection
  │    membro tenta quebrar → onBreak lê snapshot novo → can_break=false → CANCELA
  └─
```

**Comprar upgrade**
```
/terreno → Upgrades → comprar tier → plots().upgrade(plotId,"production")
  → POST /api/plot/{plotId}/upgrade {upgradeId}
  → PlotService.upgrade @Transactional:
      tier = current+1; price = PlotCatalog.tierPrice("production", tier) (config)
      EconomyService.debit(owner, price) → 0 = 422
      upsert plot_upgrades.tier ; invested_money += price
  → commit → write-through → money-updated + plot-updated {plotId,"upgrade"}
```

**Regra Kafka × Redis (master §5/§8):** `plot-updated` é **fato** (outras
instâncias **reagem** invalidando cache). O estado quente lido pelo enforcement é o
**snapshot local** hidratado de `plot:{plotId}` (Redis) — **não** se consome Kafka
por evento de bloco. Toda mutação é **write-through** (Postgres → Redis → Kafka).

## 6. Tratamento de erros (fail-open na infra, fail-safe no acesso)

- **422 (sem Money em expand/upgrade)**: `debit`==0 → `InsufficientFundsException`
  (Fase 8) → rollback (tamanho/tier não muda) → GUI "Money insuficiente".
- **409 (concorrência ou limite)**: `expand`==0 (version velha) ou target>40 /
  tier inexistente → `ConflictException` → rollback + estorno → GUI "tente
  novamente" / "máximo atingido".
- **Redis fora (infra) → fail-open**: write-through e leitura de `plot:{plotId}`
  são `try/catch` → `log.warn`. Mas o **enforcement** de acesso é **fail-safe**:
  se o snapshot de permissões não pôde ser resolvido para um plot montado, a ação
  é **negada** (terreno privado por padrão) — nunca "abre geral" por falha de
  cache. O dono, resolvido por `owner_uuid` (também no snapshot/HTTP), mantém
  acesso; miss total → nega a estranhos, permite só o dono via fallback HTTP
  off-thread na hidratação.
- **Kafka fora**: `EventPublisher` engole a falha (padrão do core) — a mutação
  conclui; a instância B re-sincroniza no próximo `plot-loaded`/TTL de 5min do
  cache, então a propagação de permissão degrada de "instantânea" para
  "eventual" (≤5min), sem inconsistência permanente.
- **HTTP fora (plugin)**: `BackendException` de transporte → "indisponível", nunca
  trava a main thread (chamadas são async).
- **Config `plot_upgrades`/`plot` ausente/corrompida**: `PlotCatalog` fail-open →
  sem preços → expand/upgrade respondem 409/erro claro; GUI "indisponível".

## 7. Propagação entre servidores

- **Metadados** (membros/permissões/upgrades/size/nome) mudam Postgres +
  `plot:{plotId}` na hora; **`plot-updated`** (Kafka, key=plotId) faz a instância
  que tem o mundo montado invalidar/re-hidratar (§4.8) — permissão editada no
  lobby vale no terrenos-XX em ≤ propagação Kafka.
- **Expansão** muda `plots.size`; o `PlotProtection` re-lê os bounds no
  `plot-updated`/write-through → mais área construível **sem** regenerar mundo.
- **Enforcement** é **server-local** (só a instância que monta o mundo aplica);
  como o terreno é **sticky** (Fase 12), há **um** enforcer por vez — sem corrida
  de dois servidores decidindo permissão do mesmo plot.
- **Edição do catálogo** (`PUT /api/config/plot_upgrades`) propaga por
  `config-updated` (Kafka, instantâneo): GUI e serviço recarregam — hot-reload
  como `chat`/`rank`.

## 8. Testes

**Unitário backend** (`PlotServiceTest`, JUnit 5 + Mockito, mockando repos +
`EconomyService` + `StringRedisTemplate` + `EventPublisher` + `PlotCatalog`, no
molde do `RankServiceTest` da Fase 9):
1. `expand` com Money → `debit`==1, `expand`==1 → `size` sobe 10, `invested_money`
   += preço, `plot-updated{expand}` emitido.
2. `expand` sem Money → `debit`==0 → **422**, `size` **não** muda (rollback).
3. `expand` com `version` velha → `expand`==0 → **409**, débito **estornado**.
4. `expand` acima de 40 → 409 "máximo", nada muda.
5. `addMember` cria `plot_members` + `plot_settings` **default-false** (membro sem
   permissão até o dono liberar); `plot-updated`.
6. `setSettings` altera flags; `plot-updated{setting}`.
7. `upgrade` sem Money → 422; com Money → tier sobe, `plot-updated{upgrade}`.
8. `PlotCatalog` desserializa `config:plot_upgrades`/`config:plot` e resolve
   `expansionPrice`/`tierPrice`.

**Unitário plugin** (JVM puro, sem Bukkit onde der): resolução de permissão do
`PlotProtection` a partir de um snapshot — dono bypass; membro sem `can_break`
negado; não-membro negado; bounds de coordenada (dentro/fora de `size`).

**Manual (curl + em jogo, CLAUDE.md)** contra o gateway com `Authorization:
Bearer <BACKEND_SERVICE_TOKEN>`:
- **curl** (determinístico): `PUT /api/config/plot_upgrades` + `PUT
  /api/config/plot` com os exemplos; criar plot (Fase 12); `POST
  /{plotId}/members`; `PUT /{plotId}/settings/{uuid}` com `canBreak=false`; `POST
  /{plotId}/expand` sem Money → **422**, com Money (`/eco give`) → sobe; conferir
  `plot-updated` no Kafka UI.
- **em jogo** (fleet `terrenos`, **recriar** o container): entrar no terreno;
  expandir cobra Money e **libera área** (colocar bloco além do antigo limite,
  agora permitido); adicionar um segundo jogador como membro, tirar `can_break`,
  ele **não** consegue quebrar; comprar upgrade sobe o tier/produção na GUI;
  **editar permissão a partir de outra instância** (spawn/lobby) e ver o
  enforcement no terrenos-XX mudar via `plot-updated`; **hot-reload**: mudar um
  preço em `config:plot_upgrades` → GUI reflete sem restart.

## 9. Arquivos afetados (resumo)

**Backend `backend/rankup-service/`** (módulo da Fase 8; contexto `plot` da Fase 12):
- **NOVO** `plot/domain/{PlotMemberEntity,PlotSettingsEntity,PlotUpgradeEntity}` +
  repositórios; `PlotEntity`/`PlotRepository` (+ `expand` `@Modifying`).
- `plot/application/PlotService` (+ métodos §4.4), **NOVO** `plot/application/PlotCatalog`.
- `plot/api/PlotController` (+ handlers §4.5), DTOs `record`.
- `shared/messaging/KafkaTopics` (+ `PLOT_UPDATED` se a Fase 12 não o adicionou).
- **NOVO** `db/migration/V5__rankup_plot_members_settings_upgrades.sql`.
- **NOVO** teste `PlotServiceTest`.
- `infra/kafka/create-topics.sh` (+ `plot-updated` se a Fase 12 não o criou).
- (Gateway: **nenhuma** rota nova — `/api/plot/**` já existe da Fase 12.)

**SDK `plugins/crystal-core`:**
- **NOVO** `http/PlotMember`, `http/PlotSettings`, `http/PlotUpgrade`; `http/PlotData`
  (+ level/production/investedMoney/listas); `http/PlotClient` (+ delegates);
  `http/BackendHttpClient` (+ métodos members/settings/expand/upgrade/name);
  `messaging/KafkaTopics` (+ `PLOT_UPDATED` se ausente).

**Plugin `plugins/crystal-plot`** (da Fase 12, estendido):
- **NOVO** `listener/PlotProtection` (enforcement §4.6) + consumidor `plot-updated`
  (§4.8); GUIs novas em `menu/` (Membros, Permissões, Upgrades, Expandir §4.7);
  `plugin.yml` (comandos `/terreno` já existem; sem permissão nova de rede).

**Infra/config de dados:** `plot_upgrades` e `plot` são **dados** (via `PUT
/api/config/{key}`), seed via curl (§8), não arquivos de código.

**Compose:** nenhum serviço novo; `crystal-plot` já montado no fleet `terrenos`
(Fase 12) — **recriar** o container para o jar novo.

## 10. Divergências do código atual (a reconciliar)

- **Depende da Fase 12 (fundação SWM), ainda não construída.** Nem
  `backend/rankup-service/` nem `plugins/crystal-plot/` existem no branch; a Fase
  12 está **desenhada no master §9/§10, sem spec próprio ainda** (o
  `docs/superpowers/specs/2026-07-01-fase12-*` **não existe** no repositório neste
  momento — confirmado por listagem). A Fase 13 assume, da Fase 12: as tabelas
  `plots`/`plot_worlds`, o contexto `plot` + `PlotService`/`PlotController`/
  `PlotEntity`/`PlotRepository`, o `PlotClient`/`PlotData`/`crystal.plots()`, o
  cache `plot:{plotId}`, os tópicos `plot-loaded`/`plot-saved` (e provavelmente
  `plot-updated`), o fleet `terrenos` sticky e a `/terreno` GUI básica. **A Fase
  13 só começa depois da Fase 12 mergeada.** Se a Fase 12 numerar a migração de
  forma diferente, ajustar `V5` (Flyway é imutável).
- **"Área construível" num VOID sem WorldGuard.** O master §9 é explícito: o
  terreno é um Slime world **VOID**, sem WorldGuard. A Fase 13 **não** introduz
  WorldGuard; o bound de `size` é **enforcement por coordenada** no
  `PlotProtection` (`BlockPlaceEvent` fora de `[-size/2,+size/2]` → cancela). É uma
  divergência do brief bruto ("plots com WorldGuard", master §1) já reconciliada no
  master §9, aqui **materializada**: proteção = listeners + coordenada, não
  regiões de plugin externo.
- **Enforcement lê snapshot local, não Redis por evento.** O brief/master falam de
  cache `plot:{plotId}` "invalidado por `plot-updated`"; o detalhe operacional é
  que ler Redis **a cada** `BlockBreakEvent` seria I/O na main thread. A Fase 13
  resolve com um **snapshot em memória** por plot montado, hidratado 1× do Redis e
  re-hidratado no `plot-updated` — coerente com o master (o `plot:{plotId}` é a
  fonte; a invalidação dispara re-hidratação), registrado aqui para não parecer que
  cada evento faz um round-trip.
- **`plot-updated` pode nascer na Fase 12.** O master §5 lista `plot-updated` na
  tabela de tópicos junto de `plot-loaded`/`plot-saved` (Fase 12). Se a Fase 12 já
  declarar a constante/tópico, a Fase 13 **reusa**; senão, adiciona. Igual para o
  `PLOT_UPDATED` no `KafkaTopics` do SDK e do backend, e a linha no
  `create-topics.sh`.
- **Produção/level são derivados, não colunas mutáveis.** O master §4 lista
  `plots.level INT d1` e `invested_money BIGINT d0` como colunas, e o §10 da Fase
  13 pede "level/produção" na GUI. A Fase 13 trata **`invested_money` como fonte**
  (coluna real, somada em expand/upgrade) e **deriva** `level` (faixas na config
  `plot`) e `produção` (soma de `productionBonus` dos tiers), em vez de manter
  `level` como coluna independente mutável — evita duas fontes de verdade. Se a
  Fase 12 já persistir `level` como coluna escrita, reconciliar para escrevê-la no
  mesmo `@Transactional` do expand/upgrade.
- **`can_plant`/`can_harvest` sem plantação ainda.** A Fase 13 entrega as duas
  permissões e os hooks de gate, mas a **semântica de cultura** (cap/contagem/
  colheita→Money) é da **Fase 14**. Até lá, `can_plant`/`can_harvest` gatam plantar/
  quebrar de blocos de cultura genericamente; a Fase 14 refina.
