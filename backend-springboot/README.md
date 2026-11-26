# backend-springboot

API Spring Boot 4.2 + Spring AI 2.1 (Java 21) dell'Enterprise AI Assistant: carica PDF, li indicizza in pgvector, risponde con RAG citando le fonti ed esegue azioni (rimborsi) tramite tool calling. Implementa lo stesso `docs/api/openapi.yaml` del backend Laravel, sullo **stesso database**: i documenti caricati da uno sono interrogabili dall'altro.

La guida completa al codice e alle decisioni è in [`docs/PROJECT_LOG.md`](../docs/PROJECT_LOG.md) (sezione 5.8).

## Avvio

Con Docker (consigliato), dalla root del monorepo:

```bash
docker compose --profile springboot up -d                       # PostgreSQL + API su http://localhost:8080
docker compose --profile ollama --profile springboot up -d      # con Ollama locale (qwen2.5:7b + nomic-embed-text)
```

Senza Docker per l'app (serve JDK 21 e PostgreSQL acceso con `docker compose up -d postgres`):

```bash
cd backend-springboot
./mvnw spring-boot:run          # usa localhost:5432 e Ollama su localhost:11434
```

Per usarlo dal frontend Angular: scegli **Spring Boot** nel selettore Backend dell'intestazione (nessuna modifica al codice).

## Test

```bash
docker compose up -d postgres   # i test usano PostgreSQL + pgvector reali (database eaa_spring_test, creato da solo)
cd backend-springboot
./mvnw test
```

Ollama è **simulato** da un server HTTP locale: nessuna rete, nessun modello. I test rifiutano di partire se il database non finisce con `_test`.

## Configurazione

Tutto in `src/main/resources/application.yml`, con variabili d'ambiente (stessi nomi di Laravel):
`DB_HOST/DB_PORT/DB_DATABASE/DB_USERNAME/DB_PASSWORD`, `OLLAMA_URL`, `AI_CHAT_MODEL`, `AI_EMBEDDING_MODEL`, `AI_EMBEDDING_DIMENSIONS` (768), `RAG_*`, `CORS_ALLOWED_ORIGINS`, `SERVER_PORT`.

**Passare a OpenAI / cloud**: in `application.yml` cambia `spring.ai.model.chat` e `spring.ai.model.embedding` da `ollama` a `openai` e decommenta il blocco `spring.ai.openai` (chiave API e modelli; per gli embedding `dimensions: 768`).
