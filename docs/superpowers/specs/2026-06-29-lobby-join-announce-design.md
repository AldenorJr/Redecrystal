# Aviso de entrada no lobby (só VIP/staff, com tag)

**Data:** 2026-06-29
**Módulo:** `plugins/crystal-lobby`
**Status:** desenho aprovado, pronto para plano

## Objetivo

Personalizar a entrada no lobby: remover as mensagens vanilla do Minecraft
(entrada e saída) e, **apenas** para jogadores com permissão dedicada (VIP/staff),
anunciar no chat do lobby uma linha com o cargo, ex.:

```
[Diretor] yNexusxz entrou no lobby!
```

## Decisões (brainstorming)

- **Trigger:** permissão dedicada `crystal.lobby.joinannounce`. Não depende de ter
  cargo; é um node próprio, concedido aos grupos VIP/staff via LuckPerms.
- **Alcance:** broadcast **local** — só quem está naquela instância de lobby vê.
  Sem Kafka, sem evento de rede.
- **Vanilla:** suprimir as mensagens padrão de **entrada e saída** para todos.
- **Saída:** sem aviso custom de saída (só entrada).
- **Formato:** `[Cargo] Nome entrou no lobby!` (prefixo do cargo + nome na cor do
  cargo + " entrou no lobby!" em cinza).

## Comportamento

### `PlayerJoinListener.onJoin`
1. `event.joinMessage(null)` — suprime a mensagem vanilla de entrada para **todos**.
2. Se `player.hasPermission("crystal.lobby.joinannounce")`:
   - Resolve o cargo do jogador (mesmo caminho do `LobbyScoreboard`):
     ```java
     String overrideId = TagOverrides.read(crystal.redis(), uuid);
     CargoResolver.Cargo cargo = CargoResolver.resolve(
             crystal.configProvider().get("chat"), overrideId, player::hasPermission);
     ```
   - Monta a linha com MiniMessage:
     - com cargo: `<prefix> <nameColor>Nome<reset><gray> entrou no lobby!`
       (`prefix`/`nameColor` vêm do cargo, já em MiniMessage, ex. `<red>[Diretor]`, `<red>`).
     - sem cargo (`cargo == null`): `<white>Nome<gray> entrou no lobby!` (sem prefixo).
   - `plugin.getServer().sendMessage(line)` → broadcast local.

### `PlayerQuitListener.onQuit`
- `event.quitMessage(null)` — suprime a mensagem vanilla de saída para **todos**.
  Nenhum aviso custom de saída.

## Arquivos afetados

- `crystal-lobby/.../listener/PlayerJoinListener.java` — suprime join vanilla;
  anuncia entrada para quem tem a permissão. Passa a receber `CrystalCore` no
  construtor (hoje só tem o plugin), como o `LobbyScoreboard`.
- `crystal-lobby/.../listener/PlayerQuitListener.java` — suprime quit vanilla.
- `crystal-lobby/.../CrystalLobbyPlugin.java` — passar `crystal` ao
  `PlayerJoinListener` na construção/registro (se ainda não passa).
- `crystal-lobby/src/main/resources/plugin.yml` — declarar a permissão
  `crystal.lobby.joinannounce` (`default: op`).

## Notas de implementação / clean code

- **Main thread:** a resolução de cargo roda na main thread, **espelhando**
  `LobbyScoreboard`/`ProfileCommand`/`NametagService` (um `hget` por entrada;
  entrada é evento único, não loop de tick). É o padrão estabelecido no projeto —
  "código que se parece com o vizinho". (Caso se queira evitar o I/O na main
  thread no futuro, dá para mover o `TagOverrides.read` para async e voltar com
  `runTask` antes do broadcast; fora do escopo desta fatia.)
- **Constantes:** node de permissão e o sufixo/formato da mensagem como constantes,
  sem literais mágicos espalhados.
- **Idioma:** texto do jogador em PT; código/comentários em inglês.
- **MiniMessage:** reusar `MiniMessage.miniMessage()` (como `LobbyScoreboard`),
  combinando `prefix`, `nameColor`+nome e o sufixo.

## Não-objetivos (YAGNI)

- Sem aviso de saída custom.
- Sem broadcast de rede (cross-lobby) / Kafka.
- Sem formato configurável em config (fixo em PT por ora).
- Sem mexer no chat (`crystal-chat`) nem na mensagem de entrada de outros
  servidores (login etc.).

## Critérios de sucesso

1. Jogador **sem** a permissão entra no lobby → **nenhuma** mensagem no chat
   (nem vanilla, nem custom).
2. Jogador **com** `crystal.lobby.joinannounce` e cargo `Diretor` entra →
   `[Diretor] yNexusxz entrou no lobby!` aparece no chat **daquele** lobby.
3. Qualquer jogador sai do lobby → **nenhuma** mensagem de saída (vanilla suprimida).
4. Jogador com a permissão mas sem cargo → `yNexusxz entrou no lobby!` (sem prefixo),
   sem erro.
5. O aviso aparece só na instância de lobby onde a pessoa entrou (não nos outros).
