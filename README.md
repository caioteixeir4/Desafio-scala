# Code Challenge — Bookmarks do meta.discourse.org

Robô em Scala 3 que autentica no fórum, coleta todos os bookmarks do usuário (título, URL e autor da postagem marcada) e grava `output.json`. Detalhes da investigação estão no [RECON.md](RECON.md).

## Como rodar

Requisito: Docker.

1. Crie o arquivo de credenciais (ele está no `.gitignore`):

   ```bash
   cp .env.example .env
   ```

   Preencha `DISCOURSE_USERNAME` (email ou usuário) e `DISCOURSE_PASSWORD`. O `USER_AGENT` já tem um padrão com nome e contato do autor; quem for rodar com outra conta deve trocá-lo pelo próprio.

2. Rode o robô:

   ```bash
   docker compose run --rm app sbt -batch run
   ```

3. Rode os testes (não usam rede):

   ```bash
   docker compose run --rm app sbt -batch test
   ```

O `output.json` é gerado na raiz do projeto (ignorado pelo git). Um exemplo de saída real está em [examples/output.json](examples/output.json).

### Variáveis de ambiente

| Variável | Obrigatória | Padrão |
|---|---|---|
| `DISCOURSE_USERNAME` | sim | |
| `DISCOURSE_PASSWORD` | sim | |
| `USER_AGENT` | não | `oystr-code-challenge-bot/0.1 (Caio Teixeira; https://github.com/caioteixeir4)` |
| `DISCOURSE_BASE_URL` | não | `https://meta.discourse.org` |
| `MIN_REQUEST_INTERVAL_MS` | não | `1000`; tempo mínimo entre uma requisição e a seguinte, em ms (inteiro ≥ 0; valor inválido interrompe com erro) |
| `OUTPUT_PATH` | não | `output.json` |
| `RAW_DUMP_DIR` | não | vazio; se definido, salva a resposta crua de cada página (útil para gerar fixtures) |

### Formato do output.json

```json
{
  "source": "https://meta.discourse.org",
  "username": "Caio_Teixeira",
  "collectedAt": "2026-11-05T23:52:50Z",
  "total": 40,
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
| 2 | variável de ambiente obrigatória ausente, ou com valor inválido (ex.: letras em `MIN_REQUEST_INTERVAL_MS`) |
| 3 | `robots.txt` indisponível (5xx ou rede); o robô não segue por cautela |
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
- **Rate limit (HTTP 429) na coleta.** O robô respeita `Retry-After` (segundos ou data HTTP): espera e tenta de novo, no máximo 3 vezes por página. Sem o cabeçalho, espera 5 s. Se o servidor pedir mais de 60 s, desiste na hora com mensagem clara (código 40) em vez de ficar parado ou insistir. No login, o 429 não é repetido: vira o erro `RateLimited` (código 21).
- **Logout ao final.** Como a sessão nasce do zero a cada execução, o robô a encerra no fim (`DELETE /session/{usuario}`) e confirma que `/session/current.json` voltou a ser anônimo, para não deixar um token de login novo na conta a cada execução. O `output.json` é gravado antes, e uma falha no logout só gera aviso; não altera o código de saída.
- **Falha explícita em vez de dado inventado.** Se o autor de um bookmark não vier na resposta, o parser retorna erro.
- **Cuidado com o alvo.** Requisições sequenciais, no mínimo 1 segundo entre elas, User-Agent identificável.
- **`robots.txt` aplicado no código.** No início o robô baixa o `robots.txt` e toda requisição passa pelas regras (RFC 9309: regra mais longa vence, `*` e `$`). Um caminho bloqueado nem chega a ser requisitado. `robots.txt` 4xx libera tudo; 5xx ou falha de rede interrompe a execução. A única exceção é `/session`, necessária para o login, configurada em `Http` e explicada no RECON.md.
- **Bibliotecas:** sttp (HTTP genérico), circe (JSON) e munit (testes). Nenhuma específica do Discourse.
- **Segredos** só por variável de ambiente / `.env`, ignorado pelo git e pelo Docker.

## O que ficou faltando

- **Limite diário de favoritos no site.** O Discourse bloqueia (HTTP 429) a criação de mais de ~20 favoritos por dia; por isso a conta chegou a 40 favoritos em dias diferentes. A coleta real de 40 bookmarks (2 páginas) funcionou e a paginação por `more_bookmarks_url` foi confirmada no site.
- **Verificação em duas etapas (bônus)** não foi implementada; o robô só detecta a exigência e termina com erro tipado.
- **Bookmarks do tipo `Post`.** O parser e o coletor são testados contra duas páginas reais (`bookmarks-page-real-p0.json` e `-p1.json`), mas todos os 40 bookmarks são do tipo `Topic`. Favoritos de uma postagem específica (`Post`) não apareceram, então esse formato não foi verificado; o parser falha com erro explícito se faltar o autor.
- **Os campos de erro de 2FA e de conta não ativada** foram escritos de memória e não verificados contra o site.
- **Termos de uso do fórum.** Os termos (versão de 25/03/2026) proíbem acesso automatizado por programa que não seja navegador. O desafio exige exatamente isso, então a entrega não afirma conformidade; os detalhes e as mitigações estão no RECON.md (seção 4). Falta confirmar com quem propôs o desafio que a automação é autorizada.
