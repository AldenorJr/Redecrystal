# Design — Fase 17: Clãs (RankUP)

Data: 2026-07-01
Branch base: `feat/login-autocomplete-e-troca-senha`

> Fase do jogo **RankUP** sobre a rede RedeCrystal. Adiciona o **contexto `clan`** ao
> `rankup-service` (banco `rankup_db`) e um plugin leve **`crystal-clan`** (GUI-first)
> que roda nos servidores de jogo (spawn/mina/arena/terrenos). Clãs são progressão
> **social + econômica** do RankUP: núcleo (criar/convidar/cargos), **banco
> compartilhado em Money** + **níveis pagos**, **chat de clã** cross-server + **tag no
> TAB/chat global**, e um **ranking de clãs** por **pontos próprios (`score`)**.
> Arquitetura de alto nível em
> [`2026-07-01-rankup-arquitetura-design.md`](2026-07-01-rankup-arquitetura-design.md)
> (§2, §4, §4.2, §5, §6, §8) — este spec detalha só o que a Fase 17 entrega. Texto de
> jogador em PT; identificadores/módulos/tópicos/chaves em inglês.

## 1. Problema

O RankUP precisa de **clãs**: grupos persistentes de jogadores com identidade
própria (tag/nome), hierarquia (líder/oficial/membro), um **banco compartilhado**
para juntar Money, **níveis** que ampliam o clã, um **canal de chat** próprio e um
**ranking** competitivo entre clãs. Cinco lacunas concretas:

1. **Não existe o contexto `clan`.** O master §2.2 lista os contextos do `rankup_db`
   (`economy`/`rank`/`prestige`/`plot`/`plantation`/`mine`/`stat`/`home`) — clã **não
   está entre eles**. Não há tabela de clãs, membros, nem endpoints.

2. **Não há grupo persistente de jogadores.** Toda a progressão hoje é individual
   (economia/rank/prestígio por `uuid`). Falta a noção de "vários jogadores
   pertencem à mesma entidade", com hierarquia e permissões por cargo.

3. **O banco do clã precisa de escrita concorrente segura.** Múltiplos membros
   depositam/sacam ao mesmo tempo; comprar nível gasta do banco. Isso exige os
   **três write-paths** do master §4.2 (aditivo no depósito, débito condicional no
   saque/nível → 422, optimistic-lock no set/estrutura → 409), sem race de gasto
   duplo.

4. **Chat e identidade de clã são cross-server.** Membros estão espalhados por
   spawn/mina/arena/terrenos; um `/c <msg>` de um servidor tem de chegar aos demais,
   e a **tag do clã** tem de aparecer no chat global e no TAB de toda a rede — sem
   estado local por instância.

5. **Não há métrica de competição entre clãs.** Falta um **ranking** ordenável e
   barato de ler no tick (sorted set), alimentado por uma pontuação própria do clã.

A Fase 17 **reaproveita** intensamente: o `rankup-service` e o contexto `economy`
(Fase 8, débito condicional `422` + aditivo), os padrões de concorrência do master
§4.2, o `ConfigProvider` hot-reload (catálogo em config, como `rank`/`mine`), o
`crystal-chat` (chat global via `player-chat`) e o `tab:rankup` (TAB por modo, Fase
10), o `RedisClient.leaderboardAdd` já existente e a infra de facade do SDK (Fases
8/9). Não reconstrói economia, chat, TAB nem descoberta de servidor.

## 2. Objetivos e não-objetivos

**Objetivos**
- **Contexto `clan`** no `rankup-service` (`api/application/domain`): tabelas `clans`
  e `clan_members`, serviço, endpoints `/api/clan/**`, migração Flyway.
- **Núcleo**: criar/dissolver clã, convidar/aceitar/expulsar/sair, **cargos**
  (LEADER/OFFICER/MEMBER) com permissões, **tag** única + nome.
- **Banco + níveis**: banco compartilhado em **Money** (depositar aditivo, sacar
  débito condicional), **níveis** pagos **do banco do clã** que elevam
  `maxMembers` — catálogo na **config `clan`** (hot-reload).
- **Score + ranking**: `score` próprio **aditivo** (regras config-driven:
  `perMoney` no depósito, `perLevel` na compra de nível), `leaderboard:clan-score`
  (sorted set) + GUI/holo de topo. **Sem** tag/cargo/recompensa especial para o clã
  nº 1 — só o ranking em si.
- **Chat de clã** cross-server (`/c <msg>`, tópico `clan-chat`) + **tag do clã** no
  **chat global** (formato `crystal-chat`) e no **TAB** (`tab:rankup`).
