# Parkour: bold, título por checkpoint, comandos, top em GUI, limpar dados

**Data:** 2026-06-29
**Módulo:** `plugins/crystal-parkour` (+ limpeza de dados no PostgreSQL/Redis)
**Status:** desenho aprovado, pronto para plano

## Objetivo

Polir o parkour do lobby em cinco frentes:
1. Reduzir o **negrito** a só onde faz sentido.
2. Mostrar um **título na tela** a cada checkpoint.
3. **Melhorar os comandos** (tab-complete, ajuda clara, admin agrupado).
4. Transformar `/parkour top` em **GUI**.
5. Confirmar que **não há seed** no código e **limpar dados de teste** do ranking.

## 1. Limpeza de bold

- **Remover** o bold dos 3 itens da hotbar de corrida (`ParkourListener`):
  `§b§lÚltimo Checkpoint` → `§bÚltimo Checkpoint`; `§e§lReiniciar` → `§eReiniciar`;
  `§c§lSair do Parkour` → `§cSair do Parkour` (mantém a cor, tira o `§l`).
- **Manter** o bold nos dois cabeçalhos de holograma (`CrystalParkourPlugin`):
  `INÍCIO` (gold bold) e `✦ CHEGADA ✦` (gold bold) — são títulos flutuantes.
- Nenhum outro texto ganha bold.

## 2. Título por checkpoint

No ponto onde hoje só há `p.sendActionBar("Checkpoint N!")` (`ParkourListener`, ao
avançar de checkpoint), adicionar:

```java
player.showTitle(Title.title(
        Component.text("Checkpoint", NamedTextColor.GREEN),
        Component.text((cp + 1) + "/" + total, NamedTextColor.GRAY),
        Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1500), Duration.ofMillis(250))));
```

- `total` = número de checkpoints do curso (`course.checkpointCount()` — adicionar
  esse getter se não existir; a lista ordenada já existe em `ParkourCourse`).
- A ActionBar do cronômetro continua igual (não substituir).
- Sem bold no título/subtítulo.

## 3. Comandos melhorados

### Superfície nova
- **Jogador:**
  - `/parkour` (ou `/parkour play`) — jogar.
  - `/parkour top` — abre a **GUI** (ver seção 4).
  - `/parkour reset` (alias `sair`) — sair da corrida.
- **Admin** (perm `crystal.parkour.admin`), agrupado sob `admin`:
  - `/parkour admin <setspawn|setstart|setcheckpoint|removecheckpoint|setfinish|setfall|clear|reload>`.
  - Os subcomandos admin **saem** do nível raiz (ex.: o antigo `/parkour setstart`
    passa a ser `/parkour admin setstart`).

### Tab-complete
- `ParkourCommand` implementa `TabCompleter` (ou `onTabComplete`):
  - 1º arg: `top`, `reset` para todos; acrescenta `admin` só se tem a permissão.
  - Após `admin`: lista os subcomandos admin (só com permissão).

### Ajuda
- Help do jogador: `/parkour [top|reset]`.
- Help do admin (quando tem permissão): inclui `/parkour admin <...>` com a lista.
- Mensagens em PT, sem bold; manter o estilo Adventure/`NamedTextColor` atual.

## 4. `/parkour top` como GUI

- Novo `ParkourTopMenu` (em `crystal-parkour`), espelhando o padrão de menu do
  lobby (`MenuHolder implements InventoryHolder` + `InventoryClickEvent` que
  cancela os cliques). Item-builder e layout próprios no módulo (pequeno helper
  local; não dá pra reusar o de crystal-skin por ser de outro módulo/pacote).
- **Conteúdo:**
  - Grid com o **top 10** como **cabeças de jogador** (`PLAYER_HEAD`,
    `SkullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(username))`), nome no
    display, `#rank` + tempo na lore; `#1` com leve destaque (ex.: glow/encantamento
    oculto, como no lobby).
  - **Rodapé:** um item com **a sua posição**: tempo via `parkourBest(uuid)` e o
    rank obtido varrendo um `parkourTop(100)`; se não estiver no top 100, mostra só
    o seu tempo (ou "Você ainda não completou.").
