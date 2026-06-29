# Padrões de Código — RedeCrystal

> Guia de _clean code_ do projeto. Não é um tratado genérico: descreve **o padrão
> que o código já segue**, com exemplos reais do repositório, para que tudo que
> entrar daqui pra frente continue consistente.
>
> Regra de ouro: **escreva código que se pareça com o código ao redor.** Antes de
> criar um arquivo novo, leia um vizinho e siga a densidade de comentários, a
> nomenclatura e os idiomas dele.

---

## 1. Princípios

1. **Clareza vence esperteza.** Código é lido muito mais vezes do que escrito.
   Prefira o óbvio ao engenhoso.
2. **Comente o _porquê_, não o _quê_.** O código já diz o que faz; o comentário
   existe para explicar a decisão, o trade-off ou a armadilha. Veja como o
   `plugins/pom.xml` explica _por que_ existe o `shade` com relocation, ou como
   `LobbyHotbar.followPets()` explica a escolha de interpolar por tick.
3. **Funções pequenas, com um propósito.** Se você precisa de um comentário no
   meio da função para marcar "agora começa a parte X", provavelmente X é um
   método.
4. **Sem estado escondido / sem surpresas.** Estado de sessão é limpo no `onQuit`;
   recursos são fechados no `onDisable`/`close()`. O que é criado é destruído.
5. **Falhe de forma previsível.** Chamada de backend que pode falhar tem fallback
   sensato (lista vazia, `null` tratado), nunca derruba a _main thread_ do
   servidor.
6. **YAGNI.** Não adicione abstração "para o futuro". O `ProfileData`/`inventory`
   vivem dentro do `core-service` por decisão consciente ("core-service for now"),
   prontos para virar microsserviço só **quando** precisar.

---

## 2. Idioma e nomenclatura

- **Código e comentários: inglês.** Identificadores, Javadoc e comentários inline
  são em inglês (`activeCosmetic`, `spawnPet`, `// re-apply the active hat`).
  É o padrão de todo o `src/`.
- **Texto exposto ao jogador e docs de equipe: português.** Mensagens in-game,
  nomes de itens de menu e este guia são em PT (`"§cRemover chapéu"`,
  `"Sem permissão."`).
- **Nomes revelam intenção.** `removeOrphanPets`, `cosmeticsVersion`,
  `applyAppearance` — sem abreviações obscuras, sem `data2`/`tmp`/`flag`.
- **Convenções Java:** `PascalCase` para tipos, `camelCase` para
  métodos/campos, `UPPER_SNAKE_CASE` para constantes (`VIP_PERM`, `PET_TAG`,
  `COSMETICS_TYPE`).
- **Constantes em vez de literais mágicos.** Permissões, chaves de config e tags
  viram `private static final String` no topo da classe
  (`CrystalLobbyPlugin`: `CONFIG_KEY`, `ADMIN_PERM`, `MAINT_PERM`).

---

## 3. Organização do arquivo

Ordem dentro de uma classe (como em `LobbyHotbar`):

1. Constantes (`static final`).
2. Campos de instância.
3. Construtor.
4. Tipos aninhados (`enum`, `record`) usados pela classe.
5. Ciclo de vida (`start`/`shutdown`, `onEnable`/`onDisable`).
6. Handlers de evento e lógica, **agrupados por tema** com divisores:

```java
    // ── locks ──
    // ── cosmetics ──
    // ── wardrobe (hats + armour) ──
    // ── persistence (cosmetic preferences, cross-session) ──
    // ── item builders ──
```

7. Helpers privados pequenos por último.

Use o divisor `// ── nome ──` para separar seções de uma classe grande. É o
padrão do projeto e torna arquivos longos navegáveis.

### 3.1 Estrutura de packages: `listener/` e `commands/` (obrigatório)

Cada plugin separa **listeners** e **comandos** em packages dedicados sob o seu
package base (`com.redecrystal.<módulo>`). A classe principal `Crystal…Plugin`
**não** implementa `Listener` nem contém `onCommand`: ela só inicializa o SDK,
constrói os serviços, **registra** os listeners/comandos e cuida do ciclo de
vida. `crystal-lobby` é a implementação de referência.