- **SDK `crystal-core`**: `ClanData`, `ClanMember`, `ClanClient`, `crystal.clans()`,
  novos tópicos/chaves.
- **Plugin leve `crystal-clan`** (GUI-first): GUI `/cla` (criar/membros/banco/
  níveis/convites/ranking), comando `/c`, listeners de `clan-chat`/`clan-updated`.
- **Rota** `/api/clan/**` no gateway; `crystal-clan` montado nos servidores de jogo.

**Não-objetivos** (ficam para fases seguintes)
- **Alianças/rivalidades** entre clãs, **guerras** clã-vs-clã, **território** de clã.
- **Banco em Tokens** — o banco desta fase é só **Money** (Tokens seguem individuais).
- **Recompensa/prefixo por posição no ranking** — o usuário decidiu **só o ranking**,
  sem premiar o líder do ranking (sem tag/cargo especial).
- **Múltiplos clãs por jogador** — **um clã por jogador** (unique global).
- **Convites persistentes** entre reinícios — convites são **efêmeros no Redis**
  (TTL ~2min); um restart do backend descarta convites pendentes (aceitável).
- **Home/base de clã, upgrades além de nível, logs de auditoria** — fora do escopo.

## 3. Decisões de design

| Tema | Decisão |
|------|---------|
| **Onde vive o contexto** | Novo contexto `clan` no **`rankup-service`/`rankup_db`** (não `core-service`). Clã é feature do jogo RankUP (banco em Money, integra com `economy`); segue "um jogo, um serviço" do master §2.2. |
| **Um clã por jogador** | `clan_members.member_uuid` tem **UNIQUE global** (não só PK composta). Tentar entrar/criar já pertencendo → **409**. Simplifica identidade (chat/TAB resolvem `clan-of:{uuid}` sem ambiguidade). |
| **Identidade** | `tag` VARCHAR(4) **UNIQUE** (2–4 chars `A–Z0–9`, uppercase; validada no serviço), `name` VARCHAR(24). Tag é o que aparece no TAB/chat; nome no GUI. Conflito de tag → **409**. |
| **Cargos** | Enum `ClanRole {LEADER, OFFICER, MEMBER}`. **MEMBER**: depositar, chat, ver. **OFFICER**: + convidar, expulsar MEMBER. **LEADER**: + sacar, comprar nível, promover/rebaixar, transferir liderança, dissolver, renomear. Exatamente **1 LEADER** por clã. |
| **Banco só Money** | O banco do clã é **Money** (contexto `economy` do jogador). Depositar = `debit` no jogador (422 se sem saldo) **+** aditivo no `clans.bank`. Sacar = `debit` no `clans.bank` (422) **+** aditivo no jogador. Tokens ficam individuais. |
| **Depósito: aditivo bank+score juntos** | Um só UPDATE atômico soma `bank += delta` **e** `score += floor(delta × scoring.perMoney)` (sem versão, sem 409) — molde do `addMoney`/`addStats` do master §4.2. Score é subproduto do depósito, calculado no backend lendo a config `clan`. |
| **Saque / comprar nível: débito condicional** | `UPDATE clans SET bank = bank - :cost, … WHERE clan_id=… AND bank >= :cost` → 0 linhas = **422** "banco insuficiente" (sem race de gasto duplo), igual ao débito de economia (Fase 8). Comprar nível também faz `level++` e `score += scoring.perLevel` no mesmo caminho. |
| **Entrar respeitando cap** | Aceitar convite é **transacional**: valida jogador sem clã (senão 409) **e** `count(membros) < levels[level].maxMembers` (senão **422** "clã cheio"). Cap vem da **config `clan`** por nível, não de coluna. |
| **Estrutura: optimistic-lock** | Promover/rebaixar/transferir/dissolver/renomear apresentam `version` → **409** em concorrência (molde do `InventoryService.save`). |
| **Catálogo em config, não tabela** | Níveis (`maxMembers`,`cost`) e regras de score (`perMoney`,`perLevel`) moram na **config central `clan`** (`ConfigProvider`, hot-reload como `rank`/`mine`) — **dado**, editável a quente, não DDL. |
| **Convites efêmeros no Redis** | `clan-invite:{uuid}` (SET de clanIds, **TTL ~2min**) — sem tabela `clan_invites`, sem limpeza de expirados. Convite é curto por natureza; some no restart (aceitável, §2). |
| **Ranking por score próprio** | `score` aditivo (não Money do banco nem soma de blocos) → **desacopla** de `mine`/`stat`, barato como Money. `leaderboard:clan-score` (sorted set, write-through) — lido no tick, **sem Kafka por jogador**. |
| **Chat de clã por Kafka** | Tópico `clan-chat` (key=clanId): o servidor de origem publica, todos os `crystal-clan` consomem e entregam só aos membros online **daquele** clã (resolvem `clan-of:{uuid}`). Espelha o `player-chat` global. |
| **Invalidação cross-instância** | Tópico `clan-updated` (key=clanId, `kind`): invalida `clan:{clanId}` e reflete em GUIs/TAB nas outras instâncias; no `kind=disband` os membros online são expulsos do clã (mensagem + limpa `clan-of`). |
| **Plugin novo `crystal-clan`** | Plugin **leve, clã-only**, montado nos servidores de jogo junto do `crystal-economy` (que roda em todo tipo). **Não** dobra em `crystal-economy` (economia-only) nem em `crystal-spawn` (spawn-only) — clã existe em spawn/mina/arena/terrenos. Espelha "um plugin por responsabilidade". **9º plugin** (após `crystal-arena`). Diverge do master §2.1 (não listou clã) — ver §10. |
| **Facade SDK por feature** | `ClanClient` + `crystal.clans()` segue `economy()`/`ranks()`/`prestige()`/`stats()` (Fases 8/9/15), delegando à `BackendHttpClient`. |
| **Tag no TAB** | O TAB por modo (`tab:rankup`) ganha o campo `clanTag`; a entrada (já escrita pelo servidor atual do jogador, Fase 10) passa a incluir a tag lendo `clan-of:{uuid}`+`clan:{clanId}`. Diverge do "TAB exibe só o cargo" do master §5 — ver §10. |

