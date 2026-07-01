# Fase 8 — `rankup-service` + Economia (Money/Tokens) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pôr o **primeiro microsserviço do RankUP** (`rankup-service` + `rankup_db`)
no ar e entregar o contexto **`economy`** (Money/Tokens) fim-a-fim: backend,
infra, SDK e um plugin GUI-first (`/saldo`, `/pagar`, `/eco`).

**Architecture:** `backend/rankup-service` espelha o `core-service` (Spring Boot
3.3, Eureka client, Flyway, Postgres, Redis, Kafka). O contexto `economy` expõe os
endpoints do master §6.1 com os **três write-paths** do §4.2 — aditivo
(`money+:delta`), débito condicional (`money-:cost WHERE money>=:cost` → **422**) e
set absoluto (optimistic-lock → **409**). Escrita é **write-through**
(Postgres → Redis `economy:{uuid}`+`leaderboard:money` → Kafka
`money-updated`/`token-updated`). O gateway roteia `/api/economy/**` para
`lb://rankup-service`. O SDK ganha `EconomyClient`/`crystal.economy()` +
`InsufficientFundsException`. O plugin `crystal-economy` roda no servidor de jogo
(na Fase 8, montado num lobby p/ verificação).

Ver o design: [`../specs/2026-07-01-fase8-rankup-service-economia-design.md`](../specs/2026-07-01-fase8-rankup-service-economia-design.md)
e o master [`../specs/2026-07-01-rankup-arquitetura-design.md`](../specs/2026-07-01-rankup-arquitetura-design.md).

**Tech Stack:** Java 21, Spring Boot 3.3 (rankup-service), Spring Cloud Gateway +
Eureka, PostgreSQL + JPA + Flyway, Redis (StringRedisTemplate / Lettuce no SDK),
Kafka (KRaft), Paper 1.21 (Bukkit), Maven, JUnit 5 + Mockito, Docker Compose.

## Global Constraints

- **Idioma:** código e comentários em **inglês**; texto de jogador e docs em **PT**.
- **Nunca bloquear a main thread do Bukkit:** HTTP ao backend em
  `runTaskAsynchronously`; volte com `runTask` antes de tocar a API do jogo (abrir
  inventário). Mensagens ao jogador podem sair da thread async.
- Plugins falam **só com o API Gateway**, com o service-token (bearer) já embutido
  no `BackendHttpClient`. Nunca com serviço/DB direto.
- **Um banco por serviço**: `rankup_db` é do `rankup-service`; nada de tocar
  `core_db`.
- **Escreva código que se pareça com o vizinho:** controller fino como
  `ProfileController`; serviço com Redis como `ProfileService`/`InventoryService`;
  optimistic-lock como `InventoryService.save`; GUI como `ParkourTopMenu`; plugin
  como `crystal-profile`. DTO é `record`, serviço/utilitário `final`.
- **Constantes, não literais mágicos** (portas, chaves Redis, nomes de tópico,
  permissões, limites).
- **Erros de backend têm fallback**; **limpe o que cria** (o SDK/plugin fecha o
  `CrystalCore` no `onDisable`).
- **Feature de jogador começa GUI-first** (`/saldo` abre uma GUI; o comando é atalho).

---

## Task 1: Infra — `rankup_db` + tópicos Kafka

**Files:**
- Modify: `infra/postgres/init-databases.sql`
- Modify: `infra/kafka/create-topics.sh`

**Interfaces:**
- Produces: banco `rankup_db` provisionado num volume Postgres novo; tópicos
  `money-updated` e `token-updated` criados pelo `kafka-init`.
- Consumes: nada.

Sem teste automatizado (scripts de provisão) — validado no build da Task 7.

- [ ] **Step 1: Criar o banco `rankup_db`**

Em `infra/postgres/init-databases.sql`, após `CREATE DATABASE luckperms_db;`
(antes do bloco comentado "Future per-service databases"), adicionar:

```sql
-- RankUP game service (economy, and later rank/prestige/plot/…): its own database.
CREATE DATABASE rankup_db;
```

- [ ] **Step 2: Declarar os tópicos do RankUP**

Em `infra/kafka/create-topics.sh`, acrescentar ao array `TOPICS` (após
`maintenance-disabled`):

```bash
  money-updated
  token-updated
```

- [ ] **Step 3: Commit**

