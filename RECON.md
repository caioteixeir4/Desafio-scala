# RECON — meta.discourse.org

Relatório de reconhecimento. Onde algo foi apenas esperado e não verificado, está marcado como **não verificado**.

## 1. Como o login funciona

O alvo é um Discourse. O login por usuário e senha é um fluxo de três passos, todo em JSON:

| # | Requisição | O que acontece |
|---|---|---|
| 1 | `GET /session/csrf` | Devolve `{"csrf": "<token>"}` e já emite o cookie de sessão `_forum_session` (`Secure; HttpOnly; SameSite=Lax`). |
| 2 | `POST /session` (form: `login`, `password`) com header `X-CSRF-Token: <token>` e o cookie do passo 1 | Em caso de sucesso devolve `{"user": {...}}`. Falhas voltam como JSON com `error` e `reason`. |
| 3 | `GET /session/current.json` | **Confirmação.** Anônimo devolve **404** (verificado). Autenticado devolve 200 com `current_user.username`. |

O campo `login` aceita o email. O username real da conta (`Caio_Teixeira`) é diferente do email usado no login, então o robô usa o username devolvido pelo servidor no passo 3 para montar a URL dos bookmarks.

**Evidência positiva de autenticação:** só `200` com `current_user.username` no passo 3 conta. O sucesso do `POST /session` sozinho não basta; ausência de erro não é confirmação.

**O que acontece se faltar cada peça**

- Sem o cookie `_forum_session` ou sem o `X-CSRF-Token`: o Discourse rejeita o `POST /session`. **Não verificado**, porque o robô sempre envia os dois e não provoquei a falha de propósito.
- Credencial errada: o Discourse responde com `reason: "invalid_credentials"` (esperado; coberto por teste com fixture sintética, **não verificado** contra o site real para não gastar tentativas de login).
- Conta sem email confirmado: `reason: "not_activated"` (**não verificado**).
- Segundo fator ativo: os campos usados (`second_factor_required`, `invalid_second_factor`, `security_key_required`) foram escritos de memória e **não foram verificados**; a conta usada não tem 2FA.

Erros são tipados (`challenge.auth.AuthError`) e cada um vira um código de saída diferente: credencial inválida (10), conta não ativada (11), 2FA exigido (12), site fora do ar (20), rate limit 429 (21), sem evidência de sessão (30), resposta inesperada (31).

## 2. Requisições que o site faz e como foram descobertas

Não usei DevTools nem captura de tráfego do navegador. O caminho foi:

1. Conhecimento do Discourse, que é código aberto e tem endpoints JSON estáveis (`/session/csrf`, `/session`, `/session/current.json`, `/u/{usuario}/bookmarks.json`).
2. Sondagem com `curl` e User-Agent identificável, só nos endpoints anônimos: `robots.txt`, `/session/csrf` e `/session/current.json`.
3. Execução real do robô autenticado, que confirmou o fluxo de login e o formato da lista de bookmarks.

A paginação dos bookmarks usa `GET /u/{usuario}/bookmarks.json` e segue o campo `user_bookmark_list.more_bookmarks_url` da própria resposta. Esse nome de campo **não foi verificado em uma resposta com mais de uma página**: a conta tem 20 bookmarks (o site deu erro ao tentar marcar mais), e uma coleta só retornou 20. A lógica de paginação foi testada com um servidor simulado de várias páginas, mas não contra o site real.

## 3. De onde veio o dado e alternativas descartadas

**Dado extraído:** JSON de `/u/{usuario}/bookmarks.json`.

- `title` -> título do tópico.
- `bookmarkable_url` -> URL (relativa, o robô a torna absoluta).
- `user.username` -> autor da **postagem marcada** (não o dono do bookmark). Confirmado com dados reais: posts oficiais aparecem com autor `Discourse`.

**Alternativas descartadas**

- **API key do Discourse:** proibida pelo desafio.
- **Navegador headless:** proibido pelo desafio.
- **Extrair da página HTML** de bookmarks: frágil (depende de classes CSS e de renderização no cliente) e desnecessário, já que o endpoint JSON existe.
- **Biblioteca cliente do Discourse:** proibida pelo desafio.

## 4. Conflito com o robots.txt

O `robots.txt` do alvo (`User-agent: *`) tem, entre outros:

```
Disallow: /session
Disallow: /auth/
Disallow: /my
Disallow: /search
```

O login passa obrigatoriamente por `/session`, caminho bloqueado. O desafio exige login e também exige respeitar o `robots.txt`.

**Decisão:** o login é uma exceção deliberada e consciente. O `robots.txt` é um protocolo para crawlers que rastreiam páginas; aqui é uma única autenticação por execução, com a conta do próprio autor, exigida pelo desafio. Fora de `/session`, o robô só acessa `/u/{usuario}/bookmarks.json`, que não está bloqueado, e evita os demais caminhos listados. Os termos de uso do fórum devem ser lidos pelo avaliador/autor; esta decisão não substitui isso.

## 5. O que quebrou no caminho

- **Total de 20 bookmarks:** o desafio pede 30 ou mais. A tentativa de marcar mais tópicos deu erro no site (causa não investigada). A entrega usa os 20 existentes e a paginação foi validada por simulação.
- **Primeira versão do coletor:** assumia o formato do JSON de bookmarks. Foi validada contra dados reais; o parser falha explicitamente se o autor não vier, em vez de inventar um valor.
- Problemas menores de compilação e de API de testes do sttp foram corrigidos durante o desenvolvimento.

## 6. O que quebraria este robô em três meses

- **Mudança no formato do JSON de bookmarks** (campos `bookmarkable_url`, `user.username`, `more_bookmarks_url`): o parser falha com mensagem clara, mas precisa de ajuste.
- **Mudança no fluxo de login:** CSRF, passkeys ou login por código em dispositivo novo.
- **Rate limit ou bloqueio por User-Agent** (o robô usa um User-Agent identificável e 1 requisição por segundo).
- **Mudança no `robots.txt` ou nos termos de uso.**
- **Proteção anti-bot (por exemplo, Cloudflare)** na frente do fórum.
- **Verificação em duas etapas** ligada na conta: o robô hoje termina com `SecondFactorRequired`.

## 7. Bônus: verificação em duas etapas

Não implementado. A conta usada não tem 2FA ativo. O robô detecta a exigência e termina com um erro tipado específico (código 12), mas não gera nem envia o código TOTP. Os nomes dos campos de resposta de 2FA no Discourse não foram verificados.
