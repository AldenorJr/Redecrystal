# Design — Jogo RankUP sobre a rede RedeCrystal (arquitetura + roadmap)

Data: 2026-07-01
Branch base: `feat/login-autocomplete-e-troca-senha`

> Documento de arquitetura de alto nível para o **jogo RankUP** adicionado à rede
> RedeCrystal existente. Não é greenfield: reaproveita proxy, login, SDK
> `crystal-core`, gateway, Kafka, Redis e observabilidade já prontos (Fases 0–7).
> As 6 decisões abertas foram **aprovadas nas recomendações** (ver §11). Texto de
> jogador/docs em PT; identificadores/módulos/tópicos/chaves em inglês. Cada fase
> do roadmap (§10) tem — ou terá — seu próprio spec+plan em `docs/superpowers/`.

## 0. Resumo executivo

O RankUP é um **jogo novo adicionado à rede existente**. Reaproveita toda a
espinha dorsal pronta — Velocity + `crystal-bungee` (descoberta de fleet +
roteamento least-loaded), Paper 1.21.x, o SDK `crystal-core` (HTTP via gateway,
Redis Lettuce, Kafka, `ConfigProvider` hot-reload), o padrão de bounded context
com Postgres + Redis + optimistic-lock do `core-service`, e a observabilidade.

Adicionamos:

- **4 tipos de servidor**: `spawn` (hub, escala), `mina` (fleet), `arena` (1
  instância, cap ~200), `terrenos` (SWM, VOID, sticky).
- **1 microsserviço novo**: `rankup-service` + banco `rankup_db`.
- **7 plugins novos** `crystal-economy / rank / prestige / spawn / mine / plot /
  plantation`, mais extensões em `crystal-bungee`, `crystal-tab`, `crystal-chat`.
- **Novos tópicos Kafka** e **novas chaves Redis** — sem MySQL nem Redis Pub/Sub
  como barramento.

## 1. Nota de reconciliação (brief bruto → stack travada)

| Brief bruto | Na RedeCrystal | Muda de fato |
|---|---|---|
| MySQL/MariaDB | **PostgreSQL**, um banco/serviço (`rankup_db`), Flyway imutável, optimistic-lock | DDL vira `V<n>__*.sql`; nada de MySQL |
| Redis Pub/Sub como bus | **Kafka (KRaft)** é o bus; **Redis** só cache+presença+leaderboards+TAB/scoreboard sync | Eventos de domínio viram tópicos Kafka; estado quente lido do Redis no tick, sem pub/sub |
| APIs internas EconomyAPI/RankAPI/… (singletons) | **Bounded contexts atrás do gateway**, expostos só pelo SDK (`BackendHttpClient`+facades) | Plugin nunca fala com serviço/DB direto; "API"=endpoint no gateway + método tipado no SDK; threading async→main |
| Rank via LuckPerms | **Rank de jogo** (TERRA…INFINITO) é dado do contexto `rank`; pode conceder grupo LuckPerms como efeito, mas não é o cargo de rede | §1.1 |
| "Coins" | `profile.coins` continua sendo moeda de rede/lobby; RankUP usa **Money+Tokens** próprios (contexto `economy` novo) | §1.1 |
| Plots mundo único/WorldGuard | **Terrenos** = servidor com **SlimeWorldManager**, VOID, 1 Slime world por terreno | §9 |
| "Servidor de minas" único | **Fleet `mina`** escalável; cada instância um mundo independente, economia/inventário/ranks/stats/chat compartilhados | escala como o fleet de lobby |

### 1.1 Duas separações críticas (aprovadas)

1. **Rank de jogo ≠ cargo de rede.** O cargo (staff/VIP) continua resolvido por
   permissão (`CargoResolver`/`chat.roles`/LuckPerms). O rank RankUP é progressão
   de jogo (contexto `rank`); um jogador tem os dois ao mesmo tempo (`[VIP]` +
   `[OURO]`).
2. **Money/Tokens ≠ `profile.coins`.** `coins` fica como moeda de rede. RankUP tem
   **Money** (ranks/upgrades/terrenos/loja) e **Tokens** (cosméticos/boosts) no
   contexto `economy` novo.

## 2. Mapa de módulos + diagrama

### 2.1 Novos plugins `crystal-*` (Paper)

