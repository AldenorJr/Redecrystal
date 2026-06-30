# Cores no chat (códigos `&`, só com permissão)

**Data:** 2026-06-29
**Módulo:** `plugins/crystal-chat`
**Status:** desenho aprovado, pronto para plano

## Objetivo

Permitir que jogadores com permissão usem **códigos de cor legacy `&`** na própria
mensagem de chat (ex.: `&6NANDINHA` → "NANDINHA" em dourado). Quem não tem
permissão continua com a mensagem em texto puro (comportamento atual).

## Decisões (brainstorming)

- **Quem:** só quem tem `crystal.chat.color` (default `op`; VIP/staff via LuckPerms).
- **Códigos:** **somente cores `&0-&f`**. Formatos/reset (`&k &l &m &n &o &r`) **não**
  são aplicados. MiniMessage do jogador fica **desabilitado** (sem `<click>`,
  `<hover>`, `<rainbow>` etc.) por segurança.
- **Escopo:** chat público de rede (`crystal-chat`). `/tell` (privado) fora de escopo.

## Contexto do pipeline (atual)

- `ChatListener.onChat` (captura): tem o `Player`; serializa a mensagem para texto
  plano, censura, resolve a tag e **publica no Kafka** (`PLAYER_CHAT`) com
  `message` como String, além de `prefix`/`nameColor`/`player`/`server`.
- `CrystalChatPlugin` (consumer): lê o payload e chama
  `ChatService.broadcast(server, player, message, prefix, nameColor)`.
- `ChatService.broadcast`: monta o componente com MiniMessage; hoje a mensagem usa
  `Placeholder.unparsed("message", message)` → **literal** (sem cor).
- A mensagem viaja como **String** pelo Kafka; o `Player` **não** existe no
  broadcast → a permissão precisa ser resolvida na captura e carregada no payload.

## Comportamento

### `ChatListener.onChat`
- Resolve `boolean allowColors = player.hasPermission("crystal.chat.color")`.
- Inclui no payload Kafka: `"allowColors", String.valueOf(allowColors)`.
- Nada mais muda (censura/tag continuam iguais).

### `CrystalChatPlugin` (consumer do `PLAYER_CHAT`)
- Lê `allowColors` (`"true".equals(event.get("allowColors"))`).
- Passa o flag para `chat.broadcast(..., allowColors)`.

### `ChatService.broadcast(..., boolean allowColors)`
- Componente da mensagem:
  - `allowColors` → `colorsOnly(message)`.
  - caso contrário → `Component.text(message)` (texto puro; `&` aparece literal — comportamento atual).
- Usa `Placeholder.component("message", <componente>)` no lugar do `unparsed`.

### Helper `colorsOnly(String)` (em `ChatService`)
- Remove os códigos de formato/reset: `raw.replaceAll("(?i)&[k-or]", "")`
  (cobre `k,l,m,n,o` e `r`).
- Aplica `LegacyComponentSerializer.legacyAmpersand().deserialize(stripped)` →
  só `&0-&f` viram cor. **Não** passa pelo `parse()` (logo, sem MiniMessage).
- Também normaliza `§` → remove para o jogador não injetar `§` direto: trocar
  `§` por `&` antes? Não — para evitar que `§` colado burle o filtro, **remover**
  qualquer `§` da mensagem do jogador antes de processar (`raw.replace("§","")`).

## Arquivos afetados

- `crystal-chat/.../listener/ChatListener.java` — resolve `allowColors`, adiciona ao payload.
- `crystal-chat/.../CrystalChatPlugin.java` — lê `allowColors`, repassa ao `broadcast`.
- `crystal-chat/.../ChatService.java` — assinatura `broadcast(..., boolean allowColors)`;
  novo helper privado `colorsOnly(String)`.
- `crystal-chat/src/main/resources/plugin.yml` — declarar `crystal.chat.color` (`default: op`).

## Notas / clean code

- Idioma: código/comentários em inglês; texto do jogador em PT.
- Constantes: node de permissão e o regex de strip como constantes nomeadas.
- O censor continua no texto plano na captura (cor só no render). Limitação
  pré-existente: `&` no meio da palavra pode driblar o censor — fora de escopo
  (cor é permissão de confiança).
- Atualizar **todos** os call sites de `broadcast(...)` para a nova assinatura.

## Não-objetivos (YAGNI)

- Sem cor em `/tell` (privado).
- Sem formatos (`&l/&n/&o/&m/&k`) nem MiniMessage do jogador.
- Sem config para ligar/desligar (permissão basta).

## Critérios de sucesso

1. Jogador com `crystal.chat.color` envia `&6NANDINHA` → "NANDINHA" dourado no chat.
2. `&6&nNANDINHA` → dourado, **sem** sublinhado (`&n` ignorado).
3. Tentativa de MiniMessage (`<rainbow>oi`, `<click:...>`) → aparece literal, sem efeito.
4. Jogador **sem** a permissão envia `&6oi` → texto puro `&6oi` (sem cor).
5. `§`-codes colados pelo jogador não colorem nem quebram a mensagem.