```
com.redecrystal.lobby
├── CrystalLobbyPlugin.java      // boot + registro, sem @EventHandler/onCommand
├── listener/                    // tudo que reage a eventos
│   ├── PlayerJoinListener.java
│   ├── PlayerQuitListener.java
│   ├── LobbyProtection.java     // listener coeso (ver exceção abaixo)
│   ├── LobbyScoreboard.java
│   └── LobbyHotbar.java
└── commands/                    // um arquivo por comando
    ├── LobbyCommand.java
    └── MaintenanceCommand.java
```

**Listeners — `<base>.listener`:**

- **Regra:** cada evento independente vive na sua própria classe `…Listener`
  (sufixo `Listener`), com um único `@EventHandler`, nomeada pelo evento/assunto
  (`PlayerJoinListener`), não pela classe do plugin.
- **Exceção (agrupar por coesão):** uma _feature_ coesa e/ou com estado
  compartilhado — um fluxo de GUI (`LobbyHotbar`), um conjunto de regras de
  proteção (`LobbyProtection`), um placar (`LobbyScoreboard`) — mantém os seus
  handlers relacionados em **uma só** classe listener, nomeada pela _feature_.
  Quebrar estado compartilhado entre N classes custa mais do que ajuda.
- Dependências entram **pelo construtor** (`plugin`, `crystal`, serviços). Sem
  singletons/estática para passar estado.

**Comandos — `<base>.commands`:**

- **Um arquivo por comando**, implementando `CommandExecutor` (e `TabCompleter`
  quando houver _tab-complete_). Nome = comando + `Command` (`LobbyCommand`,
  `MaintenanceCommand`).
- Registrado na classe principal: `getCommand("lobby").setExecutor(new
  LobbyCommand(this, crystal));`. O comando continua declarado no `plugin.yml`.
- Permissões e _config keys_ específicas do comando moram **no** comando
  (`private static final String ADMIN_PERM = …`), não na classe principal.

**Registro na classe principal (`onEnable`):**

```java
var pm = getServer().getPluginManager();
pm.registerEvents(new PlayerJoinListener(this, crystal), this);
pm.registerEvents(new PlayerQuitListener(crystal), this);
pm.registerEvents(new LobbyProtection(this), this);
getCommand("lobby").setExecutor(new LobbyCommand(this, crystal));
getCommand("manutencao").setExecutor(new MaintenanceCommand(this, crystal));
```

**Proxy Velocity (`crystal-bungee`):** mesmo princípio adaptado ao idioma do
Velocity — listeners `@Subscribe` em `listener/` (registrados via
`EventManager`), comandos em `commands/` (via `CommandManager`). A lógica coesa
de proxy (descoberta de fleet, roteamento) pode ficar agrupada pela mesma
exceção de coesão.

---

## 4. Tipos, imutabilidade e dados

- **DTO é `record`.** Dado transportado entre camadas/serviços é `record` imutável:
  `ProfileData`, `InventoryData`, `NetworkServer`, `RemoteConfig`. Inclua
  comportamento trivial no próprio record quando fizer sentido
  (`InventoryData.isEmpty()`).
- **Classes utilitárias/serviços são `final`.** `LobbyHotbar`, `BackendHttpClient`,
  `Json` — `final` sinaliza "não estenda isto".
- **Construtor privado para _holder_ estático.** `Json` tem `private Json() {}`.
- **`enum` para conjuntos fechados com dados.** Cosméticos, chapéus, pets e
  armaduras são `enum` com campos (`Hat(Material material, String name, boolean
  vip)`), não constantes soltas. Coloque a lógica derivada no enum
  (`Armor.isRemove()`).
- **Imutabilidade onde der.** Campos `final`; devolva cópia defensiva quando
  expor estado mutável (`CrystalLobbyPlugin.getSpawn()` retorna `spawn.clone()`).
- **Coleções concorrentes para estado tocado por mais de uma thread.**
  `ConcurrentHashMap` para os mapas de estado por jogador.

---

## 5. Comentários e Javadoc

- **Javadoc de classe explica o papel e o porquê**, não repete o nome. Exemplo
  (`CrystalLobbyPlugin`): _"Boots the SDK, self-registers, and serves the lobby
  experience…"_.
- **Comente decisões não óbvias.** `LobbyHotbar.spawnPet()`:
  `// No gravity: movement is driven entirely by our per-tick interpolation`.
- **Não comente o óbvio.** Nada de `// increment i`.
- **Marque dívidas/limites explicitamente.** Se algo é stub, simplificação ou
  "por enquanto", diga (o README usa "core-service for now").

