# RedeCrystal

Distributed, horizontally-scalable Minecraft network infrastructure with a
centralized backend, event-driven architecture (Kafka), distributed cache
(Redis), and centralized configuration.

## Stack decisions (locked)

| Area              | Choice                                              |
|-------------------|-----------------------------------------------------|
| Proxy             | **Velocity** (modern, async, multi-proxy)           |
| Game servers      | **PaperMC**, Minecraft **1.21.x**                   |
| Java              | **21** everywhere (backend, SDK, plugins)           |
| Build tool        | **Maven** (multi-module reactor)                    |
| Backend           | **Microservices**: Eureka + Spring Cloud Gateway + domain services |
| Backend libs      | Spring Boot 3.3, Spring Cloud 2023.0.x, PostgreSQL, Redis (Lettuce), Kafka |
| Auth              | **Only at the API Gateway** (service token now, player JWT later) |
| Databases         | **One per service** (core_db, …) in a shared Postgres |
| Messaging         | Apache Kafka (KRaft, no ZooKeeper)                  |
| Config            | Centralized via core-service; hot-reload via Kafka  |

## Project layout

```
redecrystal/
├── pom.xml                     # Maven aggregator
├── docker-compose.yml          # data plane + eureka + gateway + core-service
├── Makefile                    # build / up / down / logs / topics / clean
├── backend/
│   ├── pom.xml                 # backend parent (Spring Cloud BOM, Java 21)
│   ├── Dockerfile              # shared multi-module build (--build-arg MODULE=…)
│   ├── eureka-server/          # Netflix Eureka service registry  (:8761)
│   ├── api-gateway/            # Spring Cloud Gateway + auth       (:8080)
│   └── core-service/           # Config Service + game-server discovery (internal)
├── plugins/                    # crystal-core SDK + per-feature plugins (Phase 3+)
├── servers/                    # proxy / login / lobby runtime dirs (Phase 4+)
├── world/                      # world templates
└── infra/
    ├── kafka/create-topics.sh
    └── postgres/init-databases.sql   # database-per-service provisioning
```

## Backend topology

```
client ──▶ api-gateway (:8080, ONLY published, ONLY authenticator)
                │  routes lb://core-service  (resolved via Eureka)
                ▼
           core-service (internal)  ──▶ Postgres core_db / Redis / Kafka
                │ registers
                ▼
           eureka-server (:8761)
```

Authentication happens **only at the gateway**. `core-service` has no security and
is not published to the host — it is reachable solely through the gateway on the
internal Docker network.

## Quick start (Phase 0/1)

```bash
cp .env.example .env                                              # host port mappings
cp configuration/environment.release.example configuration/environment   # deploy config
docker compose up -d        # or: make up
```

Configuration is split in two: **`.env`** holds the published host ports (the
`${...}` interpolation in `docker-compose.yml`), and **`configuration/environment`**
holds the container/deployment config (DB creds, service token, hosts, LuckPerms,
EULA…) loaded into the services via `env_file`. Pick a template:
`configuration/environment.release.example` (dev) or `.production.example`
(strong secrets, premium auth). The active `configuration/environment` is
git-ignored; the `.example` files are committed.

Then verify:

```bash
docker compose ps                                   # all healthy
curl http://localhost:8080/actuator/health          # gateway: {"status":"UP"}
# Eureka dashboard:  http://localhost:8761   (api-gateway + core-service UP)
# Kafka UI:          http://localhost:8085   (topics created by kafka-init)

TOKEN=change-me-dev-service-token
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/config   # routed to core-service
```

Inspect the per-service schema (Flyway runs on core-service startup):

```bash
docker compose exec postgres psql -U crystal -d core_db -c "\dt"
```

## Build locally (without Docker)

Requires JDK 21 + Maven. Build everything: `mvn package`. Run one service with
infra up (`make infra`): `mvn -pl backend/core-service spring-boot:run`
(start `eureka-server` first if you want registration).

## Backend API (Phase 2)

All `/api/**` endpoints require `Authorization: Bearer <BACKEND_SERVICE_TOKEN>`.