```bash
git add infra/postgres/init-databases.sql infra/kafka/create-topics.sh
git commit -m "feat(infra): rankup_db + tópicos money/token-updated (Fase 8)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Scaffold do módulo `backend/rankup-service`

**Files:**
- Create: `backend/rankup-service/pom.xml`
- Create: `backend/rankup-service/src/main/java/com/redecrystal/rankup/RankUpApplication.java`
- Create: `backend/rankup-service/src/main/java/com/redecrystal/rankup/shared/messaging/EventEnvelope.java`
- Create: `backend/rankup-service/src/main/java/com/redecrystal/rankup/shared/messaging/EventPublisher.java`
- Create: `backend/rankup-service/src/main/java/com/redecrystal/rankup/shared/messaging/KafkaTopics.java`
- Create: `backend/rankup-service/src/main/java/com/redecrystal/rankup/shared/web/ApiExceptionHandler.java`
- Create: `backend/rankup-service/src/main/java/com/redecrystal/rankup/shared/web/NotFoundException.java`
- Create: `backend/rankup-service/src/main/java/com/redecrystal/rankup/shared/web/ConflictException.java`
- Create: `backend/rankup-service/src/main/java/com/redecrystal/rankup/shared/web/InsufficientFundsException.java`
- Create: `backend/rankup-service/src/main/resources/application.yml`
- Modify: `backend/pom.xml` (registrar o módulo)
- Modify: `backend/Dockerfile` (copiar pom + src do novo módulo)

**Interfaces:**
- Produces: um Spring Boot buildável e vazio (sem contexto ainda) que registra no
  Eureka como `rankup-service` na porta 8082; `shared/` pronto para o contexto.
- Consumes: `backend-parent` (BOM Spring Cloud/Boot); Dockerfile `MODULE`.

Sem teste (scaffold) — compilação na Task 2 Step 8.

- [ ] **Step 1: `pom.xml`** — cópia fiel do `backend/core-service/pom.xml`,
  trocando só `<artifactId>rankup-service</artifactId>`, `<name>`/`<description>`
  para RankUP. **Mesmas dependências** (eureka-client, web, validation,
  data-redis, spring-security-crypto pode ser omitido — não há auth aqui,
  **remover**; manter kafka, data-jpa, flyway-core + flyway-database-postgresql,
  postgresql runtime, actuator + micrometer-registry-prometheus,
  spring-boot-starter-test) e o `spring-boot-maven-plugin`.

- [ ] **Step 2: `RankUpApplication.java`**

```java
package com.redecrystal.rankup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Entry point for the RankUP service (economy — and later rank/prestige/plot/…).
 * Bounded contexts live as packages under {@code com.redecrystal.rankup} following
 * Clean Architecture / DDD. Reachable only through the API Gateway.
 */
@EnableDiscoveryClient
@SpringBootApplication
public class RankUpApplication {

    public static void main(String[] args) {
        SpringApplication.run(RankUpApplication.class, args);
    }
}
```

- [ ] **Step 3: `shared/messaging/`** — copiar `EventEnvelope` e `EventPublisher`
  do `core-service` (mesmo conteúdo, trocando o `package` para
  `com.redecrystal.rankup.shared.messaging`). `KafkaTopics`:

```java
package com.redecrystal.rankup.shared.messaging;

/** Canonical Kafka topic names for the RankUP service. Mirror of create-topics.sh. */
public final class KafkaTopics {

    public static final String MONEY_UPDATED = "money-updated";
    public static final String TOKEN_UPDATED = "token-updated";

    private KafkaTopics() {}
}
```

- [ ] **Step 4: `shared/web/`** — copiar `ApiExceptionHandler`, `NotFoundException`,
  `ConflictException` do `core-service` (trocar o `package`). Criar
  `InsufficientFundsException` e **adicionar o mapeamento 422** no handler.

```java
package com.redecrystal.rankup.shared.web;

/** Thrown when a conditional debit/transfer lacks funds; mapped to HTTP 422. */
public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(String message) {
        super(message);
    }
}
```

No `ApiExceptionHandler`, junto aos outros `@ExceptionHandler`:

```java
    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientFunds(InsufficientFundsException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }
```

- [ ] **Step 5: `application.yml`** — copiar o do `core-service`, trocando:
  `spring.application.name: rankup-service`; datasource URL para
  `…/${RANKUP_DB:rankup_db}`; `server.port: ${SERVER_PORT:8082}`. Remover o bloco
  `redecrystal.security.jwt-*` (não há auth/JWT aqui). Manter jpa (`ddl-auto:
  validate`), flyway, redis, kafka, eureka e management idênticos.

- [ ] **Step 6: Registrar em `backend/pom.xml`** — adicionar ao `<modules>`, após
  `core-service`:

```xml
        <module>rankup-service</module>
```

- [ ] **Step 7: Ensinar o `Dockerfile` a construir o módulo** — em
  `backend/Dockerfile`, adicionar as linhas do novo módulo junto às dos outros
  (mantendo o cache em camadas):

Após `COPY core-service/pom.xml core-service/pom.xml`:
```dockerfile
COPY rankup-service/pom.xml rankup-service/pom.xml
```
Após `COPY core-service/src core-service/src`:
```dockerfile
COPY rankup-service/src rankup-service/src
```

- [ ] **Step 8: Compilar o módulo isolado**

Run: `mvn -pl backend/rankup-service -am -q package`
Expected: `BUILD SUCCESS`; jar em `backend/rankup-service/target/rankup-service-0.1.0-SNAPSHOT.jar`. (Sem testes ainda; o contexto vem na Task 3.)

- [ ] **Step 9: Commit**

```bash
git add backend/rankup-service backend/pom.xml backend/Dockerfile
git commit -m "feat(rankup): scaffold do rankup-service (Spring Boot, Eureka, 8082)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Contexto `economy` (TDD)

**Files:**
- Test: `backend/rankup-service/src/test/java/com/redecrystal/rankup/economy/application/EconomyServiceTest.java`
- Create: `backend/rankup-service/src/main/java/com/redecrystal/rankup/economy/domain/EconomyEntity.java`
- Create: `backend/rankup-service/src/main/java/com/redecrystal/rankup/economy/domain/EconomyRepository.java`
- Create: `backend/rankup-service/src/main/java/com/redecrystal/rankup/economy/application/EconomyService.java`
- Create: `backend/rankup-service/src/main/java/com/redecrystal/rankup/economy/api/EconomyController.java`
- Create: `backend/rankup-service/src/main/resources/db/migration/V1__rankup_economy.sql`

