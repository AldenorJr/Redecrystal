# Design — Fase 8: `rankup-service` + Economia (Money/Tokens)

Data: 2026-07-01
Branch base: `feat/login-autocomplete-e-troca-senha`

> Primeira fase do jogo **RankUP** sobre a rede RedeCrystal. Adiciona o **primeiro
> microsserviço novo** (`rankup-service` + banco `rankup_db`) e o **contexto
> `economy`** (Money + Tokens), com o SDK e um plugin de jogador. Arquitetura de
> alto nível em [`2026-07-01-rankup-arquitetura-design.md`](2026-07-01-rankup-arquitetura-design.md)
> — este spec detalha só o que a Fase 8 entrega (§10 do master). Texto de jogador
> em PT; identificadores/módulos/tópicos/chaves em inglês.

## 1. Problema

O RankUP precisa de uma **economia própria** — **Money** (progressão: ranks,
upgrades, terrenos, loja) e **Tokens** (cosméticos/boosts) — separada de
`profile.coins`, que continua sendo a moeda de rede/lobby (master §1.1). Não há
onde guardar nem como mutar esses saldos: nem tabela, nem endpoint, nem método no
SDK, nem comando de jogador.

Além disso, essa economia é o **primeiro contexto de um serviço novo**. Empilhá-la
no `core-service` contraria a decisão #2 do master (`rankup_db` dedicado). Então a
Fase 8 também **põe o `rankup-service` no ar**: um segundo Spring Boot registrado
no Eureka, com o gateway roteando `/api/economy/**` para `lb://rankup-service`.

Duas mutações de saldo têm **corridas** distintas que o `core-service` ainda não
trata (só faz read-modify-write, sem 422): dar dinheiro por bloco/kill (aditivo) é
inofensivo, mas **gastar** (comprar/`/pagar`) não pode debitar duas vezes o mesmo
saldo. Precisamos dos três write-paths do master §4.2 desde já.

## 2. Objetivos e não-objetivos

**Objetivos**
- Subir o `backend/rankup-service` (Spring Boot 3.3, Java 21, Eureka client,
  Flyway, Postgres `rankup_db`, Redis, Kafka), espelhando o `core-service`.
- Contexto `economy` (`api/application/domain`): tabela `player_economy`, os
  endpoints do master §6.1, os **três write-paths** do §4.2 (aditivo, débito
  condicional → 422, set absoluto com optimistic-lock → 409).
- Write-through Redis (`economy:{uuid}` + `leaderboard:money`) e publicação Kafka
  `money-updated`/`token-updated`.
- Infra: `rankup_db`, tópicos novos, serviço `rankup-service` no compose, rota no
  gateway.
- SDK `crystal-core`: `EconomyData`, `EconomyClient`, `crystal.economy()`,
  `InsufficientFundsException` (422), constantes de tópico/chave.
- Plugin `crystal-economy` (GUI-first): `/saldo` (GUI de saldo), `/pagar`
  (transferência, trata 422) e `/eco` admin (give/set).

**Não-objetivos** (ficam para fases seguintes do master §10)
- Ranks, prestígio, minas, terrenos, plantações, arena, stats, homes.
- Produtores reais de Money (mineração/colheita/kill) — Fases 11/14/15. A Fase 8
  entrega só exibição de saldo + transferência + give/set admin.
- HUD/scoreboard/TAB de economia e consumo de `money-updated` no jogo — Fase 10.
- Servidores de jogo novos (spawn/mina/…): não existem ainda; `crystal-economy` é
  montado num lobby só para verificação (ver §8).
- Leaderboards de tokens/prestígio/blocos (o master §8 os prevê; a Fase 8 grava só
  `leaderboard:money`).

## 3. Decisões de design (já acordadas no master)