## 4. Arquitetura

### 4.1 Contexto `clan` (`api/application/domain`)

**Migração `V<n>__rankup_clan.sql`** (número definido quando a fase landa) — cria as
duas tabelas no `rankup_db`:

```sql
CREATE TABLE clans (
  clan_id      UUID PRIMARY KEY,
  tag          VARCHAR(4)  NOT NULL UNIQUE,
  name         VARCHAR(24) NOT NULL,
  leader_uuid  UUID        NOT NULL,
  level        INT         NOT NULL DEFAULT 1,
  bank         BIGINT      NOT NULL DEFAULT 0,
  score        BIGINT      NOT NULL DEFAULT 0,
  version      INT         NOT NULL DEFAULT 0,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE clan_members (
  clan_id      UUID        NOT NULL REFERENCES clans(clan_id) ON DELETE CASCADE,
  member_uuid  UUID        NOT NULL UNIQUE,          -- um clã por jogador
  role         VARCHAR(8)  NOT NULL DEFAULT 'MEMBER',
  joined_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (clan_id, member_uuid)
);
```

**`domain/ClanEntity`** (`@Table("clans")`): `clanId` (PK), `tag`, `name`,
`leaderUuid`, `level`, `bank`, `score`, `version` (int — optimistic-lock **manual**
comparando `version` no `@Modifying`, como `InventoryService.save`; **não** `@Version`
JPA, para casar com o padrão das Fases 8–16), `createdAt`, `updatedAt`.
**`domain/ClanMemberEntity`** (`@Table("clan_members")`): PK composta
(`clanId`,`memberUuid`), `role` (String do enum), `joinedAt`.

**`domain/ClanRepository`** — além dos derivados (`findByTag`, `findByLeaderUuid`),
os write-paths concorrentes do master §4.2:

```java
// depósito: aditivo bank + score, atômico, sem versão
@Modifying(clearAutomatically = true)
@Query("UPDATE ClanEntity c SET c.bank = c.bank + :money, c.score = c.score + :score, "
     + "c.updatedAt = CURRENT_TIMESTAMP WHERE c.clanId = :clanId")
int deposit(UUID clanId, long money, long score);

// saque / compra de nível: débito condicional → 0 linhas = 422
@Modifying(clearAutomatically = true)
@Query("UPDATE ClanEntity c SET c.bank = c.bank - :cost WHERE c.clanId = :clanId AND c.bank >= :cost")
int debitBank(UUID clanId, long cost);
```

**`domain/ClanMemberRepository`** (`JpaRepository<ClanMemberEntity, …>`):
`findByMemberUuid` (um clã por jogador → 0/1), `findByClanId` (lista), `countByClanId`.

**`application/ClanService`** (molde do `EconomyService`; usa `ConfigProvider` do
`rankup-service` para ler `clan.levels`/`clan.scoring`):

