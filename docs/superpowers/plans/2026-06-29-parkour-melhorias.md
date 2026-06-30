# Parkour: melhorias (bold, título de checkpoint, comandos, top GUI, limpar dados) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Reduzir bold ao necessário, mostrar título por checkpoint, reestruturar os comandos (admin agrupado + tab-complete + help), transformar `/parkour top` em GUI, e limpar dados de teste do ranking.

**Architecture:** Tudo em `crystal-parkour` (+ uma limpeza de dados no Postgres/Redis). O título do checkpoint e o bold ficam no `ParkourListener`. A GUI do top é um novo `ParkourTopMenu` (padrão `MenuHolder` + `InventoryClickEvent`, dados buscados async e inventário aberto na main thread). Os comandos viram `/parkour [top|reset]` + `/parkour admin <...>` com `TabCompleter`.

**Tech Stack:** Java 21, Paper 1.21, Maven, crystal-core (backend HTTP), Adventure (Title, Components, SkullMeta).

## Global Constraints

- Texto do jogador em **PT**; código/comentários em **inglês**.
- Nunca bloquear a main thread: backend/HTTP em `runTaskAsynchronously`; abrir GUI / tocar entidades em `runTask`.
- DTO é record; serviço/utilitário final; constantes em vez de literais (slots, tamanhos, perm).
- Seguir o padrão de menu do lobby (`MenuHolder implements InventoryHolder` + `onClick` que cancela).
- Sem testes unitários para cola Bukkit — gate por task = compilar; verificação em jogo.
- Build do módulo: `mvn -pl plugins/crystal-parkour -am compile`.
- `course().checkpointCount()` e `CrystalParkourPlugin.formatTime(long)` já existem.

---

## Task 1: Bold só onde necessário + título por checkpoint

**Files:**
- Modify: `plugins/crystal-parkour/src/main/java/com/redecrystal/parkour/listener/ParkourListener.java`

**Interfaces:** consome `plugin.course().checkpointCount()`. Sem API nova.

- [ ] **Step 1: Imports para Title**

No topo de `ParkourListener.java`, adicione:

```java
import java.time.Duration;
import net.kyori.adventure.title.Title;
```

- [ ] **Step 2: Título na tela ao avançar de checkpoint**

Substitua o bloco do checkpoint em `onMove` (hoje):

```java
        int cp = c.checkpointAt(to);
        if (cp > run.lastCheckpoint) {
            run.lastCheckpoint = cp;
            p.sendActionBar(Component.text("Checkpoint " + (cp + 1) + "!", NamedTextColor.AQUA));
        }
```

por:

```java
        int cp = c.checkpointAt(to);
        if (cp > run.lastCheckpoint) {
            run.lastCheckpoint = cp;
            int total = c.checkpointCount();
            p.showTitle(Title.title(
                    Component.text("Checkpoint", NamedTextColor.GREEN),
                    Component.text((cp + 1) + "/" + total, NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1500), Duration.ofMillis(250))));
        }
```

(A ActionBar do cronômetro logo abaixo segue mostrando o tempo a cada tick.)

- [ ] **Step 3: Remover o bold dos itens da hotbar**

Substitua as três linhas em `giveParkourHotbar`:

```java
        inv.setItem(0, item(ITEM_CHECKPOINT, "§b§lÚltimo Checkpoint", "§7Voltar ao último checkpoint"));
        inv.setItem(1, item(ITEM_RESTART, "§e§lReiniciar", "§7Recomeçar do início"));
        inv.setItem(8, item(ITEM_EXIT, "§c§lSair do Parkour", "§7Voltar ao lobby"));
```

por (remove só o `§l`, mantém a cor):

```java
        inv.setItem(0, item(ITEM_CHECKPOINT, "§bÚltimo Checkpoint", "§7Voltar ao último checkpoint"));
        inv.setItem(1, item(ITEM_RESTART, "§eReiniciar", "§7Recomeçar do início"));
        inv.setItem(8, item(ITEM_EXIT, "§cSair do Parkour", "§7Voltar ao lobby"));
```

- [ ] **Step 4: Compilar**

