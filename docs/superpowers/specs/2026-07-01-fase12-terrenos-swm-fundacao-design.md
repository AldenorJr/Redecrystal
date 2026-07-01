# Design — Fase 12: Terrenos — fundação SlimeWorldManager (load/save/unload + sticky routing)

Data: 2026-07-01
Branch base: `feat/login-autocomplete-e-troca-senha`

> Fundação dos **Terrenos** do RankUP: um novo tipo de servidor `terrenos`
> (SlimeWorldManager + mundo VOID) onde cada jogador tem **uma Slime world própria**
> carregada sob demanda, salva no Postgres e roteada de forma **sticky**. Arquitetura
> de alto nível em [`2026-07-01-rankup-arquitetura-design.md`](2026-07-01-rankup-arquitetura-design.md)
> (§2, §4, §6.3, §7.2, §8, **§9 o ciclo de vida SWM completo**, §10 Fase 12). Este
> spec detalha **só a fundação** — carregar/salvar/descarregar sem corromper nem
> duplicar. Membros, permissões, expansão, upgrades e produção são a **Fase 13**.
> Texto de jogador em PT; identificadores/módulos/tópicos/chaves em inglês.
>
> **Esta é a fase de MAIOR RISCO do projeto**: corrupção e duplicação de mundo. O
> spec é deliberadamente rigoroso sobre *single-writer*, *no-duplication* e
> *auto-recovery* (§6, §7).

## 1. Problema

O RankUP promete terrenos onde o jogador constrói livremente e **nunca perde
progresso**. Diferente de spawn/mina/arena (mundos fixos, iguais para todos), cada
terreno é um **mundo isolado e mutável por dono**. Precisamos de um lugar para
guardar os bytes do mundo, um jeito de carregá-lo só enquanto alguém está dentro, e
uma garantia de que **duas instâncias jamais escrevem o mesmo mundo ao mesmo tempo**
(o que duplicaria ou corromperia o terreno).

Hoje nada disso existe: não há tipo de servidor `terrenos`, nem SlimeWorldManager na
rede, nem tabela `plots`/`plot_worlds`, nem endpoint de blob, nem roteamento sticky.
O `crystal-bungee` só sabe balancear o fleet `lobby` (least-loaded); o `RedisClient`
não tem lock atômico (`SET … NX EX`); e o único "blob" versionado que existe é o
inventário — **texto** (base64), não binário.

A Fase 12 entrega a **espinha dorsal segura**: criar/carregar/salvar/descarregar um
terreno com lock + lease + versão, roteamento sticky, e uma GUI `/terreno` mínima.

## 2. Objetivos e não-objetivos

**Objetivos**
- Novo tipo de servidor `terrenos` (SWM + mundo default VOID) como **fleet**.
- **Prerequisito**: adicionar SlimeWorldManager (SWM API) como plugin externo +
  **custom `SlimeLoader`** que lê/grava o blob via `GET/PUT /api/plot/{plotId}/world`.
- Contexto `plot` no `rankup-service`: tabelas `plots` + `plot_worlds`, migração
  `V4__rankup_plots.sql`, endpoints do master §6.3 (por dono/id, criar, blob
  get/put com lease+version → 409).
- **Ciclo de vida completo** (master §9): ENTER · DURING · LEAVE · CRASH, com
  Redis `plot-lock:{plotId}` (NX), DB lease e `plot_worlds.version`.
- **Sticky routing** dos terrenos no `crystal-bungee` (lê `plot-server:{uuid}`).
- Plugin `crystal-plot` (fundação): orquestra o ciclo de vida + GUI `/terreno`
  **básica** (info: tamanho/level/nome; home/teleporte; renomear).
- SDK `crystal-core`: `PlotData`, `PlotClient`, `crystal.plots()`, op de lock NX no
  `RedisClient`, tópicos `plot-loaded`/`plot-saved`, chaves novas.

**Não-objetivos** (Fase 13+)
- Membros, permissões por membro, expansão 10→20→30→40, upgrades, level/produção,
  `invested_money`, `plot-updated` (invalidação cross-instância). A GUI da Fase 12
  **mostra** tamanho/level/nome mas não os altera (exceto o nome).