| Método | Comportamento |
|--------|---------------|
| `getByMember(uuid)` | membro → clã (join) ou `null`. |
| `getById(clanId)` | clã por id (para GUI/leaderboard). |
| `create(uuid, tag, name)` | valida jogador sem clã (409) e tag livre (409); cria `clans` + membro LEADER; write-through `clan:{clanId}`, `clan-of:{uuid}`, `leaderboard:clan-score`; emite `clan-updated(kind=member)`. |
| `invite(clanId, actor, target)` | valida cargo ≥ OFFICER; `SADD clan-invite:{target} clanId EX 120`. |
| `join(clanId, uuid)` | valida convite (`SISMEMBER`), sem clã (409), `count < levels[level].maxMembers` (422); insere MEMBER; write-through `clan-of`; emite `clan-updated(kind=member)`. |
| `leave/kick(clanId, actor, target)` | LEADER não sai sem transferir (422); OFFICER só expulsa MEMBER; remove membro; limpa `clan-of:{target}`; emite `clan-updated(kind=member)`. |
| `setRole(clanId, actor, target, role)` | LEADER only; transferir liderança = target vira LEADER + actor vira OFFICER (optimistic-lock, 409). |
| `deposit(clanId, uuid, amount)` | `economy.debit(uuid, amount)` (422) → `repository.deposit(clanId, amount, floor(amount×perMoney))` → write-through `clan:{}`+leaderboard; emite `clan-updated(kind=bank/score)`. |
| `withdraw(clanId, uuid, amount)` | LEADER only; `repository.debitBank(clanId, amount)` (422) → `economy.addMoney(uuid, amount, "clan-withdraw")`; write-through; emite `clan-updated(kind=bank)`. |
| `buyLevel(clanId, uuid)` | LEADER only; lê `cost` do próximo nível na config; `debitBank(clanId, cost)` (422) → `level++`, `score += perLevel` (optimistic-lock no `level`, 409); write-through; emite `clan-updated(kind=level/score)`. |
| `rename(clanId, actor, name)` | LEADER only; optimistic-lock. |
| `disband(clanId, actor)` | LEADER only; `DELETE clans` (cascata membros); limpa `clan-of:*` dos membros, remove do `leaderboard:clan-score`; emite `clan-updated(kind=disband)`. |
| `leaderboard(limit)` | `ZREVRANGE leaderboard:clan-score` → resolve `clan:{clanId}` de cada. |

**`api/ClanController`** (`@RequestMapping("/api/clan")`, controller fino) — ver §4.4.

### 4.2 Config `clan` (níveis + regras de score)

Config central **key `clan`** (via `PUT /api/config/clan`, hot-reload como
`rank`/`mine`; é **dado**, não código):

```json
{
  "levels": [
    { "level": 1, "maxMembers": 5,  "cost": 0 },
    { "level": 2, "maxMembers": 10, "cost": 50000 },
    { "level": 3, "maxMembers": 20, "cost": 200000 },
    { "level": 4, "maxMembers": 30, "cost": 750000 }
  ],
  "scoring": { "perMoney": 0.001, "perLevel": 500 }
}
```

`perMoney: 0.001` = 1 ponto de score a cada 1.000 Money depositados
(`floor(amount × perMoney)`). `perLevel` = pontos ganhos ao subir de nível. O
`ClanService` lê `ConfigProvider.get("clan")` + `onChange` (hot-reload). Config
ausente/corrompida → nível 1 fixo, `maxMembers` num default constante, score 0 (sem
crash).

### 4.3 Redis & Kafka

**Chaves Redis** (fail-open: Redis fora → cai pro Postgres/valor neutro):

| Dado | Chave | Tipo | TTL | Estratégia |
|---|---|---|---|---|
| Metadados do clã | `clan:{clanId}` | hash{tag,name,level,bank,score,leader} | 10min | write-through; read-through no miss; invalida por `clan-updated` |
| Clã do jogador | `clan-of:{uuid}` | string(clanId) | — | write-through no join/leave/disband (chat/TAB leem rápido) |
| Convite pendente | `clan-invite:{uuid}` | set(clanId) | ~2min | `SADD … EX 120` no convite; `SISMEMBER` no aceite |
| Ranking | `leaderboard:clan-score` | sorted set | sem TTL | write-through (`leaderboardAdd`); lido no tick pela GUI/holo |

**Tópicos Kafka** (kebab-case, em `KafkaTopics` + `create-topics.sh`):

| Tópico (novo) | Key | Payload | Produtor | Consumidor | Estado Redis |
|---|---|---|---|---|---|
| `clan-chat` | clanId | `{clanId,senderUuid,senderName,message}` | crystal-clan (origem) | crystal-clan (todas instâncias) | — |
| `clan-updated` | clanId | `{clanId,kind}` (member/bank/level/score/disband) | ClanService (backend) | crystal-clan (GUI/TAB refresh; disband→expulsa) | invalida `clan:{clanId}` |

