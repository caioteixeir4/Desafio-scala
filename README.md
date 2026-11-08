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

O `output.json` é gerado na raiz do projeto (ignorado pelo git). Há dois exemplos de saída real, um por conta usada no desafio (veja "Duas contas" abaixo): [examples/output.json](examples/output.json) (conta principal, sem 2FA, 40 bookmarks em 2 páginas) e [examples/output-2fa.json](examples/output-2fa.json) (conta de teste com 2FA, sem bookmarks).

### Trocar de conta

O robô lê as credenciais do arquivo indicado em `ENV_FILE` (padrão: `.env`). Para ter mais de uma conta, crie um arquivo por conta, todos fora do git (`.env.*` está no `.gitignore`):

```bash
cp .env.example .env.2fa
# preencher .env.2fa com a outra conta (e DISCOURSE_TOTP_SECRET, se ela usa 2FA)
```

Rodar com a conta padrão (`.env`):

```bash
docker compose run --rm app sbt -batch run
```

Rodar com a outra conta:

```bash
ENV_FILE=.env.2fa docker compose run --rm app sbt -batch run
```

No PowerShell:

```powershell
$env:ENV_FILE = ".env.2fa"; docker compose run --rm app sbt -batch run
Remove-Item Env:ENV_FILE   # volta ao .env padrão
```

Para não sobrescrever o `output.json` de outra conta, defina `OUTPUT_PATH=output-2fa.json` dentro do `.env.2fa` (arquivos `output-*.json` também ficam fora do git). Se o arquivo indicado não existir, o robô para com "Variavel de ambiente ausente" (código 2).

### Variáveis de ambiente

| Variável | Obrigatória | Padrão |
|---|---|---|
| `DISCOURSE_USERNAME` | sim | |
| `DISCOURSE_PASSWORD` | sim | |
| `USER_AGENT` | não | `oystr-code-challenge-bot/0.1 (Caio Teixeira; https://github.com/caioteixeir4)` |
| `DISCOURSE_BASE_URL` | não | `https://meta.discourse.org` |
| `DISCOURSE_TOTP_SECRET` | não | vazio; segredo base32 do app autenticador, só para contas com 2FA (veja "2FA") |
| `MIN_REQUEST_INTERVAL_MS` | não | `1000`; tempo mínimo entre uma requisição e a seguinte, em ms (inteiro ≥ 0; valor inválido interrompe com erro) |
| `OUTPUT_PATH` | não | `output.json` |
| `RAW_DUMP_DIR` | não | vazio; se definido, salva a resposta crua de cada página (útil para gerar fixtures) |

### Duas contas, de propósito

O desafio foi verificado com duas contas, cada uma com seu arquivo de credenciais (veja "Trocar de conta"):

| Conta | 2FA | Bookmarks | Arquivo | O que demonstra |
|---|---|---|---|---|
| principal (`Caio_Teixeira`) | não, como o enunciado manda para o requisito básico | 40 | `.env` | login, paginação real (2 páginas) e saída completa |
| de teste (`Caio_Teixeira_2FA`) | sim, por app autenticador | 0 | `.env.2fa` | bônus de 2FA sem intervenção manual, e o formato de lista vazia |

Manter as duas separadas deixa o requisito básico rodando sem 2FA, como pedido, e ainda prova o bônus no site real. Uma única conta com 2FA também funcionaria, desde que a chave esteja no `.env`.

### 2FA (bônus)

O robô suporta contas com verificação em duas etapas por **app autenticador (TOTP)**. Se `DISCOURSE_TOTP_SECRET` estiver preenchido (o segredo em base32 que o site mostra ao ativar o 2FA, aceito em minúsculas e com espaços), o robô gera o código de 6 dígitos na hora do login (RFC 6238) e o envia com a senha. Sem essa variável, o login é exatamente o de antes.

**Como ativar (uma vez por conta, feito pelo dono da conta):**

