# Code Challenge — Bookmarks do meta.discourse.org

Robô em Scala que autentica no fórum, coleta todos os bookmarks do usuário (título, URL, autor) e grava `output.json`.

## Como rodar

Requisitos: Docker.

```bash
cp .env.example .env   # preencha DISCOURSE_USERNAME e DISCOURSE_PASSWORD
docker compose run --rm app sbt -batch run
docker compose run --rm app sbt -batch test
```

O `output.json` é gerado na raiz do projeto.

## Estrutura

```
src/main/scala/challenge/
├── config/     leitura de configuração (env vars)
├── auth/       login e erros tipados
├── http/       sessão, rate limit, User-Agent
├── parse/      parsers puros (sem rede)
├── collector/  paginação dos bookmarks
└── model/      Bookmark e formato do output.json
src/test/       testes com fixtures em src/test/resources/fixtures
```

## Decisões de design

_A preencher._

## O que ficou faltando

_A preencher._
