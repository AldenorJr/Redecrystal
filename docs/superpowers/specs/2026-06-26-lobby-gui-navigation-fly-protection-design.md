# Lobby — GUIs, navegação entre lobbys, voo e proteção

**Data:** 2026-06-26
**Plugins afetados:** `crystal-lobby`, `crystal-login`

## Objetivo

Melhorar a experiência do lobby, mantendo a convenção do projeto de que toda
interação do jogador acontece por **menu GUI** (ver README → "Convenção de UX"):

1. A caveira da hotbar abre um **GUI de Perfil** (hoje só manda no chat).
2. Um novo item de hotbar abre um **seletor de lobbys** em GUI, que reflete
   dinamicamente os lobbys online (aparecem/somem sozinhos conforme sobem/caem).
3. O menu "Modos de Jogo" existente (bússola) permanece como seletor de
   modos/servidores de jogo (Parkour hoje; Rankup e outros depois).
4. Jogadores com permissão de voo entram voando no lobby **e** no login.
5. Qualquer jogador que ultrapasse um raio de 200 blocos do spawn volta ao spawn.
6. Qualquer interação com blocos é cancelada no lobby **e** no login.

## Não-objetivos (YAGNI)

- Atualizar o seletor de lobbys em tempo real enquanto o inventário está aberto
  (reabrir já reflete o estado atual).
- Criar os servidores de modo de jogo (ex: Rankup) — apenas manter o menu
  extensível; Parkour continua como está.
- Comando `/fly` — voo é automático por permissão.
- Tornar o spawn do login configurável via backend — usa o spawn do mundo.

## Arquitetura

### Navegação entre servidores (plugin messaging)

Mover um jogador entre servidores backend passa pelo proxy Velocity. O
`crystal-lobby` (Paper) registra o canal de saída **`BungeeCord`** (suportado
pelo Velocity) no `onEnable` e, ao clicar num lobby no GUI, envia uma mensagem
com subcanal **`Connect`** + nome do servidor de destino.

O nome do servidor no Velocity é igual ao `serverId` do registry (ex:
`lobby-02`), então a lista de destinos vem de `crystal.backend().listServers("lobby")`.

### Seletor de lobbys (dinâmico)

- GUI montado **sob demanda** a cada abertura.
- Fonte: `crystal.backend().listServers("lobby")` (chamada HTTP → executada
  async; o inventário é aberto/preenchido na thread principal).
- Filtra para `isOnline()` (status `ONLINE`).
- Exclui / destaca o servidor atual via `crystal.config().serverId()` — o lobby
  atual aparece destacado e **não-clicável**.
- Cada lobby vira um item com lore `online/max` (`onlinePlayers()/maxPlayers()`).
- Clicar num lobby diferente: fecha o inventário e envia `Connect` para aquele
  `serverId`.
- Comportamento dinâmico: como o GUI é reconstruído consultando o registry ao
  vivo, um `lobby-03` recém-criado aparece na próxima abertura e um que saiu
  (scale-down / heartbeat morto, removido pelo reaper) some — sem config
  estática nem mudança de código.

### GUI de Perfil

- A caveira (slot 4) abre um inventário **read-only** com a cabeça do próprio
  jogador e lore mostrando **Rank / Level / Coins**.
- Dados via `crystal.backend().getProfile(uuid)` (async, como já é feito hoje no
  `showProfile`); o inventário é aberto na thread principal após a resposta.

### Voo automático

- Permissão `crystal.fly` (default `op`).
- No **join** (lobby e login): se `player.hasPermission("crystal.fly")`, então
  `setAllowFlight(true)` e `setFlying(true)`.
- O resgate por raio **não** desabilita o voo — apenas teleporta.

### Raio de 200 blocos (todos os jogadores)

- Distância **horizontal** (x/z, ignorando Y) do spawn.
- Limite: 200 blocos. Comparação por `distanceSquared` ≥ `200*200 = 40000`.
- Checagem só quando o jogador **troca de bloco** (`from.getBlockX/Z != to...`),
  para não pesar no `PlayerMoveEvent`.
- Ao ultrapassar → `teleport(spawn)`.
- Ignorar Y é proposital: quem voa pode subir sem ser puxado; o limite é a
  "cerca" horizontal do mapa.
- Spawn de referência:
  - Lobby: `plugin.getSpawn()` (config central, já existente).
  - Login: `world.getSpawnLocation()` (mundo void com spawn definido pelo
    `crystal-worldinit`).

### Cancelamento de interação com blocos

- Cancela `BlockBreakEvent`, `BlockPlaceEvent` e `PlayerInteractEvent` quando há
  um bloco envolvido (`RIGHT_CLICK_BLOCK` / `LEFT_CLICK_BLOCK` / `PHYSICAL` —
  botões, portas, baús, placas de pressão, pisar em plantação).
- **Não quebra a hotbar:** os itens do menu abrem o GUI no handler do
  `LobbyHotbar`, que roda independente do cancelamento da ativação do bloco.

## Componentes / arquivos

### `crystal-lobby`

- **`LobbyHotbar`**
  - Slot 4 (caveira): passa a abrir o GUI de Perfil (em vez de mandar no chat).
  - Novo slot 3: item "Lobbys" que abre o seletor de lobbys.
  - `MenuHolder` reutilizado; `handleMenuClick` estendido para os tipos
    `"profile"` e `"lobbys"`.
  - Envio de `Connect` via plugin messaging (precisa de referência ao plugin
    para `sendPluginMessage`).
- **`LobbyProtection`**
  - `onMove`: além do resgate do void existente, adiciona a checagem de raio.
  - Novos handlers: cancelar `BlockBreakEvent`, `BlockPlaceEvent` e interação
    com blocos.
- **`CrystalLobbyPlugin`**
  - `onJoin`: ativa voo para quem tem `crystal.fly`.
  - `onEnable`: registra o canal de saída `BungeeCord`.
  - `plugin.yml`: declara a permissão `crystal.fly` (default `op`).

### `crystal-login`

- **`LoginProtection`** (novo): raio de 200 blocos + cancelamento de interação
  com blocos. Spawn = `world.getSpawnLocation()`.
- **`CrystalLoginPlugin`**
  - `onJoin`: ativa voo para quem tem `crystal.fly` (além da auth atual).
  - Registra o `LoginProtection`.
  - `plugin.yml`: declara a permissão `crystal.fly` (default `op`).

## Tratamento de erros

- `listServers` / `getProfile` falham → o GUI mostra mensagem de erro (ou item
  de erro) sem travar a thread principal; o jogador continua no lobby.
- `Connect` para um lobby que caiu entre a montagem do GUI e o clique → o proxy
  simplesmente não move; sem crash. (O menu reaberto já não mostraria mais ele.)

## Testes / verificação

- Build: `make plugins` (jars shaded) sem erros.
- Manual (stack up, `make up` + lobbys):
  - Caveira abre GUI de perfil com rank/level/coins.
  - Item "Lobbys" lista os lobbys online; subir um `lobby-03` faz ele aparecer;
    derrubá-lo faz sumir; clicar conecta ao destino.
  - Bússola "Modos de Jogo" continua abrindo Parkour.
  - Jogador com `crystal.fly` entra voando no lobby e no login.
  - Andar/voar além de 200 blocos (x/z) teleporta de volta ao spawn, no lobby e
    no login.
  - Quebrar/colocar/interagir com blocos é bloqueado no lobby e no login.