1. No fórum, logado: avatar → Preferências → Segurança → Autenticador → **Adicionar autenticador**.
2. Na janela "Adicionar autenticador", clique em **Inserir manualmente**: aparece a chave (32 caracteres, em grupos de 4). Copie-a **dessa mesma janela**, sem fechar nem recarregar a página; cada vez que a janela abre, o site gera uma chave nova.
3. Configure o app autenticador com a mesma janela (QR code ou a chave), digite o código de 6 dígitos que ele mostrar e clique em **Ativar**. Guarde os códigos de backup fora do repositório.
4. No arquivo de credenciais (`.env`, ou outro arquivo, veja "Trocar de conta"), preencha `DISCOURSE_TOTP_SECRET=` com a chave, sem espaços.
5. Espere cerca de 30 s (o código usado na ativação não vale de novo) e rode o robô normalmente. Nenhuma digitação é necessária nas execuções seguintes.

Se o login falhar com "Codigo TOTP rejeitado" mesmo depois de esperar, a chave do arquivo não é a que o app usa: confira se o código que o app mostra é o mesmo que um gerador TOTP calcula a partir da chave copiada, ou refaça os passos 1 a 4.

- O segredo é sensível: fica só no arquivo de credenciais (`.env` ou `.env.2fa`) e nunca aparece em log ou mensagem de erro. Guardá-lo ao lado da senha reduz a proteção do segundo fator; use só em conta de teste.
- O mesmo código não vale duas vezes: se o robô rodar de novo em menos de ~30 s, o login pode ser recusado. Espere e rode outra vez.
- Chave de segurança, passkey e códigos de backup não são suportados (código de saída 12).
- O robô **não ativa o 2FA nem descobre o segredo sozinho**: o dono da conta ativa uma vez no site (a chave aparece em "Inserir manualmente") e a coloca em `DISCOURSE_TOTP_SECRET`. A partir daí cada execução é automática.
- **Verificado no site real em 2026-11-08**, com uma conta de teste separada com 2FA (login, confirmação da sessão e logout). O gerador de código também é conferido com os vetores oficiais da RFC 6238, e o login com um servidor simulado. Detalhes no RECON.md (seção 7).

### Formato do output.json

```json
{
  "source": "https://meta.discourse.org",
  "username": "Caio_Teixeira",
  "collectedAt": "2026-11-05T23:52:50Z",
  "total": 40,
  "bookmarks": [
    { "title": "...", "url": "https://meta.discourse.org/t/...", "author": "..." }
  ],
  "warnings": []
}
```

`author` é o autor da postagem marcada, não o dono do bookmark.

`title` e `author` podem ser `null`, e `warnings` traz texto explicando o que veio incompleto (vazio quando está tudo certo). Veja "Dados incompletos" em Decisões de design.

### Códigos de saída

| Código | Significado |
|---|---|
| 0 | sucesso |
| 2 | variável de ambiente obrigatória ausente, ou com valor inválido (ex.: letras em `MIN_REQUEST_INTERVAL_MS`) |
| 3 | `robots.txt` indisponível (5xx ou rede); o robô não segue por cautela |
| 10 | credencial inválida |
| 11 | conta não ativada |
| 12 | segundo fator exigido e não satisfeito (sem `DISCOURSE_TOTP_SECRET`, código rejeitado, ou método não suportado) |
| 20 | site fora do ar |
| 21 | rate limit (HTTP 429) |
| 30 | login sem evidência de sessão autenticada |
| 31 | resposta inesperada |
| 40 | falha na coleta |

## Estrutura