**Interfaces:**
- Produces: `EconomyService` com `get/ensure/addMoney/addTokens/debit/transfer/set`;
  endpoints do master §6.1; `player_economy` via Flyway.
- Consumes: `shared/messaging/EventPublisher`+`KafkaTopics`,
  `shared/web/{NotFoundException,ConflictException,InsufficientFundsException}`
  (Task 2); `StringRedisTemplate`.

TDD: o serviço tem lógica pura de concorrência (422/409) — escreva o teste primeiro
(mockando `EconomyRepository`, `StringRedisTemplate`, `EventPublisher`), no molde
do `AuthServiceChangePasswordTest`.

- [ ] **Step 1: Escrever o teste que falha** — `EconomyServiceTest`:

```java
package com.redecrystal.rankup.economy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.redecrystal.rankup.economy.domain.EconomyEntity;
import com.redecrystal.rankup.economy.domain.EconomyRepository;
import com.redecrystal.rankup.shared.messaging.EventPublisher;
import com.redecrystal.rankup.shared.web.ConflictException;
import com.redecrystal.rankup.shared.web.InsufficientFundsException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

class EconomyServiceTest {

    private EconomyRepository repository;
    private EventPublisher events;
    private EconomyService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(EconomyRepository.class);
        events = mock(EventPublisher.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        // Redis is fail-open in the service; stub the op accessors so cache() no-ops.
        when(redis.opsForHash()).thenReturn(mock(HashOperations.class));
        when(redis.opsForZSet()).thenReturn(mock(ZSetOperations.class));
        service = new EconomyService(repository, redis, events);
        when(repository.save(any(EconomyEntity.class))).thenAnswer(i -> i.getArgument(0));
    }

    private EconomyEntity row(UUID uuid, long money, long tokens, int version) {
        return new EconomyEntity(uuid, money, tokens, version);
    }

    @Test
    void addMoneyAppliesAdditiveDelta() {
        UUID uuid = UUID.randomUUID();
        when(repository.findById(uuid))
                .thenReturn(Optional.of(row(uuid, 100, 0, 0)))  // ensure()
                .thenReturn(Optional.of(row(uuid, 150, 0, 0))); // re-read after update
        when(repository.addMoney(uuid, 50)).thenReturn(1);

        assertEquals(150, service.addMoney(uuid, 50, "test").money());
        verify(repository).addMoney(uuid, 50);
    }

    @Test
    void debitSucceedsWhenFundsSuffice() {
        UUID uuid = UUID.randomUUID();
        when(repository.findById(uuid))
                .thenReturn(Optional.of(row(uuid, 100, 0, 0)))
                .thenReturn(Optional.of(row(uuid, 40, 0, 1)));
        when(repository.debit(uuid, 60)).thenReturn(1);

        assertEquals(40, service.debit(uuid, 60, "buy").money());
    }

    @Test
    void debitThrows422WhenInsufficient() {
        UUID uuid = UUID.randomUUID();
        when(repository.findById(uuid)).thenReturn(Optional.of(row(uuid, 10, 0, 0)));
        when(repository.debit(uuid, 60)).thenReturn(0);

        assertThrows(InsufficientFundsException.class, () -> service.debit(uuid, 60, "buy"));
    }

    @Test
    void transferIsAtomicAndCreditsNothingOnInsufficient() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        when(repository.findById(any())).thenReturn(Optional.of(row(from, 10, 0, 0)));
        when(repository.debit(eq(from), anyLong())).thenReturn(0);

        assertThrows(InsufficientFundsException.class, () -> service.transfer(from, to, 100));
        verify(repository, never()).addMoney(eq(to), anyLong());
    }

    @Test
    void setThrows409OnStaleVersion() {
        UUID uuid = UUID.randomUUID();
        when(repository.findById(uuid)).thenReturn(Optional.of(row(uuid, 100, 0, 5)));

        assertThrows(ConflictException.class, () -> service.set(uuid, 999, 0, 3));
        verify(repository, never()).save(any());
    }
}
```

> Nota: `EconomyEntity` precisa de um construtor `(UUID, money, tokens, version)`
> para o teste (além do de criação zerada). Mantê-lo package-visible/públic o
> suficiente para o teste no mesmo `groupId`.

- [ ] **Step 2: Rodar o teste e confirmar que falha**

Run: `mvn -pl backend/rankup-service -am -q test -Dtest=EconomyServiceTest`
Expected: FALHA de compilação — `EconomyEntity/Repository/Service` ainda não existem.

- [ ] **Step 3: `V1__rankup_economy.sql`** (mesmo estilo do `V4__player_inventories`):

