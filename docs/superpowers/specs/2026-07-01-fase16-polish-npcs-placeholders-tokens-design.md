# Design — Fase 16 (Polish): NPCs (Citizens), holograms + animações, PlaceholderAPI, crates/boosts/cosméticos (Tokens)

Data: 2026-07-01
Branch base: `feat/login-autocomplete-e-troca-senha`

> Fase **final de polish** do jogo RankUP (ver master
> `2026-07-01-rankup-arquitetura-design.md` §2/§8/§10). Não adiciona domínio novo
> ao `rankup-service`: costura **NPCs (Citizens)** e **placeholders (PlaceholderAPI)**
> sobre as GUIs/caches já construídos nas Fases 8–15, poli **holos + animações** via
> `crystal-hologram`, e fecha o **loop de gasto de Tokens** (cosméticos/boosts/crates)
> em cima do saldo `token` da Fase 8. Texto de jogador/docs em PT; identificadores/
> chaves/tópicos em inglês. Depende de todas as fases anteriores mergeadas.

## 1. Problema

O jogo está funcional (economia, ranks/prestígio, spawn, minas, terrenos, plantações,
arena) mas falta o **acabamento** que a rede já usa no lobby e que o master pede:

1. **Navegação por NPC.** Hoje o jogador abre `/rankup`, `/prestigio`, `/terreno`,
   o seletor de minas e a loja **por comando**. Falta o NPC físico (padrão de servers
   de RankUP) que, ao clicar, abre a **mesma GUI** — nas minas, arena, terrenos, loja
   e prestígio.
2. **Holos vivos.** O `crystal-hologram` só renderiza **texto estático** de config
   (`HologramRenderer` cospe um `TextDisplay` por def). Faltam **holos de topo**
   (ranking money/tokens/prestige/blocks-mined lidos dos sorted sets Redis) que se
   **atualizam sozinhos**, e o **polimento da animação** de reset de mina.
3. **Placeholders.** Scoreboards/holos/plugins de terceiros não têm como ler
   `%rank% %money% %tokens% %prestige% %plot_size% %plot_level% %online% %server%`.
   Falta uma **PlaceholderAPI expansion** — e ela **não pode bloquear a main thread**
   (o `onRequest` roda na main; HTTP/DB ali trava o servidor).
4. **Tokens sem sink.** A Fase 8 dá saldo `token`, mas **nada gasta**. Faltam
   **cosméticos**, **boosts** (multiplicador temporário de recompensa) e **crates**,
   todos GUI-first, debitando Tokens.

## 2. Objetivos e não-objetivos

**Objetivos**
- **Citizens** (plugin externo, montado como LuckPerms/FAWE): NPCs em minas/arena/
  terrenos/loja/prestígio que **abrem a GUI existente** (fiação NPC→GUI apenas).
- **Holos + animações** via `crystal-hologram`: holos de leaderboard (money/tokens/
  prestige/blocks-mined) que se atualizam por tick lendo os sorted sets; polish da
  animação de reset de mina; holo de rank/terreno.
- **PlaceholderAPI expansion** `RedeCrystalExpansion` resolvendo os 8 placeholders do
  master **estritamente não-bloqueante** — lendo um **snapshot local** mantido
  off-thread a partir dos caches Redis (`rankup:{uuid}`, `economy:{uuid}`,
  `plot:{plotId}`, presença). **Nunca** HTTP/DB/round-trip Redis no `onRequest`.
- **Loop de Tokens**: gastar Tokens em **cosméticos** (reuso do padrão lobby +
  cosmético persistido no inventário), **boosts** (multiplicador temporário via Redis
  TTL) e **crates**, todos debitando pelo `POST /api/economy/{uuid}/debit` já existente.
- **Boost** aplica multiplicador **temporário** por duração, **em camada** sobre o
  multiplicador **permanente** de prestígio; o código de recompensa de minas/colheita/
  kill (Fases 11/14/15) passa a **consultar** o boost.

**Não-objetivos**
- Nenhum **domínio novo** no `rankup-service` (sem tabela/endpoint novo; boost/crate/
  cosmético vivem em Redis + config, gasto usa `economy` existente).
- **Citizens traits customizadas / pathfinding / combate de NPC** — NPCs são estátuas
  clicáveis que abrem GUI. Nada além disso.