| Method | Path | Purpose |
|--------|------|---------|
| GET    | `/api/config`               | List all config entries |
| GET    | `/api/config/{key}`         | Get one config (Redis-cached) |
| PUT    | `/api/config/{key}`         | Upsert config → bumps version, refreshes Redis, publishes `config-updated` (+ `maintenance-*` on flag change) |
| GET    | `/api/network`              | List registered servers (`?type=lobby` filter) |
| GET    | `/api/network/{id}`         | Get one server instance |
| POST   | `/api/network/register`     | Self-register an instance → publishes `server-started` |
| POST   | `/api/network/{id}/heartbeat` | Update online players / status |
| DELETE | `/api/network/{id}`         | Deregister → publishes `server-stopped` |
| GET/PUT/POST | `/api/profile/{uuid}[/add]` | Get / ensure / apply additive stat deltas |
| GET/PUT | `/api/inventory/{uuid}/{type}` | Load / save inventory (optimistic-lock version → 409 on conflict) |

```bash
TOKEN=change-me-dev-service-token
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/config/lobby
```

## Crystal SDK (Phase 3)

`plugins/crystal-core` is the pure-JVM SDK every plugin depends on. It talks to
the backend **only through the gateway** (bearer service token) — never to a
domain service directly.

```java
CrystalCore crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());

// config is fetched remotely and hot-reloads on `config-updated`
crystal.configProvider().onChange("lobby", cfg -> applyMotd(cfg.string("motd", "")));

// self-register + heartbeat with live player count
crystal.registerThisServer(/* maxPlayers */ 500);
crystal.startHeartbeat(() -> onlinePlayerCount());

// distributed events + caches
crystal.events().on(KafkaTopics.PLAYER_CHAT, e -> ...);
crystal.kafka().publish(KafkaTopics.PLAYER_CONNECTED, uuid, Map.of("player", name));
crystal.redis().addOnlinePlayer(uuid);
```

Bootstrap env (the only local config): `BACKEND_URL`, `BACKEND_SERVICE_TOKEN`,
`REDIS_HOST`/`REDIS_PORT`, `KAFKA_BROKERS`, `SERVER_ID`/`SERVER_TYPE`/`SERVER_HOST`/`SERVER_PORT`.

Run the live integration test (stack must be up): `make sdk-it`.

## Minecraft servers (Phase 4)

Built with the `itzg` images and a cracked dev setup (Velocity forwarding `none`).
Build the plugin jars first (`make plugins`), then bring the servers up — they are
mounted from `plugins/*/target/*.jar`:

```
proxy-01 (Velocity, :25565 published)  →  login-01 (Paper)  →  lobby-01 (Paper)
```

Player flow: join the proxy → routed to **login** → `crystal-login` authenticates
(offline) and emits `player-authenticated` → `crystal-bungee` consumes that event
and moves the player to **lobby**. Each server self-registers via the SDK and
heartbeats; the lobby hot-reloads its config from the backend.

```bash
make plugins                       # build the shaded plugin jars
docker compose up -d proxy-01 login-01 lobby-01 lobby-02 lobby-03
# connect a Minecraft 1.21.1 client (or a bot) to localhost:25565
```

### Horizontal scaling (Phase 6)

The lobby fleet scales with zero proxy/code changes — each lobby is the same
compose definition (a YAML anchor) with a different `SERVER_ID`. `crystal-bungee`
discovers lobbies from the registry, registers them with Velocity dynamically,
and routes each player to the least-loaded one. Graceful stop deregisters
(`server-stopped`); a crash is caught by the backend stale-heartbeat reaper,
which flips the instance OFFLINE so the proxy stops routing to it.

## Observability (Phase 7)

```
docker compose up -d prometheus grafana kafka-exporter
# Grafana:    http://localhost:3000   (anonymous viewer; admin/admin)
# Prometheus: http://localhost:9090
```

Mandatory metrics and where they come from:

| Metric | Source |
|--------|--------|
| TPS (per server) | `redecrystal_server_tps` — SDK heartbeat (Paper) |
| Online players | `redecrystal_online_players` (+ `redecrystal_server_players`) |
| Redis latency | `redecrystal_redis_ping_seconds` — backend gauge |
| Kafka lag | `kafka_consumergroup_lag` — kafka-exporter |
| Memory | `redecrystal_server_memory_mb` (MC) + `jvm_memory_used_bytes` (backend) |
| CPU | `redecrystal_server_cpu_load` (MC) + `process_cpu_usage` (backend) |

## Lobby features (parkour, hotbar, /tell) + worlds

- **crystal-parkour** — in-lobby parkour with a course configured live via admin
  commands (`/parkour setstart|addcheckpoint|setfinish`, stored in the central
  `parkour` config, hot-reloaded) and a best-time leaderboard (`/parkour top`,
  Redis `leaderboard:parkour` + Postgres). Backend: `/api/parkour/**`.