Ranking **não** gera Kafka por jogador — a GUI/holo lê `leaderboard:clan-score` no
próprio tick (~1–2s), como o TAB lê `tab:rankup` (master §8).

### 4.4 Endpoints `/api/clan/**`

Estilo idêntico ao atual (Bearer service token, controller fino, 409/422 onde há
concorrência), roteado p/ `lb://rankup-service`.

| Método/Path | Body → Resposta / erro |
|---|---|
| `GET /api/clan/{uuid}` | clã do jogador (join membro→clã) ou 404 |
| `GET /api/clan/id/{clanId}` | clã por id → `ClanResponse{clanId,tag,name,level,bank,score,leaderUuid,members[]}` |
| `POST /api/clan` | `{ownerUuid,tag,name}` → cria (LEADER); **409** tag/jogador |
| `POST /api/clan/{clanId}/invite` | `{actorUuid,targetUuid}` → grava convite Redis; 403 cargo |
| `POST /api/clan/{clanId}/members` | `{uuid}` (aceitar convite) → **409** já em clã · **422** clã cheio/sem convite |
| `DELETE /api/clan/{clanId}/members/{uuid}` | `?actor=` (sair/expulsar) → **422** líder sem transferir · 403 cargo |
| `PUT /api/clan/{clanId}/members/{uuid}/role` | `{actorUuid,role}` (promover/rebaixar/transferir) → **409** version · 403 |
| `POST /api/clan/{clanId}/deposit` | `{uuid,amount}` → **422** saldo do jogador |
| `POST /api/clan/{clanId}/withdraw` | `{uuid,amount}` → **422** banco insuficiente · 403 (só LEADER) |
| `POST /api/clan/{clanId}/level` | `{uuid}` → **422** banco insuficiente · **409** version · 403 |
| `PUT /api/clan/{clanId}/name` | `{actorUuid,name}` → **409** version · 403 |
| `DELETE /api/clan/{clanId}` | `?actor=` (dissolver) → 403 (só LEADER) |
| `GET /api/clan/leaderboard` | `?limit=10` → `[{rank,clanId,tag,name,score}]` |

O handler do `rankup-service` já mapeia **422** (`InsufficientFundsException`, Fase 8)
e **409** (`BackendException.statusCode()==409`, optimistic-lock); a Fase 17 reusa
sem novo mapeamento. Sem mudança no `ServiceTokenAuthFilter` (já guarda `/api/**`).

### 4.5 SDK `crystal-core`

- `http/ClanData` (`record`): `ClanData(String clanId, String tag, String name, int
  level, long bank, long score, String leaderUuid, List<ClanMember> members)`.
- `http/ClanMember` (`record`): `ClanMember(String uuid, String role)`.
- Métodos na `BackendHttpClient` (estilo economy, via `send`): `getClanOf`
  (allowNotFound→null), `getClan`, `createClan`, `inviteClan`, `joinClan`,
  `leaveClan`, `setClanRole`, `depositClan`, `withdrawClan`, `buyClanLevel`,
  `renameClan`, `disbandClan`, `clanLeaderboard`. Débitos propagam
  `InsufficientFundsException` (422); estrutura propaga 409 por `statusCode()`.
- `http/ClanClient`: facade fino, exposto por `crystal.clans()` (`new
  ClanClient(backend)` no `CrystalCore`) — segue economy/ranks/prestige/stats.
- `messaging/KafkaTopics`: `+ CLAN_CHAT`, `+ CLAN_UPDATED`.

### 4.6 Plugin `crystal-clan` (Paper, roda nos servidores de jogo, GUI-first)

Espelha `crystal-economy`/`crystal-arena` (`pom.xml` com shade do `crystal-core` +
paper-api; `CrystalClanPlugin` só boot+registro; `listener/` + `commands/` + `gui/`;
`plugin.yml` `api-version: '1.21'`). Registrar em `plugins/pom.xml`.

- `gui/ClanMenu` (GUI-first, espelha `BalanceMenu`): visão do clã (tag/nome/nível/
  banco/score/membros) e botões → submenus. Sem clã → botão **Criar clã** (abre
  entrada de tag/nome via anvil/chat prompt).
  - `gui/ClanMembersMenu`: lista membros + cargo; ações por cargo (promover/rebaixar/
    expulsar/transferir) conforme permissão do ator.
  - `gui/ClanBankMenu`: depositar/sacar (quantias via GUI), saldo do banco.
  - `gui/ClanLevelMenu`: nível atual, próximo nível (custo/maxMembers), comprar.
  - `gui/ClanInvitesMenu`: convites pendentes (do `clan-invite:{uuid}`), aceitar.
  - `gui/ClanRankingMenu`: top clãs (`crystal.clans().leaderboard(10)`), read-only.
  Todas abrem na main thread, HTTP off-thread, cliques cancelados.
