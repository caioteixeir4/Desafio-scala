# RECON — meta.discourse.org

Relatório de reconhecimento. Onde algo foi apenas esperado e não verificado, está marcado como **não verificado**.

## 1. Como o login funciona

O alvo é um Discourse. O login por usuário e senha é um fluxo de três passos, todo em JSON (o robô acrescenta um quarto passo, o logout, descrito na seção 5):

| # | Requisição | O que acontece |
|---|---|---|
| 1 | `GET /session/csrf` | Devolve `{"csrf": "<token>"}` e já emite o cookie de sessão `_forum_session` (`Secure; HttpOnly; SameSite=Lax`). |
| 2 | `POST /session` (form: `login`, `password`; o navegador manda também `second_factor_method` e `timezone`) com header `X-CSRF-Token: <token>` e o cookie do passo 1 | Em caso de sucesso devolve `{"user": {...}}`. Falhas voltam como JSON com `error` e `reason`. |
| 3 | `GET /session/current.json` | **Confirmação.** Anônimo devolve **404** (verificado). Autenticado devolve 200 com `current_user.username`. |

O campo `login` aceita o email. O username real da conta (`Caio_Teixeira`) é diferente do email usado no login, então o robô usa o username devolvido pelo servidor no passo 3 para montar a URL dos bookmarks.

**Evidência positiva de autenticação:** só `200` com `current_user.username` no passo 3 conta. O sucesso do `POST /session` sozinho não basta; ausência de erro não é confirmação.

**O que acontece se faltar cada peça**

- Sem o cookie `_forum_session` ou sem o `X-CSRF-Token`: o Discourse rejeita o `POST /session`. **Não verificado**, porque o robô sempre envia os dois e não provoquei a falha de propósito. Nem o navegador omite essas peças, então a captura não ajuda aqui.
- Credencial errada: o Discourse responde com `reason: "invalid_credentials"` (esperado; coberto por teste com fixture sintética, **não verificado** contra o site real para não gastar tentativas de login).
- Conta sem email confirmado: `reason: "not_activated"` (**não verificado**).
- Segundo fator ativo: os campos usados (`second_factor_required`, `invalid_second_factor`, `security_key_required`) foram escritos de memória e **não foram verificados**; a conta usada não tem 2FA.

Erros são tipados (`challenge.auth.AuthError`) e cada um vira um código de saída diferente: credencial inválida (10), conta não ativada (11), 2FA exigido (12), site fora do ar (20), rate limit 429 (21), sem evidência de sessão (30), resposta inesperada (31).

## 2. Requisições que o site faz e como foram descobertas

O caminho foi, em ordem:

1. Conhecimento do Discourse, que é código aberto e tem endpoints JSON estáveis (`/session/csrf`, `/session`, `/session/current.json`, `/u/{usuario}/bookmarks.json`).
2. Sondagem com `curl` e User-Agent identificável, só nos endpoints anônimos: `robots.txt`, `/tos`, `/session/csrf` e `/session/current.json`.
3. Execução real do robô autenticado, que confirmou o fluxo de login e o formato da lista de bookmarks.
4. **Captura do tráfego do navegador (aba Network do DevTools, 2026-11-07)**, fazendo login pelo site com a conta do desafio, para comparar com o que o robô faz.

**O que a captura do navegador mostrou no login** (apenas nomes de campos; valores omitidos de propósito):

| Requisição | Status | Observação |
|---|---|---|
| `GET /session/csrf` | 200 | Primeira chamada do fluxo, como o robô faz. |
| `POST /session` | 200 | Corpo `application/x-www-form-urlencoded` com os campos `login`, `password`, `second_factor_method` (valor `1`) e `timezone` (fuso do navegador). |

Isso confirma o fluxo de dois passos do robô. Diferenças em relação ao que o robô envia:

- O navegador manda também `second_factor_method` e `timezone`. O robô manda só `login` e `password`, e o login funciona para uma conta sem 2FA. Esses dois campos provavelmente só importam com 2FA ligado, mas isso **não foi verificado**.
- Os corpos das respostas não foram capturados no DevTools, então o formato JSON do sucesso e dos erros continua confirmado só pelo comportamento do robô (login aceito) e não por esta captura.