```sql
-- Player economy for RankUP: Money (progression) + Tokens (cosmetics), keyed by
-- UUID. `version` drives the optimistic lock on absolute/admin sets; additive
-- deltas and conditional debits are atomic SQL (no lock). Redis economy:{uuid} is
-- the write-through hot cache; leaderboard:money is the sorted-set ranking.
CREATE TABLE player_economy (
    player_uuid   UUID     PRIMARY KEY,
    money         BIGINT   NOT NULL DEFAULT 0,
    tokens        BIGINT   NOT NULL DEFAULT 0,
    version       INTEGER  NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- [ ] **Step 4: `EconomyEntity`** (espelha `ProfileEntity`/`InventoryEntity`):
  campos `playerUuid` (`@Id`), `money`, `tokens` (`long`), `version` (`int`),
  `updatedAt`; construtor protegido p/ JPA; construtor de criação `(UUID)` zera
  tudo; construtor de teste `(UUID, long money, long tokens, int version)`; método
  `setBalance(long money, long tokens)` que atribui e `version++`+`updatedAt=now()`;
  getters.

- [ ] **Step 5: `EconomyRepository`** com os `@Modifying @Query` (ver §4.3 do
  spec): `addMoney`, `addTokens`, `debit` (todos `@Modifying(clearAutomatically =
  true)`, JPQL). `debit` retorna `int` (linhas afetadas).

- [ ] **Step 6: `EconomyService`** (`@Service`; Redis via `StringRedisTemplate`,
  `EventPublisher` injetado). Implementar `get/ensure/addMoney/addTokens/debit/
  transfer/set` conforme a tabela do spec §4.3. Pontos-chave:
  - `debit`: `if (repository.debit(uuid, cost) == 0) throw new InsufficientFundsException(...)`.
  - `transfer`: `@Transactional`; `ensure` ambos; débito da origem 0 → 422 (rollback);
    depois `addMoney(to, amount)`.
  - `set`: carrega (ou 0), `if (current.version != expected) throw new ConflictException(...)`.
  - Constantes: `CACHE_PREFIX = "economy:"`, `MONEY_BOARD = "leaderboard:money"`,
    `CACHE_TTL = Duration.ofMinutes(10)`.
  - `cache(EconomyEntity e)`: `try { redis.opsForHash().putAll(CACHE_PREFIX+uuid,
    Map.of("money",…,"tokens",…,"version",…)); redis.expire(…, CACHE_TTL);
    redis.opsForZSet().add(MONEY_BOARD, uuid, money); } catch (Exception ex) {
    log.warn(...); }` — **fail-open**.
  - Após cada mutação: re-`findById`, `cache`, `events.publish(MONEY_UPDATED, uuid,
    Map.of("uuid",…,"money",…,"delta",…,"source",…))` (ou `TOKEN_UPDATED`).

- [ ] **Step 7: `EconomyController`** (`@RequestMapping("/api/economy")`, fino como
  `ProfileController`; DTOs `record` aninhados `EconomyResponse`, `DeltaRequest
  {long delta, String source}`, `DebitRequest {long cost, String reason}`,
  `TransferRequest {UUID from, UUID to, long amount}`, `SetRequest {long money,
  long tokens, int version}`). Endpoints exatamente como o spec §4.3.

- [ ] **Step 8: Rodar o teste e confirmar que passa**

Run: `mvn -pl backend/rankup-service -am -q test -Dtest=EconomyServiceTest`
Expected: `BUILD SUCCESS`, 5 testes verdes.

- [ ] **Step 9: Build completo do módulo**

Run: `mvn -pl backend/rankup-service -am -q package`
Expected: `BUILD SUCCESS`.

- [ ] **Step 10: Commit**

```bash
git add backend/rankup-service
git commit -m "feat(economy): contexto economy no rankup-service (money/tokens, 422/409)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Compose + rota do gateway

**Files:**
- Modify: `docker-compose.yml` (serviço `rankup-service`; `api-gateway.depends_on`)
- Modify: `backend/api-gateway/src/main/resources/application.yml` (rota)

**Interfaces:**
- Produces: `rankup-service` no compose (build `MODULE=rankup-service`, porta
  interna 8082, `RANKUP_DB=rankup_db`, healthcheck, `depends_on` data+eureka);
  gateway roteia `/api/economy/**`.
- Consumes: imagem buildada de `./backend` (Task 2/3); `rankup_db` (Task 1).

Sem teste automatizado (infra) — validado na Task 7.

- [ ] **Step 1: Serviço `rankup-service` no compose** — espelhando `core-service`,
  adicionar após o bloco `core-service:` (antes de `api-gateway:`):

```yaml
  # ── RankUP game service (economy — later rank/prestige/plot/…). Internal only. ──
  rankup-service:
    build:
      context: ./backend
      args:
        MODULE: rankup-service
    env_file: ./configuration/environment
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      kafka:
        condition: service_healthy
      eureka-server:
        condition: service_healthy
    environment:
      SERVER_PORT: 8082
      POSTGRES_PORT: 5432
      RANKUP_DB: rankup_db
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8082/actuator/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 12
      start_period: 60s
```

- [ ] **Step 2: `api-gateway` espera o `rankup-service`** — no `depends_on:` do
  `api-gateway`, adicionar:

```yaml
      rankup-service:
        condition: service_healthy
```

- [ ] **Step 3: Rota no gateway** — em
  `backend/api-gateway/src/main/resources/application.yml`, no fim da lista
  `routes:` (após `core-activity`):