- `commands/ClanCommand` (`/cla`): abre a `ClanMenu` (fallback: `/cla invite <nick>`,
  `/cla criar <tag> <nome>` como atalho admin/acessibilidade).
- `commands/ClanChatCommand` (`/c <msg>`): resolve o clã do emissor
  (`clan-of:{uuid}`); publica em `clan-chat` **off-thread**; se sem clã → aviso.
- `listener/ClanChatListener` (consumidor `clan-chat`): entrega a mensagem
  formatada (`§b[Clã] §f{sender}: {msg}`) só aos membros **online daquele clã**
  (compara `clan-of:{uuid}` do jogador local com o `clanId` do evento). Espelha o
  `crystal-chat` global.
- `listener/ClanUpdateListener` (consumidor `clan-updated`): no `kind=disband`
  notifica e limpa o clã dos membros online; nos demais `kind` invalida cache local/
  reabre GUI se aberta.
- **Tag no chat/TAB**: o `crystal-chat` passa a incluir `[TAG]` quando o emissor tem
  clã (lê `clan-of`+`clan:{}`); o servidor que escreve a entrada `tab:rankup`
  (Fase 10) adiciona o campo `clanTag`. (Divergência do master §5 — ver §10.)

### 4.7 Gateway + Compose

`api-gateway/application.yml` ganha a rota (mesmo formato das existentes):

```yaml
- id: rankup-clan
  uri: lb://rankup-service
  predicates:
    - Path=/api/clan/**
```

`docker-compose.yml`: **sem novo tipo de servidor** — o jar `crystal-clan` é montado
nos serviços de jogo já existentes (spawn/mina/arena/terrenos), ao lado de
`crystal-economy`. `create-topics.sh` += `clan-chat`, `clan-updated`. Config `clan`
(dado): seed via `PUT /api/config/clan` (curl), não arquivo de código.

## 5. Fluxo de dados

**Criar clã**
```
/cla → ClanMenu → "Criar" (tag,nome) → crystal.clans().createClan(uuid,tag,nome) off-thread
  tag em uso? → 409 "tag já existe";  jogador já em clã? → 409 "você já tem clã"
  senão → INSERT clans + membro LEADER → write-through clan:{}/clan-of:{}/leaderboard
        → clan-updated(member)
```

**Depositar (aditivo bank+score)**
```
ClanBankMenu → depositar(amount) → ClanService.deposit (off-thread):
  economy.debit(uuid, amount)                → 422 se sem saldo (aborta)
  repository.deposit(clanId, amount, floor(amount×perMoney))   (UPDATE atômico)
  write-through clan:{clanId} + leaderboardAdd(clanId, novoScore)
  clan-updated(bank/score)  → outras instâncias invalidam cache; ranking sobe no tick
```

**Comprar nível (débito condicional)**
```
ClanLevelMenu → comprar → ClanService.buyLevel (off-thread, LEADER):
  cost = config.clan.levels[level+1].cost
  repository.debitBank(clanId, cost)         → 422 se banco < cost
  level++ (optimistic-lock) + score += perLevel
  write-through + clan-updated(level/score)
```

**Chat de clã (cross-server)**
```
/c oi → clanId = clan-of:{uuid} (sem clã → aviso)
  publish clan-chat {clanId,senderUuid,senderName,"oi"}   (Kafka, off-thread)
    → todos crystal-clan consomem; cada um entrega "§b[Clã] sender: oi"
      só aos jogadores locais cujo clan-of == clanId
```

**Regra Kafka × Redis (master §5/§8):** `clan-chat`/`clan-updated` são **fatos** que
outras instâncias precisam **reagir** (entregar msg, invalidar cache, expulsar no
disband). O **ranking** e a **tag no TAB** são **estado quente lido no tick**
(`leaderboard:clan-score`, `clan-of`+`clan:{}`), nunca Kafka por jogador.

## 6. Concorrência e tratamento de erros (fail-open, padrão do projeto)

- **Depósito** (aditivo bank+score): UPDATE atômico, sem versão, sem 409;
  depósitos concorrentes somam corretamente. O `economy.debit` do jogador é o ponto
  de 422 (saldo insuficiente).