**Um falso alarme que vale registrar:** numa captura anterior apareceu um `POST /login` com `302` e `Location: /`. Parecia um login por formulário, mas a requisição já saiu com o cookie de sessão e a resposta trazia `x-discourse-route: static/enter` e o nome do usuário já logado. Ou seja, o usuário já estava autenticado e a rota apenas redirecionou; não era um login. A leitura é inferida dos cabeçalhos, não provada. Por isso o `/login` **não** foi tratado como caminho alternativo de autenticação.

**O que a captura do navegador mostrou nos bookmarks:** ao abrir a página de favoritos logado, a interface chamou `GET /u/Caio_Teixeira/bookmarks.json?q=&acting_username=` (200), com `q` e `acting_username` vazios (parâmetros de busca da interface). **É o mesmo endpoint que o robô usa**, e a resposta tem o mesmo formato (`user_bookmark_list.bookmarks` e `more_bookmarks_url` apontando para `?page=1`). O robô não envia `q` nem `acting_username`; as execuções reais funcionaram sem eles. Nos itens mais novos (criados em 2026-11-07), `bookmarkable_type` também é `Topic` e a `bookmarkable_url` não tem número de postagem. O número no final da URL (`.../416693/2`) só aparecia no painel rápido `user-menu-bookmarks`, não neste endpoint.

**Execuções reais contra o site:**

- **2026-11-05:** com 20 bookmarks na conta, o robô autenticou como `Caio_Teixeira`, aplicou o `robots.txt` e coletou os 20 em uma página.
- **2026-11-07:** com 40 bookmarks, coletou **40 em duas páginas**: `GET /u/Caio_Teixeira/bookmarks.json` (20 itens) e `GET /u/Caio_Teixeira/bookmarks.json?page=1` (20 itens), sem duplicados. Saída: `examples/output.json`.

As respostas cruas foram capturadas com `RAW_DUMP_DIR` e viraram fixtures reais em `src/test/resources/fixtures/` (`bookmarks-page-real-p0.json` e `bookmarks-page-real-p1.json`); os testes do parser e do coletor rodam contra elas.

**Paginação (verificada no site real):** cada página tem 20 itens e o robô segue o campo `user_bookmark_list.more_bookmarks_url` da própria resposta. Com 40 bookmarks, a página 0 trouxe `"more_bookmarks_url":"/u/Caio_Teixeira/bookmarks.json?page=1"` (caminho relativo, já com o usuário e o `?page=`), e a página 1, a última, **não traz o campo**: o fim é indicado pela ausência dele. O robô não conta páginas nem usa o total esperado; ele para quando o campo some. A ordem é do mais recente para o mais antigo. Antes disso, com 20 bookmarks, a única página também não tinha o campo.

O que **não foi verificado**: o que o site faz se o total for múltiplo exato de 20 (uma terceira página vazia ou o campo já ausente na última); o robô trata os dois casos (página vazia também encerra), e há teste simulado para isso.

## 3. De onde veio o dado e alternativas descartadas

**Dado extraído:** JSON de `/u/{usuario}/bookmarks.json`.

- `title` -> título do tópico, no idioma original.
- `bookmarkable_url` -> URL do tópico. Na resposta real já vem **absoluta** (`https://meta.discourse.org/t/...`); o parser também aceita caminho relativo e o torna absoluto.
- `user.username` -> autor da **postagem marcada** (não o dono do bookmark). Confirmado com dados reais: posts oficiais aparecem com autor `Discourse`.

**Observações das respostas reais (2 páginas de 20 itens, ~30 a 40 KB cada):**

