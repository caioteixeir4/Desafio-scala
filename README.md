# Code Challenge — Bookmarks do meta.discourse.org

Robô em Scala 3 que autentica no fórum, coleta todos os bookmarks do usuário (título, URL e autor da postagem marcada) e grava `output.json`. Detalhes da investigação estão no [RECON.md](RECON.md).

## Como rodar

Requisito: Docker.

1. Crie o arquivo de credenciais (ele está no `.gitignore`):

   ```bash
   cp .env.example .env
   ```

   Preencha `DISCOURSE_USERNAME` (email ou usuário) e `DISCOURSE_PASSWORD`. Defina também `USER_AGENT` com seu nome e contato.

2. Rode o robô:

   ```bash
   docker compose run --rm app sbt -batch run
   ```

3. Rode os testes (não usam rede):

   ```bash
   docker compose run --rm app sbt -batch test
   ```

O `output.json` é gerado na raiz do projeto.

### Variáveis de ambiente

| Variável | Obrigatória | Padrão |
|---|---|---|
| `DISCOURSE_USERNAME` | sim | |
| `DISCOURSE_PASSWORD` | sim | |
| `USER_AGENT` | não | `oystr-code-challenge-bot/0.1 (...)`; defina com seu contato |
| `DISCOURSE_BASE_URL` | não | `https://meta.discourse.org` |
| `MIN_REQUEST_INTERVAL_MS` | não | `1000` |
| `OUTPUT_PATH` | não | `output.json` |
| `RAW_DUMP_DIR` | não | vazio; se definido, salva a resposta crua de cada página (útil para gerar fixtures) |

### Formato do output.json

```json
{
  "source": "https://meta.discourse.org",
  "username": "Caio_Teixeira",
  "collectedAt": "2026-11-05T23:52:50Z",
  "total": 20,
  "bookmarks": [
    { "title": "...", "url": "https://meta.discourse.org/t/...", "author": "..." }
  ]
}
```

`author` é o autor da postagem marcada, não o dono do bookmark.

### Códigos de saída

| Código | Significado |
|---|---|
| 0 | sucesso |
| 2 | variável de ambiente obrigatória ausente |
| 10 | credencial inválida |
| 11 | conta não ativada |
| 12 | segundo fator exigido |
| 20 | site fora do ar |
| 21 | rate limit (HTTP 429) |
| 30 | login sem evidência de sessão autenticada |
| 31 | resposta inesperada |
| 40 | falha na coleta |

## Estrutura

```
src/main/scala/challenge/
├── config/     leitura de configuração (variáveis de ambiente)
├── auth/       login do Discourse e erros tipados
├── http/       cliente HTTP, cookies, rate limit, User-Agent
├── parse/      parsers puros de login e de bookmarks (sem rede)
├── collector/  paginação dos bookmarks
└── model/      Bookmark e formato do output.json
src/test/       testes; a coleta paginada usa um servidor simulado
```

## Decisões de design

- **Parsing separado da rede.** `LoginParser` e `BookmarkParser` recebem status e corpo e não fazem I/O, então são testáveis sem rede. A coleta paginada é testada com um backend HTTP simulado.
- **Erros tipados no login.** `AuthError` distingue credencial errada, conta não ativada, 2FA, rate limit e site fora do ar, e cada um tem código de saída próprio.
- **Evidência positiva de sessão.** Depois do `POST /session`, o robô só segue se `/session/current.json` devolver `current_user.username`.
- **Sessão do zero a cada execução**, com cookie jar vazio. Nenhum cookie ou token é guardado no código.
- **Paginação guiada pela resposta.** O robô segue `more_bookmarks_url` até ela sumir. Não há contagem de páginas fixa; existe só uma trava de 1000 páginas contra loop infinito, e a coleta para em página vazia ou link repetido.
- **Falha explícita em vez de dado inventado.** Se o autor de um bookmark não vier na resposta, o parser retorna erro.
- **Cuidado com o alvo.** Requisições sequenciais, no mínimo 1 segundo entre elas, User-Agent identificável. O login passa por `/session`, caminho bloqueado no `robots.txt`; isso é uma exceção deliberada, explicada no RECON.md.
- **Bibliotecas:** sttp (HTTP genérico), circe (JSON) e munit (testes). Nenhuma específica do Discourse.
- **Segredos** só por variável de ambiente / `.env`, ignorado pelo git e pelo Docker.

## O que ficou faltando

- **Menos de 30 bookmarks.** A conta tem 20; o site deu erro ao marcar mais. A paginação foi validada com simulação, mas não contra o site real com mais de uma página, e o nome do campo `more_bookmarks_url` não foi confirmado numa resposta real.
- **Verificação em duas etapas (bônus)** não foi implementada; o robô só detecta a exigência e termina com erro tipado.
- **Fixtures reais.** Os testes do parser de bookmarks usam JSON sintético. Falta trocá-lo por respostas reais salvas com `RAW_DUMP_DIR`.
- **Os campos de erro de 2FA e de conta não ativada** foram escritos de memória e não verificados contra o site.
- **Termos de uso** do fórum devem ser conferidos por quem entrega.
