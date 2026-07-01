# Auto-complete no login + troca de senha — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dar auto-complete/aliases aos comandos de login e adicionar um comando de rede `/trocarsenha` (verificando a senha atual) para jogadores já logados.

**Architecture:** Parte 1 registra os comandos de auth no `crystal-login` só como "cascas" com `TabCompleter` estático (o handler real continua sendo a interceptação no `PlayerCommandPreprocessEvent`). Parte 2 adiciona `POST /api/auth/password` no `core-service`, um método no `BackendHttpClient` do SDK, e um `SimpleCommand` no proxy (`crystal-bungee`) que roda em toda a rede menos o servidor de login.

**Tech Stack:** Java 21, Paper 1.21 (Bukkit), Velocity (proxy), Spring Boot 3.3 (core-service), Maven, JUnit 5 + Mockito, bcrypt.

## Global Constraints

- **Idioma:** código e comentários em **inglês**; texto de jogador e docs em **PT**.
- **A senha nunca pode ser logada, transmitida nem sugerida no tab-complete.**
- **Nunca bloquear a main thread do Bukkit/Velocity:** HTTP ao backend fora da thread principal; feedback volta antes de tocar a API do jogo.
- Plugins falam **só com o API Gateway**, com o service-token (bearer) — já embutido no `BackendHttpClient`.
- **Escreva código que se pareça com o vizinho:** proxy usa Adventure `Component`/`NamedTextColor` (ver `MaintenanceListener`); login usa MiniMessage; comando Bukkit segue o idioma de `ParkourCommand`.
- Senha válida: **3–64** caracteres (constantes `MIN_PASSWORD_LENGTH`/`MAX_PASSWORD_LENGTH`, iguais às do `crystal-login`).

---

## Task 1: Auto-complete e aliases no login (`crystal-login`)

**Files:**
- Modify: `plugins/crystal-login/src/main/resources/plugin.yml`
- Create: `plugins/crystal-login/src/main/java/com/redecrystal/login/command/AuthTabCompleter.java`
- Modify: `plugins/crystal-login/src/main/java/com/redecrystal/login/CrystalLoginPlugin.java` (dentro de `onEnable`)
- Modify: `plugins/crystal-login/src/main/java/com/redecrystal/login/listener/CommandFilterListener.java` (o `switch`)

**Interfaces:**
- Produces: comandos `login` (aliases `logar`, `l`) e `registrar` (aliases `register`, `reg`) registrados; classe `AuthTabCompleter implements TabCompleter`.
- Consumes: nada de tarefas anteriores.

Sem teste automatizado (comportamento Bukkit/cliente) — verificação manual em jogo ao final.

- [ ] **Step 1: Declarar os comandos no `plugin.yml`**

Acrescentar ao final de `plugins/crystal-login/src/main/resources/plugin.yml`:

```yaml
commands:
  login:
    description: Entrar com sua senha
    usage: /login <senha>
    aliases: [logar, l]
  registrar:
    description: Criar sua conta
    usage: /registrar <senha> <repita a senha>
    aliases: [register, reg]
```

- [ ] **Step 2: Criar o `AuthTabCompleter`**

Placeholders estáticos por posição — nunca sugere dado real. Admin vê os
subcomandos de manutenção sob `/login`.

Criar `plugins/crystal-login/src/main/java/com/redecrystal/login/command/AuthTabCompleter.java`:

```java
package com.redecrystal.login.command;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/**
 * Static tab hints for the auth commands. The commands themselves are handled by
 * {@code CommandFilterListener} (intercepted and cancelled so the password is
 * never logged); this completer only feeds the client fixed placeholders — it
 * never suggests real data, and in particular never a password.
 */
public final class AuthTabCompleter implements TabCompleter {

    private static final String ADMIN_PERM = "crystal.login.admin";

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase();
        if (name.equals("registrar")) {
            if (args.length == 1) return List.of("<senha>");
            if (args.length == 2) return List.of("<repita_a_senha>");
            return List.of();
        }
        // login (and aliases logar/l)
        if (args.length == 1) {
            List<String> out = new ArrayList<>(List.of("<senha>"));
            if (sender.hasPermission(ADMIN_PERM)) {
                out.add("manutencao");
                out.add("setspawn");
            }
            return out;
        }
        if (args.length == 2 && sender.hasPermission(ADMIN_PERM)
                && args[0].equalsIgnoreCase("manutencao")) {
            return List.of("<senha>");
        }
        return List.of();
    }
}
```

