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
- Segundo fator ativo: o `reason: "invalid_second_factor"` e os campos de envio (`second_factor_method`, `second_factor_token`) foram conferidos no código-fonte do Discourse (seção 7). Já `second_factor_required` e `security_key_required` **não** apareceram lá. O fluxo foi testado no site real com uma conta de teste com 2FA (seção 7).

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

- O navegador manda também `second_factor_method` e `timezone`. O robô manda só `login` e `password` (mais `second_factor_method` e `second_factor_token` quando há `DISCOURSE_TOTP_SECRET`, seção 7), e o login funciona para uma conta sem 2FA. O `timezone` não é enviado; na leitura de `create` no código-fonte só apareceram `login` e `password` como parâmetros diretos, mas não li o resto do fluxo.
- Os corpos das respostas não foram capturados no DevTools, então o formato JSON do sucesso e dos erros continua confirmado só pelo comportamento do robô (login aceito) e não por esta captura.

**Um falso alarme que vale registrar:** numa captura anterior apareceu um `POST /login` com `302` e `Location: /`. Parecia um login por formulário, mas a requisição já saiu com o cookie de sessão e a resposta trazia `x-discourse-route: static/enter` e o nome do usuário já logado. Ou seja, o usuário já estava autenticado e a rota apenas redirecionou; não era um login. A leitura é inferida dos cabeçalhos, não provada. Por isso o `/login` **não** foi tratado como caminho alternativo de autenticação.

**O que a captura do navegador mostrou nos bookmarks:** ao abrir a página de favoritos logado, a interface chamou `GET /u/Caio_Teixeira/bookmarks.json?q=&acting_username=` (200), com `q` e `acting_username` vazios (parâmetros de busca da interface). **É o mesmo endpoint que o robô usa**, e a resposta tem o mesmo formato (`user_bookmark_list.bookmarks` e `more_bookmarks_url` apontando para `?page=1`). O robô não envia `q` nem `acting_username`; as execuções reais funcionaram sem eles. Nos itens mais novos (criados em 2026-11-07), `bookmarkable_type` também é `Topic` e a `bookmarkable_url` não tem número de postagem. O número no final da URL (`.../416693/2`) só aparecia no painel rápido `user-menu-bookmarks`, não neste endpoint.

### De onde vem esse fluxo: fontes verificáveis

O robô usa o **mesmo fluxo de login que o navegador** (`GET /session/csrf`, depois `POST /session`); não há um "login de robô" diferente. Duas fontes permitem conferir isso de forma independente.

**1. Reproduzir no navegador (DevTools).** Fazer logout, abrir a aba Network com "Preserve log" marcado, filtrar por Fetch/XHR, fazer login pela janela do site e observar `session/csrf` (GET) seguido de `session` (POST). Depois, abrir a página de favoritos e observar `bookmarks.json`. É o que foi feito em 2026-11-07 (resultado nas tabelas acima).