| Tema | Decisão |
|------|---------|
| Serviço | **`rankup-service` novo** + `rankup_db` (master #2), não empilhar no core. |
| Porta | `SERVER_PORT=8082` (core é 8081, gateway 8080). |
| Roteamento | Gateway `/api/economy/**` → `lb://rankup-service` via Eureka; auth reaproveita o `ServiceTokenAuthFilter` (guarda todo `/api/**`). |
| Money/Tokens | Contexto `economy` novo, **separado** de `profile.coins` (master #1). |
| Aditivo | `UPDATE … SET money=money+:delta` atômico, **sem versão, sem 409** (bloco/kill/colheita). |
| Débito | `UPDATE … SET money=money-:cost, version=version+1 WHERE money>=:cost` → 0 linhas = **422** (sem gasto duplo). |
| Set absoluto | optimistic-lock clássico (apresenta `version`) → **409**, como `InventoryService.save`. |
| Tokens | Atendidos só em `/api/economy/**` (master #5, sem alias `/api/token/**`). |
| Facade SDK | `EconomyClient` em `crystal-core`, exposto por `crystal.economy()` (master #6) — **primeiro** facade por feature (hoje só existe `crystal.backend()`). |
| Cache | Write-through `economy:{uuid}` (hash) + `leaderboard:money` (sorted set); **fail-open** (Redis fora → cai no Postgres). |
| Verificação | `crystal-economy` montado num **lobby existente** p/ exercer `/saldo`+`/pagar`; economia validada também por **curl** no gateway (§8). |

## 4. Arquitetura

### 4.1 Novo módulo `backend/rankup-service` (espelha `core-service`)

Mesma receita do `core-service`: `pom.xml` herdando `backend-parent`, mesmas
dependências (eureka-client, web, validation, data-redis, spring-kafka, data-jpa,
flyway-core + flyway-database-postgresql, postgresql runtime, actuator +
micrometer-prometheus, spring-boot-starter-test). Classe main:

```java
@EnableDiscoveryClient
@SpringBootApplication
public class RankUpApplication { … }        // com.redecrystal.rankup
```

`application.yml` = cópia do `core-service` trocando:
- `spring.application.name: rankup-service`
- datasource `…/${RANKUP_DB:rankup_db}`
- `server.port: ${SERVER_PORT:8082}`

Eureka/flyway/redis/kafka/management idênticos. Flyway `classpath:db/migration`.

**Build multi-módulo.** O `backend/Dockerfile` seleciona o módulo por `--build-arg
MODULE=<…>` e copia **cada** pom/src explicitamente. A Fase 8 adiciona
`rankup-service` a: (a) `backend/pom.xml` `<modules>`, (b) as linhas `COPY
rankup-service/pom.xml …` e `COPY rankup-service/src …` do Dockerfile.

### 4.2 `shared/` do `rankup-service` (cópia por-serviço)

Cada serviço tem o seu (um banco/serviço; sem lib compartilhada). Espelham
`core-service`:
- `shared/messaging/`: `EventEnvelope` (record versionado, igual ao core),
  `EventPublisher` (`sourceServerId="backend"`, key = uuid), `KafkaTopics` com
  `MONEY_UPDATED="money-updated"`, `TOKEN_UPDATED="token-updated"`.
- `shared/web/`: `ApiExceptionHandler` (+ `NotFoundException` 404,
  `ConflictException` 409, `InsufficientFundsException` **422**),
  espelhando o handler do core e **adicionando o mapeamento 422** (que o core não
  tem).

### 4.3 Contexto `economy` (`api/application/domain`)

**`domain/EconomyEntity`** (`@Table("player_economy")`): `playerUuid` (PK),
`money`, `tokens` (long), `version` (int), `updatedAt`. Construtor de criação
zera tudo. Método `setBalance(money, tokens)` para o set absoluto (bump de version
+ `updatedAt`), no estilo do `InventoryEntity.update`.

**`domain/EconomyRepository`** (`JpaRepository<EconomyEntity, UUID>`) — introduz o
**primeiro `@Modifying @Query`** do backend (o core faz read-modify-write; aqui a
atomicidade é o ponto):

```java
@Modifying(clearAutomatically = true)
@Query("UPDATE EconomyEntity e SET e.money = e.money + :delta, e.updatedAt = CURRENT_TIMESTAMP WHERE e.playerUuid = :uuid")
int addMoney(UUID uuid, long delta);

// idem addTokens

@Modifying(clearAutomatically = true)
@Query("UPDATE EconomyEntity e SET e.money = e.money - :cost, e.version = e.version + 1, e.updatedAt = CURRENT_TIMESTAMP WHERE e.playerUuid = :uuid AND e.money >= :cost")
int debit(UUID uuid, long cost);   // 0 linhas = saldo insuficiente
```

`clearAutomatically` garante que o `findById` seguinte (para cache/evento) lê o
valor já mutado.

**`application/EconomyService`** (Redis via `StringRedisTemplate`, como
`ProfileService`/`InventoryService`):

| Método | Comportamento |
|--------|---------------|
| `get(uuid)` | `findById` → `NotFoundException` (404). |
| `ensure(uuid)` | cria zerado se ausente; `cache`; retorna. |
| `addMoney(uuid, delta, source)` | `ensure` → `repository.addMoney` → re-`findById` → `cache` + `money-updated`. |
| `addTokens(uuid, delta, source)` | idem → `token-updated`. |
| `debit(uuid, cost, reason)` | `ensure`; `repository.debit`==0 → `InsufficientFundsException` (**422**); senão re-fetch + `cache` + `money-updated` (delta negativo). |
| `transfer(from, to, amount)` | `@Transactional`; `ensure` ambos; `debit(from)`==0 → **422** (rollback); `addMoney(to)`; cache + `money-updated` dos dois. |
| `set(uuid, money, tokens, expectedVersion)` | load (0 se ausente); `version != expected` → `ConflictException` (**409**); `setBalance` + save + cache + evento. |

`cache(e)` (fail-open, `try/catch` como o `ProfileService.cache`): `HSET
economy:{uuid} money/tokens/version` + `EXPIRE 10min`; `ZADD leaderboard:money
<money> <uuid>`. Falha de Redis → só `log.warn`, nunca derruba a operação.

**`api/EconomyController`** (`@RequestMapping("/api/economy")`, controller fino
como `ProfileController`; DTOs `record` aninhados):

| Método/Path | Body → Resposta |
|---|---|
| `GET /{uuid}` | → `EconomyResponse{uuid,money,tokens,version}` (404 se ausente) |
| `PUT /{uuid}` | ensure → `EconomyResponse` |
| `POST /{uuid}/money` | `{delta,source}` → aditivo |
| `POST /{uuid}/tokens` | `{delta,source}` → aditivo |
| `POST /{uuid}/debit` | `{cost,reason}` → 422 se insuficiente |
| `POST /transfer` | `{from,to,amount}` → 422 se insuficiente |
| `PUT /{uuid}/set` | `{money,tokens,version}` → 409 se `version` velha |

### 4.4 Gateway

`api-gateway/application.yml` ganha a rota (mesmo formato das existentes):

```yaml
- id: rankup-economy
  uri: lb://rankup-service
  predicates:
    - Path=/api/economy/**
```

Sem mudança no `ServiceTokenAuthFilter`: ele já exige `Bearer <service-token>` em
todo `/api/**` antes de rotear.

### 4.5 SDK `crystal-core`

- `messaging/KafkaTopics`: `MONEY_UPDATED`/`TOKEN_UPDATED` (+ na lista `ALL`, para
  consumidores broadcast das próximas fases).
- `http/EconomyData` (`record`): `EconomyData(String uuid, long money, long tokens, int version)`.
- `http/InsufficientFundsException extends BackendHttpClient.BackendException`
  (statusCode 422). O `send(...)` passa a lançá-la quando `sc == 422` (só a
  economia devolve 422; como estende `BackendException`, catches existentes não
  quebram). **Não existe `ConflictException` no SDK hoje** — 409 continua exposto
  via `BackendException.statusCode()==409` (o master §6.4 assume que existe; ver §9).
- Métodos HTTP na `BackendHttpClient` (estilo profile/inventory, usando `send`
  privado): `getEconomy` (allowNotFound → null), `ensureEconomy`, `addMoney`,
  `addTokens`, `debitMoney`, `transfer`, `setEconomy`.
- `http/EconomyClient`: **facade fino** construído com a `BackendHttpClient`,
  delegando e expondo a chave de cache (`ECONOMY_KEY_PREFIX="economy:"`,
  `MONEY_LEADERBOARD="money"`). É o **primeiro** client por feature; segue a
  decisão #6 do master. `CrystalCore` ganha `private final EconomyClient economy`
  (`new EconomyClient(backend)`) e o accessor `economy()`.

### 4.6 Plugin `crystal-economy` (Paper, roda no servidor de jogo)

Espelha `crystal-profile` (`pom.xml` com shade do `crystal-core` + paper-api;
`Crystal…Plugin` só boot+registro; `commands/` + `gui/`). GUI-first (README):

- `gui/BalanceMenu` (espelha `ParkourTopMenu`): busca `crystal.economy().get(uuid)`
  **off-thread**, monta o inventário (item Money + item Tokens) e **abre na main
  thread**; cliques cancelados. Saldo ausente → exibe 0/0.
- `commands/BalanceCommand` (`/saldo`, atalho que abre a `BalanceMenu`).
- `commands/PayCommand` (`/pagar <jogador> <valor>`): valida valor > 0, resolve o
  UUID do alvo **online**, chama `crystal.economy().transfer(...)` off-thread;
  `InsufficientFundsException` → "§cVocê não tem saldo suficiente."; sucesso →
  mensagem a pagador e recebedor.
- `commands/EconomyAdminCommand` (`/eco`, `crystal.economy.admin` default op):
  `give <jogador> <valor>` (aditivo) e `set <jogador> <valor>` (absoluto).

Registro: módulo em `plugins/pom.xml`; `plugin.yml` com `saldo`/`pagar`/`eco` e a
permissão admin. Montado em `lobby-01` **só para a Fase 8** (a partir da Fase 10
vai em spawn/mina/arena/terrenos).

## 5. Fluxo de dados

**Dar Money (aditivo) / gastar (débito)**
```
POST /api/economy/{uuid}/money {delta,source}   (ou /debit {cost,reason})
  → EconomyService.ensure + addMoney|debit  (UPDATE atômico no Postgres)
  → re-findById (valor já mutado)
  → cache write-through: HSET economy:{uuid} + ZADD leaderboard:money
  → EventPublisher money-updated {uuid,money,delta,source}  (key=uuid)
  → resposta 200 EconomyResponse   |   débito sem saldo → 422 (nada muda)
```

**`/pagar` (transfer, atômico)**
```
/pagar <alvo> <valor> → PayCommand (off-thread) → POST /api/economy/transfer {from,to,amount}
  → @Transactional: debit(from)  → 0 linhas = 422 (rollback, nada transferido)
                     addMoney(to)
  → cache + money-updated dos dois uuids → 200
```

**Regra Kafka × Redis (master §5/§8):** `money-updated`/`token-updated` são
**fatos** (Fase 10+ reage: HUD/broadcast/leaderboard). O estado quente lido em
loop (saldo do HUD/scoreboard) virá do **Redis** (`economy:{uuid}`), não do Kafka
por jogador. Toda escrita é **write-through** (Postgres → Redis → Kafka).

## 6. Concorrência (os três write-paths — master §4.2)

- **Aditivo** (`money+:delta`): atômico no banco; sem versão, sem 409. Idempotência
  não é exigida (produtores futuros dão flush em batelada).
- **Débito condicional** (`money-:cost WHERE money>=:cost`): a condição no `WHERE`
  torna o gasto **livre de corrida** — dois débitos concorrentes não passam do
  saldo; 0 linhas afetadas ⇒ **422**, sem exceção de lock.
- **Set absoluto** (admin/`/eco set`): optimistic-lock clássico — apresenta
  `version`; divergiu ⇒ **409** (idêntico ao `InventoryService.save`).

## 7. Tratamento de erros (fail-open, padrão do projeto)

- **404** (`GET` sem linha): SDK `getEconomy` usa `allowNotFound` → `null`; o
  plugin trata como saldo 0 (não derruba).
- **422** (débito/transfer sem saldo): backend `InsufficientFundsException`; SDK
  `InsufficientFundsException`; plugin mostra mensagem PT.
- **409** (set com version velha): backend `ConflictException`; SDK
  `BackendException.statusCode()==409`; admin recebe "tente novamente".
- **Redis fora**: cache é `try/catch` → só `log.warn`; leitura de saldo cai no
  Postgres via HTTP. Nada quebra.
- **Kafka fora**: `EventPublisher` engole a falha (padrão do core) — a operação de
  negócio conclui.
- **HTTP fora** (plugin): `BackendException` transporte; mensagem "indisponível",
  nunca trava a main thread (chamada é async).

## 8. Testes

**Unitário backend** (`EconomyServiceTest`, JUnit 5 + Mockito, mockando
`EconomyRepository` + `StringRedisTemplate` + `EventPublisher`, no molde do
`AuthServiceChangePasswordTest`/`InventoryService`):
1. `addMoney` chama `repository.addMoney(delta)` e devolve o saldo re-lido.
2. `debit` com saldo → `repository.debit` retorna 1, sucesso.
3. `debit` sem saldo → `repository.debit` retorna 0 → `InsufficientFundsException` (422).
4. `transfer` sem saldo na origem → 422 e **nada** creditado no destino.
5. `set` com `version` velha → `ConflictException` (409).

**Manual em jogo + curl** (CLAUDE.md; sem servidor de jogo RankUP ainda):
- **Backend por curl** (determinístico) contra o gateway com `Authorization:
  Bearer <BACKEND_SERVICE_TOKEN>`: `PUT /api/economy/{uuid}` cria; `POST
  …/money {delta,source}` dá Money e `GET` confirma persistência; `POST …/debit`
  além do saldo → 422; `POST /transfer` além do saldo → 422; conferir
  `money-updated` no Kafka UI.
- **Plugin no lobby**: montar `crystal-economy` no `lobby-01`, **recriar** o
  container; `/saldo` abre a GUI; `/eco give <você> 1000` e `/saldo` mostra 1000;
  `/pagar <outro> 100` transfere; `/pagar <outro> 999999` → "saldo insuficiente".

## 9. Divergências do master (a reconciliar)

- **Sem `@Modifying`/`@Version` no repo hoje**: o core faz read-modify-write
  (`ProfileService`). O master §4.2 exige atomicidade; a Fase 8 introduz o
  primeiro `@Modifying @Query` (aditivo/débito) e mantém o optimistic-lock
  "manual" (compara `version`, como o `InventoryService`, **não** `@Version` JPA).
- **Sem 422 no backend**: o `ApiExceptionHandler` do core mapeia só 404/409/401/400.
  A Fase 8 adiciona o mapeamento **422** no handler do `rankup-service`.
- **SDK não tem `ConflictException`** (o master §6.4 assume que sim): hoje 409 sai
  por `BackendException.statusCode()`. A Fase 8 adiciona só a
  `InsufficientFundsException` (422); 409 continua via `statusCode()`.
- **Sem facade por feature hoje** (só `crystal.backend()`): `EconomyClient` +
  `crystal.economy()` são os primeiros, delegando à `BackendHttpClient` para
  reusar retry/auth.
- **`make plugins` não enumera módulos** (`mvn -pl plugins/crystal-core -am
  package`): o downstream é construído por-plugin (`mvn -pl
  plugins/crystal-economy -am package`); registrar em `plugins/pom.xml` basta, o
  Makefile não precisa de linha nova.

## 10. Arquivos afetados (resumo)

**NOVO** `backend/rankup-service/` — `pom.xml`; `RankUpApplication`;
`shared/{messaging,web}/…`; `economy/{api,application,domain}/…`;
`application.yml`; `db/migration/V1__rankup_economy.sql`; teste `EconomyServiceTest`.
- `backend/pom.xml` (+ módulo), `backend/Dockerfile` (+ COPY pom/src).
- `infra/postgres/init-databases.sql` (+ `CREATE DATABASE rankup_db;`),
  `infra/kafka/create-topics.sh` (+ `money-updated`, `token-updated`).
- `docker-compose.yml` (+ serviço `rankup-service`; `api-gateway.depends_on`;
  volume `crystal-economy` no `lobby-01`).
- `backend/api-gateway/src/main/resources/application.yml` (+ rota).
- `plugins/crystal-core` — `messaging/KafkaTopics`, `http/EconomyData`,
  `http/EconomyClient`, `http/InsufficientFundsException`,
  `http/BackendHttpClient` (métodos + branch 422), `CrystalCore` (`economy()`).
- **NOVO** `plugins/crystal-economy/` (`pom.xml`, `plugin.yml`,
  `CrystalEconomyPlugin`, `gui/BalanceMenu`, `commands/{BalanceCommand,PayCommand,
  EconomyAdminCommand}`); `plugins/pom.xml` (+ módulo).