```yaml
        - id: rankup-economy
          uri: lb://rankup-service
          predicates:
            - Path=/api/economy/**
```

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml backend/api-gateway/src/main/resources/application.yml
git commit -m "feat(infra): rankup-service no compose + rota /api/economy no gateway

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: SDK `crystal-core` — `EconomyClient` + `crystal.economy()`

**Files:**
- Modify: `plugins/crystal-core/src/main/java/com/redecrystal/core/messaging/KafkaTopics.java`
- Create: `plugins/crystal-core/src/main/java/com/redecrystal/core/http/EconomyData.java`
- Create: `plugins/crystal-core/src/main/java/com/redecrystal/core/http/InsufficientFundsException.java`
- Create: `plugins/crystal-core/src/main/java/com/redecrystal/core/http/EconomyClient.java`
- Modify: `plugins/crystal-core/src/main/java/com/redecrystal/core/http/BackendHttpClient.java`
- Modify: `plugins/crystal-core/src/main/java/com/redecrystal/core/CrystalCore.java`

**Interfaces:**
- Produces: `EconomyData(String uuid, long money, long tokens, int version)`;
  `InsufficientFundsException` (extends `BackendHttpClient.BackendException`, 422);
  métodos de economia na `BackendHttpClient`; `EconomyClient`;
  `CrystalCore.economy()`; `KafkaTopics.MONEY_UPDATED/TOKEN_UPDATED`.
- Consumes: endpoints `/api/economy/**` (Task 4).

Sem teste unitário (wrappers HTTP finos, seguindo o padrão dos vizinhos
profile/inventory, que não têm teste). Compilação valida.

- [ ] **Step 1: Tópicos no SDK** — em `messaging/KafkaTopics.java`, adicionar as
  constantes e incluí-las na lista `ALL`:

```java
    public static final String MONEY_UPDATED = "money-updated";
    public static final String TOKEN_UPDATED = "token-updated";
```

(e acrescentar `MONEY_UPDATED, TOKEN_UPDATED` ao `List.of(...)` de `ALL`.)

- [ ] **Step 2: `EconomyData`** (`record`, estilo `ProfileData`/`InventoryData`):

```java
package com.redecrystal.core.http;

/** A player's RankUP balance as served by the economy API. */
public record EconomyData(String uuid, long money, long tokens, int version) {
}
```

- [ ] **Step 3: `InsufficientFundsException`** — subclasse de `BackendException`
  para que catches existentes continuem valendo e o `statusCode()` seja 422:

```java
package com.redecrystal.core.http;

/**
 * A conditional debit/transfer was rejected for lack of funds (HTTP 422). Extends
 * {@link BackendHttpClient.BackendException} so generic backend-error handling
 * still catches it, while callers that care can catch this specifically.
 */
public final class InsufficientFundsException extends BackendHttpClient.BackendException {
    public InsufficientFundsException(String message) {
        super(422, message);
    }
}
```

- [ ] **Step 4: Métodos na `BackendHttpClient`** — no fim do arquivo, um bloco
  `// ── Economy (RankUP) ──` (estilo profile/inventory, usando `send` privado):

```java
    // ── Economy (RankUP) ──

    /** Fetch a balance, or {@code null} if the player has no row yet. */
    public EconomyData getEconomy(String uuid) {
        JsonNode n = send("GET", "/api/economy/" + uuid, null, true);
        return n == null ? null : toEconomy(n);
    }

    /** Ensure the row exists (creates it zeroed) and return the balance. */
    public EconomyData ensureEconomy(String uuid) {
        return toEconomy(send("PUT", "/api/economy/" + uuid, java.util.Map.of()));
    }

    /** Additive Money delta (mining/harvest/kill). Never rejected for funds. */
    public EconomyData addMoney(String uuid, long delta, String source) {
        return toEconomy(send("POST", "/api/economy/" + uuid + "/money", Map.of(
                "delta", delta, "source", source == null ? "" : source)));
    }

    /** Additive Tokens delta. */
    public EconomyData addTokens(String uuid, long delta, String source) {
        return toEconomy(send("POST", "/api/economy/" + uuid + "/tokens", Map.of(
                "delta", delta, "source", source == null ? "" : source)));
    }

    /** Conditional debit; throws {@link InsufficientFundsException} (422) if broke. */
    public EconomyData debitMoney(String uuid, long cost, String reason) {
        return toEconomy(send("POST", "/api/economy/" + uuid + "/debit", Map.of(
                "cost", cost, "reason", reason == null ? "" : reason)));
    }

    /** Atomic transfer; throws {@link InsufficientFundsException} (422) if the payer is broke. */
    public EconomyData transfer(String from, String to, long amount) {
        return toEconomy(send("POST", "/api/economy/transfer", Map.of(
                "from", from, "to", to, "amount", amount)));
    }

    /** Absolute admin set with optimistic locking; {@link BackendException} 409 if stale. */
    public EconomyData setEconomy(String uuid, long money, long tokens, int version) {
        return toEconomy(send("PUT", "/api/economy/" + uuid + "/set", Map.of(
                "money", money, "tokens", tokens, "version", version)));
    }

    private EconomyData toEconomy(JsonNode n) {
        return new EconomyData(n.path("uuid").asText(), n.path("money").asLong(),
                n.path("tokens").asLong(), n.path("version").asInt());
    }
```

E, no método privado `send(...)`, antes do `throw new BackendException(sc, …)`
genérico, adicionar o ramo de 422:

```java
                if (sc == 422) {
                    throw new InsufficientFundsException(method + " " + path + " -> HTTP 422: " + resp.body());
                }
```

(Como `InsufficientFundsException` estende `BackendException`, o `catch
(BackendException e) { throw e; }` seguinte a propaga sem retry — comportamento
correto.)

- [ ] **Step 5: `EconomyClient`** — facade fino que `CrystalCore.economy()`
  devolve; guarda as constantes de chave:

```java
package com.redecrystal.core.http;

/**
 * Typed facade over {@link BackendHttpClient} for the RankUP economy. First
 * per-feature client in the SDK (see rankup master decision #6). Every call is a
 * single HTTP round-trip — the caller runs it off the main thread.
 */
public final class EconomyClient {

    /** Redis hash holding a player's hot balance ({money,tokens,version}). */
    public static final String KEY_PREFIX = "economy:";
    /** Sorted-set leaderboard name (used with RedisClient.leaderboardAdd/Top). */
    public static final String MONEY_LEADERBOARD = "money";

    private final BackendHttpClient backend;

    public EconomyClient(BackendHttpClient backend) {
        this.backend = backend;
    }

    /** Balance, or {@code null} if the player has no row yet (treat as zeroed). */
    public EconomyData get(String uuid)                       { return backend.getEconomy(uuid); }
    public EconomyData ensure(String uuid)                    { return backend.ensureEconomy(uuid); }
    public EconomyData addMoney(String uuid, long d, String s){ return backend.addMoney(uuid, d, s); }
    public EconomyData addTokens(String uuid, long d, String s){ return backend.addTokens(uuid, d, s); }
    public EconomyData debit(String uuid, long cost, String r){ return backend.debitMoney(uuid, cost, r); }
    public EconomyData transfer(String from, String to, long a){ return backend.transfer(from, to, a); }
    public EconomyData set(String uuid, long m, long t, int v){ return backend.setEconomy(uuid, m, t, v); }

    public static String cacheKey(String uuid) { return KEY_PREFIX + uuid; }
}
```

- [ ] **Step 6: Expor em `CrystalCore`** — importar `EconomyClient`; adicionar o
  campo, construí-lo e o accessor:

```java
    private final EconomyClient economy;
    // …no construtor, após configProvider/jwtCodec:
    this.economy = new EconomyClient(backend);
    // …junto aos demais accessors:
    public EconomyClient economy() { return economy; }
```

- [ ] **Step 7: Compilar o SDK**

Run: `mvn -pl plugins/crystal-core -am -q package`
Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Commit**

```bash
git add plugins/crystal-core
git commit -m "feat(sdk): EconomyClient + crystal.economy() + InsufficientFundsException

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Plugin `crystal-economy` (GUI-first)

**Files:**
- Create: `plugins/crystal-economy/pom.xml`
- Create: `plugins/crystal-economy/src/main/resources/plugin.yml`
- Create: `plugins/crystal-economy/src/main/java/com/redecrystal/economy/CrystalEconomyPlugin.java`
- Create: `plugins/crystal-economy/src/main/java/com/redecrystal/economy/gui/BalanceMenu.java`
- Create: `plugins/crystal-economy/src/main/java/com/redecrystal/economy/commands/BalanceCommand.java`
- Create: `plugins/crystal-economy/src/main/java/com/redecrystal/economy/commands/PayCommand.java`
- Create: `plugins/crystal-economy/src/main/java/com/redecrystal/economy/commands/EconomyAdminCommand.java`
- Modify: `plugins/pom.xml` (registrar o módulo)
- Modify: `docker-compose.yml` (montar o jar no `lobby-01` — só Fase 8)

**Interfaces:**
- Produces: comandos `/saldo` (GUI), `/pagar`, `/eco` (admin).
- Consumes: `crystal.economy()` (Task 5); `InsufficientFundsException`.

Sem teste automatizado (Bukkit/GUI) — verificação manual na Task 7.

- [ ] **Step 1: `pom.xml`** — cópia do `plugins/crystal-profile/pom.xml`, trocando
  `<artifactId>crystal-economy</artifactId>`, `<name>`/`<description>`. Mesmas
  dependências (`crystal-core`, `paper-api`) e o `maven-shade-plugin`.

- [ ] **Step 2: `plugin.yml`**

```yaml
name: CrystalEconomy
version: 0.1.0
main: com.redecrystal.economy.CrystalEconomyPlugin
api-version: '1.21'
author: RedeCrystal
description: RankUP economy (Money/Tokens) — balance GUI, pay, admin give/set.
commands:
  saldo:
    description: Ver seu saldo de Money e Tokens
    aliases: [balance, money]
  pagar:
    description: Transferir Money para outro jogador
    usage: /pagar <jogador> <valor>
  eco:
    description: Administrar economia (admin)
    usage: /eco <give|set> <jogador> <valor>
permissions:
  crystal.economy.admin:
    description: Dar/ajustar Money e Tokens
    default: op