- **Loja/GUIs novas de gameplay** — as GUIs já existem (Fases 9/11/12/13); Fase 16 só
  **abre** as existentes por NPC e adiciona a **GUI da loja de Tokens/crates**.
- **Migrar o rank para grupo LuckPerms** (o "efeito opcional" que a Fase 9 adiou) —
  fica fora; permanece prefixo config-only + permissões via attachment.

## 3. Decisões de design

| Tema | Decisão |
|------|---------|
| Citizens | **Plugin externo**, jar em `EXTERNAL_PLUGINS/`, montado read-only em cada server de jogo (`:/plugins/Citizens.jar:ro`) como LuckPerms/FAWE. **Pré-requisito** (não está no repo). |
| PlaceholderAPI | **Plugin externo**, mesma montagem. **Pré-requisito** (não está no repo). |
| NPC → GUI | **Fiação apenas**: um listener de `NPCRightClickEvent` (API Citizens) mapeia `npc.data("crystal-action")` → abre a GUI já existente (`/rankup`, seletor de mina, `/terreno`, `/prestigio`, loja de tokens). NPCs criados por comando admin e persistidos pelo próprio Citizens (`saves.yml`), **não** no nosso backend. |
| Casa do código NPC | Um pacote fino `npc/` **em cada plugin dono da GUI** (o mesmo que registra o comando), não um plugin novo. `depend`/`softdepend: [Citizens]` só onde há NPC. |
| Holos de leaderboard | **`crystal-hologram` estendido**: um `HologramDef` com `source` dinâmica (`leaderboard:money` etc.) cujas linhas são **recomputadas por task** (não texto fixo). Leitura dos sorted sets no tick, render na main. |
| Placeholders (não-bloqueio) | **Snapshot local** `Map<UUID,…>` mantido por uma task assíncrona (`hgetAll`/`get` off-thread ~1–2s, igual `crystal-tab`); `onRequest` lê **só o mapa em memória**. **Zero I/O** no callback do PlaceholderAPI. |
| Estado do boost | **Redis com TTL**: `boost:{uuid}` = hash `{multiplier, source}` + `EXPIRE` = duração. Sem TTL persistido = sem boost. Fail-open (Redis fora → sem boost, nunca erro). |
| Camadas de multiplicador | Recompensa = `base × prestigeMultiplier × boostMultiplier`. Prestígio (permanente, `rankup:{uuid}.multiplier`) e boost (temporário, `boost:{uuid}`) são **multiplicados**, não somados. |
| Gasto de Tokens | Reusa `POST /api/economy/{uuid}/debit` (débito condicional atômico, 422 insuficiente) da Fase 8 — **débito é de `token`**, não `money` (novo campo `currency` no request, ver §4.6). Nenhum endpoint novo de domínio. |
| Cosméticos | Reuso do padrão lobby (`LobbyHotbar`/cosmético) + persistência no inventário do jogador; **catálogo em config** `token_shop` (hot-reload), não em tabela. |
| Crates | GUI-first: abre a crate (debita Tokens), sorteio ponderado por config, entrega recompensa (Money/Tokens/cosmético/boost). Estado do sorteio é efêmero (sem tabela). |

## 4. Arquitetura

### 4.1 Pré-requisitos externos (montar como LuckPerms/FAWE)

O master §10 (Fase 16) e §2 dizem que Citizens monta em `EXTERNAL_PLUGINS` "como
LuckPerms/FAWE". Verificado no repo: `EXTERNAL_PLUGINS/` hoje tem **FAWE, LuckPerms,
Via{Version,Backwards,Rewind}** — **não** tem Citizens nem PlaceholderAPI. São
**pré-requisitos** desta fase:

```
EXTERNAL_PLUGINS/Citizens-<versão-1.21>.jar
EXTERNAL_PLUGINS/PlaceholderAPI-<versão>.jar
```

Montagem no `docker-compose.yml`, em **cada** server de jogo (spawn/mina/arena/
terrenos), espelhando o bloco de volumes já usado (ex.: linhas 214/218 do compose):

```yaml
- ./EXTERNAL_PLUGINS/Citizens-<v>.jar:/plugins/Citizens.jar:ro
- ./EXTERNAL_PLUGINS/PlaceholderAPI-<v>.jar:/plugins/PlaceholderAPI.jar:ro
```