| Plugin | Roda em | Responsabilidade |
|---|---|---|
| `crystal-economy` | spawn/mina/arena/terrenos | GUI `/saldo`,`/pagar`, HUD, batelada de deltas de Money, leitura de cache |
| `crystal-rank` | idem | GUI `/rankup`+NPC, compra do próximo rank, aplica prefixo/permissões, holo de progresso |
| `crystal-prestige` | spawn | GUI `/prestigio`, reset de ranks, multiplicadores/recompensas |
| `crystal-spawn` | spawn | Hub: scoreboard, hotbar/NPCs de navegação, portais mina/arena/terrenos, seletor de minas |
| `crystal-mine` | mina | Auto-regen config, reset auto+manual, holo+NPC+animação, recompensa por bloco → economy |
| `crystal-plot` | terrenos | Ciclo de vida SWM, GUI `/terreno`, membros/permissões, expansão, upgrades, home |
| `crystal-plantation` | terrenos | Caps por cultura por terreno, upgrades elevam caps, colheita dropa → venda |
| `crystal-arena` | arena | PvP de loot (dropa ao morrer), kill reward, stats, loja de gear (Fase 15/16) |
| `crystal-clan` | spawn/mina/arena/terrenos | Clãs: núcleo/cargos, banco em Money, níveis, chat de clã, sigla no TAB, ranking (Fase 17) |

Reaproveitados sem reescrever: `crystal-bungee` (ganha descoberta/roteamento dos
novos tipos), `crystal-tab`, `crystal-tag`, `crystal-chat` (chat global via
`player-chat` já existente, só ajusta formato `[Spawn] [TERRA] Steve: …`),
`crystal-hologram`, `crystal-skin`, `crystal-inventory`, `crystal-profile`.
**Global TAB/chat/scoreboard não viram plugin novo** — TAB global = `crystal-tab`
lendo hash Redis nova `tab:rankup`.

### 2.2 Novos bounded contexts

**`rankup-service` novo** com `rankup_db` (aprovado), em vez de empilhar no
`core-service`. Racional: "core-service for now" foi para contextos leves
(profile/inventory); RankUP é um jogo inteiro com escrita alta e independente
(economia por mineração, produção de terreno, blobs Slime). Isolar mantém
`core_db` limpo e é o "split when needed" da regra. Gateway roteia
`/api/economy/**` etc. p/ `lb://rankup-service` via Eureka.

Contextos (DDD `api/application/domain`): `economy` (`player_economy`), `rank`
(`player_rank`; ladder em config), `prestige` (`player_prestige`), `plot`
(`plots`,`plot_members`,`plot_settings`,`plot_upgrades`,`plot_worlds`),
`plantation` (`plot_plantations`), `mine` (`mine_stats`; minas em config), `stat`
(`player_rankup_stats`), `home` (`player_homes`), `clan` (`clans`, `clan_members`;
níveis/score em config — Fase 17). **Fora** do rankup_db: Punishments
(rede→`moderation`, mais tarde) e Settings (rede→`profile`).

### 2.3 Diagrama ASCII

```
 client ─▶ proxy-01 (Velocity, crystal-bungee)
   │ least-loaded routing por tipo · sticky p/ terrenos (plot-server:{uuid})
   ▼
 ┌─────┬────────┬────────┬────────┬──────────────────┐
 │login│spawn(N)│mina(N) │arena(1)│terrenos(N,sticky)│  todos no crystal-core SDK
 └─────┴───┬────┴───┬────┴───┬────┴────────┬─────────┘
   HTTP(Bearer)   Kafka(KRaft)      Redis(Lettuce)
        │            │                  │
        ▼            ▼                  ▼
 api-gateway(:8080 ÚNICO publicado+autenticador)  rankup:{uuid},economy:{uuid},
   │ routes lb://core-service                     tab:rankup,plot-server:{uuid},
   │ routes lb://rankup-service ◀─NOVO            leaderboard:money,mine:{id}…
   ▼                 ▼
 core-service   rankup-service◀─NOVO
  (config/disc/  (economy/rank/prestige/plot/
   profile/auth)  plantation/mine/stat/home)
   │core_db          │rankup_db     shared Postgres
   └────────┬────────┘
            ▼
      eureka(:8761) + Kafka
```

Fronteiras mantidas: plugin nunca fala com serviço/DB direto (só gateway); auth
só no gateway; um banco por serviço.

## 3. Estrutura de pastas

```
backend/rankup-service/                 # NOVO, espelha core-service
  pom.xml (herda backend/pom.xml)
  src/main/java/com/redecrystal/rankup/
    RankUpApplication.java (@SpringBootApplication+@EnableDiscoveryClient)
    economy|rank|prestige|plot|plantation|mine|stat|home/{api,application,domain}/
    shared/{messaging,web}/            # EventPublisher, KafkaTopics(RankUP), handlers
  src/main/resources/{application.yml, db/migration/V1__rankup_economy.sql …}
plugins/
  crystal-core/                        # + DTOs/facades RankUP + tópicos + chaves
  crystal-economy|rank|prestige|spawn|mine|plot|plantation/   # NOVOS (listener/ commands/ gui/)
  pom.xml                              # registra módulos
servers/{spawn,mina,arena,terrenos}/<id>/    # config runtime (terrenos: config SWM)
world/{WORLD_SPAWN,WORLD_MINA,WORLD_ARENA}/…
infra/postgres/init-databases.sql       # + CREATE DATABASE rankup_db;
infra/kafka/create-topics.sh            # + tópicos RankUP
docker-compose.yml                      # + rankup-service + fleets spawn/mina/arena/terrenos
```

