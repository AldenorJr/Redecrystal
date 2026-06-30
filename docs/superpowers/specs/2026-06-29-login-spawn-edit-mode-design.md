# Spawn do login + modo edição (`/login manutencao`, `/login setspawn`)

**Data:** 2026-06-29
**Módulo:** `plugins/crystal-login`
**Status:** desenho aprovado, pronto para plano de implementação

## Objetivo

Permitir que a staff defina, em jogo, o ponto de spawn do servidor de login —
o lugar onde todo jogador cai congelado ao conectar. Como o servidor de login
mantém o jogador travado (modo aventura, cegueira, sem movimento), também é
preciso um **modo edição** que destrave temporariamente o membro da staff para
ele voar até o local e marcar o spawn.

Escopo desta fatia: **apenas** o modo edição individual + setar spawn. **Não**
mexe na manutenção de rede já existente (`/manutencao on|off` no lobby, gate do
proxy em `crystal-bungee`).

## Comandos

Os comandos de auth (`/login <senha>`, `/registrar …`) **não** são comandos
registrados — são interceptados no `CommandFilterListener`
(`PlayerCommandPreprocessEvent`) e cancelados, para a senha nunca ser logada nem
transmitida. A staff conectada ao login também está **não autenticada** e
congelada, então os subcomandos admin precisam ser tratados **no mesmo ponto de
interceptação**, e não como comando Bukkit registrado.

Roteamento dentro do case `/login` (e `/l`) do `CommandFilterListener`:

- `args[0]` é `manutencao` **e** o sender tem permissão → modo edição (toggle).
- `args[0]` é `setspawn` **e** o sender tem permissão → setar spawn.
- caso contrário → fluxo de autenticação atual (`args[0]` tratado como senha).

A checagem de permissão vem **antes** de tratar como senha, então um jogador
comum cuja senha seja `manutencao`/`setspawn` nunca cai no ramo admin (sem
permissão → segue para auth). Sem vazamento e sem conflito.

### `/login manutencao` — modo edição (toggle, individual)

- **Permissão:** `crystal.login.admin` (default: op).
- **Liga:**
  - cancela o timeout de login (senão a staff levaria kick em 60 s no meio da edição);
  - remove a cegueira e demais efeitos;
  - `GameMode.CREATIVE`, `setAllowFlight(true)`, `setFlying(true)`;
  - marca o UUID como "em edição" (set em memória no plugin);
  - mensagem: "Modo edição ON — voe até o local e use /login setspawn".
- **Desliga** (ou ao sair/quit):
  - tira o UUID do set de edição;
  - restaura o estado congelado (aventura, cegueira, sem voo) e teleporta de
    volta ao spawn de login;
  - re-arma o timeout de login;
  - mensagem: "Modo edição OFF".

### `/login setspawn` — setar spawn (staff)

- **Permissão:** `crystal.login.admin`.
- Salva a posição atual do jogador em `login.spawn` no backend (chave de config
  `login`), igual ao `/lobby setspawn`:
  ```java
  Map<String,Object> cfg = new HashMap<>(crystal.configProvider().get("login").config());
  cfg.put("spawn", Map.of("x",…, "y",…, "z",…, "yaw",…, "pitch",…));
  crystal.backend().putConfig("login", cfg);   // dispara Kafka → hot-reload na rede
  ```
- Chamada HTTP em `runTaskAsynchronously` (nunca bloquear a main thread).
- Mensagem de confirmação: "Spawn do login definido para todos os servidores de login."
- Funciona estando em modo edição ou não, mas o fluxo natural é estar editando.

## Spawn aplicado no join

Hoje o `PlayerJoinListener` congela o jogador mas **não** o teleporta — ele fica
no spawn padrão do mundo (definido pelo `crystal-worldinit`). Mudança:

- `CrystalLoginPlugin` passa a guardar um `volatile Location spawn`, populado a
  partir de `login.spawn` no `onEnable` e mantido por
  `configProvider().onChange("login", …)` (hot-reload, mesmo padrão do lobby).