---

## 6. Tratamento de erros

- **Exceções tipadas no domínio.** `BackendHttpClient.BackendException`,
  `ConflictException` (web 409) — nada de `throw new RuntimeException("...")` cru.
- **Retry só onde é seguro.** O `BackendHttpClient.send` tenta 3x **erros de
  transporte** com backoff, mas **não** repete respostas não-2xx (não-retryável).
- **Fallback explícito no chamador.** Quem chama o backend decide o fallback:

```java
List<NetworkServer> fetched;
try {
    fetched = crystal.backend().listServers("lobby");
} catch (Exception e) {
    fetched = List.of();   // degrade: menu vazio, servidor não cai
}
```

- **`null` é tratado, não propagado por acidente.** `getProfile` devolve `null`
  em 404 e o chamador trata; `getInventory` devolve `("", 0)` em vez de estourar.
- **Nunca engula erro em silêncio sem motivo.** Se ignorar uma exceção, deixe
  claro o porquê (ex.: `catch (IllegalArgumentException ignored)` ao desserializar
  um enum que pode não existir mais).

---

## 7. Concorrência e a _main thread_ (Bukkit/Paper) — regra crítica

A API do Bukkit **não é thread-safe**. Quase tudo que toca mundo/jogador/inventário
**precisa** rodar na main thread.

- **I/O (HTTP, DB) nunca na main thread.** Faça a chamada em
  `runTaskAsynchronously` e volte para a main thread com `runTask` antes de tocar
  a API do jogo. Padrão canônico (`LobbyHotbar.loadCosmetics` / `openLobbys`):

```java
Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
    InventoryData data = crystal.backend().getInventory(id.toString(), COSMETICS_TYPE); // off-thread
    Bukkit.getScheduler().runTask(plugin, () -> applyLoaded(p, data)); // back on main
});
```

- **Tarefas repetitivas via scheduler**, com período pensado: `tickCosmetics` a
  cada 6 ticks (barato), `followPets` a cada 1 tick (precisa de suavidade). Comente
  a escolha do período quando não for óbvia.
- **Sempre cheque `p.isOnline()`** antes de aplicar algo a um jogador num callback
  atrasado/async — ele pode ter saído.
- **Limpe o que você cria.** Entidades spawnadas levam tag (`crystal_pet`) e são
  removidas no `onQuit`, no `shutdown` e por uma varredura de órfãos no `start`.

---

## 8. Backend (Spring) — camadas e fronteiras

Mantenha a separação DDD por pacote, já usada no `core-service`:

```
inventory/
├── api/         → Controller (HTTP, DTOs de request/response)
├── application/ → Service (regra de negócio, @Transactional)
└── domain/      → Entity, Repository, value objects (InventoryId)
```

- **Controller fino.** Só traduz HTTP ↔ chamada de serviço
  (`InventoryController` delega tudo para `InventoryService`). Sem regra de
  negócio no controller.
- **Regra de negócio no Service**, com `@Transactional` no nível certo (`readOnly`
  para leitura). O _optimistic locking_ vive no `InventoryService.save`, não no
  controller.
- **`@Transactional`**: leitura → `@Transactional(readOnly = true)`; escrita →
  `@Transactional`.
- **Fronteiras de rede invioláveis:**
  - Plugins falam com o backend **só pelo API Gateway** (bearer token), nunca com
    um serviço de domínio direto (`BackendHttpClient` aponta só pro gateway).
  - Autenticação acontece **só no gateway**.
  - **Um banco por serviço** (`core_db`, …) num Postgres compartilhado.
- **Construtor para injeção de dependência** (sem `@Autowired` em campo) — como
  `InventoryService(InventoryRepository, StringRedisTemplate)`.

---

## 9. Persistência e dados

- **Optimistic locking onde há escrita concorrente.** Linha versionada; um
  `save` precisa apresentar a versão lida, senão 409 (`InventoryService.save`,
  migração `V4__player_inventories.sql`). Reaproveite esse padrão — foi como a
  persistência de cosméticos foi feita, sob `serverType = "lobby-cosmetics"`, sem
  endpoint novo.
- **Entidade JPA:** construtor `protected` vazio "for JPA" + construtor real;
  mutação encapsulada em método de negócio (`InventoryEntity.update`), não setters
  públicos soltos.