- [ ] **Step 3: Registrar os completers no `onEnable`**

Em `CrystalLoginPlugin.java`, adicionar o import e, no fim do `onEnable` (após
registrar os listeners, antes do log final), setar o completer nos dois comandos.

Import (junto aos demais imports):

```java
import com.redecrystal.login.command.AuthTabCompleter;
```

No `onEnable`, antes de `getLogger().info("CrystalLogin enabled ...")`:

```java
        // Register the auth commands only as shells so the client offers name +
        // static hints in tab-complete. The real handling stays in
        // CommandFilterListener (which cancels the event, so the password is
        // never logged). No executor is set: the listener consumes them first.
        AuthTabCompleter completer = new AuthTabCompleter();
        getCommand("login").setTabCompleter(completer);
        getCommand("registrar").setTabCompleter(completer);
```

- [ ] **Step 4: Aceitar os novos aliases no `CommandFilterListener`**

Em `CommandFilterListener.java`, ampliar os dois cases do `switch`:

Trocar:

```java
            case "/login", "/l" -> {
```

por:

```java
            case "/login", "/logar", "/l" -> {
```

E trocar:

```java
            case "/registrar", "/reg" -> {
```

por:

```java
            case "/registrar", "/register", "/reg" -> {
```

- [ ] **Step 5: Compilar**

Run: `mvn -pl plugins/crystal-login -am -q package`
Expected: `BUILD SUCCESS`; jar em `plugins/crystal-login/target/crystal-login.jar`.

- [ ] **Step 6: Commit**