Citizens grava seus dados (`saves.yml`, `citizens.yml`) no volume `…data:/data` do
server — persistem entre boots como o resto do estado do Paper. PlaceholderAPI não
precisa de estado.

### 4.2 NPC → GUI (fiação por Citizens, sem plugin novo)

Cada plugin **dono de uma GUI** ganha um pacote `npc/` fino (mesma casa que já
registra o comando), sem inventar armazenamento de NPC — quem persiste os NPCs é o
próprio Citizens.

| NPC (server) | Plugin dono | Ação (`npc.data("crystal-action")`) | Abre |
|---|---|---|---|
| Loja (spawn) | `crystal-economy` (loja Tokens, §4.6) | `token-shop` | GUI da loja de Tokens/crates |
| Prestígio (spawn) | `crystal-prestige` | `prestige` | GUI `/prestigio` |
| Rank (spawn) | `crystal-rank` | `rankup` | GUI `/rankup` |
| Seletor de mina (spawn) | `crystal-spawn` | `mine-selector` | seletor de minas |
| Arena (spawn) | `crystal-spawn`/`crystal-bungee` | `arena` | conecta à arena (Fase 15) |
| Terreno (spawn/terrenos) | `crystal-plot` | `plot` | GUI `/terreno` |

- **Listener único por plugin**: `NPCRightClickEvent` (e `NPCLeftClickEvent` para
  simetria) filtrado por `event.getNPC().data().has("crystal-action")` com o valor
  esperado → chama **o mesmo método** que o comando já usa para abrir a GUI. É
  **fiação**, não lógica nova.
- **Criação/edição** dos NPCs: comando admin de cada plugin (ex.: `/rankupnpc`) que
  faz `CitizensAPI.getNPCRegistry().createNPC(...)`, seta skin/nome via traits padrão
  do Citizens e grava `npc.data().setPersistent("crystal-action", <acao>)`. Sem GUI de
  edição de NPC (não-objetivo).
- **`depend`/`softdepend`**: os plugins com NPC declaram `softdepend: [Citizens]` no
  `plugin.yml` (o server roda sem Citizens; sem ele, só o comando funciona e o
  listener não registra — fail-open). PlaceholderAPI idem no plugin da expansion.

### 4.3 Holos + animações (`crystal-hologram` estendido)

Hoje `HologramDef(id, type, world, x, y, z, lines)` é **estático** e
`HologramRenderer.render()` faz um `spawn` único por def. Extensão **retrocompatível**:

- **`HologramDef` ganha `source`** (nullable): `null` = holo estático de hoje;
  `"leaderboard:money"`/`":tokens"`/`":prestige"`/`":blocks-mined"` = holo **dinâmico**
  de topo. `HologramStore.fromMap/toMap` passam a ler/gravar o campo opcional (mesmo
  jeito do `type` opcional).
- **Task de refresh** (`~40 ticks`, padrão do projeto): para cada def com `source`,
  lê `redis.leaderboardTop(board, N)` **off-thread**, resolve o nick de cada UUID e o
  score, monta as linhas (`§e#1 Steve §7- 12.500`) e **atualiza o `TextDisplay`** na
  main (`td.text(...)`), sem respawnar. Holos estáticos continuam intactos.
- **Nick + score**: `leaderboardTop` hoje devolve **só os membros** (UUIDs), sem
  score (`zrevrange`). Ver §10 — precisamos de um `leaderboardTopWithScores`
  (`zrevrangeWithScores`) no `RedisClient` e resolver o nick pelo snapshot/`rankup:{uuid}`.
- **Animação de reset de mina**: o `crystal-mine` (Fase 11) já emite `mine-reset` e
  tem holo/animação; Fase 16 **poli** — o holo da mina exibe barra `%` (lida de
  `mine:{server}:{mineId}`) e uma sequência curta de estados ("§eResetando…" →
  countdown → "§aPronto!") ao consumir `mine-reset`, tudo na própria instância. É
  polish visual sobre código existente, não novo domínio.

### 4.4 PlaceholderAPI expansion (não-bloqueante, snapshot local)

Novo plugin fino `plugins/crystal-placeholder` (ou pacote `placeholder/` no
`crystal-spawn`; **decisão a fechar no plano** — plugin próprio favorece rodar em
todos os tipos de server, igual `crystal-tab`). Registra uma `PlaceholderExpansion`:

```
RedeCrystalExpansion extends PlaceholderExpansion
  getIdentifier() = "redecrystal"   // %redecrystal_rank%, etc. (alias %rank% via config do PAPI)
  persist() = true                  // sobrevive a /papi reload
  onRequest(OfflinePlayer p, String params) → lê SÓ o snapshot em memória
```

**Chaves não-bloqueio (o ponto crítico do master §8):** o `onRequest` roda na main
thread. O `RedisClient` do SDK é **Lettuce síncrono** — um `hgetAll`/`get` ali é um
round-trip de rede na main = proibido. Solução (idêntica ao padrão `crystal-tab`, que
lê o hash 1×/tick):

- Um `SnapshotService` mantém `Map<UUID, PlayerSnapshot>` + `online`/`server`, populado
  por **uma task assíncrona** (~1–2 s) que faz os `hgetAll`/`get`/`scard` **off-thread**:
  - `rankup:{uuid}` → `rank`, `prestige`, `multiplier`, `money`
  - `economy:{uuid}` → `money`, `tokens`
  - `plot:{plotId}` (resolvido do dono) → `plot_size`, `plot_level`
  - presença → `online` (`scard online_players`), `server` (`player-server:{uuid}` ou
    o id local)
- `onRequest` faz **apenas** `snapshot.get(uuid)` (map em memória) e formata. Miss =
  string vazia/`"0"` (fail-open), nunca I/O.

| Placeholder | Fonte no snapshot | Cache Redis de origem |
|---|---|---|
| `%rank%` | `snap.rank` | `rankup:{uuid}.rankId` (prefixo via config `rank`) |
| `%money%` | `snap.money` | `economy:{uuid}`/`rankup:{uuid}` |
| `%tokens%` | `snap.tokens` | `economy:{uuid}` |
| `%prestige%` | `snap.prestige` | `rankup:{uuid}` |
| `%plot_size%` | `snap.plotSize` | `plot:{plotId}` |
| `%plot_level%` | `snap.plotLevel` | `plot:{plotId}` |
| `%online%` | contador do snapshot | `scard online_players` |
| `%server%` | `snap.server` | `player-server:{uuid}` / id local |

### 4.5 Boosts (multiplicador temporário, Redis TTL)

- **Estado**: `boost:{uuid}` = hash `{multiplier, source}` + `EXPIRE ttlSeconds`. Sem a
  chave = sem boost. Escrito ao ativar (comprado na loja / ganho em crate); expira
  sozinho (TTL) — **auto-limpo**, sem job.
- **Alcance do boost (DECIDIDO, dono):** o boost multiplica **Money gerado por
  gameplay** — **mineração** (Fase 11) e **vendas na loja** (venda de culturas,
  Fase 14/§4.6) — e, pela mesma lógica de "Money novo", o **kill reward** da arena
  (Fase 15). **NÃO** se aplica a **transferências entre jogadores** (`/pagar`,
  `POST /api/economy/transfer`): mover Money existente entre contas nunca é
  multiplicado. `/eco give` admin também não. (Se PvP não deve ter boost, remover o
  kill da lista — ponto único a confirmar.)
- **Leitura pelo código de recompensa** (retro-touch nas Fases 11/14/15): onde hoje
  calculam `base × prestigeMultiplier`, passam a `base × prestigeMultiplier ×
  boostMultiplier`, com `boostMultiplier` lido do snapshot local (o `SnapshotService`
  do §4.4 também mantém `boost:{uuid}` no mapa) para **não** bater no Redis por bloco/
  venda/kill. Miss = `1.0` (sem boost). O `transfer` do `EconomyService` (Fase 8)
  **não** consulta boost.
- **HUD**: o tempo restante do boost pode virar placeholder extra (`%boost_time%`) e/ou
  linha de scoreboard — opcional, não bloqueia a fase.

### 4.6 Lojas: Tokens (cosmético/boost/crate) + Money (venda de culturas, gear da arena)

**DECIDIDO (dono):** a Fase 16 entrega **três lojas** GUI-first, cada uma com
catálogo em config (hot-reload, padrão `holograms`/`chat`), abertas por comando e
por **NPC** (§4.2). Layout copiado do lobby (`MenuHolder` + `framedSize`/`bodySlots`/
`barCenter`/`backButton` + PDC + `onClick`).