- **Migrações Flyway versionadas e imutáveis** (`V<n>__nome.sql`). Nunca edite uma
  migração já aplicada — crie a próxima.
- **Cache com TTL/estratégia clara.** Redis é _write-through_ da versão; o
  comentário na migração registra essa decisão.

---

## 10. Minecraft / Bukkit — especificidades

- **Metadados de item via `PersistentDataContainer` + `NamespacedKey`**, nunca por
  comparação de nome/lore. As keys são criadas uma vez no construtor
  (`cosmeticKey`, `hatKey`, `petKey`, `armorKey`, `navKey`).
- **Texto:** `MiniMessage` para mensagens novas
  (`MM.deserialize("<green>Pet ativado!")`); o `§` _legacy_ aparece em nomes de
  item por consistência com o menu existente. Em mensagem nova, prefira
  `Component`/MiniMessage.
- **GUIs próprias usam um `InventoryHolder` marcador** (`MenuHolder(type)`) para
  diferenciar clique em menu nosso (cancelar + tratar) de clique no inventário do
  jogador (bloquear). Roteie o clique pelo `holder.type()`.
- **Itens de menu carregam a ação na PDC**, e o `handleMenuClick` despacha por
  `switch` no tipo do menu — adicione um `case` novo em vez de inflar um existente.
- **UX é GUI-first** (convenção já registrada no README): toda nova interação de
  jogador começa por menu GUI; comando só como atalho/admin/fallback.

---

## 11. Build e dependências (Maven)

- **Versões centralizadas no parent.** Toda versão de lib mora no
  `dependencyManagement` do `plugins/pom.xml` ou `backend/pom.xml`; o módulo só
  declara o `groupId/artifactId`.
- **`provided` para o que o runtime fornece** (Paper, Velocity, WorldEdit,
  LuckPerms) — nunca shade a API do servidor.
- **`shade` com relocation** para libs do SDK (Jackson/Lettuce/Netty/Kafka/...),
  evitando conflito com classes do servidor. `slf4j` fica de fora (o servidor
  provê). Não mexa nas relocations sem entender o porquê (documentado no pom).
- **Java 21** em todo lugar (backend, SDK, plugins).

---

## 12. Testes

- **JUnit 5** (`junit-jupiter`), já gerenciado no parent.
- Teste a **lógica de negócio e os contratos** (serializações, locking, parsing de
  config). Não tente testar a API do Bukkit — extraia a lógica pura para um método
  testável.
- Há um caminho de **teste de integração ao vivo** contra a stack (`make sdk-it`)
  para validar SDK ↔ backend end-to-end.

---

## 13. Checklist de PR (_Definition of Done_)

Antes de abrir/encerrar uma mudança:

- [ ] Compila: `mvn -pl <módulo> -am compile` (ou `make plugins`).
- [ ] Segue o estilo do arquivo vizinho (seções, idioma, nomenclatura).
- [ ] Listeners em `listener/` e comandos em `commands/` (ver §3.1); a classe
      principal não implementa `Listener` nem tem `onCommand`.
- [ ] Nenhuma chamada bloqueante (HTTP/DB) na main thread do servidor.
- [ ] Recursos/entidades criados são limpos no quit/disable.
- [ ] Erros de backend têm fallback; nada derruba o servidor.
- [ ] Texto novo de jogador em PT; código/comentário em inglês.
- [ ] Constantes em vez de literais mágicos.
- [ ] Sem versão de dependência hardcoded no módulo (usar o parent).
- [ ] Para feature de jogador: começa por GUI.
- [ ] Validado de verdade quando aplicável (rebuild + recriar container, observar
      em jogo) — não afirme "funciona" sem evidência.

---

## 14. Enforcement (mecânico)

- **`.editorconfig`** (na raiz) já padroniza encoding, fim de linha, indentação e
  trailing whitespace — respeitado por IntelliJ/VS Code automaticamente.
- **Opcional (ainda não habilitado):** para garantir formatação no build,
  pode-se adicionar **Spotless** (`google-java-format` ou estilo próprio) e/ou
  **Checkstyle** no `pluginManagement` do parent, rodando em `verify`. Ficou de
  fora por enquanto para não quebrar o build/CI sem combinar; se quiserem,
  habilitamos com um perfil opt-in.

---

_Quando este guia e o código divergirem, o código vizinho de boa qualidade vence —
e atualize este guia._