```

- [ ] **Step 3: `CrystalEconomyPlugin`** — espelha `CrystalProfilePlugin`
  (bootstrap do `CrystalCore`, registra os 3 comandos, `close()` no `onDisable`):

```java
package com.redecrystal.economy;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.economy.commands.BalanceCommand;
import com.redecrystal.economy.commands.EconomyAdminCommand;
import com.redecrystal.economy.commands.PayCommand;
import com.redecrystal.economy.gui.BalanceMenu;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * RankUP economy plugin. Shows a player's Money/Tokens (GUI-first {@code /saldo}),
 * transfers Money ({@code /pagar}) and lets admins give/set balances
 * ({@code /eco}). All persistence goes through the backend economy API off the
 * main thread. Real Money producers (mining/harvest/kill) arrive in later phases.
 */
public final class CrystalEconomyPlugin extends JavaPlugin {

    private CrystalCore crystal;

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());

        BalanceMenu menu = new BalanceMenu(this, crystal);
        getServer().getPluginManager().registerEvents(menu, this);

        getCommand("saldo").setExecutor(new BalanceCommand(menu));
        getCommand("pagar").setExecutor(new PayCommand(this, crystal));
        getCommand("eco").setExecutor(new EconomyAdminCommand(this, crystal));
        getLogger().info("CrystalEconomy enabled.");
    }

    @Override
    public void onDisable() {
        if (crystal != null) {
            crystal.close();
        }
    }
}
```

- [ ] **Step 4: `BalanceMenu`** — espelha `ParkourTopMenu`: `Holder` record
  interno; `open(Player)` busca `crystal.economy().get(uuid)` **off-thread**
  (null → 0/0), monta um inventário pequeno (ex.: 27 slots) com um item Money
  (`Material.GOLD_INGOT`) e um item Tokens (`Material.EMERALD`) mostrando os
  valores no `displayName`/lore, e **abre na main thread** (`runTask`). `onClick`
  cancela cliques do `Holder`. Texto PT, `MiniMessage`/`Component` com
  `TextDecoration.ITALIC, false` como o vizinho.

- [ ] **Step 5: `BalanceCommand`** — atalho GUI-first: só delega para
  `menu.open(player)` (checando `sender instanceof Player`).

- [ ] **Step 6: `PayCommand`** (`/pagar <jogador> <valor>`):
  - `sender instanceof Player`; `args.length == 2`; parse `long amount` (>0, senão
    mensagem PT).
  - Resolver alvo **online** via `getServer().getPlayerExact(args[0])`; ausente →
    "§cJogador não encontrado." (offline fica p/ fase futura).
  - Não pagar a si mesmo.
  - Off-thread: `crystal.economy().transfer(fromUuid, toUuid, amount)`; sucesso →
    mensagem ao pagador e (se online) ao recebedor; `catch
    (InsufficientFundsException)` → "§cVocê não tem saldo suficiente.";
    `catch (BackendException)` → "§cNão foi possível pagar agora." + `log`.

- [ ] **Step 7: `EconomyAdminCommand`** (`/eco …`, `crystal.economy.admin`):
  - Sem permissão → mensagem e fim.
  - `give <jogador> <valor>` → off-thread `crystal.economy().addMoney(uuid, valor,
    "admin")`; feedback.
  - `set <jogador> <valor>` → off-thread: lê `get(uuid)` p/ a `version` atual (null
    → 0) e chama `set(uuid, valor, tokens, version)`; `catch (BackendException e)`
    com `e.statusCode()==409` → "§cConflito, tente de novo."
  - UUID do alvo: online via `getPlayerExact`; se precisar offline, `UUID` offline
    como o chat faz — mas p/ Fase 8 exigir online basta.

- [ ] **Step 8: Registrar o módulo** — em `plugins/pom.xml`, no `<modules>`,
  adicionar (após `crystal-profile` ou no fim):

```xml
        <module>crystal-economy</module>
```

- [ ] **Step 9: Montar no `lobby-01`** (só p/ verificação da Fase 8) — em
  `docker-compose.yml`, no `volumes:` do `lobby-01`, adicionar:

```yaml
      # RankUP economy — mounted on a lobby for Fase 8 verification only; moves to
      # spawn/mina/arena/terrenos from Fase 10.
      - ./plugins/crystal-economy/target/crystal-economy.jar:/plugins/crystal-economy.jar:ro
```

- [ ] **Step 10: Build do plugin**

Run: `mvn -pl plugins/crystal-economy -am -q package`
Expected: `BUILD SUCCESS`; jar shaded em `plugins/crystal-economy/target/crystal-economy.jar`.

- [ ] **Step 11: Commit**

```bash
git add plugins/crystal-economy plugins/pom.xml docker-compose.yml
git commit -m "feat(economy): plugin crystal-economy (/saldo GUI, /pagar, /eco)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Verificação fim-a-fim

**Files:** nenhum — build + subir/recriar containers + curl + jogo.

Pré-requisito: Tasks 1–6 commitadas e compiladas.

- [ ] **Step 1: Build de tudo o que muda**

Run:
```bash
mvn -pl backend/rankup-service -am -q package
mvn -pl plugins/crystal-economy -am -q package
```
Expected: `BUILD SUCCESS` nos dois (backend com testes verdes).

- [ ] **Step 2: Recriar tópicos + subir o serviço novo + gateway**

O `rankup-service` e o `core-service` são imagens buildadas de `./backend`; o
`api-gateway` idem. Os tópicos são criados pelo `kafka-init`.

