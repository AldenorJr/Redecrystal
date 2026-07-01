# Auto-complete no login + comando de troca de senha

**Data:** 2026-07-01
**Módulos:** `plugins/crystal-login`, `plugins/crystal-bungee`, `plugins/crystal-core`, `backend/core-service`
**Status:** desenho aprovado, pronto para plano de implementação

## Objetivo

Três melhorias na autenticação da rede:

1. **Auto-complete** nos comandos de login/registro (hoje não completam nada,
   porque não são comandos registrados).
2. Novo comando **`/trocarsenha`** (alias `/mudarsenha`) para o jogador já
   logado trocar a própria senha, **em toda a rede** (todos os servidores de
   jogo, menos o servidor de login).
3. Padronizar **aliases**: `login`/`logar`/`l` e `registrar`/`register`/`reg`.

Restrições invioláveis do projeto que guiam o desenho:

- **A senha nunca pode ser logada, transmitida nem sugerida no tab-complete.**
- **Nada bloqueia a main thread** — chamadas HTTP ao backend vão para fora da
  thread principal e o feedback volta antes de tocar a API do servidor.
- Plugins falam **só com o API Gateway**, com o service-token (bearer).

## Parte 1 — Auto-complete e aliases no login (`crystal-login`)

### Por que hoje não completa

Os comandos de auth **não são registrados**: são interceptados no
`CommandFilterListener` (`PlayerCommandPreprocessEvent`) e cancelados, de
propósito, para a senha nunca ser logada/transmitida. Como o comando não existe
no grafo de comandos do cliente, não há o que completar.

### Solução

Registrar os comandos **apenas como cascas** para popular o grafo de comandos do
cliente, mantendo o `CommandFilterListener` como **único handler real**. O
cancelamento no preprocess continua sendo o que impede a senha de ir ao console
— a "casca" registrada nunca chega a despachar.

`plugin.yml` (novo bloco `commands:`):

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

No `onEnable`, setar um `TabCompleter` em cada comando devolvendo **placeholders
estáticos** (texto fixo, nunca dado real):

- `/login`:
  - arg 1 → `["<senha>"]`;
  - para quem tem `crystal.login.admin`, arg 1 inclui também `manutencao` e
    `setspawn`; arg 2 de `manutencao` → `["<senha>"]`.
- `/registrar`:
  - arg 1 → `["<senha>"]`; arg 2 → `["<repita_a_senha>"]`.

O `CommandFilterListener` passa a reconhecer os novos aliases no `switch`:

- case do login: `/login`, `/logar`, `/l`;
- case do registro: `/registrar`, `/register`, `/reg`.

A lista de aliases no `plugin.yml` e no `switch` deve ficar **consistente**.

### Segurança

Senha nunca é sugerida (completer só devolve texto estático) e nunca é logada (o
preprocess continua cancelando antes do dispatch). Mantém a intenção original do
desenho de interceptação.

## Parte 2 — Troca de senha

### Backend (`core-service`)

Novo endpoint no `AuthController`:

```
POST /api/auth/password
body: { uuid: UUID, currentPassword: String, newPassword: String }
```

`AuthService.changePassword(uuid, currentPassword, newPassword)`:

1. Carrega a conta por `uuid`; ausente → `UnauthorizedException` (401).
2. Conta **premium** não tem senha → `ConflictException` (409, "conta premium
   não usa senha") — a mesma exceção que o `register` já usa para conflito.
3. Verifica `currentPassword` via `BCryptPasswordEncoder.matches` contra o hash
   atual; não confere (ou conta sem senha) → 401 ("senha atual incorreta").
4. Valida `newPassword` (não-branco no backend; tamanho 3–64 é validado no
   proxy antes de chamar).
5. `account.setPasswordHash(encode(newPassword))` + `repository.save`.
6. Resposta 200 simples (sem corpo relevante). **Não** reemite token — a sessão
   ativa continua válida.

- **Gateway:** `/api/auth/password` cai em `/api/**`, já protegido pelo
  `ServiceTokenAuthFilter`. Sem alteração.
- **Migration:** nenhuma — usa a tabela `player_accounts` existente e o
  `setPasswordHash` que já existe no domínio.

### SDK (`crystal-core`)

Novo método no `BackendHttpClient`, no bloco `// ── Auth ──`:

```java
/** Change a cracked account's password after verifying the current one. */
public void changePassword(String uuid, String currentPassword, String newPassword) {
    send("POST", "/api/auth/password", Map.of(
            "uuid", uuid, "currentPassword", currentPassword, "newPassword", newPassword));
}
```

Erros de backend continuam propagando `BackendException` com `statusCode` (o
comando distingue 401 de outros).

### Comando no proxy (`crystal-bungee`)

O comando mora **no proxy** porque:

- o proxy já roda o `CrystalCore` SDK → tem `crystal.backend()`;
- o gate `onServerPreConnect` garante que **só jogador autenticado sai do
  servidor de login** — logo "servidor atual ≠ `login`" já prova auth e
  implementa exatamente "rede toda menos login";
- comandos registrados no Velocity são interceptados **no proxy** e não
  repassados ao backend → a senha nem é encaminhada nem logada.

`SimpleCommand` registrado via `proxy.getCommandManager().register(meta, cmd)`
no `onInit`, com meta `trocarsenha` + alias `mudarsenha`.

`execute(invocation)`:

1. Só jogador (`Player`); console → mensagem e retorno.
2. **Guard de auth:** `player.getCurrentServer()` ausente **ou** nome igual a
   `login` (constante `LOGIN_SERVER` já usada no `ConnectionRoutingListener`) →
   "Faça login primeiro." Reaproveitar/expor essa constante para não duplicar o
   literal.
3. Args: exige `<atual> <nova> <confirmar>` (3 args). Faltando → mostra uso.
4. `nova != confirmar` → "As senhas não conferem."
5. Tamanho de `nova` fora de 3–64 → "A senha deve ter entre 3 e 64 caracteres."
   (mesmas constantes do `crystal-login`, replicadas como constantes locais).
6. **Async** (`proxy.getScheduler().buildTask(...)`): chama
   `crystal.backend().changePassword(uuid, atual, nova)`; feedback ao jogador
   dentro do callback (a API do Velocity para enviar mensagem é thread-safe;
   ainda assim o HTTP fica fora de qualquer caminho de evento).

`suggest(invocation)`: placeholders estáticos por posição
(`<senha_atual>`, `<nova_senha>`, `<confirmar>`), nunca dado real.

Mensagens em **PT** via Adventure/MiniMessage, no estilo das já usadas
(`<#b14aed>`/`<green>`/`<red>`). O comando **nunca** ecoa a senha digitada.

### Tratamento de erro (comando)

- `BackendException` com `statusCode == 401` → "Senha atual incorreta."
- Qualquer outro erro (transporte, 5xx, premium/409) → "Não foi possível trocar
  a senha agora. Tente novamente." + log no proxy. (Premium não deveria chegar
  aqui na prática, pois premium não passa por senha; a mensagem genérica cobre.)

## Fluxo de dados (troca de senha)

```
jogador → /trocarsenha (Velocity, proxy)
  → valida local (contagem, igualdade, tamanho)
  → async: BackendHttpClient.changePassword
    → API Gateway (Bearer service-token)
      → core-service AuthController POST /api/auth/password
        → AuthService: verifica atual (bcrypt), grava nova (setPasswordHash), save
      ← 200
  ← callback no proxy → mensagem de sucesso
```

## Componentes e arquivos afetados

- `plugins/crystal-login/src/main/resources/plugin.yml` — bloco `commands:`.
- `plugins/crystal-login/.../CrystalLoginPlugin.java` — setar `TabCompleter`(s)
  no `onEnable` (ou classe dedicada de completer).
- `plugins/crystal-login/.../listener/CommandFilterListener.java` — aliases
  `/logar` e `/register` no `switch`.
- `plugins/crystal-core/.../http/BackendHttpClient.java` — `changePassword`.
- `backend/core-service/.../auth/api/AuthController.java` — endpoint + DTO.
- `backend/core-service/.../auth/application/AuthService.java` — `changePassword`.
- `plugins/crystal-bungee/.../` — nova classe de comando `TrocarSenhaCommand`
  (SimpleCommand) + registro no `CrystalBungeePlugin.onInit`; possivelmente
  expor `LOGIN_SERVER` como constante compartilhada.

## Testes

- **Backend:** teste de `AuthService.changePassword` (unit/IT no padrão do
  projeto):
  - senha atual errada → 401 e hash inalterado;
  - conta premium → rejeitada;
  - sucesso → hash muda, a senha **antiga** deixa de autenticar e a **nova**
    autentica.
- **Plugin/proxy:** verificação manual em jogo (rebuild dos jars + recriar os
  containers afetados: `login-01`, `proxy-01`):
  - tab-complete mostra os nomes dos comandos e as dicas estáticas;
  - `/login`, `/logar`, `/l`, `/registrar`, `/register`, `/reg` funcionam;
  - `/trocarsenha` no lobby: sucesso, senha atual errada, senhas não conferem,
    tamanho inválido; e, no servidor de login, bloqueado com "Faça login
    primeiro".

## Fora de escopo

- GUI para troca de senha (o projeto é GUI-first, mas senha em GUI é
  desconfortável/insegura de exibir; mantém-se via comando).
- Recuperação de senha esquecida, e-mail, 2FA.
- Troca de senha para contas premium (não têm senha).
