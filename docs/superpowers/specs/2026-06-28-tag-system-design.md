# Design — Sistema de tags: nametag acima da cabeça + comando `/tag` (admin)

Data: 2026-06-28
Branch base: `feat/lobby-pets-e-hub-sem-ciclo`

## 1. Problema

Hoje a "tag" (cargo) de um jogador é definida na config central `chat` (`roles`:
`id`, `permission`, `weight`, `prefix`, `nameColor`) e resolvida **por permissão**
(o `CargoResolver` escolhe o cargo de maior `weight` cuja permissão o jogador tem).
Essa tag já aparece no **chat**, na **tab**, na **sidebar** do lobby e no `/profile`.

Faltam duas coisas:

1. **Nametag acima da cabeça** — a tag não aparece in-world, sobre o nome do jogador.
2. **Comando admin `/tag`** — não há como **selecionar/atribuir** uma tag de teste
   (override), nem **editar** as definições de cargo em jogo.

## 2. Objetivos e não-objetivos

**Objetivos**
- Renderizar a tag acima da cabeça (prefixo na linha do nome, via scoreboard team).
- `/tag` (admin) — GUI para selecionar a própria tag de teste (override).
- `/tag <jogador>` (admin) — selecionar/atribuir a tag de outro jogador.
- `/tag editar` (admin) — GUI para **editar prefixo/cor/peso** e **criar/excluir** cargos.
- O override de teste deve aparecer **em todos os lugares** (nametag, tab, chat,
  sidebar, `/profile`), de forma consistente.
- Tudo restrito a administradores (`crystal.tag.admin`, `default: op`).

**Não-objetivos**
- Permitir que jogadores comuns troquem de tag (fica para o futuro).
- Pub/sub para propagação instantânea entre servidores (a propagação por timer em
  ≤2s é suficiente; ver §7).

## 3. Decisões de design (já acordadas)

| Tema | Decisão |
|------|---------|
| Tag padrão | Continua resolvida **por permissão** (maior `weight`). |
| Override | Tag de teste guardada no **Redis**, **sobrescreve** a resolução por permissão. |
| Persistência do override | Redis hash `tag:overrides` (campo = UUID, valor = id do cargo). |
| Nametag | **Prefixo na linha do nome** via scoreboard team (`[VIP] Nome`). |
| Interface do `/tag` | **GUI** (menu de itens), seguindo o padrão GUI-first do projeto. |
| Casa do código | **Novo plugin `crystal-tag`** (nametag + comando + GUIs). |
| `/tag editar` | Escreve via `putConfig("chat", …)` (endpoint **já existente**) → hot-reload. |
| Alcance do override | **Todos os lugares**: nametag, tab, chat, sidebar, `/profile`. |
| Escopo do `/tag editar` | Editar prefixo/cor/peso **e** criar/excluir cargos. |

## 4. Arquitetura

### 4.1 `CargoResolver` (crystal-core) — resolução com override
Novo overload, sem quebrar o existente:

```java
// overrideId pode ser null/blank. Se apontar para um cargo definido, vence;
// senão, cai na resolução por permissão atual.
public static Cargo resolve(RemoteConfig chatConfig, String overrideId, Predicate<String> hasPermission)
```

- `resolve(chatConfig, hasPermission)` continua existindo e passa a delegar para o
  overload com `overrideId = null`.
- Override apontando para cargo inexistente/apagado → ignorado (fallback por permissão).

### 4.2 Override no Redis — hash `tag:overrides`
Adicionar ao `RedisClient` (mesma forma das outras ops):

```java
public void hset(String key, String field, String value)
public String hget(String key, String field)
public void hdel(String key, String field)
public Map<String,String> hgetAll(String key)
```

Constantes compartilhadas (em crystal-core, p.ex. `CargoResolver` ou nova classe
`TagOverrides`): `OVERRIDES_KEY = "tag:overrides"`.

Padrão de leitura para **não** fazer N chamadas Redis na main thread:
- Loops por tick (tab, sidebar, nametag): **um `hgetAll` por tick** (1 round-trip).
- Pontos avulsos (chat por mensagem, `/profile`): **um `hget`**.