- **`title` x `fancy_title`:** a resposta traz os dois. `fancy_title` e `excerpt` vêm **traduzidos** (a conta recebe conteúdo localizado em português, ex.: "Navegação PWA de Desktop"), enquanto `title` fica no original ("Desktop PWA Navigation"). O robô usa `title`, para o resultado não depender do idioma da conta. Há um teste que garante isso.
- **`bookmarkable_type`:** os 40 itens são `"Topic"` e têm `linked_post_number: 1`, então a "postagem marcada" é a primeira do tópico e `user` é quem abriu o tópico. Bookmarks de uma postagem específica (`"Post"`) não apareceram, então o formato deles **não foi verificado**; o parser falha explicitamente se faltar `user.username`.
- A resposta tem muitos campos que o robô ignora (`excerpt` em HTML, `tags`, `category_id`, `last_read_post_number` etc.). Não foi encontrado email nem outro dado pessoal sensível, só nomes de usuário públicos do fórum; por isso o arquivo pôde virar fixture.

**Alternativas descartadas**

- **API key do Discourse:** proibida pelo desafio.
- **Navegador headless:** proibido pelo desafio.
- **Extrair da página HTML** de bookmarks: frágil (depende de classes CSS e de renderização no cliente) e desnecessário, já que o endpoint JSON existe.
- **Biblioteca cliente do Discourse:** proibida pelo desafio.
- **`GET /u/{usuario}/user-menu-bookmarks`:** apareceu na captura do navegador (2026-11-07) como a chamada do painel rápido do menu do avatar. Descartado porque é uma lista curta de uso da interface, devolve só o `fancy_title` já traduzido (sem o `title` original) e não informa o tipo do bookmark. Se ele é paginado ou quantos itens devolve **não foi verificado**. O endpoint usado pelo robô é `/u/{usuario}/bookmarks.json`, que traz `title`, `bookmarkable_type` e `more_bookmarks_url`.

## 4. Conflito com o robots.txt

O `robots.txt` do alvo (`User-agent: *`) tem, entre outros:

```
Disallow: /session
Disallow: /auth/
Disallow: /my
Disallow: /search
```

O login passa obrigatoriamente por `/session`, caminho bloqueado. O desafio exige login e também exige respeitar o `robots.txt`.

**Como o robô lida com isso:** ele baixa o `robots.txt` no início e aplica as regras a toda requisição, com o parser em `RobotsTxt` testado contra o arquivo real do site (salvo em `src/test/resources/fixtures/robots.txt`). A única exceção configurada é o prefixo `/session`.

**Decisão sobre o `/session`:** o login é uma exceção deliberada e consciente. O `robots.txt` é um protocolo para crawlers que rastreiam páginas; aqui é uma única autenticação por execução, com a conta do próprio autor, exigida pelo desafio. Fora de `/session`, o robô só acessa `/u/{usuario}/bookmarks.json`, que não está bloqueado, e evita os demais caminhos listados.

### Termos de uso (versão de 25/03/2026, lidos em https://meta.discourse.org/tos)

Os termos **proíbem** pontos que este robô, por natureza, exerce. Em resumo (paráfrase da seção "Acceptable Use"):

- Proíbem automatizar o acesso ao fórum ou monitorá-lo com programa que não seja um navegador (crawler, plug-in etc.). A única exceção citada é indexação para um buscador público.
- Proíbem sobrecarregar a infraestrutura com volume de requisições não razoável.
- Proíbem desativar, evitar ou contornar restrições de segurança ou de acesso.
- A empresa pode encerrar o acordo e o acesso a qualquer momento.

**Isto é um conflito real, não um detalhe.** O desafio pede um robô autenticado e também diz para respeitar os termos de uso; as duas coisas não se conciliam literalmente. Esta entrega não afirma estar em conformidade com os termos. O que foi feito para reduzir o impacto:

- Conta do próprio autor, criada para o desafio; nada de conta de terceiros.
- Volume mínimo: poucas requisições sequenciais por execução (`robots.txt`, 3 do login, 1 por página de bookmarks e 3 do logout; 9 no total com 2 páginas), com intervalo mínimo de 1 segundo.
- Somente leitura, exceto o login. Nenhum conteúdo é publicado, nada é marcado ou alterado.
- User-Agent identificável e `robots.txt` respeitado, com a única exceção do `/session`.
- Nenhuma tentativa de contornar proteção (sem CAPTCHA, sem navegador headless, sem rotação de IP ou de identidade).

