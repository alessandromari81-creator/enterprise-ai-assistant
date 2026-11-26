# frontend-angular

Dashboard dell'Enterprise AI Assistant: Angular 22 (componenti standalone, Signals, zoneless) + Tailwind CSS 4.
Tre colonne: **documenti** (drag & drop di PDF) · **chat** (con badge delle fonti) · **richieste di rimborso** (si aggiorna quando l'IA esegue l'azione). Nessun login: opera come utente demo.

La guida completa al codice e alle decisioni è in [`docs/PROJECT_LOG.md`](../docs/PROJECT_LOG.md) (sezione 5.7).

## Avvio

```bash
# dalla root del monorepo: backend + Ollama
docker compose --profile ollama --profile laravel up -d

cd frontend-angular
npm install
npm start            # http://localhost:4200
```

## Scegliere il backend

Nell'intestazione c'è il selettore **Backend: Laravel | Spring Boot**: cambia l'URL base delle API **a runtime** (senza riavviare), ricarica subito documenti e rimborsi dal backend scelto e ricorda la scelta in `localStorage` (chiave `eaa.backend`). Gli URL sono in [`src/environments/environment.ts`](src/environments/environment.ts) (`backends`, e `defaultBackend` per il primo avvio).

## Comandi

| | |
|---|---|
| `npm start` | server di sviluppo |
| `npm test` | test unitari (Vitest) |
| `npm run build` | build di produzione in `dist/` |