### 4.3 Novo módulo `plugins/crystal-tag`
Plugin Paper, `api-version: '1.21'`, `depend: [LuckPerms]`, shade do `crystal-core`
espelhando o `pom.xml` de um plugin existente (p.ex. `crystal-tab`). Entradas a
adicionar: módulo em `plugins/pom.xml` e alvo de jars no `Makefile` (`make plugins`).
Roda em todo servidor de jogo (junto de lobby/parkour/tab/chat).

**`NametagService`** (Listener + task)
- A cada ~40 ticks e no `PlayerJoinEvent`: lê `hgetAll(tag:overrides)` uma vez e,
  para cada jogador online, resolve o cargo efetivo.
- Opera sobre o `player.getScoreboard()` **atual** de cada jogador (convive com a
  sidebar do lobby, cujos times são `line0..line6`; sem conflito de nomes).
- Garante 1 time por cargo `ct_<id>` (nome ≤ 16 chars) com:
  - `prefix(parse(cargoPrefix) + space)` — o `[TAG]` mostrado antes do nome.
  - cor do nome best-effort: `NamedTextColor.nearestTo(...)` a partir de `nameColor`.
- Cada jogador é adicionado como **entry** (nome) do time do seu cargo efetivo;
  sem cargo → time `ct_default` (sem prefixo).
- Ao trocar de cargo, o jogador é movido de time. `onQuit`: remove a entry.
- Falha por jogador é logada (`warning`), nunca derruba o servidor (padrão do tab).

> Observação 1.21: o nome acima da cabeça aceita só **uma** `NamedTextColor` pelo
> team. Hex (`<#b14aed>`) é aproximado via `nearestTo`. O `prefix` (o `[TAG]` em si)
> aceita cor cheia por ser um `Component`.

### 4.4 Consumidores honram o override (centralizado, DRY)
Cada consumidor passa a ler o override e usar o novo overload do `CargoResolver`:

| Consumidor | Leitura | Mudança |
|------------|---------|---------|
| `crystal-chat` | `hget` por mensagem | resolve efetivo antes de publicar `prefix`/`nameColor` |
| `crystal-tab` | `hgetAll` 1×/tick | resolve efetivo por jogador no `refresh()` |
| `crystal-lobby` `LobbyScoreboard` | `hgetAll` 1×/tick no loop | usa overload no `lines()` |
| `crystal-profile` | `hget` 1× (já async) | usa overload no `/profile` |
| `crystal-tag` `NametagService` | `hgetAll` 1×/tick | — |

A lógica de resolução continua única (no `CargoResolver`); cada consumidor só
fornece o `overrideId` lido do Redis.

### 4.5 Comando `/tag` + GUIs (crystal-tag)
Permissão única: `crystal.tag.admin` (`default: op`). Sem permissão → mensagem e fim.
Layout de GUI copiado do lobby (`MenuHolder(String)` + `framedSize`/`bodySlots`/
`barCenter`/`barLeft`/`backButton` + chaves PDC + `onClick(InventoryClickEvent)`).

- **`/tag`** → GUI seletora para o próprio admin. Lista **todos** os cargos definidos
  (lidos de `chat.roles`); item clicado → `hset tag:overrides <uuid> <cargoId>`;
  botão "remover override" → `hdel` (volta ao padrão por permissão); cargo atual com
  glow + `§a✔ Selecionado`.
- **`/tag <jogador>`** → mesma GUI mirando outro jogador. Alvo online: UUID via
  Bukkit; offline: UUID offline (`UUID.nameUUIDFromBytes("OfflinePlayer:"+nome)`),
  como o chat já faz. Grava/limpa o override do alvo.
- **`/tag editar`** → GUI de edição das definições:
  - Lista os cargos atuais + botão "criar cargo".
  - Clicar num cargo abre um sub-menu: **editar prefixo**, **editar cor do nome**,
    **editar peso**, **excluir cargo**.
  - Editar valores usa o fluxo **digitar-no-chat** (igual ao renomear pet do lobby:
    `awaiting*` + `AsyncChatEvent` cancelado, aplica na main thread).
  - "Criar cargo" pede um `id` no chat; cria entrada com defaults
    (`permission = "tag."+id`, `weight = 0`, `prefix = ""`, `nameColor = ""`).
  - Cada alteração muta uma cópia do mapa `roles` e chama
    `crystal.backend().putConfig("chat", chatMap)` **off-thread** → backend persiste,
    atualiza cache e emite `config-updated` → todos os plugins recarregam `roles`.

