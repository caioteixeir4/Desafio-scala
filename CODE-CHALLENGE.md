# Code Challenge

## Regras gerais

| Item           | Definição                                                                                                           |
|----------------|---------------------------------------------------------------------------------------------------------------------|
| Linguagem      | **Scala** (2 ou 3)                                                                                                  |
| Framework/libs | **Livre.** Use o que você domina: ZIO, cats-effect, Akka, sttp, requests-scala, jsoup, circe, play-json…            |
| Build          | sbt, mill ou scala-cli, desde que rode com um comando documentado                                                   |
| Entrega        | Repositório Git (link público ou `.zip` com o `.git` dentro), com histórico de commits, não um commit único gigante |

Três proibições

- **Sem browser headless.** Nada de Selenium, Playwright ou Puppeteer. Queremos ver você entender o tráfego HTTP, não
  delegar isso para um navegador.
- **Sem biblioteca cliente específica do alvo.** Wrapper de API pronto para o alvo está fora. HTTP genérico e parser de
  HTML ou JSON, sim.
- **Sem API key.** A autenticação é por usuário e senha, como um humano faria.

---

## O alvo

`https://meta.discourse.org`

É o fórum oficial do Discourse. Cadastro aberto e gratuito, só com email.

**Antes de começar:** crie sua conta e, logado, marque como favorito (bookmark) **pelo menos 30 tópicos**. Qualquer
tópico serve, escolha rápido. Sem isso não há volume para o robô paginar.

Deixe a verificação em duas etapas desligada nessa conta. O requisito básico não é sobre isso. Se quiser encarar, veja o
bônus.

---

## O que o robô deve fazer

Vale para tudo abaixo: a lógica de parsing tem que ser testável sem rede.

### 1. Autenticação

Descubra como o login desse site funciona e implemente.

Requisitos:

- A sessão é estabelecida pelo próprio robô, do zero, a cada execução. Nada de cookie, token ou header copiado na mão
  para dentro do código.
- O robô confirma por **evidência positiva** que está autenticado antes de seguir. Ausência de erro não é confirmação.
- Se a autenticação falhar, o robô termina com um erro tipado e específico. Credencial errada e site fora do ar são
  falhas diferentes e o seu código deve saber disso.
- Credenciais entram por variável de ambiente ou arquivo fora do versionamento. Segredo commitado é reprovação
  automática, inclusive se estiver só no histórico.

### 2. Coleta autenticada e paginada

Autenticado, colete **todos os seus bookmarks**. Para cada um:

- título do tópico
- URL do tópico
- autor da postagem marcada

A quantidade de páginas não pode ser hardcoded, nem inferida do número que você sabe ter marcado. O robô descobre quando
parar a partir da própria resposta.

Esse dado só existe para quem está logado. Se o seu robô consegue extrair isso, a sessão é real.

### 3. Saída

Grave um `output.json` com o resultado, num formato que você desenhou. Nós vamos ler esse arquivo. Ele precisa ser óbvio
para quem nunca viu o seu código.

### 4. Comportamento em rede

O alvo é o fórum de terceiros, não nosso. Trate como tal.

- Não dispare cem requisições de uma vez.
- `User-Agent` identificável, que diga quem você é.
- Respeite o `robots.txt` e os termos de uso.

---

## Bônus

- **Verificação em duas etapas.** Habilite na sua conta e faça o robô passar por ela **sem intervenção manual**, numa
  execução automatizada. Se só conseguir com intervenção manual, entregue assim mesmo e explique no `RECON.md` por que
  não deu para automatizar.

---

## O que entregar

```
/
├── README.md          # como rodar, decisões, o que ficou faltando
├── RECON.md           # relatório de reconhecimento
├── build.sbt          # ou equivalente
└── src/…
```

### README.md

Direto ao ponto:

1. Como rodar.
2. Se faltou algo.
3. Que decisões de design você tomou e por quê.

### RECON.md

**É o documento mais importante da entrega.** Escrever um robô é metade investigação, e quem não registra o que
descobriu descobriu só para si mesmo. Conte:

- Como o login funciona ali, de fato. O que é necessário para a sessão nascer e o que acontece se faltar cada peça.
- Que requisições o site faz de verdade, e como você descobriu isso.
- De onde veio o dado que você extraiu, e que outros caminhos você considerou e descartou.
- O que quebrou no caminho e como você contornou.
- O que quebraria esse robô daqui a três meses.
