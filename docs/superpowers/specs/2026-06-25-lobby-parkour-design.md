# Spec — Parkour no lobby, hotbar, chat /tell, inventário por tipo

**Data:** 2026-06-25
**Status:** Aprovado para implementação

## 1. Objetivo e escopo

Quatro entregas, todas reusando a infra existente da RedeCrystal (config central com
hot-reload, Redis, Kafka, gateway/SDK, `leaderboard:*`):

1. **crystal-parkour** (plugin novo, roda nos lobbies) — parkour jogado no próprio
   lobby, curso configurável depois, cronômetro e **ranking de menor tempo**.
2. **crystal-lobby** (estender) — **hotbar travada** de 4 itens.
3. **crystal-chat** (estender) — **`/tell`** privado cross-server + **toggle** para
   desativar recebimento.
4. **crystal-inventory** (refactor) — inventário **por tipo de servidor (mapa)**,
   **opt-in**; quando o tipo não sincroniza, **não faz nada**.

**Fora de escopo:** servidores de minigame separados, fila/matchmaking/sessões,
party, cosméticos reais, NPCs. O parkour roda no mundo do lobby (`WORLD_LOBBY`),
com coordenadas iguais em todas as instâncias.

## 2. Arquitetura

| Peça | Onde roda | Backend |
|------|-----------|---------|
| crystal-parkour | lobby-* | novo contexto `parkour` em core-service |
| crystal-lobby (hotbar) | lobby-* | usa profile existente |
| crystal-chat (/tell) | lobby-* (e demais) | Redis (online_players, tells_disabled) + Kafka |
| crystal-inventory (refactor) | qualquer tipo | contexto `inventory` existente |

Plugins dependem do `crystal-core` (shade herdado). Backend = bounded contexts no
core-service (decisão "core-service por enquanto"). **Rebuild de plugin após mexer no
SDK deve usar `clean`** (build incremental pode shadear crystal-core velho).

## 3. Backend — contexto `parkour` (core-service)

**Migração** `V5__parkour_times.sql`:
```sql
CREATE TABLE parkour_times (
    player_uuid   UUID         PRIMARY KEY,
    username      VARCHAR(16),
    best_time_ms  BIGINT       NOT NULL,
    achieved_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_parkour_best ON parkour_times (best_time_ms ASC);
```

**Redis:** `leaderboard:parkour` (ZSET, score = best_time_ms, menor = melhor).
Mantido em sincronia com o Postgres a cada recorde.

**Endpoints** (via gateway, `/api/parkour/**`):
- `POST /api/parkour/time` `{uuid, username, timeMs}` → grava só se for **melhor**
  (ou primeiro) tempo; atualiza Postgres + ZADD Redis. Retorna
  `{bestTimeMs, isRecord, rank}` (rank = posição no ranking, 1-based).
- `GET /api/parkour/top?limit=10` → top N por menor tempo (lê Postgres
  `ORDER BY best_time_ms ASC LIMIT N`): `[{rank, username, timeMs}]`.
- `GET /api/parkour/best/{uuid}` → melhor tempo pessoal, ou 404 se não houver.

**SDK (BackendHttpClient):** `submitParkourTime(uuid, username, timeMs) -> ParkourResult`,
`parkourTop(limit) -> List<ParkourEntry>`, `parkourBest(uuid) -> Long|null`.

## 4. crystal-parkour (plugin)

**Curso na config central** chave `parkour`:
```json
{
  "world": "world",
  "start":  {"x": 0.5, "y": 65, "z": 0.5, "yaw": 0, "pitch": 0},
  "checkpoints": [{"x": 10.5, "y": 66, "z": 0.5}],
  "finish": {"x": 20.5, "y": 65, "z": 0.5},
  "fallY": 55,
  "radius": 1.5
}
```
Hot-reload via `config-updated` (já existe). Sem config → plugin fica ocioso (curso
"não configurado").

**Comandos admin** (`crystal.parkour.admin`) — persistem na config central
(PUT `/api/config/parkour` pelo gateway):
`/parkour setstart`, `/parkour addcheckpoint`, `/parkour setfinish`,
`/parkour clear`, `/parkour reload`. (Posições pegas da posição atual do admin.)

**Comandos jogador:** `/parkour` (tp ao start e zera run), `/parkour top`,
`/parkour reset` (cancela run).

**Fluxo (detecção por distância ao ponto, raio `radius`, no `PlayerMoveEvent`):**
- entra no **start** → marca `startNanos`, limpa último checkpoint, action bar "Vai!";
- passa por **checkpoint** → salva índice; se cair abaixo de `fallY` → tp ao último
  checkpoint (ou start), cronômetro **continua**;
- entra no **finish** → calcula `timeMs`, chama `submitParkourTime`, mostra
  title com tempo + posição; se recorde, anuncia; tp ao start; encerra run.
- `PlayerQuitEvent` / `/parkour reset` → cancela run.