- **Dados:** buscar no backend **assíncrono** (`runTaskAsynchronously`), montar os
  `ItemStack` fora da main thread, e **abrir o inventário na main thread**
  (`runTask`). Nunca tocar API de inventário/entidade fora da main thread.
- **Caveat (offline-mode):** a textura da cabeça pode sair como skin padrão (o
  `ParkourEntry` traz só `username`+`timeMs`, sem UUID, e a rede é offline-mode).
  Nome, rank e tempo ficam corretos. Buscar skin real é fora de escopo.
- `/parkour top` deixa de imprimir as linhas no chat; passa a abrir o menu. O
  holograma de chegada (recorde top 1) continua como está.

## 5. "Seed" / dados de teste

- **Não existe seed no código** (migrations sem INSERT; sem placeholder/fake; o
  ranking só registra via `submitParkourTime`). Nada a remover em código.
- **Ação de dados (uma vez):** limpar dados de teste do ranking:
  - PostgreSQL: `TRUNCATE TABLE parkour_times;`
  - Redis: `DEL leaderboard:parkour`
  - Via `docker exec` no container do Postgres e do Redis.
- Após limpar, o ranking volta a "Ninguém completou ainda." e popula só com
  jogadas reais.

## Arquivos afetados

- `crystal-parkour/.../listener/ParkourListener.java` — bold dos itens da hotbar; título no checkpoint.
- `crystal-parkour/.../CrystalParkourPlugin.java` — (holograms mantêm bold; registrar o listener do menu; expor `formatTime` já é estático/público).
- `crystal-parkour/.../ParkourCourse.java` — getter `checkpointCount()` (se faltar).
- `crystal-parkour/.../commands/ParkourCommand.java` — restruturação dos subcomandos, `admin`, help, tab-complete; `top` abre a GUI.
- `crystal-parkour/.../menu/ParkourTopMenu.java` — **novo**: GUI do top + helper de item/slots + handler de clique.
- `crystal-parkour/src/main/resources/plugin.yml` — (sem novos comandos; `parkour` já existe; manter perm admin).
- **Dados:** `parkour_times` (Postgres) + `leaderboard:parkour` (Redis) — limpeza via docker exec (não é arquivo).

## Notas / clean code

- Código/comentários em inglês; texto de jogador em PT.
- Nunca bloquear a main thread: backend/HTTP em `runTaskAsynchronously`; abrir
  GUI/tocar entidades em `runTask`.
- Limpar o que cria (o menu é inventário efêmero; cliques cancelados).
- Constantes em vez de literais (slots, tamanhos, perm).
- Seguir o padrão de menu do lobby (`MenuHolder` + `onClick`).

## Não-objetivos (YAGNI)

- Sem busca de skin real para as cabeças (offline-mode; nome/rank/tempo bastam).
- Sem novo endpoint de "meu rank" no backend (rodapé usa top 100 + `parkourBest`).
- Sem paginação do top (top 10 + rodapé bastam).
- Sem mexer no holograma de chegada além do que já existe.

## Critérios de sucesso

1. Itens da hotbar de corrida aparecem **sem** negrito; hologramas `INÍCIO`/`CHEGADA` seguem em negrito.
2. Ao passar por um checkpoint, aparece o **título** "Checkpoint" + "N/M" na tela.
3. `/parkour <tab>` autocompleta (`top`, `reset`; `admin` e subcomandos só p/ admin).
4. `/parkour admin setstart` etc. funcionam; `/parkour setstart` (raiz) não é mais aceito (cai no help).
5. `/parkour top` abre uma **GUI** com top 10 em cabeças + rodapé com a sua posição; nada de linhas no chat.
6. Após a limpeza, o ranking começa vazio e passa a registrar jogadas reais; um tempo novo aparece no top.