- **Saque / comprar nível** (débito condicional no banco): `WHERE bank>=:cost` → 0
  linhas = **422**; sem gasto duplo mesmo com dois LEADERs (só há um) ou cliques
  repetidos.
- **Entrar** (cap por nível): `count(membros) < maxMembers` dentro da transação de
  insert; excedente → **422** "clã cheio". `member_uuid UNIQUE` barra corrida de
  aceitar dois convites ao mesmo tempo (o 2º INSERT viola a unique → 409).
- **Estrutura** (promover/transferir/nível/renomear): optimistic-lock (`version`) →
  **409**; o GUI recarrega e reapresenta.
- **Convites**: TTL no Redis expira sozinho; aceitar convite inexistente/expirado →
  **422**. Redis fora → convite falha fechado (não deixa entrar sem convite válido),
  mas o resto do clã (leitura via Postgres) segue fail-open.
- **Redis fora** (cache/leaderboard): fail-open — metadados caem no Postgres;
  ranking degrada (lista vazia/desatualizada) sem derrubar o servidor.
- **HTTP fora** (plugin): `BackendException` off-thread, logado; a ação do jogador
  falha com aviso, o servidor nunca cai.
- **Kafka fora**: `EventPublisher` engole a falha (padrão do core) — a mutação no
  Postgres conclui; chat de clã daquela mensagem é perdido (janela mínima).
- **Config `clan` ausente/corrompida**: nível 1, `maxMembers` default, score 0; sem
  crash.

## 7. Propagação entre servidores

- **Metadados/banco/score/nível** mudam Postgres na hora; write-through em
  `clan:{clanId}` + `leaderboard:clan-score` (Redis compartilhado), então GUIs/holos
  em qualquer servidor vêem o novo estado no próximo tick, sem pub/sub por jogador.
  `clan-updated` invalida o cache local das outras instâncias.
- **Pertencimento** (`clan-of:{uuid}`) é write-through no join/leave/disband; chat
  global e TAB o lêem para pintar a tag em qualquer servidor.
- **Chat de clã** é instantâneo por `clan-chat` (Kafka), entregue só aos membros
  online do clã, onde quer que estejam.
- **Edição da config `clan`** (`PUT /api/config/clan`) propaga por `config-updated`
  (Kafka): `crystal-clan` e o `ClanService` recarregam níveis/score — hot-reload sem
  restart.

## 8. Testes

**Unitário backend** (`ClanServiceTest`, JUnit 5 + Mockito, mockando repositórios +
`economy` + `ConfigProvider`, no molde do `EconomyServiceTest` da Fase 8):
1. `deposit` chama `economy.debit` e `repository.deposit(clanId, amount,
   floor(amount×perMoney))` com o score derivado da config; propaga 422 do débito.
2. `withdraw` em banco insuficiente → 422 e **não** credita o jogador.
3. `join` em clã cheio (count == maxMembers do nível) → 422; jogador já em clã → 409.
4. `buyLevel` debita o custo do próximo nível, sobe `level`, soma `perLevel` ao score.
5. `disband` só pelo LEADER; remove do leaderboard.

**Manual (curl + em jogo, CLAUDE.md)** contra o gateway com `Authorization: Bearer
<BACKEND_SERVICE_TOKEN>`:
- **curl** (determinístico): criar clã; `POST /deposit` incrementa `bank` e `score`
  (config `perMoney`); `POST /withdraw` acima do banco → 422; `POST /level` cobra e
  sobe nível; `GET /leaderboard` ordena por score; `POST /members` acima do cap → 422.
- **em jogo** (montar `crystal-clan`+`crystal-economy` num spawn+mina, **recriar** os
  containers):
  1. Jogador A cria clã (`/cla`), convida B; B aceita; ambos veem membros no GUI.
  2. A e B depositam; o banco sobe na GUI dos dois (cross-server); `score` sobe e o
     clã aparece em `/cla` → Ranking.
  3. `/c oi` de A no spawn chega a B na mina com `[Clã]`; tag `[XYZ]` no chat global
     e no TAB.
  4. A compra nível → cap de membros sobe; convidar além do cap antigo agora entra.
  5. A dissolve o clã → B é notificado e removido; tag some do TAB/chat.
  6. **Hot-reload**: `PUT /api/config/clan` mudando `perMoney`/custos → próximo
     depósito/compra usa os novos valores **sem restart**.

## 9. Arquivos afetados (resumo)

**Backend `backend/rankup-service/`** (módulo já existe desde a Fase 8):
- **NOVO** `clan/{api,application,domain}/…` (`ClanController`, `ClanService`,
  `ClanEntity`, `ClanMemberEntity`, `ClanRepository`, `ClanMemberRepository`).
