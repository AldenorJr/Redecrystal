# Holograma de rede (`/hologram`) + melhoria do texto do seletor de Parkour

Data: 2026-06-29
Status: aprovado para implementação

## Objetivo

Duas entregas:

1. **GUI** — melhorar o texto do item "Parkour" no menu "Modos de Jogo" do lobby.
2. **Holograma de rede** — novo plugin genérico `crystal-hologram` que renderiza
   hologramas de apresentação configuráveis por comando, replicados a todos os
   servidores via config central + hot-reload (Kafka), sobrevivendo a restart.

Texto de jogador em **PT**; código e comentários em **inglês** (ver
`docs/CODING_STANDARDS.md`).

---

## Parte 1 — Texto do seletor de Parkour

Arquivo: `plugins/crystal-lobby/src/main/java/com/redecrystal/lobby/listener/LobbyHotbar.java`,
método `openGames()` (chamada ao helper `item(Material.FEATHER, ...)`).

**Hoje:**
```
§aParkour
  §7Teste a sua agilidade
  §eClique para ir ao início
  §7e pise na placa de ferro
```

**Proposto:**
```
§a✦ Parkour
  §7Desafie sua agilidade e seus
  §7reflexos em um percurso cheio
  §7de obstáculos e checkpoints.
  (linha em branco)
  §eClique para começar — pise
  §ena placa de ferro no início!
```

Restrições:
- Mantém o padrão legacy (`§`) e o helper `item(...)` existentes — só trocam as strings.
- A ação do clique permanece `p.performCommand("parkour")` (sem alteração).
- A linha em branco é uma string vazia `""` passada ao varargs de lore (o helper já
  trata cada argumento como uma linha).

---

## Parte 2 — Módulo `plugins/crystal-hologram`

Plugin Paper 1.21 genérico, instalável em qualquer servidor (login, lobbies e
futuros servidores de jogo). Espelha o bootstrap de `crystal-tag` e o padrão de
render de `crystal-parkour/ParkourHologram.java`.

### Comando

`/hologram` (sentido de "rede", não "lobby"), permissão `crystal.hologram.admin`
(default `op`):

| Subcomando | Efeito |
|---|---|
| `/hologram set <id> <texto>` | Cria/atualiza o holograma `<id>` na posição atual do jogador. `\n` (literal: barra + n) vira nova linha; cores legacy com `&`. |
| `/hologram move <id>` | Move o holograma `<id>` para a posição atual (mantém o texto). |
| `/hologram remove <id>` | Remove o holograma `<id>` da rede. |
| `/hologram list` | Lista os ids existentes (e o mundo de cada um). |

- `set` e `move` exigem um `Player` (precisam de localização). `remove` e `list`
  aceitam o console também (só dependem do id).
- Mensagens de erro/uso em PT, via Adventure `Component` + `NamedTextColor`
  (espelhar `LobbyCommand`).
- Validação: `id` não-vazio, sem espaços; `texto` obrigatório no `set`.

### Persistência e replicação

- Chave de config nova: `holograms` (independente da chave `lobby`).
- `set/move/remove` fazem **read-modify-write** em task assíncrona
  (`runTaskAsynchronously`), exatamente como `/lobby setspawn`:
  1. Lê `crystal.configProvider().get("holograms").config()`.
  2. Muta a lista em memória.
  3. `crystal.backend().putConfig("holograms", cfg)`.
  4. Responde ao jogador no fim (mensagem em PT).
- O backend emite `config-updated` no Kafka → `ConfigProvider` atualiza o cache e
  dispara os listeners → cada servidor com o plugin re-renderiza. Sem restart.

### Formato no backend

```json
{
  "items": [
    {
      "id": "boasvindas",
      "world": "world",
      "x": 100.5,
      "y": 65.0,
      "z": -200.3,
      "lines": ["&b&lRede Crystal", "&fBem-vindo ao servidor!"]
    }
  ]
}
```

- Coordenadas arredondadas a 2 casas (`Math.round(v*100)/100`), igual aos outros plugins.
- `world` é o nome do mundo onde o `set`/`move` foi executado.
- `lines` é a lista de linhas já separadas (texto do comando dividido em `\n`),
  cada uma com cores legacy `&` ainda não traduzidas (tradução acontece no render).
- Yaw/pitch não são guardados (billboard CENTER sempre encara o jogador).

### Classes

- **`HologramDef`** — DTO puro: `record HologramDef(String id, String world, double x,
  double y, double z, List<String> lines)`. A serialização ⇄ `Map` fica no
  `HologramStore`, não no record.