### 4.6 `plugin.yml` (crystal-tag)
```yaml
name: CrystalTag
main: com.redecrystal.tag.CrystalTagPlugin
api-version: '1.21'
depend: [LuckPerms]
commands:
  tag:
    description: Selecionar/atribuir/editar tags (admin)
    usage: /tag [jogador|editar]
permissions:
  crystal.tag.admin:
    description: Selecionar, atribuir e editar tags
    default: op
```

## 5. Fluxo de dados

**Selecionar/atribuir tag**
```
/tag [jogador] → GUI → clique no cargo
  → hset tag:overrides <uuid> <cargoId>   (ou hdel para remover)
  → ticks de refresh (tab/sidebar/nametag) leem o hash → tag efetiva renderizada (≤2s)
  → chat usa na próxima mensagem; /profile na próxima chamada
```

**Editar definições**
```
/tag editar → GUI → prompt no chat → muta mapa roles
  → PUT /api/config/chat  (putConfig já existente)
  → backend persiste + atualiza cache Redis + emite config-updated
  → ConfigProvider hot-reload → chat/tab/profile/lobby/tag recarregam roles
```

## 6. Tratamento de erros (fail-open, padrão do projeto)
- Redis indisponível na leitura do override → trata como "sem override" (cai na
  resolução por permissão). Nada quebra.
- `putConfig` falha em `/tag editar` → mensagem ao admin (`§cFalha ao salvar…`),
  estado em memória não é considerado salvo; sem crash.
- Override apontando para cargo apagado → fallback por permissão.
- Render de nametag falha para um jogador → `warning` no log, continua os demais.
- Texto digitado pelo admin (prefixo) **nunca** é re-parseado de volta como entrada
  de outro jogador; é tratado como string de config (MiniMessage/legacy só na
  renderização).

## 7. Propagação entre servidores
A escrita do override é imediata no Redis (compartilhado pela rede). Cada servidor
relê o hash no seu próprio tick (~2s), então a tag aparece em ≤2s mesmo que o alvo
esteja em outro servidor. Pub/sub para atualização instantânea fica como evolução
futura (não necessário agora). A edição de cargos propaga via `config-updated`
(Kafka), que já é instantâneo.

## 8. Testes
- **Unitário** (JVM puro, sem Bukkit): `CargoResolverTest` cobrindo o overload com
  override — override válido vence; override inexistente cai no fallback por
  permissão; sem override = comportamento atual.
- **Manual em jogo** (CLAUDE.md): rebuild do(s) plugin(s) → **recriar** o container
  (`docker compose up -d --force-recreate --no-deps lobby-01 …`). Roteiro:
  1. `/tag` → selecionar um cargo → confirmar tag acima da cabeça, no tab, no chat,
     na sidebar e no `/profile`.
  2. "remover override" → volta ao cargo por permissão em todos os lugares.
  3. `/tag <outro>` → confirma override no outro jogador.
  4. `/tag editar` → editar prefixo/cor/peso e criar/excluir cargo → confirmar
     hot-reload em chat/tab/nametag sem restart.

## 9. Arquivos afetados (resumo)
- **Novo** `plugins/crystal-tag/` (pom.xml, plugin.yml, `CrystalTagPlugin`,
  `NametagService`, GUIs do `/tag`).
- `plugins/crystal-core` — `CargoResolver` (overload), `RedisClient` (ops de hash),
  (opcional) `TagOverrides` com a constante da chave.
- `plugins/crystal-chat`, `plugins/crystal-tab`, `plugins/crystal-lobby`
  (`LobbyScoreboard`), `plugins/crystal-profile` — honrar o override.
- `plugins/pom.xml` + `Makefile` — registrar o novo módulo.
- (Backend: **nenhuma** mudança — `putConfig`/`PUT /api/config/{key}` já existem.)