**2. Código-fonte aberto do Discourse.** O site roda Discourse, cujo código é público em [github.com/discourse/discourse](https://github.com/discourse/discourse). O arquivo de rotas, [`config/routes.rb`](https://github.com/discourse/discourse/blob/main/config/routes.rb), declara as rotas usadas pelo robô (consultado em 2026-11-07, branch `main`; a numeração de linhas muda, então não a cito):

| Uso no robô | Declaração em `routes.rb` |
|---|---|
| `GET /session/csrf` | `get "session/csrf" => "session#csrf"` |
| `POST /session` (login) e `DELETE /session/:usuario` (logout) | `resources :session, ... only: %i[create destroy]` |
| `GET /session/current.json` (confirmação) | `get "session/current" => "session#current"` |
| `GET /u/:usuario/bookmarks.json` (coleta) | `get ".../:username/bookmarks" => "users#bookmarks"` |
| painel rápido do avatar (descartado) | `get ".../:username/user-menu-bookmarks" => "users#user_menu_bookmarks"` |
| criar favorito (o `429` do limite diário) | `resources :bookmarks, only: %i[create destroy update]` |

O mesmo arquivo declara rotas de segundo fator (`session/2fa`, `session/passkey/...`). O bônus não precisou delas: o código TOTP vai no próprio `POST /session` (seção 7).

**Ordem em que as coisas realmente aconteceram:** o fluxo foi montado a partir do conhecimento prévio do Discourse, testado primeiro com sondagens anônimas e depois com execução real; o DevTools e a consulta ao `routes.rb` vieram **depois**, como confirmação, e não como origem da descoberta. A documentação pública de API do Discourse não foi usada: ela trata de autenticação por API key, que o desafio proíbe.

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
- **`bookmarkable_type`:** os 40 itens são `"Topic"` e têm `linked_post_number: 1`, então a "postagem marcada" é a primeira do tópico e `user` é quem abriu o tópico. Bookmarks de uma postagem específica (`"Post"`) não apareceram, então o formato deles **não foi verificado**. Se faltar `user.username` (ou `title`), o parser mantém o item com `null` naquele campo e gera aviso; se faltar `bookmarkable_url`, ignora o item e avisa. Nada é inventado, e os avisos aparecem no `stderr` e em `warnings` no `output.json`.
- **Lista vazia:** uma conta **sem nenhum bookmark** recebe `{"bookmarks": []}` na raiz, sem `user_bookmark_list` e sem `more_bookmarks_url` (confirmado no `users_controller.rb` do Discourse e visto no site em 2026-11-08, com a conta de teste). O primeiro parser só conhecia o formato com bookmarks e terminava com erro (código 40) nessa conta; agora o formato vazio é tratado como lista válida e o `output.json` sai com `total: 0`.
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
- **Primeira versão do coletor:** assumia o formato do JSON de bookmarks e foi validada contra dados reais. Ela derrubava a página inteira se um único bookmark viesse sem autor. Isso foi trocado por um tratamento por item: o item fica com `null` no campo que faltou, ou é ignorado se não tiver URL, sempre com aviso em `warnings` (seção 3).
- **Conta sem bookmarks dava erro:** o site responde `{"bookmarks": []}` nesse caso, formato que o parser não conhecia, e o robô terminava com código 40. Descoberto ao rodar a conta de teste do 2FA; corrigido e coberto por teste (seção 3, "Lista vazia").
- **Primeira chave do 2FA não correspondia ao app:** o site recusou o código; o diagnóstico descartou o relógio e mostrou que a chave copiada não era a do app (seção 7, "Tropeço na verificação").
- **Intervalo mínimo inválido aceito em silêncio:** `MIN_REQUEST_INTERVAL_MS=abc` caía no padrão de 1000 ms sem avisar. Agora o robô para com erro (código 2) e mostra o valor recebido.
- Problemas menores de compilação e de API de testes do sttp foram corrigidos durante o desenvolvimento.
- **Efeito colateral observado: um token de login novo por execução.** A resposta de `/u/Caio_Teixeira.json` (perfil da própria conta, capturada no navegador) lista `user_auth_tokens` com várias entradas "Unknown browser" cujos horários coincidem com as execuções do robô (por exemplo, 2026-11-05 por volta de 23:50 e 23:52 UTC). Ou seja, como a sessão nasce do zero a cada execução, cada execução deixava um token de autenticação na conta. **Correção:** o robô agora encerra a sessão no final (`GET /session/csrf`, `DELETE /session/{usuario}` com `X-CSRF-Token`, e confirma com `/session/current.json` voltando a 404). O logout é tolerante a falha: o `output.json` é gravado antes, e se o logout falhar o robô só avisa. Está coberto por testes com um servidor simulado e **foi verificado contra o site real em 2026-11-07**: na execução, o robô coletou os 40 bookmarks, gravou o `output.json` e imprimiu "Sessao encerrada (logout confirmado)", que só aparece quando o `/session/current.json` volta a dar 404 depois do `DELETE`. O formato do `DELETE` veio do conhecimento do Discourse e se mostrou correto. Não conferi a lista de tokens da conta depois desta execução; a prova é a confirmação feita pelo próprio robô.

## 6. O que quebraria este robô em três meses

- **Mudança no formato do JSON de bookmarks** (campos `bookmarkable_url`, `user.username`, `more_bookmarks_url`): se a estrutura da lista mudar, a coleta falha com mensagem clara (código 40); se só alguns campos sumirem, o robô segue com `null` e avisos em `warnings`. Nos dois casos o parser precisa de ajuste.
- **Mudança no fluxo de login:** CSRF, passkeys ou login por código em dispositivo novo.
- **Mudança no logout** (`DELETE /session/{usuario}`): o robô só avisa e segue, mas voltaria a deixar um token de login por execução.
- **Mudança no tamanho da página** (hoje 20 itens): não afeta o robô, que segue `more_bookmarks_url` e não conta páginas.
- **Rate limit ou bloqueio por User-Agent** (o robô usa um User-Agent identificável e 1 requisição por segundo).
- **Mudança no `robots.txt` ou nos termos de uso.**
- **Proteção anti-bot (por exemplo, Cloudflare)** na frente do fórum.
- **Verificação em duas etapas** exigida sem `DISCOURSE_TOTP_SECRET`, ou com chave de segurança/passkey: o robô termina com `SecondFactorRequired` (código 12).

## 7. Bônus: verificação em duas etapas

Implementado para **TOTP (app autenticador)** e **verificado no site real em 2026-11-08**, com uma conta de teste separada que tem 2FA. A conta do desafio segue sem 2FA, como o enunciado manda (o perfil dela traz `second_factor_enabled: false`). Na conta de teste, a execução real fez o login com o código gerado pelo robô, confirmou a sessão em `/session/current.json` e confirmou o logout. Essa conta tem 0 bookmarks, e a primeira coleta falhou porque o site responde à lista vazia em outro formato (ver "Lista vazia", seção 3). Depois da correção, a execução completa terminou sem erro: login com 2FA, coleta (0 bookmarks, página 0 sem próxima), gravação do `output.json` com `total: 0` e logout confirmado. A saída dessa execução está em `examples/output-2fa.json`. As duas contas foram mantidas separadas de propósito: a principal sem 2FA (requisito básico, como o enunciado manda) e a de teste com 2FA (bônus).

**O que foi verificado, e como.** Li o código-fonte do Discourse (`app/controllers/session_controller.rb`, `app/models/concerns/second_factor_manager.rb`, `app/models/user_second_factor.rb`, ramo `main`, em 2026-11-08):

- `POST /session` lê `second_factor_token` e `second_factor_method` (convertido para inteiro).
- Valores do método: `1` = TOTP, `2` = código de backup, `3` = chave de segurança, `4` = passkey.
- O código TOTP usa os padrões do ROTP (6 dígitos, janela de 30 s, SHA-1), com tolerância de ±30 s, e o servidor guarda o último uso (`after: last_used`): o mesmo código não vale duas vezes.
- Token ausente e token errado dão a **mesma** resposta (HTTP 200, `reason: "invalid_second_factor"`). O robô não consegue distinguir os dois pela resposta e escolhe a mensagem pelo que ele mesmo enviou.
- Sem 2FA na conta, o servidor aceita o login normalmente (`authenticate_second_factor` devolve ok); por isso os campos extras só são enviados quando há segredo configurado.
- O campo `second_factor_required`, que o parser também checa, **não** aparece nesse código; o que vale é o `reason`.

**O que o robô faz.** Com `DISCOURSE_TOTP_SECRET` (segredo em base32), gera o código com HMAC-SHA1 (RFC 6238) logo antes do `POST /session` e envia `second_factor_method=1` e `second_factor_token`. O gerador é conferido contra os vetores oficiais da RFC 6238 (Apêndice B). O login com o servidor simulado confirma que os campos só vão quando há segredo, e que o erro de código rejeitado nunca imprime o segredo nem o código.

**Quem faz o quê.** Ativar o 2FA é um passo único e manual do dono da conta (o enunciado diz "habilite na sua conta"): o site mostra a chave uma única vez, em "Inserir manualmente" na tela de adicionar autenticador. O robô **não** ativa o 2FA nem descobre o segredo sozinho; ele só gera o código a cada execução, a partir da chave em `DISCOURSE_TOTP_SECRET`, sem ninguém digitar nada. Isso é inerente ao TOTP: se o robô pudesse criar o próprio segundo fator só com a senha, o segundo fator não protegeria nada.

**Tropeço na verificação.** A primeira tentativa foi recusada pelo site ("Invalid authentication code. Each code can only be used once."; a mesma mensagem serve para código errado e repetido). O relógio do contêiner estava certo (diferença de 1 a 2 s para o cabeçalho `Date` do site) e o código não vinha da chave que eu tinha em mãos. A causa exata não foi determinada; a chave copiada daquela tentativa não correspondia ao app. Resolveu refazer a ativação e copiar a chave direto da tela, **conferindo antes de rodar o robô** se o código calculado a partir da chave do arquivo era igual ao do app. Cada tentativa de login com 2FA errado conta para o limite do site, então vale conferir antes em vez de repetir o robô.

**O que continua fora.** Chave de segurança e passkey (métodos 3 e 4), que exigem dispositivo ou navegador, e códigos de backup. Guardar o segredo TOTP no mesmo `.env` da senha anula o benefício do segundo fator; é aceitável para uma conta de teste, não para uma conta real.