**Pendente, fora do código:** confirmar com quem propôs o desafio que a automação nesse alvo é autorizada por eles. Até lá, o risco é de a conta ser suspensa ou encerrada pelo fórum.

## 5. O que quebrou no caminho

- **Limite de 20 favoritos por dia no site:** o desafio pede 30 ou mais. Ao tentar marcar mais tópicos pelo site, o navegador recebeu `HTTP 429 (Too Many Requests)` em `POST /bookmarks.json`, que corresponde ao limite diário de criação de bookmarks do Discourse (o padrão documentado no Meta é 20 por dia; o valor atual desta instância não foi confirmado, só observado: a conta parou em 20). A interface ainda lança um `TypeError` ao tratar esse erro, defeito do site e não do robô. O limite diário zera: marcando em dias diferentes a conta chegou a **40 bookmarks**, acima dos 30 pedidos, e a paginação foi então validada contra o site real.
- **Primeira versão do coletor:** assumia o formato do JSON de bookmarks. Foi validada contra dados reais; o parser falha explicitamente se o autor não vier, em vez de inventar um valor.
- Problemas menores de compilação e de API de testes do sttp foram corrigidos durante o desenvolvimento.
- **Efeito colateral observado: um token de login novo por execução.** A resposta de `/u/Caio_Teixeira.json` (perfil da própria conta, capturada no navegador) lista `user_auth_tokens` com várias entradas "Unknown browser" cujos horários coincidem com as execuções do robô (por exemplo, 2026-11-05 por volta de 23:50 e 23:52 UTC). Ou seja, como a sessão nasce do zero a cada execução, cada execução deixava um token de autenticação na conta. **Correção:** o robô agora encerra a sessão no final (`GET /session/csrf`, `DELETE /session/{usuario}` com `X-CSRF-Token`, e confirma com `/session/current.json` voltando a 404). O logout é tolerante a falha: o `output.json` é gravado antes, e se o logout falhar o robô só avisa. Está coberto por testes com um servidor simulado e **foi verificado contra o site real em 2026-11-07**: na execução, o robô coletou os 40 bookmarks, gravou o `output.json` e imprimiu "Sessao encerrada (logout confirmado)", que só aparece quando o `/session/current.json` volta a dar 404 depois do `DELETE`. O formato do `DELETE` veio do conhecimento do Discourse e se mostrou correto. Não conferi a lista de tokens da conta depois desta execução; a prova é a confirmação feita pelo próprio robô.

## 6. O que quebraria este robô em três meses

- **Mudança no formato do JSON de bookmarks** (campos `bookmarkable_url`, `user.username`, `more_bookmarks_url`): o parser falha com mensagem clara, mas precisa de ajuste.
- **Mudança no fluxo de login:** CSRF, passkeys ou login por código em dispositivo novo.
- **Mudança no logout** (`DELETE /session/{usuario}`): o robô só avisa e segue, mas voltaria a deixar um token de login por execução.
- **Mudança no tamanho da página** (hoje 20 itens): não afeta o robô, que segue `more_bookmarks_url` e não conta páginas.
- **Rate limit ou bloqueio por User-Agent** (o robô usa um User-Agent identificável e 1 requisição por segundo).
- **Mudança no `robots.txt` ou nos termos de uso.**
- **Proteção anti-bot (por exemplo, Cloudflare)** na frente do fórum.
- **Verificação em duas etapas** ligada na conta: o robô hoje termina com `SecondFactorRequired`.

## 7. Bônus: verificação em duas etapas

Não implementado. A conta usada não tem 2FA ativo (o perfil da conta traz `second_factor_enabled: false`). O robô detecta a exigência e termina com um erro tipado específico (código 12), mas não gera nem envia o código TOTP. Os nomes dos campos de resposta de 2FA no Discourse não foram verificados.