Run:
```bash
make topics
docker compose up -d --build --force-recreate --no-deps rankup-service api-gateway
```
Expected: `rankup-service` fica `healthy` (Flyway cria `player_economy` em
`rankup_db`), gateway `healthy`; `docker compose logs rankup-service` mostra o
registro no Eureka. `money-updated`/`token-updated` aparecem em `make topics`/Kafka UI.

> Se `rankup_db` não existir (volume Postgres antigo), recriar o volume:
> `docker compose down && docker compose up -d postgres` (o
> `init-databases.sql` só roda em volume novo) — ou criar o banco manualmente:
> `docker compose exec postgres psql -U crystal -c "CREATE DATABASE rankup_db;"`.

- [ ] **Step 3: Verificar a economia por curl (backend, determinístico)**

Use `GW=http://localhost:${GATEWAY_PORT:-8080}` e `TOK=<BACKEND_SERVICE_TOKEN>`
(de `configuration/environment`). Com um UUID de teste `U=00000000-0000-0000-0000-000000000001`:

```bash
curl -s -X PUT  $GW/api/economy/$U           -H "Authorization: Bearer $TOK"                    # cria zerado
curl -s -X POST $GW/api/economy/$U/money      -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d '{"delta":1000,"source":"test"}'
curl -s        $GW/api/economy/$U             -H "Authorization: Bearer $TOK"                    # money=1000
curl -s -o /dev/null -w "%{http_code}\n" -X POST $GW/api/economy/$U/debit -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d '{"cost":99999,"reason":"test"}'   # 422
curl -s -o /dev/null -w "%{http_code}\n" -X POST $GW/api/economy/transfer -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d "{\"from\":\"$U\",\"to\":\"00000000-0000-0000-0000-000000000002\",\"amount\":99999}"   # 422
```
Expected: `PUT`/`POST money`/`GET` → 200 com `money` correto e persistente; débito
e transfer acima do saldo → **422**; `money-updated` no Kafka UI após o `POST
money`.

- [ ] **Step 4: Verificar o plugin no lobby (UX)**

Run: `docker compose up -d --force-recreate --no-deps lobby-01`
(o lobby copia o jar montado no boot). Depois, em jogo no `lobby-01`:
- `/saldo` abre a GUI mostrando Money/Tokens (0/0 para conta nova);
- `/eco give <você> 1000` → `/saldo` mostra 1000;
- `/pagar <outro> 100` transfere (saldo dos dois muda); `/saldo` confirma;
- `/pagar <outro> 999999` → "§cVocê não tem saldo suficiente.";
- reconectar e `/saldo` → saldo persiste (Postgres).

- [ ] **Step 5: Commit (se algum ajuste foi necessário)**

Se nada mudou, nada a commitar. Caso contrário, commitar o ajuste com mensagem
descritiva e o trailer `Co-Authored-By`.

---

## Notas de verificação (self-review do plano vs spec)

- **Módulo novo:** `backend/rankup-service` criado (Task 2), registrado em
  `backend/pom.xml` **e** no `backend/Dockerfile` (COPY pom+src) — sem isso a
  imagem não builda o módulo. Porta 8082, Eureka client. ✓
- **Contexto `economy`:** `player_economy` (Flyway V1), entity/repository/service/
  controller, os **três write-paths** do master §4.2 (aditivo, débito→422, set→409)
  cobertos por teste TDD (Task 3). ✓
- **Endpoints do master §6.1:** GET, PUT (ensure), POST money, POST tokens, POST
  debit, POST transfer, PUT set — todos na Task 3 Step 7 e no SDK Task 5. ✓
- **Infra:** `rankup_db` (Task 1), tópicos `money/token-updated` (Task 1 +
  KafkaTopics backend Task 2 + SDK Task 5), serviço no compose + `depends_on` do
  gateway (Task 4), rota `/api/economy/**` (Task 4). ✓
- **Write-through + eventos:** `cache()` fail-open grava `economy:{uuid}` +
  `leaderboard:money`; `EventPublisher` emite `money/token-updated` (Task 3 Step 6). ✓
- **SDK:** `EconomyData`, `EconomyClient`, `crystal.economy()`,
  `InsufficientFundsException` (422) e o ramo 422 no `send()`; tópicos no
  `KafkaTopics`/`ALL` (Task 5). ✓
- **Plugin GUI-first:** `/saldo` abre `BalanceMenu` (o comando é atalho);
  `/pagar` trata 422 com mensagem PT; `/eco` give/set admin (Task 6). Registrado
  em `plugins/pom.xml`; montado no `lobby-01` só p/ Fase 8. ✓
- **Constraints:** HTTP sempre off-thread (async) e abertura de GUI de volta na
  main; gateway-only; constantes p/ porta/chave/tópico/permissão; texto de jogador
  em PT, código em inglês. ✓
- **Divergências registradas** (spec §9): primeiro `@Modifying @Query` do backend;
  422 novo no handler; SDK sem `ConflictException` (409 via `statusCode()`);
  primeiro facade por feature; `make plugins` não enumera módulos (build por-plugin
  com `-pl … -am`). ✓
- **Fora de escopo (fases seguintes):** ranks/prestígio/minas/terrenos/plantações/
  arena/stats/home; produtores reais de Money; HUD/scoreboard/TAB de economia;
  leaderboards de tokens/prestígio/blocos; servidores de jogo novos. ✓
```