```bash
git add plugins/crystal-login
git commit -m "feat(login): registra comandos p/ auto-complete e aliases logar/register

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Endpoint de troca de senha no backend (`core-service`)

**Files:**
- Modify: `backend/core-service/src/main/java/com/redecrystal/auth/application/AuthService.java`
- Modify: `backend/core-service/src/main/java/com/redecrystal/auth/api/AuthController.java`
- Test: `backend/core-service/src/test/java/com/redecrystal/auth/application/AuthServiceChangePasswordTest.java`

**Interfaces:**
- Produces: `AuthService.changePassword(UUID uuid, String currentPassword, String newPassword)` — `void`, `@Transactional`; lança `UnauthorizedException` (401: conta inexistente / senha atual errada) e `ConflictException` (409: conta premium). Endpoint `POST /api/auth/password` com body `{uuid, currentPassword, newPassword}`.
- Consumes: `PlayerAccount.setPasswordHash`, `PlayerAccount.hasPassword`, `PlayerAccount.isPremium` (já existem); `ConflictException`/`UnauthorizedException` (já importados no `AuthService`).

- [ ] **Step 1: Escrever o teste que falha**

Criar `backend/core-service/src/test/java/com/redecrystal/auth/application/AuthServiceChangePasswordTest.java`:

```java
package com.redecrystal.auth.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.redecrystal.auth.domain.PlayerAccount;
import com.redecrystal.auth.domain.PlayerAccountRepository;
import com.redecrystal.shared.web.ConflictException;
import com.redecrystal.shared.web.UnauthorizedException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthServiceChangePasswordTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private PlayerAccountRepository repository;
    private AuthService service;

    @BeforeEach
    void setUp() {
        repository = mock(PlayerAccountRepository.class);
        service = new AuthService(repository, mock(JwtService.class), mock(StringRedisTemplate.class));
        when(repository.save(any(PlayerAccount.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void changesPasswordWhenCurrentMatches() {
        UUID uuid = UUID.randomUUID();
        PlayerAccount account = new PlayerAccount(uuid, "Steve", false, encoder.encode("old-pass"));
        when(repository.findById(uuid)).thenReturn(Optional.of(account));

        service.changePassword(uuid, "old-pass", "new-pass");

        assertTrue(encoder.matches("new-pass", account.getPasswordHash()));
        assertFalse(encoder.matches("old-pass", account.getPasswordHash()));
        verify(repository).save(account);
    }

    @Test
    void rejectsWrongCurrentPassword() {
        UUID uuid = UUID.randomUUID();
        PlayerAccount account = new PlayerAccount(uuid, "Steve", false, encoder.encode("old-pass"));
        when(repository.findById(uuid)).thenReturn(Optional.of(account));

        assertThrows(UnauthorizedException.class,
                () -> service.changePassword(uuid, "wrong", "new-pass"));
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsUnknownAccount() {
        UUID uuid = UUID.randomUUID();
        when(repository.findById(uuid)).thenReturn(Optional.empty());

        assertThrows(UnauthorizedException.class,
                () -> service.changePassword(uuid, "x", "new-pass"));
    }

    @Test
    void rejectsPremiumAccount() {
        UUID uuid = UUID.randomUUID();
        PlayerAccount premium = new PlayerAccount(uuid, "Notch", true, null);
        when(repository.findById(uuid)).thenReturn(Optional.of(premium));

        assertThrows(ConflictException.class,
                () -> service.changePassword(uuid, "x", "new-pass"));
    }
}
```

- [ ] **Step 2: Rodar o teste e confirmar que falha**

Run: `mvn -pl backend/core-service -am -q test -Dtest=AuthServiceChangePasswordTest`
Expected: FALHA na compilação/execução — `changePassword` ainda não existe em `AuthService`.

- [ ] **Step 3: Implementar `changePassword` no `AuthService`**

Em `AuthService.java`, adicionar o método após `login(...)` (antes de `find(...)`):

```java
    /**
     * Change a cracked account's password after proving the current one. Premium
     * accounts have no password and are rejected. The active session stays valid
     * (no token is re-issued).
     */
    @Transactional
    public void changePassword(UUID uuid, String currentPassword, String newPassword) {
        PlayerAccount account = repository.findById(uuid)
                .orElseThrow(() -> new UnauthorizedException("conta não registrada"));
        if (account.isPremium()) {
            throw new ConflictException("conta premium não usa senha");
        }
        requirePassword(newPassword);
        if (!account.hasPassword() || !passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw new UnauthorizedException("senha atual incorreta");
        }
        account.setPasswordHash(passwordEncoder.encode(newPassword));
        repository.save(account);
    }
```

- [ ] **Step 4: Adicionar o endpoint no `AuthController`**

Em `AuthController.java`, adicionar o DTO junto aos outros records:

```java
    public record ChangePasswordRequest(@NotNull UUID uuid,
                                        @NotBlank String currentPassword,
                                        @NotBlank String newPassword) {}
```

E o handler após `refresh(...)`:

```java
    @PostMapping("/password")
    public void changePassword(@RequestBody @NotNull ChangePasswordRequest body) {
        authService.changePassword(body.uuid(), body.currentPassword(), body.newPassword());
    }
```

(Os imports `@NotNull`, `@NotBlank`, `@PostMapping`, `@RequestBody`, `UUID` já
existem no arquivo.)

- [ ] **Step 5: Rodar o teste e confirmar que passa**

Run: `mvn -pl backend/core-service -am -q test -Dtest=AuthServiceChangePasswordTest`
Expected: `BUILD SUCCESS`, 4 testes passando.

- [ ] **Step 6: Commit**

```bash
git add backend/core-service
git commit -m "feat(auth): endpoint POST /api/auth/password (verifica senha atual)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Método `changePassword` no SDK (`crystal-core`)

**Files:**
- Modify: `plugins/crystal-core/src/main/java/com/redecrystal/core/http/BackendHttpClient.java` (bloco `// ── Auth (player JWT) ──`)

**Interfaces:**
- Produces: `BackendHttpClient.changePassword(String uuid, String currentPassword, String newPassword)` — `void`; propaga `BackendException` (com `statusCode`) em falha.
- Consumes: endpoint `POST /api/auth/password` da Task 2.

Sem teste automatizado (wrapper HTTP fino, seguindo o padrão dos métodos vizinhos, que não têm teste unitário).

- [ ] **Step 1: Adicionar o método**

Em `BackendHttpClient.java`, no bloco de Auth, após `refresh(...)`:

```java
    /** Change a cracked account's password after verifying the current one. */
    public void changePassword(String uuid, String currentPassword, String newPassword) {
        send("POST", "/api/auth/password", Map.of(
                "uuid", uuid, "currentPassword", currentPassword, "newPassword", newPassword));
    }
```

- [ ] **Step 2: Compilar**

Run: `mvn -pl plugins/crystal-core -am -q compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add plugins/crystal-core
git commit -m "feat(sdk): BackendHttpClient.changePassword

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Comando `/trocarsenha` no proxy (`crystal-bungee`)

**Files:**
- Create: `plugins/crystal-bungee/src/main/java/com/redecrystal/bungee/command/ChangePasswordCommand.java`
- Modify: `plugins/crystal-bungee/src/main/java/com/redecrystal/bungee/listener/ConnectionRoutingListener.java` (tornar `LOGIN_SERVER` público)
- Modify: `plugins/crystal-bungee/src/main/java/com/redecrystal/bungee/CrystalBungeePlugin.java` (registrar o comando no `onInit`)

**Interfaces:**
- Consumes: `crystal.backend().changePassword(...)` (Task 3); `ConnectionRoutingListener.LOGIN_SERVER`.
- Produces: comando de proxy `trocarsenha` (alias `mudarsenha`).

Sem teste automatizado (comando Velocity) — verificação manual em jogo ao final.

- [ ] **Step 1: Expor `LOGIN_SERVER` como constante pública**

Em `ConnectionRoutingListener.java`, trocar:

```java
    private static final String LOGIN_SERVER = "login";
```

por:

```java
    /** Name of the login server in velocity.toml — the only server a non-authed player may reach. */
    public static final String LOGIN_SERVER = "login";
```

- [ ] **Step 2: Criar o `ChangePasswordCommand`**

Criar `plugins/crystal-bungee/src/main/java/com/redecrystal/bungee/command/ChangePasswordCommand.java`:

```java
package com.redecrystal.bungee.command;

import com.redecrystal.bungee.BrandColors;
import com.redecrystal.bungee.listener.ConnectionRoutingListener;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.BackendHttpClient.BackendException;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

/**
 * {@code /trocarsenha <atual> <nova> <confirmar>} (alias {@code /mudarsenha}):
 * lets an authenticated player change their own password from anywhere on the
 * network. Registered on the proxy so it covers every backend server at once and
 * the typed password is never forwarded to (or logged by) a game server. Players
 * still on the login server aren't authenticated yet and are turned away.
 */
public final class ChangePasswordCommand implements SimpleCommand {

    private static final int MIN_PASSWORD_LENGTH = 3;
    private static final int MAX_PASSWORD_LENGTH = 64;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final List<String> HINTS = List.of("<senha_atual>", "<nova_senha>", "<confirmar>");

    private final Object plugin;
    private final ProxyServer proxy;
    private final CrystalCore crystal;
    private final Logger logger;

    public ChangePasswordCommand(Object plugin, ProxyServer proxy, CrystalCore crystal, Logger logger) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.crystal = crystal;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("Apenas jogadores.", NamedTextColor.RED));
            return;
        }
        // Only players past the login gate may change a password: anyone still on
        // the login server hasn't proven their identity yet.
        String server = player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("");
        if (server.isEmpty() || server.equals(ConnectionRoutingListener.LOGIN_SERVER)) {
            player.sendMessage(Component.text("Faça login primeiro.", NamedTextColor.RED));
            return;
        }
        String[] args = invocation.arguments();
        if (args.length < 3) {
            player.sendMessage(Component.text(
                    "Uso: /trocarsenha <atual> <nova> <confirmar>", BrandColors.PURPLE_SOFT));
            return;
        }
        String current = args[0];
        String next = args[1];
        String confirm = args[2];
        if (!next.equals(confirm)) {
            player.sendMessage(Component.text("As senhas não conferem.", NamedTextColor.RED));
            return;
        }
        if (next.length() < MIN_PASSWORD_LENGTH || next.length() > MAX_PASSWORD_LENGTH) {
            player.sendMessage(Component.text(
                    "A senha deve ter entre " + MIN_PASSWORD_LENGTH + " e " + MAX_PASSWORD_LENGTH + " caracteres.",
                    NamedTextColor.RED));
            return;
        }
        // HTTP off the caller thread; feedback is a plain message (thread-safe).
        String uuid = player.getUniqueId().toString();
        proxy.getScheduler().buildTask(plugin, () -> {
            try {
                crystal.backend().changePassword(uuid, current, next);
                player.sendMessage(Component.text("Senha alterada com sucesso!", NamedTextColor.GREEN));
            } catch (BackendException e) {
                if (e.statusCode() == HTTP_UNAUTHORIZED) {
                    player.sendMessage(Component.text("Senha atual incorreta.", NamedTextColor.RED));
                } else {
                    player.sendMessage(Component.text(
                            "Não foi possível trocar a senha agora. Tente novamente.", NamedTextColor.RED));
                    logger.warn("changePassword failed for {}: {}", player.getUsername(), e.toString());
                }
            }
        }).schedule();
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        int idx = invocation.arguments().length; // position of the arg being typed
        return idx < HINTS.size() ? List.of(HINTS.get(idx)) : List.of();
    }
}
```

- [ ] **Step 3: Registrar o comando no `onInit`**

Em `CrystalBungeePlugin.java`, adicionar o import:

```java
import com.redecrystal.bungee.command.ChangePasswordCommand;
```

E, no `onInit`, após o registro dos listeners (depois do bloco
`events.register(this, new ConnectionRoutingListener(...))`):

```java
        // Network-wide self-service password change (see ChangePasswordCommand).
        var commandManager = proxy.getCommandManager();
        var passwordMeta = commandManager.metaBuilder("trocarsenha")
                .aliases("mudarsenha")
                .plugin(this)
                .build();
        commandManager.register(passwordMeta, new ChangePasswordCommand(this, proxy, crystal, logger));
```

- [ ] **Step 4: Compilar**

Run: `mvn -pl plugins/crystal-bungee -am -q package`
Expected: `BUILD SUCCESS`; jar shaded em `plugins/crystal-bungee/target/crystal-bungee.jar`.

- [ ] **Step 5: Commit**

```bash
git add plugins/crystal-bungee
git commit -m "feat(proxy): comando /trocarsenha (mudarsenha) em toda a rede

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Verificação manual em jogo

**Files:** nenhum — build + recriar containers e testar.

Pré-requisito: Tasks 1–4 commitadas e compiladas.

- [ ] **Step 1: Rebuild dos jars afetados**

Run: `make plugins`
Expected: jars atualizados em `plugins/*/target/`.

- [ ] **Step 2: Rebuild da imagem do backend + recriar serviços**

O `core-service` é imagem buildada de `./backend`; o login e o proxy copiam os
jars montados no boot, então precisam ser recriados.

Run:
```bash
docker compose up -d --build --force-recreate --no-deps core-service
docker compose up -d --force-recreate --no-deps login-01 proxy-01
```
Expected: `core-service` volta `healthy`; `login-01` e `proxy-01` sobem.

- [ ] **Step 3: Testar auto-complete e aliases (login)**

Conectar um cliente e, no servidor de login, verificar:
- digitar `/` mostra `login`, `logar`, `l`, `registrar`, `register`, `reg` no menu;
- `/login ` + Tab sugere `<senha>`; `/registrar ` + Tab sugere `<senha>` e o 2º arg `<repita_a_senha>`;
- registrar uma conta nova com `/register <senha> <senha>` e depois entrar com `/logar <senha>` funcionam.

- [ ] **Step 4: Testar `/trocarsenha` (rede)**

Já autenticado (no lobby):
- `/trocarsenha <atual> <nova> <nova>` → "Senha alterada com sucesso!";
- reconectar e logar com a **nova** senha funciona; com a **antiga**, falha;
- `/trocarsenha <errada> <nova> <nova>` → "Senha atual incorreta.";
- `/trocarsenha a b c` (b≠c) → "As senhas não conferem.";
- `/mudarsenha` funciona igual (alias);
- no servidor de login (pré-auth), `/trocarsenha ...` → "Faça login primeiro."

- [ ] **Step 5: Commit (se algum ajuste manual foi necessário)**

Se nada mudou no código, nada a commitar. Caso contrário, commitar o ajuste com
mensagem descritiva.

---

## Notas de verificação (self-review do plano vs spec)

- **Parte 1 (auto-complete + aliases):** Tasks 1 (declaração, completer, wiring,
  aliases) + verificação na Task 5. ✓
- **Parte 2 (troca de senha):** backend (Task 2), SDK (Task 3), comando de proxy
  (Task 4), verificação (Task 5). ✓
- **Segurança da senha:** completer só devolve texto estático; comando de proxy
  não é repassado ao backend; backend exige senha atual (bcrypt). ✓
- **Nomes/tipos consistentes:** `changePassword(uuid, currentPassword, newPassword)`
  em `AuthService` (UUID) e `BackendHttpClient` (String uuid); `ChangePasswordRequest`
  no controller; `ConnectionRoutingListener.LOGIN_SERVER` reutilizado. ✓
- **Fora de escopo:** GUI de senha, recuperação/2FA, senha para premium. ✓