- **crystal-lobby hotbar** — locked 4-item hotbar (games selector → GUI, profile,
  hide-players toggle, cosmetics stub).
- **crystal-chat** — `/tell`, `/r`, and `/telltoggle` (cross-server private
  messages over Kafka; `tells_disabled` in Redis).
- **crystal-inventory** — now per server type and opt-in: syncs only when
  `<type>.syncInventory = true`; no-ops on the lobby (fixed hotbar wins).

### External plugins + worlds

`EXTERNAL_PLUGINS/` holds third-party jars mounted into the servers:
**ViaVersion + ViaBackwards + ViaRewind** on the Velocity proxy (join from **any**
client version) and **FastAsyncWorldEdit** on the Paper servers. The lobby/login
worlds are **void** (clean canvas); their build schematics live in
`world/WORLD_LOBBY/` and `world/WORLD_LOGIN/` and are copied into each server's
`FastAsyncWorldEdit/schematics/` folder.

The hub is applied **automatically** on first startup by the `crystal-worldinit`
plugin: it pastes the configured schematic into the void via the WorldEdit API
(headless, no player) and sets the world spawn, then writes a marker so it runs
once. Configured per server with env `CRYSTAL_WORLD_SCHEMATIC` / `CRYSTAL_WORLD_PASTE`.
To re-initialize, wipe the world volume (`redecrystal_lobby01data`, …) and restart.
Refine the spawn in-game with `/setworldspawn` if desired.

## Roadmap

- **Phase 0/1 ✅** — infra, schema, Kafka topics, backend health
- **Phase 2 ✅** — Config Service (centralized config, Redis cache, hot-reload events) + game-server Service Discovery
- **Phase 2.5 ✅** — backend refactored to microservices: Eureka registry + Spring Cloud Gateway (single entry point + sole authenticator) + core-service; database-per-service
- **Phase 3 ✅** — `crystal-core` SDK: backend HTTP client (via gateway), Redis (Lettuce), Kafka client + EventBus, `ConfigProvider` with hot reload, `CrystalCore` facade (register/heartbeat). Verified end-to-end against the live stack (`make sdk-it`)
- **Phase 4 ✅** — first vertical slice: Velocity proxy (`crystal-bungee`) + Paper login (`crystal-login`) + Paper lobby (`crystal-lobby`), all on the SDK. A real headless client connects proxy → login → lobby (event-driven handoff via `player-authenticated`), with Redis presence, Service Discovery, and live config hot-reload reaching the lobby plugin
- **Phase 6 ✅** — horizontal scaling: lobby fleet (lobby-01/02/03, same definition + different `SERVER_ID`) auto-registers; `crystal-bungee` discovers it dynamically and least-loaded-balances. Verified: 6 bots spread 2/2/2, graceful scale-down reroutes, and a hard-killed lobby is reaped by the backend (stale-heartbeat) and dropped by the proxy
- **Phase 5 ✅** — feature plugins:
  - `crystal-chat` — network-wide chat over `player-chat` (a message on lobby-01 reaches lobby-02), config-driven banned-word moderation with hot reload.
  - `crystal-profile` — persisted progression (coins/XP/level/playtime) via a `profile` context in core-service (Postgres + Redis cache). Verified: coins survive reconnect.
  - `crystal-inventory` — inventory synced through an `inventory` context (Redis write-through + Postgres, **optimistic-lock version**). Verified: items restored across disconnect/reconnect, stale saves rejected with 409.

  > Profile/inventory live as new bounded contexts inside **core-service** (per the "core-service for now" decision); they can be split into dedicated microservices later.
- **Phase 7 ✅** — observability: **Prometheus + Grafana**. Backend exposes `/actuator/prometheus`; game servers report TPS/memory/CPU through the SDK heartbeat → backend gauges; `kafka-exporter` for broker/consumer-lag. Grafana auto-provisions a Prometheus datasource and a "RedeCrystal Network" dashboard. All mandatory metrics verified live: TPS, online players, Redis latency, Kafka lag, memory, CPU.
- **Phase 3** — `crystal-core` SDK (HTTP/Redis/Kafka clients, ConfigProvider)
- **Phase 4** — first vertical slice: proxy + login + lobby end-to-end
- **Phase 5** — chat / profile / inventory plugins
- **Phase 6** — horizontal scaling + service discovery
- **Phase 7** — Prometheus + Grafana observability
- **Phase 8** — Kubernetes manifests + CI