**Estado:** `Map<UUID, Run>` em memória (startNanos, lastCheckpoint). Action bar
mostra tempo decorrido enquanto corre.

## 5. crystal-lobby — hotbar travada

No `PlayerJoinEvent` (agendado +1 tick para vencer o load) e `PlayerRespawnEvent`:
limpa o inventário e seta:
- **Slot 0** — Bússola "§bJogos" → abre GUI (inventário chest) com ícone **Parkour**;
  clicar = fecha e executa o fluxo do `/parkour` (tp ao start). Extensível.
- **Slot 4** — Cabeça do jogador "§aPerfil" → mostra stats (via `backend.getProfile`).
- **Slot 7** — "Esconder Jogadores" (alterna §7cinza/§averde) → `hidePlayer`/`showPlayer`
  para todos online; estado por jogador.
- **Slot 8** — "§dLobby/Cosméticos" → GUI placeholder (stub).

**Travas (apenas no servidor de lobby):** cancela `PlayerDropItemEvent`,
`InventoryClickEvent` (inventário do jogador), `PlayerSwapHandItemsEvent`,
move de itens da hotbar. GUIs são read-only (cancela clicks, trata ações).

**Interação com inventário:** resolvida pela seção 7 — no lobby o `crystal-inventory`
não age (opt-out), então a hotbar fixa prevalece sem conflito.

## 6. crystal-chat — /tell + toggle

Reusa o tópico `player-chat` com envelope estendido:
`{scope: "global"|"tell", from, fromUuid, targetUuid, message}`.

- **`/tell <jogador> <msg>`** (alias `/msg`, `/w`): resolve `targetUuid` pelo
  esquema offline determinístico (`UUID.nameUUIDFromBytes("OfflinePlayer:"+nome)`).
  Antes de enviar: `SISMEMBER online_players targetUuid` (Redis) — se ausente →
  "§cjogador offline"; `SISMEMBER tells_disabled targetUuid` — se presente →
  "§cesse jogador não recebe mensagens". Senão publica tell + eco ao remetente
  ("§7você → alvo: msg"). Guarda último remetente por alvo (em memória) para `/r`.
- **`/r <msg>`** — responde ao último que te mandou tell.
- **Consumer:** `scope=="global"` → broadcast (comportamento atual);
  `scope=="tell"` → entrega só ao jogador local com `targetUuid` (se online e não
  em `tells_disabled`).
- **`/telltoggle`** — alterna `SADD`/`SREM tells_disabled <uuid>` (Redis, compartilhado);
  responde "§erecebimento de tells desativado/ativado".

## 7. crystal-inventory — por tipo de servidor + opt-in

- **Namespace = `crystal.config().serverType()`** (não mais `"lobby"` fixo). Cada tipo
  (factions, rankup, …) tem inventário **isolado** na tabela `(player_uuid, server_type)`.
- **Opt-in:** no `onEnable`, lê `configProvider.get(serverType).bool("syncInventory", false)`.
  Se **false/ausente → não registra listeners (no-op)** e loga "inventory sync disabled
  for type=<tipo>". Se true → load no join, save no quit (com versionamento otimista,
  como hoje).
- **Efeito:** lobby (sem `syncInventory`) → não faz nada → hotbar fixa segura. Tipos de
  jogo futuros → sincronizam seu próprio inventário.

## 8. Eventos / config / chaves

- Config novas: `parkour` (curso). Flag `syncInventory` por tipo (ex.: `factions`).
- Redis: `leaderboard:parkour` (ZSET), `tells_disabled` (SET), reuso `online_players`.
- Kafka: reuso `player-chat` (campo `scope`). Sem tópicos novos.

## 9. Verificação

- **Backend parkour:** curl submit/top/best; recorde só melhora com tempo menor;
  top ordenado asc; rank correto.
- **Parkour com bot:** configurar curso curto/plano por comando admin; bot anda
  start→finish; tempo gravado; `/parkour top` lista; segundo bot pior fica abaixo.
- **Hotbar:** bot recebe os 4 itens; drop/move bloqueados; bússola abre GUI.
- **/tell:** bot A (lobby-01) → `/tell` bot B (lobby-02): B recebe; B `/telltoggle`;
  A recebe aviso de bloqueio; A `/tell` offline → aviso.
- **Inventário:** lobby = no-op (log "disabled"); setar `lobby.syncInventory=true`
  via config → passa a sincronizar (toggle prova o gating).

## 10. Riscos / notas

- O teste antigo do `crystal-inventory` no lobby (InvBot) deixa de sincronizar por
  design (lobby = opt-out). Esperado.
- Detecção por `PlayerMoveEvent` + distância é barata para 1 curso; AABB se precisar.
- Hotbar vs inventário resolvido por opt-in (seção 7).
- Bots não pulam parkour real — verificação usa curso plano / teleporte ao finish.
