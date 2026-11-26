# backend-laravel

API Laravel 13 dell'Enterprise AI Assistant: carica PDF, li indicizza in pgvector, risponde con RAG citando le fonti ed esegue azioni (rimborsi) tramite tool calling. Implementa `docs/api/openapi.yaml`.

La guida completa al codice e alle decisioni è in [`docs/PROJECT_LOG.md`](../docs/PROJECT_LOG.md) (sezione 5.5).

## Avvio (solo Docker; Laravel 13 richiede PHP ≥ 8.3)

Dalla root del monorepo:

```bash
docker compose --profile laravel up -d                          # PostgreSQL + API su :8000
docker compose --profile ollama --profile laravel up -d         # con Ollama locale
```

Il container crea `.env` da `.env.example`, genera `APP_KEY` ed esegue le migration (idempotenti).

## Test

```bash
docker compose up -d postgres
docker compose exec postgres psql -U eaa -d eaa -c 'CREATE DATABASE eaa_test'   # una sola volta
docker compose run --rm -e RUN_MIGRATIONS=false backend-laravel php artisan test
```

I test usano PostgreSQL + pgvector reali (database `eaa_test`) e **simulano** Ollama: nessuna rete, nessun modello. Rifiutano di partire se il database non finisce con `_test`.

## Configurazione

Vedi `.env.example`. Le principali: `AI_PROVIDER` (`ollama`|`openai`), `AI_CHAT_MODEL`, `AI_EMBEDDING_MODEL`, `AI_EMBEDDING_DIMENSIONS` (768, deve coincidere con `VECTOR(768)`), `OPENAI_API_KEY`.
