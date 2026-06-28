# CLAUDE.md

Orientações para agentes/IA e devs trabalhando neste repositório.

## Padrões de código (obrigatório)

**Antes de escrever código, leia [`docs/CODING_STANDARDS.md`](docs/CODING_STANDARDS.md).**
É o padrão de _clean code_ canônico do projeto. Resumo do que mais importa:

- **Escreva código que se pareça com o vizinho** — copie seções (`// ── nome ──`),
  idioma e densidade de comentário do arquivo ao lado.
- **Idioma:** código e comentários em **inglês**; texto de jogador e docs em **PT**.
- **Nunca bloqueie a main thread do Bukkit.** HTTP/DB em
  `runTaskAsynchronously`, volte com `runTask` antes de tocar a API do jogo.
- **DTO é `record`**, serviço/utilitário é `final`, conjunto fechado é `enum` com
  dados. Constantes em vez de literais mágicos.
- **Comente o _porquê_**, não o _quê_.
- **Erros de backend têm fallback** — nada derruba o servidor.
- **Limpe o que cria** (entidades/recursos) no quit/disable.
- **Backend:** camadas `api/application/domain`; plugins falam só com o **API
  Gateway**; um banco por serviço; _optimistic locking_ em escrita concorrente.
- **UX é GUI-first** (ver README): feature de jogador começa por menu GUI.

Siga o **checklist de PR** da seção 13 do guia antes de considerar algo pronto.

## Build / validação rápida

- Compilar um módulo: `mvn -pl plugins/<módulo> -am compile`
- Plugins (jars shaded): `make plugins`
- Subir/observar: `docker compose up -d` · logs: `docker compose logs <serviço>`
- Validar plugin em jogo: rebuild → **recriar** o container
  (`docker compose up -d --force-recreate --no-deps lobby-01 …`), pois a imagem
  copia o jar montado no boot.

## Mapa do projeto

Ver [`README.md`](README.md) para arquitetura, topologia do backend e fases.