```
src/main/scala/challenge/
├── config/     leitura de configuração (variáveis de ambiente)
├── auth/       login, logout e 2FA (TOTP) do Discourse, erros tipados
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
- **2FA opcional, sem biblioteca extra.** O código TOTP (RFC 6238) é gerado com `javax.crypto` e testado com os vetores oficiais da RFC. Só é enviado quando há `DISCOURSE_TOTP_SECRET`, então o login de uma conta sem 2FA continua idêntico.
- **Sessão do zero a cada execução**, com cookie jar vazio. Nenhum cookie ou token é guardado no código.
- **Paginação guiada pela resposta.** O robô segue `more_bookmarks_url` até ela sumir. Não há contagem de páginas fixa; existe só uma trava de 1000 páginas contra loop infinito, e a coleta para em página vazia ou link repetido.
- **Rate limit (HTTP 429) na coleta.** O robô respeita `Retry-After` (segundos ou data HTTP): espera e tenta de novo, no máximo 3 vezes por página. Sem o cabeçalho, espera 5 s. Se o servidor pedir mais de 60 s, desiste na hora com mensagem clara (código 40) em vez de ficar parado ou insistir. No login, o 429 não é repetido: vira o erro `RateLimited` (código 21).
- **Logout ao final.** Como a sessão nasce do zero a cada execução, o robô a encerra no fim (`DELETE /session/{usuario}`) e confirma que `/session/current.json` voltou a ser anônimo, para não deixar um token de login novo na conta a cada execução. O `output.json` é gravado antes, e uma falha no logout só gera aviso; não altera o código de saída.
- **Dados incompletos: avisa e segue, sem inventar valor.** Um bookmark ruim não derruba a coleta. Sem `bookmarkable_url` (a identidade do item) ele é ignorado; sem `title` ou autor (ex.: usuário apagado) é mantido com `null` naquele campo. Cada caso gera um aviso (com a página, o `id` e o tipo do bookmark) que vai para o `stderr` ao final e para o campo `warnings` do `output.json`. O código de saída continua 0, porque o arquivo foi gravado; quem consome o resultado deve olhar `warnings`. Só uma resposta que não é a lista esperada (não-JSON, sem `bookmarks`) é erro da página inteira (código 40). A paginação conta os itens recebidos, não os aproveitados, para que uma página só com itens ruins não interrompa a coleta. Uma conta **sem bookmarks** também é válida: o site responde `{"bookmarks": []}` e o `output.json` sai com `total: 0`.
- **Cuidado com o alvo.** Requisições sequenciais, no mínimo 1 segundo entre elas, User-Agent identificável.
- **`robots.txt` aplicado no código.** No início o robô baixa o `robots.txt` e toda requisição passa pelas regras (RFC 9309: regra mais longa vence, `*` e `$`). Um caminho bloqueado nem chega a ser requisitado. `robots.txt` 4xx libera tudo; 5xx ou falha de rede interrompe a execução. A única exceção é `/session`, necessária para o login, configurada em `Http` e explicada no RECON.md.
- **Bibliotecas:** sttp (HTTP genérico), circe (JSON) e munit (testes). Nenhuma específica do Discourse.
- **Segredos** só por variável de ambiente / `.env`, ignorado pelo git e pelo Docker.

## O que ficou faltando

- **Limite diário de favoritos no site.** O Discourse bloqueia (HTTP 429) a criação de mais de ~20 favoritos por dia; por isso a conta chegou a 40 favoritos em dias diferentes. A coleta real de 40 bookmarks (2 páginas) funcionou e a paginação por `more_bookmarks_url` foi confirmada no site.
- **Verificação em duas etapas (bônus)** está implementada para TOTP e foi verificada no site real com uma conta de teste (a conta do desafio segue sem 2FA, como o enunciado manda). Chave de segurança, passkey e códigos de backup não são suportados.
- **Bookmarks do tipo `Post`.** O parser e o coletor são testados contra duas páginas reais (`bookmarks-page-real-p0.json` e `-p1.json`), mas todos os 40 bookmarks são do tipo `Topic`. Favoritos de uma postagem específica (`Post`) não apareceram, então o formato real não foi verificado; há um teste com um `Post` sintético (não capturado do site). Se algum campo faltar, o robô não trava: mantém o item com `null`, ou o ignora se não tiver URL, e avisa (ver "Dados incompletos").
- **O erro de conta não ativada** (`reason: "not_activated"`) foi escrito de memória e não verificado contra o site. Os campos de 2FA foram conferidos no código-fonte do Discourse, e o fluxo, no site real (código aceito e código rejeitado).
- **Termos de uso do fórum.** Os termos (versão de 25/03/2026) proíbem acesso automatizado por programa que não seja navegador. O desafio exige exatamente isso, então a entrega não afirma conformidade; os detalhes e as mitigações estão no RECON.md (seção 4). Falta confirmar com quem propôs o desafio que a automação é autorizada.