Run: `mvn -pl plugins/crystal-parkour -am compile` → Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add plugins/crystal-parkour/src/main/java/com/redecrystal/parkour/listener/ParkourListener.java
git commit -m "feat(parkour): título na tela por checkpoint; remove bold dos itens da hotbar"
```

---

## Task 2: GUI do ranking (`ParkourTopMenu`)

Cria a GUI do top (sem ligar ao comando ainda — isso é a Task 3). A classe é registrada como listener para cancelar cliques.

**Files:**
- Create: `plugins/crystal-parkour/src/main/java/com/redecrystal/parkour/menu/ParkourTopMenu.java`
- Modify: `plugins/crystal-parkour/src/main/java/com/redecrystal/parkour/CrystalParkourPlugin.java`

**Interfaces:**
- Produz: `public final class ParkourTopMenu implements Listener` com `public ParkourTopMenu(CrystalParkourPlugin plugin, CrystalCore crystal)` e `public void open(Player player)`.
- `CrystalParkourPlugin` ganha um campo `private ParkourTopMenu topMenu;` exposto via getter `public ParkourTopMenu topMenu()` (consumido na Task 3).

- [ ] **Step 1: Criar `menu/ParkourTopMenu.java`**

```java
package com.redecrystal.parkour.menu;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.ParkourEntry;
import com.redecrystal.parkour.CrystalParkourPlugin;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * The {@code /parkour top} leaderboard GUI: the top 10 as player heads plus a
 * footer with the viewer's own position. Data is fetched off the main thread and
 * the inventory is opened back on it. Clicks are purely visual (cancelled).
 */
public final class ParkourTopMenu implements Listener {