- **`HologramStore`** (`final`) — fala com o backend, métodos síncronos (o
  `HologramCommand` é quem agenda o async, mantendo o store testável sem scheduler):
  - `List<HologramDef> all()` — lê config `holograms`, mapeia `items` → lista de DTOs
    (fail-open: lista vazia se ausente/erro).
  - `void put(HologramDef def)` / `remove(String id)` — read-modify-write da lista +
    `crystal.backend().putConfig("holograms", cfg)`. (`move` = `all()` → acha o id →
    `put` com novas coords.)
- **`HologramRenderer`** (`final`) — espelha `ParkourHologram`:
  - Tag `HOLO_TAG = "crystal_holo"`.
  - `render(List<HologramDef>)` — limpa tudo e spawna um `TextDisplay` por def cujo
    `world` exista neste servidor; ignora (com `log.warn`) defs de mundos ausentes.
  - `clearAll()` / `removeOrphans()` — limpeza no enable/disable/reload.
  - `spawn(...)` — `TextDisplay` com `Billboard.CENTER`, `setSeeThrough(true)`,
    `setPersistent(false)`, `addScoreboardTag(HOLO_TAG)`, texto multilinha
    (`Component` por linha unidas com `Component.newline()`), cores via
    `LegacyComponentSerializer.legacyAmpersand()`.
  - Posição: o holograma é spawnado na localização exata gravada (o jogador escolhe
    onde pisar ao usar `set`/`move`). Sem offset vertical automático (diferente do
    parkour, onde o offset é sobre a placa).
- **`CrystalHologramPlugin`** (`final extends JavaPlugin`) — espelha `CrystalTagPlugin`:
  - `onEnable`: `CrystalCore.bootstrap(CrystalConfig.fromEnv())`;
    `configProvider().preload("holograms")`; instancia `HologramStore` e
    `HologramRenderer`; renderiza o estado atual; registra
    `configProvider().onChange("holograms", cfg -> render(...))`; registra o comando.
  - O `onChange` roda fora da main thread (callback do Kafka) → o re-render deve
    voltar à main thread com `getServer().getScheduler().runTask(this, ...)` antes de
    tocar a API de entidades.
  - `onDisable`: `renderer.clearAll()` e `crystal.close()`.

### Build, registro e deploy

- Adicionar `crystal-hologram` à lista `<modules>` de `plugins/pom.xml`.
- `pom.xml` do módulo espelha `crystal-tag` (parent `plugins-parent`, deps
  `crystal-core` + `paper-api`, shade plugin, `finalName = ${project.artifactId}`).
  **Sem** dependência de LuckPerms.
- `plugin.yml`: `name: CrystalHologram`, `main`, `api-version: '1.21'`, comando
  `hologram`, permissão `crystal.hologram.admin` (default op).
- Montar o jar no `docker-compose` nos serviços que devem renderizar — **login e
  lobbies** por enquanto — seguindo o padrão dos commits recentes
  (`crystal-skin`/`crystal-tag`). Recriar containers para validar (a imagem copia o
  jar montado no boot).

---

## Restrições de clean code (do guia do projeto)

- Nunca bloquear a main thread: HTTP/`putConfig` em `runTaskAsynchronously`; voltar a
  `runTask` antes de tocar entidades.
- DTO é `record` (`HologramDef`); serviços/utilitários são `final`.
- Fallback em erro de backend (fail-open: lista vazia, nunca derruba o servidor).
- Limpar o que cria (TextDisplay) no quit/disable/reload, via tag.
- Comentar o *porquê*, não o *quê*.
- Texto de jogador em PT; código/comentários em inglês.

## Critérios de sucesso

1. Item "Parkour" no menu "Modos de Jogo" exibe o novo texto.
2. `/hologram set <id> <texto>` cria um TextDisplay multilinha colorido na posição do
   jogador, persistido no backend.
3. O mesmo holograma aparece em todos os servidores com o plugin, sem restart
   (hot-reload), e ressurge após restart.
4. `/hologram move`, `/hologram remove` e `/hologram list` funcionam e replicam.
5. Config com `world` inexistente no servidor não causa erro (apenas warn).
6. `mvn -pl plugins/crystal-hologram -am compile` e `make plugins` compilam.

## Fora de escopo (YAGNI)

- GUI de gerenciamento de hologramas (comando admin basta).
- Hologramas com conteúdo dinâmico/animado (ex.: placar) — o parkour já tem o seu.
- Edição de linha individual (`addline`/`setline`) — `set` reescreve tudo.
- Yaw/pitch/escala/billboard configuráveis por comando.