- No `PlayerJoinListener`, **depois** de congelar, teleporta o jogador para o
  spawn de login configurado, **se existir**. Se `login.spawn` não estiver
  setado, mantém o comportamento atual (fica no spawn do mundo). Sem regressão.
- Ao desligar o modo edição, o teleporte de volta usa esse mesmo spawn.

Parsing do spawn segue o padrão do `CrystalLobbyPlugin.parseSpawn` (lê
`Map` x/y/z/yaw/pitch; mundo = primeiro mundo do servidor).

## Interação com o `LoginGuard`

O `LoginGuard` trava movimento (leash de 200 blocos a partir do spawn),
quebra/coloca bloco, interações, dano, fome, drop e clique em inventário — tudo
condicionado a `!isAuthenticated`. A staff em modo edição continua **não
autenticada**, então essas travas a atingiriam. Ajuste:

- `CrystalLoginPlugin` expõe `boolean isEditing(UUID)`.
- No `LoginGuard`, o helper `locked(p)` passa a considerar
  `!isAuthenticated && !isEditing` — quem está em edição não é travado.
- `onMove` (leash): liberar quando o jogador está em edição (voo livre para
  posicionar o spawn). As demais travas (break/place/interact/damage/hunger/
  drop/inventory) reusam `locked(p)`, então também liberam em edição.
  - `onBreak`/`onPlace` hoje não checam `locked` (cancelam sempre); passam a
    respeitar `locked(p)` para a staff conseguir, se precisar, ajustar o cenário.
    *(Opcional — só se quisermos permitir edição de blocos. Caso contrário,
    deixamos como está; o modo edição serve para voar e marcar o spawn, não
    para construir.)* **Decisão:** manter break/place cancelados — fora do
    escopo; edição é só voar + setspawn.

## Estado em memória

`CrystalLoginPlugin` ganha:

```java
private final Set<UUID> editing = ConcurrentHashMap.newKeySet();
public boolean isEditing(UUID uuid) { return editing.contains(uuid); }
```

- Volátil/em memória: não persiste reinício. Se o servidor reiniciar, a staff
  sai do modo edição (precisa religar) — aceitável e seguro.
- Limpeza no `PlayerQuitListener.clearLocalState` / no quit: remover do set
  `editing` (junto da limpeza de `authenticated`/`attempts`/`timeouts`).

## Arquivos afetados (estimativa)

- `crystal-login/.../CrystalLoginPlugin.java` — estado `editing`, `spawn`,
  `onChange("login")`, métodos `toggleEditMode(player)`, `setLoginSpawn(player)`,
  `isEditing`, `getLoginSpawn`, teleporte helper; limpeza no quit.
- `crystal-login/.../listener/CommandFilterListener.java` — roteamento dos
  subcomandos admin dentro do case `/login`.
- `crystal-login/.../listener/PlayerJoinListener.java` — teleporta ao spawn de
  login configurado após congelar.
- `crystal-login/.../listener/LoginGuard.java` — `locked()` considera edição;
  `onMove` libera quem edita.
- `crystal-login/.../listener/PlayerQuitListener.java` — remover do set `editing`.
- (sem `plugin.yml`: nada novo a registrar — tudo passa pelo interceptador.)

## Não-objetivos (YAGNI)

- Não tocar na manutenção de rede / proxy (`crystal-bungee`, `/manutencao`).
- Não criar GUI (operação puramente de staff, rara; comando basta).
- Não persistir o modo edição entre reinícios.
- Não permitir construção/edição de blocos no login (só voar + setspawn).

## Critérios de sucesso

1. Staff conecta ao login (congelado), roda `/login manutencao` → destravado,
   voando, sem cegueira, sem kick por timeout.
2. Staff voa até o ponto desejado, roda `/login setspawn` → confirmação; config
   `login.spawn` gravada no backend.
3. Próximos jogadores que conectam ao login caem **congelados no novo spawn**.
4. `/login manutencao` de novo (ou quit) → staff volta ao estado congelado.
5. Jogador comum sem permissão digitando `/login manutencao` → tratado como
   tentativa de senha (comportamento atual), sem efeito admin.
6. Sem `login.spawn` configurado → comportamento atual preservado (spawn do mundo).