- **NOVA** migração `V<n>__rankup_clan.sql` (tabelas `clans`, `clan_members`).
- `shared/messaging/KafkaTopics` (+ `clan-chat`, `clan-updated`).
- `backend/api-gateway/src/main/resources/application.yml` (+ rota `clan`).

**SDK `plugins/crystal-core`:**
- **NOVO** `http/ClanData`, `http/ClanMember`, `http/ClanClient`;
  `http/BackendHttpClient` (+ métodos de clã); `CrystalCore` (+ `clans()`);
  `messaging/KafkaTopics` (+ `CLAN_CHAT`, `CLAN_UPDATED`).

**Plugins:**
- **NOVO** `plugins/crystal-clan/` (`pom.xml`, `plugin.yml`, `CrystalClanPlugin`,
  `commands/{ClanCommand,ClanChatCommand}`, `listener/{ClanChatListener,
  ClanUpdateListener}`, `gui/{ClanMenu,ClanMembersMenu,ClanBankMenu,ClanLevelMenu,
  ClanInvitesMenu,ClanRankingMenu}`).
- `plugins/pom.xml` (+ módulo `crystal-clan`).
- `plugins/crystal-chat` (+ `[TAG]` do clã no formato global) e o escritor de
  `tab:rankup` (+ campo `clanTag`) — Fase 10.

**Infra/Compose:**
- `infra/kafka/create-topics.sh` (+ `clan-chat`, `clan-updated`).
- `docker-compose.yml` (montar jar `crystal-clan` nos serviços de jogo; **sem** novo
  serviço/tipo).
- Config `clan` (dado, via `PUT /api/config/clan`): `levels`, `scoring` — seed via
  curl (§4.2), não arquivo de código.

## 10. Divergências do código atual (a reconciliar)

- **Depende das Fases 8 e 10 (ainda não construídas).** A Fase 17 assume: da **Fase
  8** o `rankup-service` + `economy` (débito condicional `debit`→422, `addMoney`
  aditivo, `EconomyClient`/`crystal.economy()`, mapeamento 422/409 no handler); da
  **Fase 10** o `tab:rankup` (TAB por modo) e o `crystal-chat` no formato RankUP, além
  do fleet de servidores de jogo onde o `crystal-clan` roda. **Só começa depois
  dessas mergeadas.**
- **Contexto `clan` novo (o master não o listou).** O master §2.2 enumera os
  contextos do `rankup_db` **sem** clã. A Fase 17 **adiciona** `clan` (tabelas
  `clans`/`clan_members`) — mesmo banco, contexto coexistindo, sem colisão de DDL
  (migração própria `V<n>__rankup_clan.sql`).
- **Plugin `crystal-clan` novo (9º plugin).** O master §2.1 enumera 7 plugins (8º é
  `crystal-arena`, Fase 15); clã é o **9º**. Roda nos servidores de jogo junto do
  `crystal-economy`; dobrar isso em `crystal-economy` (economia-only) ou
  `crystal-spawn` (spawn-only) violaria responsabilidade única — clã existe em
  spawn/mina/arena/terrenos.
- **Tag no TAB diverge do master §5.** O master decidiu "a TAB exibe **só o cargo**;
  o rank de jogo aparece no scoreboard e no chat". A Fase 17 **acrescenta a tag do
  clã** (`clanTag`) ao json `tab:rankup` e a exibe junto do cargo — extensão
  explícita, decidida com o usuário. O rank de jogo continua fora do TAB.
- **`clan-of:{uuid}` é chave Redis nova.** Não está no §8 do master; é write-through
  simples (string→clanId), fail-open, usada por chat/TAB para pintar a tag sem HTTP.
- **`crystal.clans()` é o 5º/6º facade por feature.** Nasce da infra de facade da
  Fase 8 (`economy()`); segue `ranks()`/`prestige()` (Fase 9) e `stats()` (Fase 15).
- **Ranking sem recompensa ao topo.** Decisão do usuário: entregar **só o ranking**
  (`leaderboard:clan-score` + GUI/holo), **sem** tag/cargo/prefixo/recompensa para o
  clã nº 1. Se uma fase futura quiser premiar o topo, entra aí (fora do escopo).
- **Convites efêmeros (sem tabela).** Diverge do padrão "tudo no Postgres": convites
  vivem só no Redis (`clan-invite:{uuid}`, TTL ~2min) — dado descartável, sem DDL nem
  reaper. Se uma fase futura exigir convites persistentes/auditáveis, promove-se a
  tabela `clan_invites`.