    private static final int SIZE = 36;                 // 4 rows
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 19, 20, 21, 22, 23};
    private static final int FOOTER_SLOT = 31;
    private static final int FETCH_LIMIT = 100;         // enough to locate the viewer's rank

    private final CrystalParkourPlugin plugin;
    private final CrystalCore crystal;

    public ParkourTopMenu(CrystalParkourPlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
    }

    /** Marks an inventory this menu owns so its clicks are cancelled. */
    private record Holder() implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    /** Fetch the leaderboard off-thread, build the heads, open on the main thread. */
    public void open(Player player) {
        String name = player.getName();
        String uuid = player.getUniqueId().toString();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<ParkourEntry> top;
            long best;
            try {
                top = crystal.backend().parkourTop(FETCH_LIMIT);
                best = crystal.backend().parkourBest(uuid);
            } catch (Exception e) {
                top = List.of();
                best = -1;
            }
            long myRank = -1;
            long myTime = best;
            for (ParkourEntry e : top) {
                if (e.username().equalsIgnoreCase(name)) {
                    myRank = e.rank();
                    myTime = e.timeMs();
                    break;
                }
            }
            Inventory inv = Bukkit.createInventory(new Holder(), SIZE, Component.text("Ranking do Parkour"));
            int shown = Math.min(SLOTS.length, top.size());
            for (int i = 0; i < shown; i++) {
                inv.setItem(SLOTS[i], head(top.get(i)));
            }
            inv.setItem(FOOTER_SLOT, footer(name, myRank, myTime));
            final Inventory built = inv;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.openInventory(built);
                }
            });
        });
    }

    private ItemStack head(ParkourEntry e) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(e.username()));
        meta.displayName(Component.text("#" + e.rank() + " " + e.username(),
                e.rank() == 1 ? NamedTextColor.GOLD : NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(CrystalParkourPlugin.formatTime(e.timeMs()), NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false)));
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack footer(String name, long rank, long timeMs) {
        ItemStack it = new ItemStack(Material.NAME_TAG);
        var meta = it.getItemMeta();
        meta.displayName(Component.text("Você: " + name, NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (timeMs > 0) {
            lore.add(Component.text("Tempo: " + CrystalParkourPlugin.formatTime(timeMs), NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text(rank > 0 ? "Posição: #" + rank : "Fora do top " + FETCH_LIMIT, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("Você ainda não completou.", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }
}
```

- [ ] **Step 2: Instanciar e registrar o menu no plugin**

Em `CrystalParkourPlugin`, adicione o import e o campo:

```java
import com.redecrystal.parkour.menu.ParkourTopMenu;
```
```java
    private ParkourTopMenu topMenu;
```

Em `onEnable`, depois de registrar o `listener` e ANTES de `getCommand("parkour").setExecutor(...)`, crie e registre o menu:

```java
        this.topMenu = new ParkourTopMenu(this, crystal);
        pm.registerEvents(topMenu, this);
```

E adicione o getter (perto de `course()`):

```java
    /** The leaderboard GUI, opened by {@code /parkour top}. */
    public ParkourTopMenu topMenu() {
        return topMenu;
    }
```

- [ ] **Step 3: Compilar**

Run: `mvn -pl plugins/crystal-parkour -am compile` → Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add plugins/crystal-parkour/src/main/java/com/redecrystal/parkour/menu/ParkourTopMenu.java \
        plugins/crystal-parkour/src/main/java/com/redecrystal/parkour/CrystalParkourPlugin.java
git commit -m "feat(parkour): GUI do ranking (top 10 em cabeças + posição do jogador)"
```

---

## Task 3: Comandos — admin agrupado, `top` abre GUI, tab-complete, help

**Files:**
- Modify: `plugins/crystal-parkour/src/main/java/com/redecrystal/parkour/commands/ParkourCommand.java`
- Modify: `plugins/crystal-parkour/src/main/java/com/redecrystal/parkour/CrystalParkourPlugin.java`

**Interfaces:**
- `ParkourCommand` passa a implementar `CommandExecutor, TabCompleter`; construtor ganha `ParkourTopMenu topMenu`.

- [ ] **Step 1: Plugin passa o menu ao comando e registra o tab-completer**

Em `CrystalParkourPlugin.onEnable`, troque a linha do executor por:

```java
        ParkourCommand parkourCmd = new ParkourCommand(this, crystal, listener, topMenu);
        getCommand("parkour").setExecutor(parkourCmd);
        getCommand("parkour").setTabCompleter(parkourCmd);
```

(O `topMenu` já foi criado na Task 2, antes desta linha.)

- [ ] **Step 2: Reescrever `ParkourCommand`**

Substitua o conteúdo de `ParkourCommand.java` por:

```java
package com.redecrystal.parkour.commands;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.parkour.CrystalParkourPlugin;
import com.redecrystal.parkour.ParkourCourse;
import com.redecrystal.parkour.listener.ParkourListener;
import com.redecrystal.parkour.menu.ParkourTopMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /parkour} (alias {@code /pk}): player shortcuts ({@code top}, {@code reset})
 * plus the admin course editor grouped under {@code /parkour admin <...>}. Play/exit
 * are delegated to {@link ParkourListener}; {@code top} opens {@link ParkourTopMenu};
 * the {@code admin} subcommands mutate the central parkour config (hot-reloaded).
 */
public final class ParkourCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERM = "crystal.parkour.admin";
    private static final List<String> ADMIN_SUBS = List.of(
            "setspawn", "setstart", "setcheckpoint", "removecheckpoint",
            "setfinish", "setfall", "clear", "reload");

    private final CrystalParkourPlugin plugin;
    private final CrystalCore crystal;
    private final ParkourListener listener;
    private final ParkourTopMenu topMenu;

    public ParkourCommand(CrystalParkourPlugin plugin, CrystalCore crystal,
                          ParkourListener listener, ParkourTopMenu topMenu) {
        this.plugin = plugin;
        this.crystal = crystal;
        this.listener = listener;
        this.topMenu = topMenu;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Apenas jogadores.");
            return true;
        }
        String sub = args.length == 0 ? "play" : args[0].toLowerCase();
        switch (sub) {
            case "play" -> listener.startPlaying(player);
            case "top" -> topMenu.open(player);
            case "reset", "sair" -> listener.exitRun(player);
            case "admin" -> handleAdmin(player, args);
            default -> help(player);
        }
        return true;
    }

    private void handleAdmin(Player player, String[] args) {
        if (!player.hasPermission(ADMIN_PERM)) {
            player.sendMessage(Component.text("Sem permissão.", NamedTextColor.RED));
            return;
        }
        String sub = args.length < 2 ? "" : args[1].toLowerCase();
        switch (sub) {
            case "setspawn" -> admin(player,
                    m -> m.put("spawn", ParkourCourse.facingMap(player.getLocation())),
                    "spawn (chegada da bússola) definido");
            case "setstart" -> admin(player,
                    m -> m.put("start", ParkourCourse.pointMap(player.getLocation())),
                    "início definido");
            case "setcheckpoint" -> admin(player, m -> {
                @SuppressWarnings("unchecked")
                List<Object> cps = new ArrayList<>((List<Object>) m.getOrDefault("checkpoints", new ArrayList<>()));
                cps.add(ParkourCourse.pointMap(player.getLocation()));
                m.put("checkpoints", cps);
            }, "checkpoint adicionado");
            case "removecheckpoint", "undocheckpoint" -> admin(player, m -> {
                if (m.get("checkpoints") instanceof List<?> l && !l.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    List<Object> cps = new ArrayList<>((List<Object>) l);
                    cps.remove(cps.size() - 1);
                    m.put("checkpoints", cps);
                }
            }, "último checkpoint removido");
            case "setfinish" -> admin(player,
                    m -> m.put("finish", ParkourCourse.pointMap(player.getLocation())),
                    "fim definido");
            case "setfall" -> admin(player,
                    m -> m.put("fallY", Math.round(player.getLocation().getY() - 3)),
                    "plano de queda definido");
            case "clear" -> admin(player, m -> {
                m.remove("spawn");
                m.remove("start");
                m.remove("checkpoints");
                m.remove("finish");
            }, "curso limpo");
            case "reload" -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                plugin.refreshCourse(crystal.backend().getConfig(CrystalParkourPlugin.CONFIG_KEY).config());
                plugin.getServer().getScheduler().runTask(plugin, plugin::rebuildHolograms);
                player.sendMessage(Component.text("Curso recarregado.", NamedTextColor.GREEN));
            });
            default -> adminHelp(player);
        }
    }

    /** Admin helper: mutate the central parkour config and persist it (hot-reloads). */
    private void admin(Player player, Consumer<Map<String, Object>> mutator, String okMsg) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Object> cfg = new java.util.HashMap<>(
                    crystal.configProvider().get(CrystalParkourPlugin.CONFIG_KEY).config());
            cfg.put("world", player.getWorld().getName());
            cfg.putIfAbsent("fallY", Math.round(player.getLocation().getY() - 5));
            mutator.accept(cfg);
            crystal.backend().putConfig(CrystalParkourPlugin.CONFIG_KEY, cfg);
            player.sendMessage(Component.text("Parkour: " + okMsg + ".", NamedTextColor.GREEN));
        });
    }

    private void help(Player player) {
        player.sendMessage(Component.text("/parkour [top|reset]", NamedTextColor.GRAY));
        if (player.hasPermission(ADMIN_PERM)) {
            adminHelp(player);
        }
    }

    private void adminHelp(Player player) {
        player.sendMessage(Component.text(
                "/parkour admin [setspawn|setstart|setcheckpoint|removecheckpoint|setfinish|setfall|clear|reload]",
                NamedTextColor.GRAY));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission(ADMIN_PERM);
        if (args.length == 1) {
            List<String> base = new ArrayList<>(List.of("top", "reset"));
            if (admin) {
                base.add("admin");
            }
            return filter(base, args[0]);
        }
        if (args.length == 2 && "admin".equalsIgnoreCase(args[0]) && admin) {
            return filter(ADMIN_SUBS, args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }
}
```

(O antigo `showTop` e o import de `ParkourEntry` saem; o `top` agora abre o menu. Os subcomandos admin no nível raiz deixam de existir — caem no `help`.)

- [ ] **Step 3: Compilar**

Run: `mvn -pl plugins/crystal-parkour -am compile` → Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add plugins/crystal-parkour/src/main/java/com/redecrystal/parkour/commands/ParkourCommand.java \
        plugins/crystal-parkour/src/main/java/com/redecrystal/parkour/CrystalParkourPlugin.java
git commit -m "feat(parkour): comandos com admin agrupado, tab-complete e top em GUI"
```

---

## Task 4: Limpar dados de teste + build + redeploy + verificação

**Files:** nenhum (dados + build + deploy + verificação manual).

- [ ] **Step 1: Limpar o ranking (dados de teste)**

Run (Postgres — tabela em `core_db`, user `crystal`):
```bash
docker compose exec -T postgres psql -U crystal -d core_db -c "TRUNCATE TABLE parkour_times;"
```
Run (Redis — ZSET do leaderboard):
```bash
docker compose exec -T redis redis-cli DEL leaderboard:parkour
```
Expected: `TRUNCATE TABLE` e um inteiro (0/1) do `DEL`. Confirme vazio:
```bash
docker compose exec -T postgres psql -U crystal -d core_db -c "SELECT COUNT(*) FROM parkour_times;"
```
Expected: `0`.

- [ ] **Step 2: Build**

Run: `make plugins` → Expected: `BUILD SUCCESS`; `crystal-parkour.jar` atualizado.

- [ ] **Step 3: Recriar os lobbies (onde o parkour roda)**

Run: `docker compose up -d --force-recreate --no-deps lobby-01 lobby-02 lobby-03`
Expected: sobem; `docker compose logs lobby-01 | grep CrystalParkour` mostra `CrystalParkour enabled`.

- [ ] **Step 4: Verificação em jogo (critérios da spec)**

1. Itens da hotbar de corrida **sem** negrito; hologramas `INÍCIO`/`✦ CHEGADA ✦` ainda em negrito.
2. Passar por um checkpoint → **título** "Checkpoint" + "N/M" na tela; cronômetro segue na ActionBar.
3. `/parkour <tab>` → `top`, `reset` (e `admin` + subcomandos só para admin).
4. `/parkour admin setstart` funciona; `/parkour setstart` (raiz) cai no help.
5. `/parkour top` abre **GUI**: top 10 em cabeças + rodapé com sua posição; sem linhas no chat.
6. Após a limpeza, o top começa vazio ("Você ainda não completou." no rodapé / holograma "Seja o primeiro a terminar!"). Complete uma corrida → seu tempo aparece no top e no holograma de chegada.

- [ ] **Step 5: Commit final (se houver ajustes)**

---

## Self-Review

**Spec coverage:**
- Bold só nos hologramas; hotbar sem `&l` → Task 1 Step 3. ✓
- Título por checkpoint ("Checkpoint" + "N/M"), mantém actionbar do timer → Task 1 Step 2. ✓
- Comandos: admin agrupado em `/parkour admin <...>`, help jogador/admin → Task 3 Step 2. ✓
- Tab-complete (top/reset; admin + subs só p/ admin) → Task 3 Step 2 (`onTabComplete`) + Step 1 (setTabCompleter). ✓
- `/parkour top` abre GUI (top 10 heads + rodapé) → Task 2 + Task 3 (top → topMenu.open). ✓
- Sem seed no código; limpar `parkour_times` + `leaderboard:parkour` → Task 4 Step 1. ✓
- Critérios 1-6 → Task 4 Step 4. ✓

**Placeholder scan:** sem TBD/TODO; todo passo de código mostra o código.

**Type consistency:** `ParkourTopMenu(plugin, crystal)` + `open(Player)`; `CrystalParkourPlugin.topMenu()` getter; `ParkourCommand(plugin, crystal, listener, topMenu)` implements `CommandExecutor, TabCompleter`; `course().checkpointCount()`/`formatTime(long)` existentes. Ordem de criação no onEnable: listener → topMenu (registrado) → parkourCmd (usa topMenu) → setExecutor/​setTabCompleter. ✓

**Ordering note:** Task 2 cria/registra o menu (compila com o comando antigo ainda usando chat); Task 3 troca o comando para usar o menu. Sem conflito (sequencial; mesmo arquivo de plugin editado em 2 e 3).