- Plantações (Fase 14), NPCs/holos de terreno (Fase 16).
- HPA/StatefulSet de verdade no K8s (Fase 8 da rede; aqui só o fleet Docker).

## 3. Decisões de design (já acordadas no master)

| Tema | Decisão |
|------|---------|
| Onde vivem os bytes | **Custom `SlimeLoader` → Postgres `plot_worlds.slime_data BYTEA`** via `GET/PUT /api/plot/{plotId}/world` (master #3). **Rejeitado**: file data source do SWM em volume compartilhado (quebraria "um banco/serviço, só Postgres" e reintroduziria estado em disco → o problema que o sticky+SWM resolvem). |
| Um mundo por terreno | Cada terreno = **uma Slime world VOID** própria; carregada só enquanto dono/membro está no fleet. |
| Single-writer | **Três camadas combinadas**: Redis `plot-lock:{plotId}` `SET NX EX` (fence rápido) + DB lease (`lease_server`/`lease_expires_at`, durável) + `plot_worlds.version` (rejeita save atrasado). Ver §6. |
| Anti-perda | **Save-on-leave E save-on-unload** + **save de segurança** periódico (§6.2). Nunca confiar num único ponto de salvamento. |
| Roteamento | **Sticky** por `plot-server:{uuid}` no `crystal-bungee` (master §7.2): instância viva → vai pra ela; senão least-loaded + grava lease. |
| Servidor | Tipo `terrenos`, mundo default **VOID** (SWM cria/carrega as slime worlds por cima; o mundo do disco fica vazio). |
| **Distribuição SWM: Advanced Slime Paper (fork) — DECIDIDO** | O fleet `terrenos` roda o **jar do Advanced Slime Paper (ASP)** — um **fork do Paper** que embute a Slime API nativamente (load/unload assíncrono; entidades/mobs/tile-entities/baús persistidos no próprio blob Slime). Escolhido por ser o caminho **mantido para 1.21** e o mais consistente para o ciclo load→build→save→unload. **O resto da rede segue em Paper stock**; só o `terrenos` troca a imagem base. O plano fixa a versão exata de ASP (compat 1.21.x) e valida velocidade de load/unload + persistência de mob/baú num teste. |
| Concorrência do blob | Optimistic-lock igual `InventoryService.save` (apresenta `version` → **409**), mas sobre **BYTEA** + campos de **lease**. |
| Lock atômico | `RedisClient` **não tem** `SET NX EX` hoje → **adicionar** `setNx(key,val,ttl):boolean` (Lettuce `SetArgs.nx().ex()`). |
| Escopo GUI | `/terreno` **básica** (info + home + rename). Resto na Fase 13. |
| Verificação | Fleet `terrenos` real no compose; teste de **crash** (matar container) e de **join concorrente** (nunca 2 writers). |

## 4. Arquitetura

### 4.1 Prerequisito — Advanced Slime Paper (fork do Paper) no fleet `terrenos`

SWM **não existe no repo** (os plugins externos montados hoje são
ViaVersion/ViaBackwards/ViaRewind, FastAsyncWorldEdit e LuckPerms — ver
`docker-compose.yml`). **Decisão (dono):** em vez de um plugin externo, o fleet
`terrenos` roda o **Advanced Slime Paper (ASP)** — um **fork do Paper** que já
embute a Slime API. Consequências:

- A imagem base do `terrenos` **deixa de ser** `itzg/minecraft-server:java21`
  PAPER stock e passa a rodar o **jar do ASP** (via `TYPE: CUSTOM` +
  `CUSTOM_SERVER=<url/arquivo do ASP>` na itzg, ou imagem dedicada — o plano fixa o
  mecanismo). **Só o `terrenos`** muda; lobby/spawn/mina/arena/login seguem Paper
  stock.
- A Slime API (`SlimePlugin`/`SlimeLoader`/`SlimeWorld`) é fornecida pelo **próprio
  servidor** (fork), não por um plugin `depend`. O `crystal-plot` compila contra a
  API do ASP como dependência **provided** (fornecida em runtime pelo fork), no
  molde de como o `crystal-mine` trata o WorldEdit/FAWE.
- Entidades/mobs/tile-entities/baús são **persistidos no blob Slime** pelo fork —
  o custom `SlimeLoader` (§4.4) só transporta os bytes; a consistência de mob é do
  ASP. Validar (velocidade + persistência) no plano.

O plano fixa a **versão exata de ASP** compatível com 1.21.x. Superfície da API de
que dependemos (nível de design, comum ao ASP):

| Símbolo SWM | Uso |
|---|---|
| `SlimePlugin` (serviço via `Bukkit.getPluginManager()`) | ponto de entrada |
| `SlimeLoader` (interface a **implementar**) | ponte para o Postgres (§4.4) |
| `SlimePropertyMap` | props do mundo VOID (spawn, difficulty, pvp, world type=FLAT/empty) |
| `plugin.createEmptyWorld(loader, name, readOnly=false, props)` | terreno **novo** (10×10 vazio) |
| `plugin.loadWorld(loader, name, readOnly=false, props)` | carregar terreno existente |
| `plugin.generateWorld(slimeWorld)` | materializa o `SlimeWorld` como `World` do Bukkit |
| `plugin.getLoadedWorlds()` / `asyncSaveWorld` / `Bukkit.unloadWorld(world,save)` | salvar/descarregar |

Nome da slime world = `plotId` (string). Toda I/O do SWM sobre esse mundo passa
pelo **nosso** `SlimeLoader`, que traduz para HTTP (§4.4). O SWM **serializa/desserializa**
os bytes; nós só transportamos o blob (nunca interpretamos o formato Slime).

### 4.2 Novo tipo de servidor `terrenos` (fleet, VOID)

Espelha o padrão de fleet do lobby (`docker-compose.yml`, âncoras YAML +
`SERVER_TYPE`), adicionando `terrenos-01`/`terrenos-02`:

- `SERVER_TYPE: terrenos`; monta os jars do jogo + **SlimeWorldManager** + os
  plugins do RankUP (`crystal-plot`, `crystal-economy`, …).
- **Mundo default VOID**: o servidor sobe com um mundo vazio (sem schematic —
  diferente de `crystal-worldinit`, que cola um schematic num hub). As slime worlds
  dos terrenos são geradas em runtime pelo SWM por cima, isoladas.
- Descoberto pela registry como qualquer fleet (`listServers("terrenos")`), sem
  mudança manual no proxy.

### 4.3 Contexto `plot` no `rankup-service` (`api/application/domain`)

Segue o molde de `economy` (Fase 8) e `inventory` (core): controller fino,
optimistic-lock manual (compara `version`, **não** `@Version` JPA), `ConflictException`
→ 409. Duas tabelas em `rankup_db`, migração **`V4__rankup_plots.sql`** (após V1
economy, V2 rank, V3 prestige):

**`plots`** (metadados — master §4):
```
plot_id UUID PK · owner_uuid UUID UNIQUE · size SMALLINT d10 · level INT d1 ·
invested_money BIGINT d0 · display_name VARCHAR(48) · version INT d0 ·
created_at · updated_at
```
Na Fase 12 só `size`/`level`/`invested_money` **default** e `display_name` mutável
(rename). Expansão/level/upgrades são Fase 13.

**`plot_worlds`** (blob Slime + controle de writer — master §4, §9):
```
plot_id UUID PK (FK plots) · slime_data BYTEA · lease_server VARCHAR(48) ·
lease_expires_at TIMESTAMPTZ · version INT d0 · saved_at TIMESTAMPTZ
```
`slime_data` pode ser **NULL** logo após `POST /api/plot` (linha criada, mundo ainda
não serializado) — o primeiro `createEmptyWorld` + save preenche.

**`domain/PlotEntity`** (`@Table("plots")`): construtor de criação (owner, size=10,
level=1, version=0, display_name default `"Terreno de <nick>"`), `rename(name)` (bump
version), getters. **`domain/PlotWorldEntity`** (`@Table("plot_worlds")`): `slime_data`
`byte[]`, campos de lease, `version`; métodos `acquireLease(server, ttl)`,
`saveBytes(data, expectedVersion)` (bump version + `saved_at`), `releaseLease()`.

**`application/PlotService`**:

| Método | Comportamento |
|--------|---------------|
| `getByOwner(uuid)` | `plots.findByOwnerUuid` → `NotFoundException` (404). |
| `getById(plotId)` | `findById` → 404. |
| `create(ownerUuid, nick)` | idempotente por `owner_uuid UNIQUE`: se já existe → 409/retorna; senão insere `plots` (10×10) **e** `plot_worlds` (slime_data NULL, version 0). Emite nada (o mundo é criado pelo plugin no primeiro ENTER). |
| `loadWorld(plotId, server)` | **acquire lease** condicional (§6) + retorna `{bytes, version, present}`; 409 se lease de outro server vivo. `bytes==null` (mundo novo) → sinaliza "criar vazio". |
| `saveWorld(plotId, server, bytes, expectedVersion)` | valida lease (server dono) **e** version (== esperado); divergiu → **409**; senão grava BYTEA, `version++`, `saved_at=now`. |
| `releaseWorld(plotId, server, bytes?, version?)` | save final opcional + limpa lease (`lease_server=null`). |
| `renameByOwner(uuid, name)` | rename com optimistic-lock. |

**`api/PlotController`** (`@RequestMapping("/api/plot")`):

| Método/Path | Body → Resposta |
|---|---|
| `GET /{uuid}` | terreno por **dono** → `PlotResponse` (404) |
| `GET /id/{plotId}` | terreno por id → `PlotResponse` (404) |
| `POST /` | `{ownerUuid,nick}` → cria 10×10 + `plot_worlds` vazio |
| `PUT /{plotId}/name` | `{ownerUuid,name,version}` → 409 |
| `GET /{plotId}/world?server=…` | **carrega**: adquire lease → `WorldResponse{bytesBase64|null, version, present}`; **409** se lease vivo de outro server |
| `PUT /{plotId}/world` | **salva**: `{server, bytesBase64, version, release?}` → **409** lease/version; grava BYTEA + version++; `release=true` limpa lease (save-on-leave/unload) |

> **Transporte do blob.** O corpo do `/world` é JSON com `bytesBase64` (o SDK
> hoje só faz `send` JSON via `BackendHttpClient`; um endpoint `application/octet-stream`
> exigiria caminho HTTP novo). BYTEA guarda os **bytes crus** (o base64 é só o fio).
> Alternativa (se o blob crescer muito): endpoint binário dedicado — **não** nesta
> fase. Ver §9 Divergências.

### 4.4 Custom `SlimeLoader` → Postgres (no `crystal-plot`)

Implementa a interface `SlimeLoader` do SWM, **traduzindo cada operação para os
endpoints do §4.3** via `crystal.plots()`. Guarda um mapa em memória
`plotId → version` (a versão lida no último load) para apresentar o
`expectedVersion` correto no save:

| Método `SlimeLoader` | Implementação |
|---|---|
| `worldExists(name)` | `GET /api/plot/id/{name}` → 200? |
| `readWorld(name, readOnly)` | `GET /{plotId}/world?server=<this>` → guarda `version`, devolve `bytes` (409 → `WorldInUseException` → nega ENTER) |
| `saveWorld(name, bytes, lock)` | `PUT /{plotId}/world {server,bytes,version}` → 409 → **aborta + alarma** (zumbi); sucesso → `version = resp.version` |
| `isWorldLocked(name)` | lease vivo de outro server no `plot_worlds`? |
| `unlockWorld(name)` | `PUT …/world {release:true}` (libera lease) |
| `deleteWorld(name)` | fora de escopo (Fase 12 não apaga terrenos) → no-op/`Unsupported` |
| `listWorlds()` | não usado pelo fluxo on-demand → lista vazia |

O SWM chama `readWorld`/`saveWorld`; a orquestração de **quando** (ENTER/DURING/LEAVE)
é do `PlotLifecycle` (§4.5). A `version` viaja **fora** da API SWM (o `SlimeLoader`
a mantém internamente), porque `saveWorld(name,bytes,lock)` não a carrega.

### 4.5 Plugin `crystal-plot` (Paper, roda no fleet `terrenos`)

Espelha `crystal-economy`/`crystal-profile` (`pom.xml` shade `crystal-core` +
paper-api; `CrystalPlotPlugin` só boot+registro; `listener/`, `commands/`, `gui/`).
`depend: [SlimeWorldManager, LuckPerms]`.

- **`SlimeLoaderPostgres`** (§4.4).
- **`PlotLifecycle`** — o coração (§6): registra o `SlimeLoader` no `SlimePlugin`,
  orquestra ENTER (no `PlayerJoinEvent`/troca pra terrenos), DURING (renovação de
  lock/lease + save de segurança por timer), LEAVE (quando o último jogador do mundo
  sai / `PlayerQuitEvent` / `onDisable`). Mantém o mapa `plotId → LoadedPlot`
  (world, lease, timers, dirty-flag).
- **`gui/PlotMenu`** (espelha `BalanceMenu`): busca `crystal.plots().getByOwner()`
  off-thread; itens **info** (tamanho `size`, level, `display_name`), **home/ir ao
  terreno** e **renomear** (fluxo digitar-no-chat como o rename de pet do lobby);
  abre na main thread. Sem terreno → oferece "criar" (`POST /api/plot`).
- **`commands/PlotCommand`** (`/terreno`) → abre `PlotMenu`. "Ir ao terreno" pede ao
  proxy o roteamento sticky (§4.6) e, ao chegar, o `PlotLifecycle` carrega/gera.

### 4.6 Roteamento sticky no `crystal-bungee` — `TerrenosRouter` (NOVO)

Variante **sticky** do balanceamento do lobby. O master §7.2 e a Fase 10 preveem um
`FleetRouter` genérico por tipo; a Fase 12 adiciona a variante sticky (least-loaded
como o `LobbyRouter`, mas consultando o assignment antes):

```
routeToTerreno(player, plotId):
  assigned = redis.get("plot-server:" + uuid)          // sticky
  if assigned != null && isOnline(assigned):
      → conecta em assigned (mesma instância enquanto o mundo está ativo)
  else:
      best = least-loaded online de listServers("terrenos")   // reusa a lógica do LobbyRouter
      redis.setex("plot-server:" + uuid, best, LEASE_TTL)      // grava o assignment (lease ~5min)
      → conecta em best
```

Fica junto do `LobbyRouter` no `CrystalBungeePlugin` (mesma descoberta/sync de fleet,
`SERVER_STARTED`/`SERVER_STOPPED`). O assignment `plot-server:{uuid}` **expira** após
save+unload (o `crystal-plot` chama uma liberação; ou o TTL vence) → permite
rebalancear noutra instância na próxima entrada. A conexão em si é disparada por um
plugin-message do `crystal-plot`/`crystal-spawn` ("quero ir ao terreno"), como o
handshake de auth já usa um canal dedicado.

## 5. Fluxo de dados (resumo)

```
/terreno → PlotMenu → "Ir ao terreno"
  → plugin-message ao proxy → TerrenosRouter.routeToTerreno
      → plot-server:{uuid} vivo? sim: mesma instância | não: least-loaded + grava lease
  → jogador conecta em terrenos-XX
  → PlotLifecycle.enter(plotId):  lock NX → lease DB → GET /world → SWM load/create VOID → teleporta
      → emite plot-loaded ; grava plot-server:{uuid}
  ... jogador constrói ...  (renova lock+lease ~60s ; save de segurança 3–5min)
  → último jogador sai → PlotLifecycle.leave: PUT /world (save-on-leave) → unload → PUT se mudou
      → DEL lock, libera lease, emite plot-saved, expira plot-server:{uuid}
```

**Kafka × Redis** (master §5/§8): `plot-loaded`/`plot-saved` são **fatos**
(observabilidade/auditoria/anti-corrupção); o estado quente lido em loop é o Redis
(`plot-server:{uuid}` sticky, `plot-lock:{plotId}` fence). Metadados de terreno
(`plot:{plotId}`) são cache read-through — **na Fase 12 opcional**, já que a GUI lê
direto via HTTP; o cache/invalidação por `plot-updated` entra na Fase 13.

## 6. Ciclo de vida do mundo — o núcleo anti-corrupção (master §9)

Objetivo inegociável: **um único writer por vez**, **sem duplicação**, **recuperação
automática** de crash, **nunca perder progresso**. Alcançado por **sticky routing +
lock NX + lease com TTL + version**, combinados.

### 6.1 ENTER (roteado ao `terrenos-XX`, já sticky)
```
1. FENCE (Redis):  setNx("plot-lock:{plotId}", serverId, EX 120)
     falhou  → o mundo está ativo noutra instância (ou lock recém-adquirido):
               nega a entrada / manda mensagem "seu terreno está sendo carregado, tente em instantes"
2. LEASE (DB):     UPDATE plot_worlds SET lease_server=serverId, lease_expires_at=now()+2min
                     WHERE plot_id=:id AND (lease_expires_at < now() OR lease_server=serverId)
     0 linhas → lease vivo de outro server → solta o lock recém-pego e nega (belt-and-suspenders)
3. LOAD:  GET /api/plot/{plotId}/world?server=serverId
     bytes != null → SWM loadWorld(loader, plotId, readOnly=false, VOID props)
     bytes == null → SWM createEmptyWorld(loader, plotId, VOID props)  (terreno novo)
     → generateWorld → teleporta o jogador ao spawn do terreno
4. PUBLISH:  emite plot-loaded {plotId, ownerUuid, server}; grava/renova plot-server:{uuid}
```

### 6.2 DURING (mundo montado)
- **Renovação**: a cada ~60s, `EXPIRE plot-lock:{plotId} 120` **e**
  `UPDATE plot_worlds SET lease_expires_at=now()+2min WHERE lease_server=serverId`.
  Enquanto o processo vive e renova, ninguém mais toma o mundo (o lease sempre está à
  frente do relógio).
- **Save de segurança** a cada **3–5min** (config): serializa a Slime world (SWM
  `asyncSaveWorld` → nosso `saveWorld`) → `PUT /world {version}`; **409** = alguém
  mais novo escreveu (não deveria acontecer com writer único → **alarme**, aborta
  esse save); sucesso → `version++`. Só salva se **dirty** (houve alteração desde o
  último save) — evita reescrever BYTEA idêntico.
- **Janela de perda máxima** = intervalo do save de segurança (≤3–5min). Trade-off:
  menor intervalo = menos perda em crash, mais I/O de BYTEA. Documentar o valor
  escolhido na config `plot`.

### 6.3 LEAVE (último jogador deixa o mundo)
```
5. SAVE-ON-LEAVE:  serializa → PUT /world {bytes, version}          → version++
6. UNLOAD:         Bukkit.unloadWorld(world, /*save*/ false)        (já salvamos no passo 5)
7. SAVE-ON-UNLOAD: se o unload mexeu em algo / dirty ainda setado → PUT /world de novo (consistência)
8. RELEASE:        PUT /world {release:true}  (limpa lease)  ; DEL plot-lock:{plotId}
                   emite plot-saved {plotId, version, server} ; expira plot-server:{uuid}
```
Save-on-leave **e** save-on-unload cobrem qualquer estado sujo entre "último jogador
saiu" e "mundo descarregado". O `onDisable` do plugin roda o mesmo `leave` para
**todos** os mundos montados (shutdown gracioso).

### 6.4 CRASH do `terrenos-XX`
- `plot-lock` (EX 120) e `lease_expires_at` (+2min) **expiram sozinhos** em ≤2min;
  nenhuma outra instância carrega antes disso porque o `setNx` do passo 1 falha
  enquanto o lock existe → **sem 2 writers**.
- Perda máxima = janela desde o último save de segurança (§6.2).
- Um **processo zumbi** (crashou mas ainda tenta um `PUT /world` atrasado) é barrado
  pelo `version`: sua versão está velha → **409**, rejeitado. O lease reforça: se o
  `lease_server` já não é ele, o save é negado. Assim um save fantasma **nunca**
  sobrescreve estado mais novo.

### 6.5 Por que isso garante as três propriedades

| Propriedade | Mecanismo |
|---|---|
| **Single-writer** | `setNx` (fence atômico) + lease DB (`WHERE lease_expirado OR lease_server=eu`) + version→409 no PUT. Três independentes; qualquer um sozinho já barra o segundo writer, os três juntos cobrem falha de Redis, de relógio e de processo zumbi. |
| **No-duplication** | Sticky `plot-server:{uuid}`: enquanto ativo, o jogador **sempre** volta à mesma instância → um `plotId` só é montado num lugar. |
| **Auto-recovery** | Lock + lease têm **TTL**; após crash expiram e o próximo ENTER reassume limpo, sem intervenção. |
| **No-loss** | Save-on-leave + save-on-unload + save de segurança; version impede regressão. |

## 7. Tratamento de erros (fail-safe, não só fail-open)

Diferente do resto da rede (onde falha de infra é *fail-open*), aqui **integridade
vence disponibilidade**:
- **Redis fora no ENTER** (não dá pra pegar `plot-lock`): **negar a entrada** com
  mensagem ("terrenos indisponível, tente já já"). *Fail-closed* — sem o fence não
  há garantia de writer único. O lease no DB sozinho é o fallback de segurança, mas o
  caminho feliz exige o lock.
- **409 no save de segurança/leave**: NÃO reintentar cegamente (poderia clobber).
  Logar `severe` + emitir alarme (métrica/`plot-saved` com flag de conflito); manter
  a cópia local; um humano/anti-corrupção decide. Com writer único isto é "nunca
  deveria ocorrer" → sinal forte de bug.
- **Backend/HTTP fora no save**: reter os bytes em memória e **reintentar** com
  backoff (não descartar); no `onDisable`, tentar um último flush síncrono curto.
- **SWM falha ao (des)serializar**: logar `severe`, **não** descarregar destruindo o
  disco; manter o mundo montado e alarmar (melhor um terreno preso que um corrompido).
- **Falha por terreno** nunca derruba o servidor inteiro (padrão do projeto).

## 8. Testes / verificação (CLAUDE.md)

**Unitário backend** (`PlotServiceTest`, JUnit 5 + Mockito, molde do
`EconomyServiceTest`/`InventoryService`):
1. `create` idempotente (2ª chamada com mesmo owner não duplica).
2. `loadWorld` adquire lease quando livre; **409** quando lease vivo de outro server.
3. `saveWorld` com version correta → grava + version++; version velha → **409**.
4. `releaseWorld` limpa `lease_server`.

**Manual em jogo** (rebuild → **recriar** container `terrenos-01`/`-02`
`docker compose up -d --force-recreate --no-deps terrenos-01 terrenos-02`):
1. **Entrar cria/carrega**: `/terreno` → criar → é levado a um mundo VOID vazio.
2. **Construir + sair salva**: coloca blocos, sai; reentra → blocos preservados.
3. **Reconnect preserva** entre sessões (relog).
4. **Crash**: colocar blocos, esperar passar um save de segurança, **matar** o
   container (`docker kill terrenos-01`), reconectar → sem perda **além** da janela
   de segurança; nenhum mundo "preso".
5. **Sticky**: com 2 instâncias, o mesmo jogador sempre cai na mesma enquanto ativo.
6. **Concorrência (o teste crítico)**: forçar duas tentativas de ENTER do mesmo
   `plotId` (ex.: dono + membro chegando quase juntos, ou dois joins rápidos) →
   **nunca** dois servidores carregam o mesmo plot; o segundo recebe "carregando,
   aguarde" e depois entra no mesmo lugar. Conferir `plot-loaded`/`plot-saved` no
   Kafka UI e um único `lease_server` em `plot_worlds`.

## 9. Divergências do master / da rede (a reconciliar)

- **SWM ausente → Advanced Slime Paper (fork), decidido**: SlimeWorldManager **não
  está no repo**. **Decisão do dono:** o fleet `terrenos` roda o **Advanced Slime
  Paper (ASP)** — fork do Paper com a Slime API embutida — em vez de um plugin
  externo; o resto da rede segue Paper stock. Implica **trocar a imagem base só do
  `terrenos`** (`TYPE: CUSTOM`/imagem dedicada), compilar o `crystal-plot` contra a
  API do ASP como **provided**, e implementar o **custom `SlimeLoader`** sobre o
  Postgres. **Primeiro passo do plano:** fixar a versão de ASP compatível com
  1.21.x e validar load/unload + persistência de mob/baú. (ASP é o caminho
  mantido para 1.21; o SWM-plugin clássico está sem suporte em versões novas.)
- **`RedisClient` sem lock atômico**: hoje há `set`/`setex`/`del`, mas **não**
  `SET … NX EX`. **Adicionar** `setNx(key, val, ttl): boolean` (Lettuce
  `SetArgs.Builder.nx().ex(seconds)`) — sem isso o fence do §6.1 não é atômico.
- **Tipo de servidor `terrenos` novo** + **mundo VOID**: os fleets atuais colam
  schematic via `crystal-worldinit`; terrenos sobe **vazio** e o SWM gera por cima.
  Novo `SERVER_TYPE`, novas linhas de compose, novos binds (SWM + plugins RankUP).
- **Primeiro blob BYTEA / binário**: o único blob versionado hoje é o inventário —
  **texto** (`content` String, base64). `plot_worlds.slime_data` é **BYTEA** e o
  endpoint `/world` transporta bytes (via `bytesBase64` no JSON para reusar o `send`
  do `BackendHttpClient`; um caminho `octet-stream` fica para depois se o tamanho
  exigir). Optimistic-lock reaproveita o padrão do `InventoryService` (compara
  `version` → 409), agora com **lease** acoplado.
- **Primeiro roteador sticky**: o `crystal-bungee` só faz least-loaded (`LobbyRouter`).
  A Fase 12 adiciona `TerrenosRouter` (sticky por `plot-server:{uuid}`). O master §7.2
  e a **Fase 10** preveem um `FleetRouter` genérico; **a Fase 10 ainda não tem spec**
  — se o `FleetRouter` chegar antes, o `TerrenosRouter` deve ser a variante sticky
  dele, não um paralelo. Reconciliar a ordem com a Fase 10.
- **Fail-closed no ENTER** diverge do *fail-open* padrão da rede: aqui a integridade
  do mundo justifica negar a entrada quando o Redis/lock não está disponível. Decisão
  explícita a validar com o time.
- **SDK sem facade de plot / sem `plots()`**: adiciona `PlotData`, `PlotClient`,
  `crystal.plots()` (2º facade por feature, após `economy()` da Fase 8) e os métodos
  HTTP de blob na `BackendHttpClient`. 409 continua via `BackendException.statusCode()`
  (não há `ConflictException` no SDK; mantido da Fase 8).

## 10. Arquivos afetados (resumo)

**NOVO** `backend/rankup-service/.../plot/{api,application,domain}/` —
`PlotController`, `PlotService`, `PlotEntity`, `PlotWorldEntity`, `PlotRepository`,
`PlotWorldRepository`; `shared/messaging/KafkaTopics` (+ `PLOT_LOADED`, `PLOT_SAVED`);
`db/migration/V4__rankup_plots.sql`; teste `PlotServiceTest`.
- `backend/api-gateway/.../application.yml` (+ rota `/api/plot/**` → `lb://rankup-service`).
- `infra/kafka/create-topics.sh` (+ `plot-loaded`, `plot-saved`).
- `docker-compose.yml` (+ fleet `terrenos-01/-02` VOID; bind do SWM + jars RankUP;
  `EXTERNAL_PLUGINS/SlimeWorldManager-<versão>.jar`).
- `plugins/crystal-core` — `http/PlotData`, `http/PlotClient`, `http/BackendHttpClient`
  (métodos plot + blob), `redis/RedisClient` (**`setNx`**), `messaging/KafkaTopics`
  (`PLOT_LOADED`/`PLOT_SAVED`), `CrystalCore` (`plots()`).
- **NOVO** `plugins/crystal-plot/` (`pom.xml`, `plugin.yml` `depend:[SlimeWorldManager,
  LuckPerms]`, `CrystalPlotPlugin`, `SlimeLoaderPostgres`, `PlotLifecycle`,
  `gui/PlotMenu`, `commands/PlotCommand`); `plugins/pom.xml` (+ módulo).
- `plugins/crystal-bungee` — `TerrenosRouter` (sticky), fiação no `CrystalBungeePlugin`,
  canal/plugin-message "ir ao terreno".
```