Cada plugin: `Crystal<Nome>Plugin` só boot+registro; listeners em `listener/`,
comandos em `commands/`, GUIs em `gui/`.

## 4. Modelo de dados (ERD) — Postgres `rankup_db`

```
player_economy   (economy)  ← optimistic-lock em SET/admin
  player_uuid UUID PK · money BIGINT d0 · tokens BIGINT d0 · version INT d0 · updated_at
player_rank      (rank)
  player_uuid PK · rank_id VARCHAR(32) d'TERRA' · rank_order INT d0 · version INT d0 · updated_at
  (nome/preço/prefixo/permissões/reward → CONFIG `rank`, não tabela)
player_prestige  (prestige)
  player_uuid PK · prestige INT d0 · multiplier NUMERIC(6,3) d1.000 · version INT d0 · updated_at
plots            (plot)  ← optimistic-lock (expansão)
  plot_id UUID PK · owner_uuid UUID UNIQUE · size SMALLINT d10 (10→20→30→40) ·
  invested_money BIGINT d0 · display_name VARCHAR(48) · version INT d0 · created/updated_at
  (level e produção NÃO são colunas — derivados de invested_money + tiers de upgrade; ver Fase 13)
plot_members     (plot)   PK(plot_id,member_uuid)
plot_settings    (plot)   PK(plot_id,member_uuid) · can_build/break/open_chests/plant/harvest BOOL
plot_upgrades    (plot)   PK(plot_id,upgrade_id) · tier INT d0  (catálogo em config `plot_upgrades`)
plot_worlds      (plot)  ← blob SWM (custom SlimeLoader, aprovado)
  plot_id UUID PK · slime_data BYTEA · lease_server VARCHAR(48) · lease_expires_at TIMESTAMPTZ ·
  version INT d0 (anti double-save) · saved_at
plot_plantations (plantation) PK(plot_id,crop) · planted_count INT d0
  crop∈{cactus,cana,bambu,wheat,carrot,potato,melon,pumpkin,cacau,beetroot,nether_wart}
  (cap NÃO é coluna — derivado de baseCap(config) + bonusPerTier×tier; ver Fase 14.
   Colheita dropa item e vende na loja; pistões por terreno limitados via upgrade piston_limit.)
mine_stats       (mine)   PK(mine_id,player_uuid) · blocks_mined BIGINT d0  (minas em config `mine`)
player_rankup_stats (stat) player_uuid PK · blocks_mined/ores_mined/pvp_kills/pvp_deaths BIGINT
player_homes     (home)   PK(player_uuid,name) · world/x/y/z/yaw/pitch
clans            (clan)  ← optimistic-lock (estrutura/nível)  [Fase 17]
  clan_id UUID PK · tag CHAR(3) UNIQUE (sigla 3 letras) · name VARCHAR(24) ·
  leader_uuid UUID · level INT d1 · bank BIGINT d0 · score BIGINT d0 · version INT d0 · created/updated_at
clan_members     (clan)   PK(clan_id,member_uuid) · member_uuid UNIQUE (1 clã/jogador) · role · joined_at
```

Owner de cada tabela = seu contexto; todas em `rankup_db`. **Catálogos** (ladder
de 25 ranks, composição de minas, upgrades) moram na **config central**
(`ConfigProvider`, keys `rank`/`mine`/`plot_upgrades`), editável a quente como
`parkour`/`chat` — não em tabela.

### 4.1 Fora do rankup_db