**(a) Loja de Tokens** (`crystal-economy`, `/loja`/`/tokens`, NPC `token-shop`) —
gasta **Tokens**:
- **Catálogo** `token_shop`: itens com `id`, `type` (`cosmetic`/`boost`/`crate`),
  `cost` (Tokens), payload por tipo (cosmético→id; boost→`multiplier`+
  `durationSeconds`; crate→tabela de loot ponderada).
- **Cosméticos**: reusa o padrão do lobby (`LobbyHotbar`/cosmético), persistido no
  inventário/perfil. Catálogo em config; nada em tabela.
- **Crates**: abrir debita Tokens, sorteio ponderado por config, entrega
  Money/Tokens/cosmético/**boost** (`boost:{uuid}`). Animação de abertura é polish
  opcional.

**(b) Venda de culturas** (`crystal-plantation`/`crystal-economy`, `/vender`, NPC
`crop-shop`) — recebe **Money**: a versão rica do `SellMenu` da Fase 14 — vende os
itens de cultura do inventário por `value(crop) × multiplier × boost` (o boost do
§4.5 se aplica às **vendas na loja**). Catálogo de preços = a config `plantation`
(`value` por cultura). O NPC de venda é a casa "oficial" do loop agrícola.

**(c) Loja de gear da arena** (`crystal-arena`, `/gearshop` ou NPC `gear-shop`) —
gasta **Money**: como o jogador **leva o próprio inventário** e **dropa ao morrer**
(Fase 15), esta loja vende **equipamento** (armas/armaduras/consumíveis) por Money,
para o jogador se equipar antes/depois de morrer. Catálogo em config `gear_shop`
(item→preço em Money). **Compra = débito** (§ abaixo). Fica na arena (ou no spawn,
decisão do plano).

**Débito**: as três reusam `POST /api/economy/{uuid}/debit` com `currency`
(`"money"` default / `"token"`) — ver §10. **422** se saldo insuficiente ("§cSaldo
insuficiente"). Atômico = sem gasto duplo. (Venda de culturas **credita** via
`addMoney`, não debita.)

## 5. Fluxo de dados

**NPC abre GUI**
```
clique no NPC → NPCRightClickEvent → npc.data("crystal-action")
  → mesma chamada do comando (openRankupGui / openPlotGui / openTokenShop / …)
  (GUI já existente; NPC é só o gatilho)
```

**Holo de leaderboard**
```
task ~40t (async): redis.leaderboardTopWithScores("money", N)
  → resolve nick (snapshot / rankup:{uuid}) → monta linhas
  → main thread: td.text(linhas)   (atualiza, sem respawnar)
```

**Placeholder (não-bloqueante)**
```
task ~1–2s (async): hgetAll rankup:{uuid} / economy:{uuid} / plot:{plotId}, scard online
  → grava Map<UUID,Snapshot> em memória
scoreboard/holo de 3º chama %redecrystal_money% (main)
  → onRequest lê SÓ o Map → devolve string   (zero I/O na main)
```

**Boost de Tokens**
```
loja/crate → debit(token, cost) 200 → HSET boost:{uuid} {multiplier,source} + EXPIRE ttl
minerar/colher/kill (Fases 11/14/15):
  reward = base × prestigeMultiplier × boostMultiplier(snapshot, =1.0 se expirado)
TTL expira → boost:{uuid} some → boostMultiplier volta a 1.0
```

**Compra de cosmético/crate**
```
GUI (NPC/loja) → clique → debit(token, cost)
  → 422? "§cTokens insuficientes" e fim
  → 200 → aplica cosmético (persistido) / sorteia crate (loot ponderado por config)
```

## 6. Tratamento de erros (fail-open, padrão do projeto)

- **Citizens ausente** (dev sem o jar) → `softdepend`, listener/comando de NPC não
  registram; os comandos de GUI continuam funcionando. Nada quebra.
- **PlaceholderAPI ausente** → a expansion não registra; placeholders simplesmente não
  existem para terceiros. Sem crash.
- **Snapshot/Redis fora** → `onRequest` devolve vazio/`"0"`; a task de snapshot loga
  `warning` e tenta no próximo ciclo. **Nunca** faz fallback HTTP no `onRequest`.
- **Boost** — Redis fora na leitura → `boostMultiplier = 1.0` (jogador não perde
  recompensa base; só não ganha o bônus). Escrita do boost falha → mensagem ao
  jogador, Tokens não são debitados sem boost (debit e HSET na mesma sequência; se o
  HSET falha após o debit, compensar/logar — **decisão de compensação a fechar no
  plano**).
- **debit 422** (Tokens insuficientes) → GUI mostra aviso, sem efeito colateral.
- **Holo dinâmico** — sorted set vazio/backend blip → linhas de placeholder
  ("§7—") em vez de erro; render por-holo isolado (`warning`, segue os demais), igual
  o `HologramRenderer` faz hoje com world ausente.

## 7. Propagação entre servidores

- **Boost/economia** são estado compartilhado no Redis (`boost:{uuid}`,
  `economy:{uuid}`) → visíveis em qualquer server assim que a task de snapshot/tick
  relê (~1–2 s), sem pub/sub. Coerente com o §8 do master (estado quente lido no tick).
- **Leaderboards** são sorted sets globais (write-through das Fases 8/9/11) → o holo de
  qualquer spawn mostra o mesmo topo.
- **Catálogo `token_shop`** propaga por `config-updated` (Kafka), instantâneo, igual
  `holograms`/`chat`.
- **NPCs** são locais de cada server (Citizens `saves.yml` por instância) — criados por
  admin onde precisam existir; não há sincronização cross-server (nem faz sentido).

## 8. Verificação (CLAUDE.md: rebuild → recriar container → observar)

Rebuild dos plugins tocados → `docker compose up -d --force-recreate --no-deps <server>`
(recriar, pois a imagem copia o jar no boot). Roteiro:

1. **NPC → GUI**: clicar no NPC de rank/prestígio/mina/terreno/loja abre **a GUI certa**
   (a mesma do comando).
2. **Placeholders sem lag**: um scoreboard/holo de terceiro com `%redecrystal_rank%`/
   `%redecrystal_money%`/`%online%`/`%server%` resolve os valores e **não** trava o
   servidor (sem stall no `onRequest`); `/papi parse me %redecrystal_tokens%` bate com
   `/saldo`.
3. **Holo de topo**: minerar/ganhar money reordena o holo de leaderboard em ~1–2 s.
4. **Boost temporário**: comprar/ganhar um boost → minerar rende **mais** (base ×
   prestígio × boost); ao expirar o TTL, a recompensa **volta ao normal**.
5. **Loja/crates**: comprar cosmético e crate **deduz Tokens**; Tokens insuficientes →
   422 tratado ("§cTokens insuficientes"); crate entrega recompensa por peso.
6. **Reset de mina**: animação/holo polido dispara no reset (timer e `/`admin).

## 9. Arquivos afetados (resumo)

- **Pré-req (NOVO)**: `EXTERNAL_PLUGINS/Citizens-<v>.jar`, `EXTERNAL_PLUGINS/
  PlaceholderAPI-<v>.jar`; montagens em `docker-compose.yml` (todos os servers de jogo).
- **NOVO** `plugins/crystal-placeholder/` (expansion + `SnapshotService`) — ou pacote
  `placeholder/` no `crystal-spawn` (decisão no plano); registrar no `plugins/pom.xml`
  + `Makefile`.
- `plugins/crystal-hologram` — `HologramDef.source` (opcional), `HologramStore`
  (ler/gravar `source`), `HologramRenderer`/task de refresh dos holos dinâmicos.
- `plugins/crystal-core` — `RedisClient.leaderboardTopWithScores` (`zrevrangeWithScores`);
  helpers de leitura de `boost:{uuid}`; constantes de chave (`boost:`).
- `plugins/crystal-economy` — GUI da **loja de Tokens/crates**, catálogo config
  `token_shop`, ativação de boost/cosmético; NPC `token-shop`.
- `plugins/crystal-rank` / `crystal-prestige` / `crystal-spawn` / `crystal-plot` —
  pacote `npc/` (listener + comando admin de criação), `softdepend: [Citizens]`.
- `plugins/crystal-mine` / `crystal-plantation` — recompensa passa a **multiplicar pelo
  boost** (retro-touch §4.5); `crystal-mine` poli holo/animação de reset. `crystal-`
  (arena, Fase 15) — kill reward também multiplica pelo boost.
- **Backend** `rankup-service` — `debit` ganha `currency` (`money`/`token`) para debitar
  Tokens pelo endpoint existente (única mudança de API; **sem** contexto novo).

## 10. Divergências do código atual (a reconciliar)

> **Decisões do dono (2026-07-01), refletidas em §4.5/§4.6:** a Fase 16 entrega
> **três lojas** — Tokens (cosmético/boost/crate), **venda de culturas** (Money,
> rica, fecha o loop da Fase 14) e **loja de gear da arena** (Money, para o PvP de
> loot da Fase 15). O **boost** multiplica **mineração + vendas na loja (+ kill
> reward)**, mas **nunca transferências** (`/pagar`) nem `/eco give`. Consequências:
> a Fase 16 ganha os catálogos `gear_shop` e o NPC de venda de culturas, além do
> `token_shop`; o `crystal-arena` ganha uma loja de gear.

- **Citizens e PlaceholderAPI não estão no repo.** `EXTERNAL_PLUGINS/` tem só FAWE,
  LuckPerms e os Via*. Ambos são **pré-requisitos** desta fase (baixar o jar, montar no
  compose em todos os servers de jogo). O master assume "montar como LuckPerms/FAWE" —
  confirmado como padrão, mas os jars **precisam ser adicionados**.
- **Placeholders não podem bloquear — o `RedisClient` do SDK é síncrono.** Não há API
  async exposta; um `hgetAll` no `onRequest` (main thread) é round-trip de rede =
  proibido. Por isso a fase **introduz um snapshot em memória** mantido por task
  assíncrona (padrão `crystal-tab`), e o `onRequest` lê **só o mapa**. **Não** existe
  hoje um "cache local não-bloqueante" pronto — nasce aqui.
- **`leaderboardTop` devolve só membros, sem score.** Hoje é `zrevrange` (UUIDs). O holo
  de topo precisa dos **valores** → adicionar `leaderboardTopWithScores`
  (`zrevrangeWithScores`) e resolver o nick pelo snapshot/`rankup:{uuid}`. Mudança
  aditiva no `RedisClient`, não quebra chamadas atuais.
- **Holos são estáticos.** `HologramRenderer` faz `spawn` único de `TextDisplay` de
  texto fixo; **não há** loop de atualização nem holo "data-source". A fase adiciona
  `HologramDef.source` (opcional, retrocompatível) + task de refresh — sem alterar o
  comportamento dos holos estáticos existentes.
- **Boost é uma NOVA camada de multiplicador que o código de recompensa das Fases
  11/14/15 tem de consultar (retro-touch).** Hoje a recompensa (a ser construída nessas
  fases) multiplica só pelo prestígio; Fase 16 insere `× boostMultiplier` lido do
  snapshot. Como as Fases 11/14/15 podem ser mergeadas antes da 16, deixar um **ponto
  de extensão** (multiplicador = produto de camadas) evita reescrever depois — anotar no
  plano dessas fases.
- **`debit` só debita `money`.** A Fase 8 modela `POST /api/economy/{uuid}/debit` sobre
  `money`. Gastar **Tokens** exige um `currency` no request (`money`/`token`) e o
  `EconomyService.debit` operar na coluna certa — pequena extensão do endpoint
  existente, **não** um endpoint/contexto novo (respeita "Tokens só em `/api/economy/**`",
  decisão #5 do master).
- **Nenhum facade novo por feature.** Boost/crate/cosmético **não** viram cliente SDK
  novo; usam `crystal.economy().debit(...)` (Fase 8) + `crystal.redis()` + config. Sem
  `crystal.tokens()`/`crystal.boosts()` — evita inflar o SDK para estado que é só
  Redis+config.
- **Cosmético persistido reusa lobby, que roda no lobby, não nos servers RankUP.** O
  padrão `LobbyHotbar`/cosmético existe no `crystal-lobby`; a loja de Tokens roda nos
  servers de jogo (spawn). Reaproveitar o **padrão** (não o plugin) — extrair o mínimo
  necessário ou espelhar — **decisão no plano** (copiar padrão vs. extrair util comum
  para `crystal-core`).