Punishments (rede→futuro `moderation` no core-service — mais tarde, decisão #4);
Settings de jogador (rede→`profile`).

### 4.2 Concorrência por operação (não tudo é 409)

- **Delta aditivo** (bloco/colheita/kill): `SET money=money+:delta` atômico, sem
  versão, sem 409 (como `ProfileService.addStats`). Plugins acumulam e dão flush
  periódico/no quit/na troca de servidor.
- **Débito condicional** (comprar rank/upgrade/expandir/`/pagar`): `SET
  money=money-:cost, version=version+1 WHERE money>=:cost` → 0 linhas = **HTTP
  422** (sem race de gasto duplo).
- **Set absoluto/admin/expansão/level**: **optimistic-lock** clássico (apresenta
  version, 409), como `InventoryService.save`. Colunas `version` em
  economy/rank/prestige/plots/plot_worlds.

## 5. Fluxo de eventos Kafka

Nomes kebab-case (espelham `KafkaTopics`/`create-topics.sh`). Key = `uuid`
(particiona por jogador ⇒ ordem por jogador) ou id do recurso. Payload =
`EventEnvelope` JSON como hoje.

| Tópico (novo) | Key | Payload | Produtor | Consumidor | Estado no Redis |
|---|---|---|---|---|---|
| `money-updated` | uuid | `{uuid,money,delta,source}` | economy (backend) | crystal-spawn/mina/arena (HUD/broadcast), stat | write-through `economy:{uuid}` + `tab:rankup` |
| `token-updated` | uuid | `{uuid,tokens,delta,source}` | economy | cosmético/boost | `economy:{uuid}` |
| `rank-updated` | uuid | `{uuid,rankId,rankOrder}` | rank | crystal-rank (efeito/anim), tab | `rankup:{uuid}` + `tab:rankup` |
| `prestige-updated` | uuid | `{uuid,prestige,multiplier}` | prestige | crystal-prestige, tab | `rankup:{uuid}` + `tab:rankup` |
| `plot-loaded` | plotId | `{plotId,ownerUuid,server}` | crystal-plot | observabilidade, TAB "server" | `plot-server:{uuid}` |
| `plot-saved` | plotId | `{plotId,version,server}` | crystal-plot | auditoria/anti-corrupção | libera lease em `plot_worlds` |
| `plot-updated` | plotId | `{plotId,kind}` (member/setting/upgrade/expand) | plot | crystal-plot de outras instâncias (invalida cache), GUI | invalida `plot:{plotId}` |

> **TAB por modo (decisão Fase 10/17, 2026-07-01):** a TAB é **agrupada por modo de
> jogo** (lobby vê lobby; RankUP vê RankUP, qualquer instância), num hash Redis
> **por grupo** `tab:<group>` (mapa `tab.groups` por `SERVER_TYPE`:
> `lobby={lobby}`, `rankup={spawn,mina,arena,terrenos}`), field=uuid → json
> `{nick,cargo,clanTag,rankId,prestige,money,server}`. A **entrada é escrita pelo
> servidor atual do jogador** (cargo resolvido localmente + sigla do clã de
> `clan-of`/`clan:{}` + rank/money do Redis). No **RankUP** a TAB exibe **cargo +
> nick + sigla do clã** (`[VIP] Steve [CLA]`, sigla = 3 letras, Fase 17); no
> **lobby**, cargo + nick. O **rank de jogo não entra na TAB** (fica no scoreboard e
> no chat do RankUP). Isto substitui o `tab:rankup` `{nick,rank,…}` antes listado.
| `mine-reset` | mineId+server | `{mineId,server,resetAt}` | crystal-mine | holograma/animação da própria instância | `mine:{server}:{mineId}` |
| `player-chat` **(existente)** | uuid | + `server`,`rankId` no envelope | crystal-chat | crystal-chat (formato global) | — |
| `clan-chat` (Fase 17) | clanId | `{clanId,senderUuid,senderName,message}` | crystal-clan | crystal-clan (entrega aos membros online do clã) | — |
| `clan-updated` (Fase 17) | clanId | `{clanId,kind}` (member/bank/level/score/disband) | clan (backend) | crystal-clan (GUI/TAB; disband expulsa) | invalida `clan:{clanId}` |

**Regra Kafka × Redis:**

- **Kafka** = fato de domínio que outros precisam **reagir** (broadcast de rankup,
  animação de reset, invalidação de cache de terreno, achievements).
- **Redis** = estado quente **lido em loop**: `tab:rankup` (TAB),
  `economy:{uuid}`/`rankup:{uuid}` (scoreboard/HUD), `leaderboard:*`, `mine:{…}`
  (barra %), `plot-server:{uuid}` (sticky). Scoreboard/TAB **não** consomem Kafka
  por jogador — leem Redis no próprio tick (~1–2s), como `crystal-tag` lê
  `tag:overrides`. Toda escrita que muda TAB/placar é **write-through**
  (Postgres→Redis→emite Kafka). Tópicos adicionados ao `KafkaTopics` (SDK+backend)
  e ao `create-topics.sh`.

## 6. API interna (gateway) + facade SDK

Estilo idêntico ao atual (`/api/profile`,`/api/inventory`): Bearer service token,
controller fino, 409/422 onde há concorrência. Roteadas p/ `lb://rankup-service`.

### 6.1 Economy/Token `/api/economy/**`

| Método/Path | Propósito |
|---|---|
| GET `/api/economy/{uuid}` | saldo (money+tokens+version); 404→cria no PUT |
| PUT `/api/economy/{uuid}` | ensure linha, retorna saldo |
| POST `/api/economy/{uuid}/money` | delta **aditivo** `{delta,source}` |
| POST `/api/economy/{uuid}/tokens` | delta aditivo |
| POST `/api/economy/{uuid}/debit` | **débito condicional** `{cost,reason}` → **422** insuficiente |
| POST `/api/economy/transfer` | `/pagar` `{from,to,amount}` atômico → 422 |
| PUT `/api/economy/{uuid}/set` | set absoluto (admin), optimistic-lock → 409 |

`/api/token/**` do brief é atendido aqui (decisão #5: só `/api/economy/**`).

### 6.2 Rank `/api/rank/**`

GET `/api/rank` (catálogo config) · GET `/api/rank/{uuid}` (atual) · POST
`/api/rank/{uuid}/promote` (lê preço config → debit atômico → avança rank_order →
aplica reward/permissões → emite `rank-updated`; 422 saldo, 409 concorrência).

### 6.3 Prestige/Plot/Mine/Stat/Home

| Método/Path | Propósito |
|---|---|
| POST `/api/prestige/{uuid}` | exige rank==último; reseta rank p/ TERRA, +prestige, acumula multiplier, reward; emite `prestige-updated`; 422 se não está no último |
| GET `/api/prestige/{uuid}` | prestígio+multiplier |
| GET `/api/plot/{uuid}` · GET `/api/plot/id/{plotId}` | terreno por dono/id |
| POST `/api/plot` | cria terreno 10×10 + Slime world vazio |
| POST `/api/plot/{plotId}/expand` | 10→20→30→40; debit + optimistic-lock; 422/409 |
| POST/DELETE `/api/plot/{plotId}/members[/{uuid}]` | add/remove membro |
| PUT `/api/plot/{plotId}/settings/{memberUuid}` | permissões (build/break/open-chests/plant/harvest) |
| POST `/api/plot/{plotId}/upgrade` | compra tier (debit); 422 |
| PUT `/api/plot/{plotId}/name` | renomear |
| GET/PUT `/api/plot/{plotId}/world` | **blob Slime** (bytes) com lease/version (§9); GET carrega, PUT salva; 409 lease/version |
| GET `/api/mine` · `/api/mine/{mineId}` | catálogo(config)/estado; POST `/api/mine/{mineId}/mined` batelada stats |
| GET/POST `/api/stats/{uuid}` | stats (get/add aditivo) |
| GET/POST/DELETE `/api/home/{uuid}` | homes |

### 6.4 Facade SDK

Novos clients por feature em `crystal-core`, expostos pelo `CrystalCore`:
`crystal.economy()`, `.ranks()`, `.prestige()`, `.plots()`, `.mines()`. DTOs
`record` novos em `com.redecrystal.core.http`: `EconomyData(uuid,money,tokens,
version)`, `RankData`, `PrestigeData`, `PlotData`, `PlotMember`, etc. Cada método
faz só HTTP (off-thread pelo chamador async→main), fallback explícito (404→zerado,
falha→null/lista vazia). Exceções: o SDK hoje **não** tem `ConflictException` — o
409 sai por `BackendException.statusCode()==409` (mantido); adicionamos apenas
`InsufficientFundsException`(422) como subclasse de `BackendException` (não quebra
catches existentes). O backend também não mapeia 422 hoje: o `rankup-service`
introduz esse mapeamento no seu handler. Clients no `crystal-core` (decisão #6).
(Ver §9 do spec da Fase 8 para as divergências completas do código atual.)

## 7. Escalabilidade Docker/K8s

### 7.1 Docker Compose (padrão de fleet do lobby)

Cada fleet = mesma definição (âncora YAML `&spawn-env`/`&mina-env`) com
`SERVER_ID`/`SERVER_HOST` diferentes, `depends_on: *mc-deps`, montando os jars do
jogo. `crystal-bungee` descobre pela registry (sem mudança no proxy p/
adicionar/remover instância). Adições:

```
rankup-service:      # build MODULE=rankup-service, RANKUP_DB=rankup_db
spawn-01/spawn-02:   # SERVER_TYPE=spawn, âncora &spawn-env, schematic WORLD_SPAWN
mina-01/mina-02:     # SERVER_TYPE=mina, âncora &mina-env
arena-01:            # SERVER_TYPE=arena, instância única (sem âncora de fleet)
terrenos-01/-02:     # SERVER_TYPE=terrenos, SWM+VOID, sticky (§9)
```

`init-databases.sql` += `CREATE DATABASE rankup_db;`. `create-topics.sh` +=
tópicos do §5.

### 7.2 Roteamento no `crystal-bungee`

`LobbyRouter` generalizado p/ **`FleetRouter` por tipo** (mesma lógica
least-loaded, `pending` smoothing, `waitingFor…`, drop de instância que saiu da
registry):

- **spawn/mina** → least-loaded como o lobby (spawn menos cheio; ao escolher mina
  no GUI, mina menos cheia).
- **arena** → instância única, conecta direto (cap ~200; cheia→mensagem).
- **terrenos** → **sticky**: lê `plot-server:{uuid}` no Redis; se aponta p/
  instância **online**, envia p/ ela (garante Slime world sempre no mesmo servidor
  enquanto ativo); senão escolhe least-loaded, grava `plot-server:{uuid}`
  (TTL/lease), roteia. Ao sair (após save+unload) o assignment expira p/ permitir
  rebalanceamento.

### 7.3 K8s (Fase 8 da rede)

- spawn/mina: `Deployment`+`HPA` (CPU/TPS/online), discovery pela registry.
- arena: `Deployment` replicas=1.
- terrenos: `StatefulSet` (identidade estável) + sticky por `plot-server:{uuid}`.
  SWM torna o pod stateless quanto a mundo (vem do Postgres) → sticky é por sessão
  ativa, não por disco, facilitando reagendar noutro pod após save.

## 8. Estratégia de cache (Redis)

| Dado | Chave | Tipo | TTL | Estratégia |
|---|---|---|---|---|
| Saldo | `economy:{uuid}` | hash{money,tokens,version} | 10min | write-through; read-through no miss |
| Perfil RankUP (HUD/TAB) | `rankup:{uuid}` | hash{rankId,rankOrder,prestige,multiplier,money} | 10min | write-through nas mutações rank/prestige/economy |
| TAB por modo | `tab:<group>` (ex. `tab:lobby`, `tab:rankup`) | hash field=uuid→json{nick,cargo,rankId,prestige,money,server} | limpo no quit (+refresh no tick) | escrita pelo servidor atual do jogador (cargo local + rank/money do Redis); **lida 1×/tick** pelo crystal-tab do mesmo grupo. TAB exibe só o cargo (ver Fase 10) |
| Servidor atual | `player-server:{uuid}` | string | 30s (refresh no heartbeat/join) | usado no chat global e TAB |
| Ladder/catálogo | via ConfigProvider (`config:rank`) | — | hot-reload Kafka | já existe |
| Estado mina (barra %) | `mine:{server}:{mineId}` | hash{percentRemaining,resetAt} | 60s | write-through crystal-mine; lido pelo holograma |
| Metadados terreno | `plot:{plotId}` | hash | 5min | read-through; invalida por `plot-updated` |
| Sticky terreno | `plot-server:{uuid}` | string(serverId) | lease ~5min renovado | escrito no roteamento/entrada; apagado após save+unload |
| Lock Slime world | `plot-lock:{plotId}` (NX) + `plot_worlds.lease_*` | string NX+TTL | ~2min renovável | anti double-load (§9) |
| Leaderboards | `leaderboard:money`/`:tokens`/`:prestige`/`:blocks-mined`/`:clan-score` | sorted set | sem TTL | write-through (`RedisClient.leaderboardAdd` já existe) |
| Metadados de clã (Fase 17) | `clan:{clanId}` | hash{tag,name,level,bank,score,leader} | 10min | write-through; read-through; invalida por `clan-updated` |
| Clã do jogador (Fase 17) | `clan-of:{uuid}` | string(clanId) | — | write-through no join/leave/disband (chat/TAB leem a sigla) |
| Convite de clã (Fase 17) | `clan-invite:{uuid}` | set(clanId) | ~2min | `SADD … EX 120`; efêmero, sem tabela |

Read-through (miss→Postgres→popula): saldos, metadados de terreno. Write-through
(grava os dois): saldo, rank, prestige, TAB, leaderboard, mina. Falha de Redis é
**fail-open** (cai pro Postgres/valor neutro), nunca derruba o servidor.

## 9. Save dos terrenos (SlimeWorldManager)

Cada terreno = **uma Slime world própria** (VOID), carregada só enquanto
dono/membro está no fleet `terrenos`. Objetivo inegociável: **nunca perder
progresso, nunca corromper/duplicar**.

### 9.1 Onde vivem os dados

- **Metadados** (tamanho/membros/permissões/upgrades/level/invested_money/nome) →
  Postgres `rankup_db` (contexto `plot`), via gateway.
- **Bytes da Slime world** → *Custom SlimeLoader* lê/grava blob em
  `plot_worlds.slime_data (BYTEA)` via `GET/PUT /api/plot/{plotId}/world`
  (decisão #3). Mantém "só Postgres, um banco por serviço", sem MySQL; mundo
  versionado/leaseado junto dos metadados. (Fallback de fase inicial, se preciso:
  file data source do SWM em volume compartilhado — não escolhido.)
- **Distribuição SWM (decisão Fase 12, 2026-07-01):** o fleet `terrenos` roda o
  **Advanced Slime Paper (ASP)** — um **fork do Paper** com a Slime API embutida
  (load/unload assíncrono; mobs/tile-entities/baús persistidos no blob) — em vez de
  um plugin SWM externo. **Só o `terrenos`** troca a imagem base; o resto da rede
  segue Paper stock. O `crystal-plot` compila contra a API do ASP como `provided`.
  O plano fixa a versão de ASP compatível com 1.21.x.

### 9.2 Ciclo de vida (lock + versão, anti-corrupção)

```
ENTRAR (roteado ao terrenos-XX, sticky):
 1. lock: SET plot-lock:{plotId}=serverId NX EX 120 (Redis) → falhou = mundo ativo noutra instância: nega/espera
 2. lease: UPDATE plot_worlds SET lease_server=serverId, lease_expires_at=now()+2min
           WHERE plot_id=… AND (lease expirado OU lease_server=serverId)
 3. GET /api/plot/{plotId}/world → SWM load VOID → teleporta
 4. emite plot-loaded; grava plot-server:{uuid}
DURANTE:
 - renova plot-lock e lease a cada ~60s enquanto o mundo está montado
 - save de segurança a cada 3–5min: serializa Slime → PUT /world {version} (409 se sobrescrita) → version++
SAIR (último jogador deixa o mundo):
 5. PUT /world {bytes,version}            (save-on-leave)
 6. SWM unload
 7. PUT /world de novo pós-unload se mudou (save-on-unload) → consistência
 8. DEL plot-lock, libera lease; emite plot-saved; expira plot-server:{uuid}
CRASH do terrenos-XX:
 - plot-lock e lease têm TTL → expiram sozinhos (≤2min); nenhuma outra instância carrega antes (lock NX) → sem 2 writers
 - perda máxima = janela desde o último save de segurança; version+lease impedem que save atrasado de processo zumbi sobrescreva estado mais novo (PUT com version antiga → 409, rejeitado)
```

Sticky routing (§7.2) + lock NX + lease com TTL + `version` = **um único writer
por vez**, **sem duplicação** (jogador volta sempre à mesma instância enquanto
ativo), **recuperação automática** de crash. Save-on-leave **e** on-unload cobrem
o "never lose progress".

## 10. Roadmap (continua a numeração — última foi Fase 7)

Cada fase compilável e integrável, ordenada por dependência. "Verificado" =
`make plugins`/`mvn -pl … compile`, **recriar container** (`docker compose up -d
--force-recreate --no-deps …`), **observar em jogo**. Cada fase tem — ou terá —
seu próprio spec+plan em `docs/superpowers/`.

**Fase 8 — Fundação: `rankup-service` + Economia (Money/Tokens).** Microsserviço
no ar, contexto `economy`, SDK `EconomyClient`, GUI `/saldo`+`/pagar`. Módulos:
`backend/rankup-service`(economy), `crystal-core`(EconomyClient+DTO+tópicos+
chaves), `crystal-economy`. Infra: `rankup_db`, rota no gateway, tópicos
money/token-updated. Riscos: dois write-paths (delta aditivo vs débito
condicional) — testar 422/409; rota do gateway p/ 2º serviço via Eureka.
Verificado: dar Money, reconectar, persiste; `/pagar` 422 em saldo insuficiente;
`money-updated` no Kafka UI.

**Fase 9 — Ranks + Prestige (config-driven).** Ladder TERRA…INFINITO na config
`rank`, `/rankup` GUI+NPC (compra o próximo), `/prestigio` reseta e multiplica.
Módulos: `rankup-service`(rank,prestige), `crystal-rank`, `crystal-prestige`,
`crystal-core`(Rank/PrestigeClient). Riscos: atomicidade da compra (debit +
avanço de order); conceder permissões/prefixo sem colidir com cargo de rede
(§1.1). Verificado: comprar rank deduz Money e sobe; no último rank `/prestigio`
reseta p/ TERRA, multiplier sobe, rewards; hot-reload da ladder sem restart.

**Fase 10 — Hub spawn (fleet) + TAB global + chat global + scoreboard.**
`SERVER_TYPE=spawn` escalável, `crystal-spawn` (scoreboard rank/prestige/money/
tokens/online/server, hotbar/NPCs de navegação), `FleetRouter` no bungee, portal
lobby→spawn, `tab:rankup`, formato chat `[Spawn] [TERRA] Steve`. Módulos:
`crystal-spawn`, `crystal-bungee`(FleetRouter), `crystal-tab`/`crystal-chat`.
Riscos: TAB/placar por leitura Redis no tick (não Kafka por jogador); presença
cross-server (`player-server:{uuid}`). Verificado: 2 spawns recebem 2/2;
scoreboard atualiza saldo ~1–2s; chat de um spawn aparece no outro com prefixo;
TAB lista todos com server/rank.

**Fase 11 — Minas (fleet `mina`).** `crystal-mine` — auto-regen config (blocos+%
na config `mine`), reset auto (timer/percent)+manual (admin), holo+NPC+animação,
recompensa por bloco → economy (batelada). Módulos: `crystal-mine`,
`rankup-service`(mine/stat leves), `crystal-core`(MineClient), tópico
`mine-reset`. Riscos: performance de regen (API eficiente/FAWE, sem travar main
thread); janela de perda da batelada em crash. Verificado: minerar dá Money; mina
reseta por timer e por comando com holo/anim; barra de % via `mine:{…}`.

**Fase 12 — Terrenos: fundação SWM (load/save/unload + sticky).**
`SERVER_TYPE=terrenos` (SWM,VOID), custom SlimeLoader→`plot_worlds`, ciclo de vida
completo (§9) com lock/lease/version, `/terreno` GUI básica (info/home/rename),
sticky routing. Módulos: `crystal-plot`, `rankup-service`(plot+world blob),
`crystal-bungee`(sticky terrenos), `crystal-core`(PlotClient). Riscos: **maior
risco do projeto** — corrupção/duplicação; validar lock NX+TTL+version,
save-on-leave **e** unload, teste de crash. Verificado: entrar cria/carrega;
construir; sair salva; reconectar preserva; matar container e reconectar → sem
perda além da janela; nunca 2 servidores carregam o mesmo plot.

**Fase 13 — Terrenos avançado: expansão + membros + permissões + upgrades +
level/produção.** Expandir 10→20→30→40 pago em Money (debit+optimistic-lock),
add/remove membros, permissões por membro (build/break/open-chests/plant/harvest),
upgrades, level/produção/invested_money na GUI. Módulos: `crystal-plot` (GUI +
enforcement nos listeners), `rankup-service`(settings/members/upgrades), tópico
`plot-updated` (invalidação cross-instância). Riscos: enforcement por evento sem
vazar; concorrência de expansão (409). Verificado: expandir cobra e aumenta área;
membro sem `can_break` não quebra; upgrade sobe cap/produção; muda em outra
instância via `plot-updated`.

**Fase 14 — Plantações.** `crystal-plantation` — caps por cultura por terreno
(`plot_plantations`), upgrades elevam caps, colheita → economy; culturas do brief.
Módulos: `crystal-plantation`, `rankup-service`(plantation). Riscos: contagem de
plantas (contabilizar em plantio/quebra, sem scan de chunk caro); interação com
permissões plant/harvest (Fase 13). Verificado: plantar até o cap bloqueia acima;
upgrade sobe cap; colher dá Money conforme config.

**Fase 15 — Arena PvP (instância única).** `SERVER_TYPE=arena` (1 instância, cap
~200), conexão direta pelo bungee, stats PvP (kills/deaths) → `stat`/economy
(reward de kill). Módulos: `crystal-bungee`(conexão direta+cap), reuso de
`crystal-economy`/`crystal-inventory`, contexto `stat`. Riscos: cap 200 + "arena
cheia"; kill reward aditivo. Verificado: entrar; kill dá Money e incrementa stats;
cap respeitado.

**Fase 16 — Polish: NPCs (Citizens), holograms, animações, PlaceholderAPI,
crates/boosts/cosméticos (Tokens).** NPCs Citizens (minas/arena/terrenos/loja/
prestígio), holos de topo (ranking/mina), animações de reset, expansão
PlaceholderAPI (`%rank% %money% %tokens% %prestige% %plot_size% %plot_level%
%online% %server%`), gasto de Tokens (cosméticos/boosts/crates). Módulos: todos os
`crystal-*` do jogo + `crystal-hologram` + nova PlaceholderExpansion. Riscos:
Citizens como plugin externo (montar em `EXTERNAL_PLUGINS/` como LuckPerms/FAWE);
placeholders lendo cache Redis (não bloquear). Verificado: NPCs abrem as GUIs
certas; placeholders resolvem em scoreboard/holo de terceiros; boost de Tokens
aplica multiplicador temporário.

**Fase 17 — Clãs.** Contexto `clan` no `rankup-service` (`clans`, `clan_members`;
níveis/score em config), plugin `crystal-clan` (9º), banco compartilhado em Money +
níveis pagos, chat de clã cross-server (`clan-chat`) + **sigla (3 letras) no TAB**
(cargo + nick + sigla) e no chat global, e ranking por `score` próprio
(`leaderboard:clan-score`). Um clã por jogador; cargos LEADER/OFFICER/MEMBER;
convites efêmeros no Redis. Reusa débito/aditivo da economia (Fase 8) e a TAB por
modo (Fase 10). Verificado: criar/convidar/depositar/sacar/nível cross-server,
`/c` chega aos membros, sigla no TAB/chat, ranking ordena, dissolver expulsa.

> Fase 8 da rede (Kubernetes): quando os fleets existirem, materializar os
> manifests do §7.3 (HPA spawn/mina, StatefulSet sticky terrenos, replica única
> arena).

## 11. Decisões (aprovadas 2026-07-01)

1. ✅ Money/Tokens separados de `profile.coins` + rank de jogo ≠ cargo de rede.
2. ✅ `rankup-service`/`rankup_db` dedicado (não empilhar no `core-service`).
3. ✅ Slime blob em `plot_worlds` via custom SlimeLoader (não file data source).
4. ✅ Punishments/`moderation` fica para depois; Settings de jogador no `profile`.
5. ✅ Tokens só em `/api/economy/**` (sem alias `/api/token/**`).
6. ✅ Facades RankUP no `crystal-core` (não módulo `crystal-rankup-common` shaded).
