# Enterprise AI Assistant — Diario di progetto

Documento vivo: registra **cosa è stato fatto, perché, e come funziona il codice**.
Ogni passo nuovo si aggiunge in fondo alla sezione [Registro dei passi](#6-registro-dei-passi).

---

## 1. Obiettivo

Monorepo `enterprise-ai-assistant`: un assistente aziendale **RAG + Azioni**.

1. L'utente carica un PDF aziendale (es. policy rimborsi).
2. Il backend estrae il testo, genera gli embedding e li salva in PostgreSQL con pgvector.
3. L'utente chatta con i documenti: le risposte citano la fonte (file e pagina) e non inventano dati.
4. L'utente può chiedere un'azione ("Apri una richiesta di rimborso per 50 €"): il modello invoca un tool/funzione che salva un record in `reimbursement_requests`.

**Idea chiave del monorepo:** due backend (Laravel e Spring Boot) implementano **lo stesso contratto API** sopra **lo stesso database**. Angular non sa quale dei due sta usando: basta cambiare l'URL base. È il modo per dimostrare lo stesso problema risolto con due stack.

---

## 2. Architettura

```
                         ┌───────────────────────┐
                         │   frontend-angular    │  Angular 22, Signals, standalone
                         └───────────┬───────────┘
                                     │  REST /api  (contratto: docs/api/openapi.yaml)
                  ┌──────────────────┴──────────────────┐
                  ▼                                     ▼
       ┌─────────────────────┐               ┌─────────────────────┐
       │  backend-springboot │               │   backend-laravel   │
       │  Spring Boot 4.1    │               │   Laravel 13        │
       │  + Spring AI 2.0    │               │   (PHP ≥ 8.3)       │
       └──────────┬──────────┘               └──────────┬──────────┘
                  │            stesso schema SQL         │
                  └──────────────────┬───────────────────┘
                                     ▼
                  ┌──────────────────────────────────────┐
                  │ PostgreSQL 18 + pgvector 0.8.2       │   (Docker)
                  │ documents · document_chunks ·        │
                  │ reimbursement_requests · chat_messages│
                  └──────────────────────────────────────┘
                                     ▲
                  LLM + embedding: Ollama (Docker, opzionale) oppure OpenAI
```

### Flusso 1 — Ingestione (`POST /api/documents`)
1. Si riceve il PDF e se ne calcola lo **SHA-256**; se esiste già un documento con lo stesso hash → `409` (niente doppia indicizzazione).
2. Si estrae il testo **pagina per pagina** (serve a citare "p. N").
3. Si divide in **chunk** da ~500 token con sovrapposizione di 50 (la sovrapposizione evita di tagliare una frase importante a metà).
4. Per ogni chunk si genera l'**embedding** (vettore a 768 numeri) e si salva in `document_chunks`.
5. `documents.status` passa da `PROCESSING` a `READY` (o `FAILED` con `error_message`).

### Flusso 2 — Domanda RAG (`POST /api/chat`)
1. Si calcola l'embedding della domanda.
2. Ricerca per **similarità coseno** in pgvector: i 5 chunk più vicini, con soglia minima 0.62 (calibrata, D22).
3. I chunk vengono inseriti nel prompt; il system prompt impone: *rispondi solo dal contesto, cita `[file, p. N]`, se non trovi dì che non c'è*.
4. La risposta torna con `sources[]` strutturato, così Angular mostra le citazioni come elementi cliccabili e non come semplice testo.

### Flusso 3 — Azione (tool calling)
1. Insieme alla domanda si dichiara al modello il tool `createReimbursementRequest(amount, description)`.
2. Se l'utente chiede un rimborso, il **modello decide** di chiamare il tool; il codice del backend lo esegue (validazione + `INSERT`).
3. L'esito torna in `actions[]` (`REIMBURSEMENT_CREATED`) e il modello lo racconta all'utente.
4. Se l'importo manca o non è valido, il modello chiede chiarimento e **non** chiama il tool.

> Sicurezza: l'IA propone, il codice decide. L'importo è validato nel backend e anche dal vincolo `CHECK` del database (0 < importo ≤ 10.000). Lo stato iniziale è sempre `OPEN`: un umano approva.

---

## 3. Versioni scelte (verificate il 25/11/2026)

| Componente | Versione | Nota |
|---|---|---|
| Spring Boot | **4.2.0** | Installato al Passo 4 (al Passo 1 la ricerca indicava 4.1: nel frattempo era uscita la 4.2). Richiesto "3.x" nel Passo 4, ma **Spring AI 2.x esiste solo per Boot 4** e si era deciso di usare le ultime versioni (D32). Java 21 |
| Spring AI | **2.1.0** | Su Maven Central; baseline Spring Boot 4.2 / Spring Framework 7. L'API è cambiata molto rispetto alla 1.x (D34). Hibernate 7.4, Jackson 3, PDFBox 3.0 |
| Laravel | **13** | PHP minimo 8.3 |
| Angular | **22.3.0** | Installato al Passo 3 (la ricerca iniziale indicava 22.1: nel frattempo era uscita la 22.3). Richiede Node ≥ 22.22 (sul Mac c'è la 22.23) |
| Tailwind CSS | **4.3** | Integrazione v4: `@tailwindcss/postcss` + `@import "tailwindcss"`, senza `tailwind.config.js` |
| Vitest | **5.x** | Test runner predefinito di Angular 22 (`ng test`) |
| PostgreSQL | **18** | verificato all'avvio: 18.4 |
| pgvector | **0.8.2** | immagine `pgvector/pgvector:0.8.2-pg18` |
| PHP (container Laravel) | **8.4** | `php:8.4-cli`. Il PHP del Mac è 8.2.12: **troppo vecchio** per Laravel 13, per questo il backend gira in Docker |
| Laravel (installato) | **13.17** | |
| Prism (`prism-php/prism`) | **0.103** | Il pacchetto `echolabsdev/prism` citato in giro è il vecchio nome: oggi è `prism-php/prism`. Supporta embedding, tool calling, Ollama e OpenAI |
| `smalot/pdfparser` | **2.13** | |
| `pgvector/pgvector` (PHP) | **0.2.2** | tipo `vector` per Eloquent e per le migration |

---

## 4. Decisioni di progetto e motivazioni

| # | Decisione | Perché |
|---|---|---|
| D1 | **Schema DB unico, in `db/init/01-init.sql`**; `ddl-auto` Hibernate disattivato. Le migration Laravel esistono ma sono **idempotenti** (vedi D14) | Due backend sullo stesso DB: se ognuno gestisse lo schema in modo autonomo, divergerebbero. *(Aggiornata al Passo 2: in origine escludeva del tutto le migration Laravel, ma erano state richieste esplicitamente.)* |
| D2 | **Contratto API in OpenAPI** (`docs/api/openapi.yaml`) | Angular dipende solo dal contratto; i due backend sono intercambiabili e verificabili contro lo stesso file. |
| D3 | **Embedding a 768 dimensioni** (`nomic-embed-text`) | Modello Ollama locale e gratuito. Con OpenAI si usa `text-embedding-3-small` con `dimensions=768`. Cambiare modello = ricreare `document_chunks` e reindicizzare. |
| D4 | **Importi come stringa** (`"50.00"`) nel JSON | Evita errori di arrotondamento dei float. Nel DB è `NUMERIC(10,2)`. |
| D5 | **Errori RFC 9457** (`application/problem+json`) con `type` stabile (`urn:eaa:error:...`) | Angular gestisce gli errori allo stesso modo con entrambi i backend. |
| D6 | **Indice HNSW** con `vector_cosine_ops` | Ricerca approssimata veloce, nessun training preliminare (a differenza di IVFFlat). |
| D7 | **Ingestione sincrona e chat senza streaming** in v1 | Contratto più semplice. Lo streaming (SSE) e l'ingestione asincrona cambierebbero il contratto: rimandati a una v2. |
| D8 | **Parametri RAG identici** nei due backend: top-5, soglia 0.62 (D22), chunk 500 token, overlap 50 | Le risposte dei due stack devono essere confrontabili. |
| D9 | **Ollama opzionale** tramite *profile* Docker | Chi usa OpenAI non scarica gigabyte di modelli. |
| D10 | **Nessuna autenticazione** in v1, utente fisso `demo-user` | Fuori dallo scopo del portfolio; il campo `requested_by` è già pronto per introdurla. |
| D11 | **Endpoint aggiunto `GET /api/documents`** (non richiesto nel set minimo) | La UI deve mostrare i file caricati. |
| D12 | **Tabella `chat_messages`** (non richiesta) | Serve la memoria di conversazione e l'audit delle azioni compiute dall'IA. |
| D13 | **Come si decide cosa va in `sources[]`** (`SourceResolver`): chunk citati nella risposta; se nessuna citazione è leggibile, i chunk recuperati; se la risposta è il rifiuto o c'è stata un'azione, nessuna fonte | I modelli piccoli spesso sbagliano il formato della citazione: mostrare le fonti consultate è meglio di nessuna. Il rifiuto e le azioni non si basano sui documenti. |
| D14 | **Migration Laravel idempotenti**: se la tabella esiste già (creata dall'init SQL) non fanno nulla | Permette di usare Laravel sia sopra il DB Docker già inizializzato, sia su un database vuoto (test, installazioni standalone). Le due definizioni vanno tenute equivalenti. Verificato in entrambi i casi. |
| D15 | **Ingestione: i duplicati si riconoscono con l'hash; un documento `FAILED` non blocca il ricaricamento** | Se l'embedding fallisce (es. Ollama spento) il file resta visibile come `FAILED` con il motivo. Ricaricarlo cancella il vecchio record e riprova, invece di rispondere 409. |
| D16 | **Scrittura dei chunk atomica**: prima tutti gli embedding, poi una sola transazione | Un documento è o completo o assente: mai metà dei chunk. |
| D17 | **Dimensione dei chunk stimata in caratteri** (4 caratteri ≈ 1 token → 2000 caratteri, overlap 200) | PHP non ha un tokenizer nativo. Spring userà token reali: i due stack non producono chunk identici al carattere, ma equivalenti. |
| D18 | **I test usano PostgreSQL + pgvector reali, con Ollama simulato** (embedding "bag of words" deterministici) | SQLite non ha il tipo `vector`. Così si collaudano per davvero l'indice, la query coseno e le transazioni, senza modelli né rete. |
| D19 | **Protezione dei test**: rifiutano di partire se il database non finisce con `_test` | `RefreshDatabase` esegue `migrate:fresh` e **cancella tutte le tabelle**. Il guard ha già evitato di distruggere il DB di sviluppo durante lo sviluppo di questo passo. |
| D21 | **Il tool dei rimborsi ha due barriere nel codice, indipendenti dal modello**: (1) l'ultimo messaggio deve contenere un **verbo di apertura** (apri, aprimi, crea, registra, invia, richiedi…): una domanda non è un ordine; (2) l'**importo deve averlo scritto l'utente** (messaggio corrente o precedenti). Cifre solo a parole ("cinquanta") non valgono: si chiede di scriverle in cifre | Provate dal vivo con `llama3.2`: a una domanda sul limite aprì un rimborso da 50 € copiando la cifra dai documenti; a "Posso farmi rimborsare 200€ per una cena?" ne aprì uno da 200 €. Il prompt da solo non basta con un modello da 3B. Un'azione con effetti non si decide solo con il testo del modello. |
| D22 | **Soglia di similarità 0.62 (non 0.70)**; da ricalibrare se cambia il modello di embedding | Misurata su `nomic-embed-text` reale: domande pertinenti 0,64–0,84, fuori tema 0,53–0,61. Con 0,70 la domanda "Quanto posso spendere per l'hotel a notte?" (0,669) non trovava nulla e il modello riceveva contesto vuoto. Limite noto: domande brevissime ("Serve la ricevuta?" 0,53) restano sotto soglia. **Spring dovrà usare lo stesso valore.** |
| D23 | **Dopo un'azione riuscita, la risposta è la conferma costruita dal codice se il modello scrive il rifiuto o nulla**; **una citazione `[file.pdf, p. N]` senza estratti recuperati è considerata inventata e sostituita dal rifiuto** | Visto dal vivo: `llama3.2` dopo il tool rispondeva "Non ho trovato…"; `qwen2.5:7b` con contesto vuoto citò `prenotazione_aeroporto.pdf`, un file mai caricato. |
| D24 | **Modello di chat predefinito: `qwen2.5:7b`** (era `llama3.2`) | Vedi il confronto in 5.6. Costa più memoria (4,7 GB) e tempo su CPU, ma rispetta meglio citazioni e uso del tool. |
| D25 | **Frontend: solo componenti standalone, Signals e controllo di flusso `@if/@for`; nessun router, nessun NgModule, nessuna libreria UI** | Una dashboard a pagina singola senza login non ha bisogno di routing. Angular 22 è zoneless di default: lo stato è nei Signals e la UI si aggiorna da sola. Tailwind basta per uno stile professionale. |
| D26 | **Stato in 3 store a Signals (`ChatStore`, `DocumentsStore`, `ReimbursementsStore`), non nei componenti** | I componenti restano sottili (solo eventi del browser); la logica è testabile senza DOM. `ChatStore` chiama `ReimbursementsStore.refresh()` quando la risposta contiene azioni: è il collegamento che aggiorna la tabella "in tempo reale" **a evento**, senza polling. |
| D27 | **Backend selezionabile dal frontend: elenco in `src/environments/environment.ts`** (`backends`, `defaultBackend`) letto tramite il token `BACKENDS` | Prima (Passo 3) era una riga commentata in `environment.ts` da cambiare a mano e riavviare; dal Passo 5 si sceglie a runtime dal selettore (D42). I token permettono ai test di sostituire l'elenco. *(Sostituisce l'`API_URL` originale.)* |
| D28 | **"Utente demo con user_id = 1" è solo visualizzato** (`environment.demoUser`): il frontend **non invia** alcun id utente | Il contratto API non ha un campo utente: il backend registra tutto come `demo-user` (D10). Inventare un header o un campo avrebbe rotto il contratto condiviso con Spring. Se un domani serve l'autenticazione, va aggiunta al contratto per entrambi i backend. |
| D29 | **Upload dei PDF uno alla volta**, con controllo lato client (PDF, ≤ 20 MB, non vuoto) prima di chiamare il server | L'indicizzazione è sincrona e lenta (D7): in parallelo si saturerebbe il backend. Il controllo client dà un errore immediato, ma il server resta l'autorità (413/415/422 gestiti comunque). |
| D30 | **Il testo del modello e dei PDF si mostra solo con l'interpolazione `{{ }}`, mai con `innerHTML`** (coperto da un test con `<script>` e `<img onerror>`) | Il contenuto arriva da un LLM che legge documenti non fidati: è un vettore di XSS / prompt injection. |
| D31 | **Errori: `toApiError()` converte ogni errore in un messaggio leggibile** usando il `detail` del problem+json (già in italiano) e un messaggio dedicato per "server irraggiungibile" (status 0) | Entrambi i backend rispondono con lo stesso formato (D5): un solo punto di conversione, nessun testo duplicato nel frontend. |
| D32 | **Spring Boot 4.2 + Spring AI 2.1, non "Spring Boot 3.x"** come scritto nella richiesta del Passo 4 | Spring AI 2.x richiede Spring Boot 4 (Framework 7, Jackson 3, Hibernate 7): su Boot 3 si può usare solo Spring AI 1.x, con API diverse. Al Passo 1 si era deciso di usare sempre le ultime versioni; la richiesta "3.x" è stata letta come un refuso. Se serve davvero Boot 3, cambia lo stack di Spring AI (non solo la versione del parent). |
| D33 | **`VectorStore` di Spring AI implementato sulle NOSTRE tabelle** (`DocumentChunkVectorStore` su `document_chunks`), non il `PgVectorStore` ufficiale | Il `PgVectorStore` crea e usa una sua tabella (`id, content, metadata, embedding`). Lo schema è condiviso con Laravel (D1): con il `PgVectorStore` i PDF indicizzati da un backend sarebbero invisibili all'altro. Implementando l'interfaccia (`add`, `upsert`, `similaritySearch`, `SearchRequest`, filtri) il resto del codice usa l'API standard e **i due backend condividono lo stesso indice** (provato: una domanda a Spring su un PDF indicizzato da Laravel dà la stessa fonte e lo stesso score, 0,8425). |
| D34 | **Function calling: `@Bean @Description` + `FunctionToolCallback`, dati della richiesta nel `ToolContext`** | In Spring AI 2.x `ChatClient` non ha più `.toolNames("...")`: le funzioni-bean non si richiamano più per nome. Il bean (`BiFunction<Args, ToolContext, String>` con `@Description`) è incapsulato in un `ToolCallback` e passato con `.toolCallbacks(...)`. Essendo un singleton non vede la richiesta corrente: conversazione, messaggi dell'utente e raccolta delle azioni gli arrivano dal `ToolContext`. |
| D35 | **Il system prompt va come `SystemMessage`, non con `.system(String)`** | `.system(String)` è un *template* (StringTemplate): le parentesi `{ }` presenti nei documenti o nei messaggi verrebbero lette come variabili e farebbero fallire la chiamata. Coperto da un test. |
| D36 | **Con entrambi gli starter (Ollama + OpenAI) nel classpath vanno disattivati esplicitamente i modelli non usati** (`spring.ai.model.audio.speech/transcription`, `image`, `moderation`, `decision` = `none`) | `spring.ai.model.chat/embedding` non basta: l'autoconfigurazione di OpenAI crea comunque gli altri modelli e **l'app non parte senza chiave API** ("At least one credential source must be specified"). Trovato avviando l'app per la prima volta. |
| D37 | **Retry 2 tentativi (default Spring AI: 10), timeout di lettura 300 s, limiti al tool calling** | Con 10 tentativi un Ollama spento darebbe un 502 dopo minuti, mentre Laravel risponde subito. Il timeout è 300 s perché su CPU un prompt con 5 chunk lunghi ha superato 180 s (visto dal vivo). Il tool può girare al massimo 3 volte per richiesta (equivalente di `max_steps`). |
| D38 | **JPA per `documents` e `reimbursement_requests`; `JdbcClient` per `chat_messages` e per i chunk** | Le colonne JSONB (`sources`, `actions`) e il tipo `vector` con SQL esplicito (`CAST(... AS jsonb/vector)`) sono prevedibili e identici a ciò che scrive Laravel; con l'ORM richiederebbero convertitori JSON/vettore. Il repository JPA dei rimborsi resta quello su cui scrive il tool, come richiesto. |
| D39 | **Prompt identico a Laravel, verificato da un test** (`PromptParityTest` confronta `rag-system.txt` dei due backend) | Se qualcuno modifica il prompt in uno solo, il test fallisce: è ciò che rende confrontabili le risposte dei due stack (D8). |
| D40 | **Test: PostgreSQL + pgvector reali e un finto Ollama che è un vero server HTTP** (JDK `HttpServer`), database dedicato `eaa_spring_test` con lo schema creato da `db/init/01-init.sql` | L'app usa il VERO client Ollama di Spring AI contro il finto server: si collaudano per davvero serializzazione, tool calling e errori. Lo schema non è duplicato nei test (D1). Stesso guard `_test` di Laravel (D19). Non si usa Testcontainers: richiederebbe Docker-in-Docker; basta `docker compose up -d postgres`. |
| D41 | **Tomcat `max-swallow-size: 100MB`** | Senza, un upload oltre i 20 MB chiude la connessione (Tomcat scarta solo 2 MB del corpo) e il client vede un errore di rete invece del 413 del contratto. Verificato con file da 21 e 50 MB. |
| D42 | **Selettore di backend a runtime** (`BackendStore` + `BackendSwitch`): l'URL base è un Signal letto da `ApiService` a ogni chiamata; la scelta si salva in `localStorage` (`eaa.backend`); i pannelli osservano `apiUrl` con un `effect` e si ricaricano da soli | Cambiare backend ha effetto immediato, senza riavvio e senza dipendenze circolari (lo store del backend non conosce gli altri store). Gli store scartano le risposte "superate" con un contatore di esecuzione: se l'utente cambia backend mentre una richiesta è in volo, la risposta lenta del vecchio backend non sovrascrive i dati del nuovo (coperto da un test). La chat **non** si azzera al cambio: `chat_messages` è condivisa, quindi la stessa conversazione prosegue sull'altro backend (verificato dal vivo). |
| D43 | **Chip suggeriti sopra la casella di testo: l'etichetta è corta, il messaggio inviato è la frase completa** (es. "Limite massimo per la cena?" → "Qual è il limite massimo per una cena?") | Le domande troppo brevi recuperano peggio i documenti (D22); le frasi complete sono quelle collaudate dal vivo. I chip si disabilitano mentre si attende la risposta. |
| D44 | **`.gitignore` radice come rete di sicurezza**, più quelli dei sottoprogetti; verifica con comandi nel README e con un test negativo | Blocca `.env*` (tranne `.env.example`), chiavi, dipendenze, build, log e file di sistema. Verificato con un repository git temporaneo *fuori* dal progetto (210 file versionati, solo `.env.example`) e con un test che forza un `.env` e un `.log` per accertarsi che il controllo li segnali. |
| D45 | **`OLLAMA_BASE_URL` nel compose** (default `http://ollama:11434`) | Permette di usare Ollama **nativo sul Mac** (GPU) senza toccare i file: `OLLAMA_BASE_URL=http://host.docker.internal:11434`. Il percorso di rete container→host è verificato da entrambi i container con un servizio di prova; Ollama nativo non è installato sulla macchina di test. |
| D46 | **Barriera sull'intento resa flessibile e testabile** (`ReimbursementIntent`, identica in Laravel e Spring): accetta i verbi naturali di creazione (apri, aggiungi, crea, inserisci, registra, richiedi, invia, fai, metti, presenta…) e la forma breve imperativa con rimborso + importo ("allora un rimborso per hotel da 120 euro"); blocca domande informative, di permesso e ipotesi ("posso…?", "qual è…?", "mi spetta…?", "se apro…"), frasi negate e affermazioni ("il rimborso hotel è di 150 euro"). Prompt e descrizione del tool aggiornati di conseguenza | Segnalato dall'utente provando dal vivo: con "aggiungi un rimborso 20 euro per taxi" e simili il modello rispondeva "non ho trovato nei documenti". Due cause: l'elenco di verbi non conteneva "aggiungi" (e la frase senza verbo non passava) e la regola 5 del prompt parlava solo di "aprire/creare/registrare". La stessa tabella di 23 frasi da accettare e 20 da bloccare è nei test di entrambi i backend: le due implementazioni (PCRE e Java) devono coincidere. |
| D47 | **Due reti di sicurezza dopo la risposta del modello**: (1) **conferma falsa** — se la richiesta era un ordine, non è stato creato nulla e la risposta dichiara il contrario (o contiene una finta chiamata del tool come testo, o un ID inventato come `RR123456`), la risposta è sostituita da un messaggio onesto; (2) **fallback deterministico** — se l'ordine è non ambiguo (un solo importo, mai con "meno" o segno negativo) e il modello non ha chiamato il tool, il backend crea lui il rimborso con lo stesso `ReimbursementService` | Provato dal vivo: con le frasi brevi `qwen2.5:7b` a volte **narra** la chiamata ("Lo strumento ha risposto: { id: RR123456 }… registrato con successo") senza farla: all'utente arrivava una conferma falsa. Misurato sulla frase senza verbo prima del fallback: il modello ha chiamato il tool solo 3 volte su 11 (Laravel 1 su 4, Spring 2 su 7). Il fallback usa le stesse barriere (importo scritto dall'utente per costruzione, tetto 10.000 € e CHECK del DB, stato OPEN, utente demo); se il servizio rifiuta (es. oltre il tetto) l'utente riceve il motivo. È una scelta di progetto: per l'ordine esplicito è l'utente a decidere, non il modello. |
| D20 | **Server in container: `php -S`, non `php artisan serve`** | `artisan serve` non inoltra al server le variabili d'ambiente di Docker (`DB_HOST=postgres`) e usa quelle del file `.env` (`127.0.0.1`). Trovato solo provando il server vero. |

---

| D48 | **Serve sempre un verbo esplicito; la frase senza verbo NON esegue nulla e mostra un avviso a video** (sostituisce l'accettazione della "forma breve" di D46). `ReimbursementIntent.verblessHint()` riconosce una frase che sembra un ordine ma non ha il verbo ("allora un rimborso per hotel da 120 euro", "rimborso #2 eseguito"): niente azione, **nessuna chiamata al modello**, risposta immediata che elenca i verbi giusti con un esempio costruito sul messaggio ("Prova così: «Aggiungi un rimborso da 120 € per hotel»") e campo `warning` nella risposta, mostrato dal frontend come avviso ambra | Richiesta dell'utente: meglio usare i verbi; senza verbo corretto ci deve essere la barriera, oppure la risposta deve dire concretamente che servono i verbi giusti, "magari come avviso a video". Misurato in precedenza: senza verbo il modello chiamava il tool solo 3 volte su 11 e a volte narrava un risultato inventato; indovinare l'azione dalle parole era fragile. Costo zero (nessuna inferenza) e comportamento prevedibile. Il messaggio e la risposta sono comunque salvati nella conversazione. |
| D49 | **Secondo tool `updateReimbursementStatus`** (Laravel: Prism `Tool`; Spring: `FunctionToolCallback`) con tre barriere nel codice, indipendenti dal modello: (1) intento = verbo esplicito di aggiornamento (segna, approva, rifiuta, esegui, aggiorna, chiudi, paga, marca, imposta…; "segna"/"metti" solo con un riferimento a un rimborso esistente, altrimenti sono verbi di creazione); (2) **il nuovo stato lo decide il testo dell'utente** (`requestedStatus`: approva→APPROVED, rifiuta→REJECTED, esegui/segna come eseguito/paga/chiudi→EXECUTED; se ce n'è più di uno non si fa nulla); (3) il `#numero` o la parola della descrizione devono comparire nel messaggio dell'utente. Il servizio (`ReimbursementService::updateStatus`, unico punto che modifica lo stato) rifiuta con un messaggio chiaro: numero inesistente, parola senza corrispondenze o con più corrispondenze ("Indica il numero"), stato già impostato, stato finale (EXECUTED e REJECTED non si modificano). Stesse due reti di sicurezza della creazione: conferma falsa di aggiornamento → messaggio onesto (`NOT_UPDATED`); ordine non ambiguo non eseguito dal modello → lo esegue il backend | Richiesta del Passo 9. Un modello piccolo può scegliere il rimborso o lo stato sbagliato: per questo stato e riferimento li fissa il testo dell'utente e non gli argomenti del modello (testato: il modello prova a rifiutare l'hotel mentre l'utente dice "approva la cena" → l'hotel non cambia e la cena viene approvata dalla rete di sicurezza). |
| D50 | **Colonna `number` (identity) e stato `EXECUTED` nello schema condiviso**, con migrazione idempotente di aggiornamento: Laravel `2026_11_26_000005_upgrade_reimbursement_requests` (aggiunge la colonna e la UNIQUE solo se mancano e rimpiazza il CHECK dello stato), Spring `db/upgrade-reimbursements.sql` eseguito da `spring.sql.init` (separatore `^^^`, per il blocco `DO $$`). `01-init.sql` è aggiornato per i database nuovi. **Modifica rispetto a D1** ("schema solo in `01-init.sql`"): per non costringere a cancellare i dati dell'utente si è aggiunto uno script di upgrade per backend | L'utente poteva già avere rimborsi nel database di sviluppo e `01-init.sql` viene eseguito solo alla *prima* creazione del volume. Gli UUID non sono utilizzabili in chat ("#2" sì): serviva un numero progressivo, assegnato dal DB (non dai backend) per essere identico nei due stack. Le righe esistenti sono state numerate automaticamente (#1 e #2) e i dati sono rimasti. Il test di Spring esegue sia `01-init.sql` sia lo script di upgrade: verifica anche l'idempotenza. |

## 5. Guida ai file creati

### 5.1 `docker-compose.yml` (root)

Avvia l'infrastruttura. Comandi:

```bash
docker compose up -d                     # solo PostgreSQL + pgvector
docker compose --profile ollama up -d    # anche Ollama + download dei modelli
docker compose down -v                   # ferma e CANCELLA i dati (necessario per rieseguire l'init SQL)
```

Servizi:

- **`postgres`** — immagine `pgvector/pgvector:0.8.2-pg18` (PostgreSQL 18 con l'estensione già compilata).
  - Le variabili `POSTGRES_*` hanno un valore di default (`eaa`) sovrascrivibile da `.env`.
  - Il volume `pgdata` è montato su **`/var/lib/postgresql`**: da PostgreSQL 18 l'immagine non usa più `.../data` e il vecchio percorso impedisce l'avvio.
  - `./db/init` è montata in `/docker-entrypoint-initdb.d`: gli script SQL girano **una sola volta**, al primo avvio con volume vuoto. Se modifichi lo schema devi ricreare il volume.
  - L'`healthcheck` con `pg_isready` permette agli altri servizi di attendere che il DB sia pronto. Il doppio `$$` impedisce a Compose di sostituire la variabile al posto del container.
- **`ollama`** (profile `ollama`) — server LLM locale sulla porta 11434, con volume `ollama` per non riscaricare i modelli.
- **`ollama-pull`** (profile `ollama`) — servizio *one-shot*: aspetta che `ollama` sia sano, scarica `qwen2.5:7b` (chat, vedi D24) e `nomic-embed-text` (embedding) ed esce.
- **`backend-laravel`** (profile `laravel`, aggiunto al Passo 2) — costruisce `backend-laravel/Dockerfile` e serve l'API su `:8000`. Si avvia con `docker compose --profile laravel up -d`. Monta la cartella `backend-laravel` per sviluppare senza ricostruire; riceve `DB_HOST=postgres` e `OLLAMA_URL=http://ollama:11434` dall'ambiente.

### 5.2 `db/init/01-init.sql`

Crea le estensioni e le tabelle. Commento per tabella:

- **`documents`** — un record per PDF.
  - `content_hash CHAR(64) UNIQUE`: SHA-256, impedisce duplicati.
  - `status` con `CHECK` su `PROCESSING | READY | FAILED`; `error_message` spiega il fallimento.
- **`document_chunks`** — i frammenti di testo con il loro vettore.
  - `embedding VECTOR(768)`: la colonna pgvector.
  - `page_number`: base 1, è ciò che permette la citazione "p. N".
  - `ON DELETE CASCADE`: cancellando un documento spariscono i suoi chunk.
  - `UNIQUE (document_id, chunk_index)`: garantisce l'ordine e impedisce chunk doppi.
  - Indice HNSW: `USING hnsw (embedding vector_cosine_ops)`. L'operatore `<=>` è la **distanza** coseno; lo **score** del contratto è `1 - distanza` (1 = identico). La query di retrieval di riferimento è nel commento del file.
- **`reimbursement_requests`** — prodotto dal tool calling.
  - `amount NUMERIC(10,2)` con `CHECK (amount > 0 AND amount <= 10000)`: il limite è imposto dal DB, quindi anche se l'IA o il codice sbagliano il dato non entra.
  - `source_conversation_id`: collega la richiesta alla chat da cui è nata (tracciabilità).
  - `status` `OPEN | APPROVED | REJECTED | EXECUTED` (CHECK), default `OPEN`. `REJECTED` ed `EXECUTED` sono stati finali (regola del servizio, non del DB).
  - `number BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE` (Passo 9): numero progressivo `#1, #2…` che l'utente scrive in chat; `id` UUID resta la chiave primaria. I database creati prima del Passo 9 vengono aggiornati da una migrazione idempotente (D49).
- **`chat_messages`** — storico della conversazione; `sources` e `actions` sono JSONB per conservare ciò che l'IA ha citato e fatto.

### 5.3 `docs/api/openapi.yaml`

Il contratto. Struttura:

- **`POST /api/documents`** — `multipart/form-data` con il campo `file`. Risposte: `201` (Document), `400`, `409` duplicato, `413` > 20 MB, `415` non PDF, `422` PDF senza testo (scansione), `500`.
- **`GET /api/documents`** — lista dei documenti, più recente per primo.
- **`POST /api/chat`** — richiesta `{conversationId?, message, documentIds?}`.
  - Risposta `{conversationId, messageId, answer, sources[], actions[], createdAt}`.
  - `sources[]`: `chunkId, documentId, filename, page, score, snippet`.
  - `actions[]`: `type: REIMBURSEMENT_CREATED` con `reimbursementId, amount, currency, description`.
  - Il blocco `description` dell'operazione elenca le regole di comportamento comuni ai due backend (D8).
- **`GET /api/reimbursements`** — paginata (`page` base 0, `size` 1–100), filtro `status`.
  - Risposta `{items[], page, size, totalItems, totalPages}`.
- **`components/schemas`** — tipi riusabili (`Document`, `Source`, `Action`, `Reimbursement`, `Problem`…). I campi che possono mancare sono `["tipo","null"]` e sono sempre presenti nel JSON (mai omessi), per semplificare il frontend.
- **`components/responses`** — gli errori `problem+json` con il loro `type` URN.

Come si usa: i due backend devono rispettarlo alla lettera; più avanti si potrà generare il client TypeScript di Angular da questo file e testare i backend contro di esso.

### 5.4 Struttura del repo

```
enterprise-ai-assistant/   (cartella attuale: automazione-backoffice)
├── docker-compose.yml
├── db/init/01-init.sql
├── docs/
│   ├── PROJECT_LOG.md          ← questo file
│   └── api/openapi.yaml
├── frontend-angular/           Angular 22 + Tailwind 4 — vedi 5.7
├── backend-laravel/            Laravel 13 — vedi 5.5
└── backend-springboot/         Spring Boot 4.2 + Spring AI 2.1 — vedi 5.8
```

### 5.5 `backend-laravel/` — guida al codice

**Come avviarlo** (serve solo Docker; il PHP del Mac non basta):

```bash
docker compose --profile laravel up -d            # PostgreSQL + API su http://localhost:8000
docker compose --profile ollama --profile laravel up -d   # con Ollama locale
docker compose run --rm -e RUN_MIGRATIONS=false backend-laravel php artisan test   # 52 test
```

Variabili principali (`backend-laravel/.env.example`): `AI_PROVIDER` (`ollama` | `openai`), `AI_CHAT_MODEL`, `AI_EMBEDDING_MODEL`, `AI_EMBEDDING_DIMENSIONS` (768), `OLLAMA_URL`, `OPENAI_API_KEY`, `PRISM_REQUEST_TIMEOUT` (180 s: un LLM locale è lento), `RAG_*` e `CORS_ALLOWED_ORIGINS`.

**Struttura**

```
backend-laravel/
├── bootstrap/app.php                 rotte API + conversione errori in problem+json
├── routes/api.php                    le 4 rotte del contratto
├── config/eaa.php                    parametri RAG, provider AI, limiti (un solo posto)
├── config/prism.php · cors.php
├── database/migrations/              4 migration idempotenti
├── resources/prompts/rag-system.txt  system prompt anti-allucinazione
├── app/
│   ├── Http/Controllers/             Document · Chat · Reimbursement (sottili)
│   ├── Http/Requests/                validazione + mappatura degli status del contratto
│   ├── Http/Resources/               forma JSON esatta (camelCase, date ISO, importi stringa)
│   ├── Services/Rag/                 estrazione PDF · chunking · embedding · ingestione · ricerca
│   ├── Services/Chat/                ChatService · PromptBuilder · SourceResolver · ReimbursementTool
│   ├── Services/ReimbursementService.php   UNICO punto che scrive i rimborsi
│   ├── Models/ · Enums/              Eloquent + stati tipizzati
│   ├── Exceptions/ApiProblem.php     errori di dominio -> RFC 9457
│   └── Support/Iso.php               formato data del contratto
├── tests/                            Unit (chunker, fonti) + Feature (le 4 rotte)
└── Dockerfile · docker/              PHP 8.4 + pdo_pgsql, php.ini, entrypoint
```

**Pipeline di ingestione — `POST /api/documents`**

1. `UploadDocumentRequest` — traduce i controlli nei codici del contratto: file assente → 400; oltre 20 MB (anche se PHP lo ha scartato per `upload_max_filesize`) → 413; non-PDF → 415. Il tipo si legge dal **contenuto** del file (magic bytes), non dall'estensione: un eseguibile rinominato `.pdf` viene rifiutato.
2. `DocumentIngestionService::ingest()` — orchestra la pipeline:
   - calcola lo SHA-256; se il documento esiste ed è `READY`/`PROCESSING` → 409, se è `FAILED` lo sostituisce (D15);
   - crea il record `PROCESSING`; la `UNIQUE` sull'hash protegge anche dai caricamenti simultanei;
   - estrae → divide → calcola gli embedding → scrive i chunk in **una transazione** (D16) → `READY`;
   - se qualcosa fallisce: stato `FAILED` con `error_message` e l'eccezione prosegue verso il client.
3. `PdfTextExtractor` — usa `smalot/pdfparser` **pagina per pagina**. Pulisce il testo: UTF-8 valido, caratteri di controllo rimossi (il byte NUL farebbe rifiutare l'INSERT a PostgreSQL), spazi compressi. PDF corrotto o senza testo (scansione) → 422.
4. `TextChunker` — finestra di ~2000 caratteri con 200 di sovrapposizione (D17). Taglia sul confine migliore dell'ultimo 40% della finestra (paragrafo → a capo → fine frase → `;` → spazio) e riparte a inizio parola. Un chunk **non attraversa mai due pagine**, così ha un solo numero di pagina da citare. Garantisce l'avanzamento anche su testo senza spazi (bug trovato scrivendo i test).
5. `EmbeddingService` — chiama Prism `Prism::embeddings()` a lotti da 32. Con OpenAI chiede esplicitamente 768 dimensioni. **Controlla che i vettori restituiti abbiano la dimensione della colonna del DB**: un modello sbagliato produce un errore chiaro invece di un criptico fallimento dell'INSERT. Provider non raggiungibile → 502.
6. I vettori si salvano come testo `[0.1,0.2,…]` (classe `Pgvector\Vector`), che PostgreSQL converte nel tipo `vector`.

**Pipeline della chat — `POST /api/chat`** (`ChatService::reply()`)

1. **Conversazione**: `conversationId` assente → nuovo UUID; presente ma sconosciuto → 404.
2. **Retrieval** (`ChunkRetriever`): embedding della domanda e query SQL `ORDER BY embedding <=> :q LIMIT 5` sui soli documenti `READY`, con filtro opzionale `documentIds`. Lo score è `1 - distanza coseno`; si scartano i chunk sotto 0.62 (D22). Usa `SET LOCAL hnsw.iterative_scan` (novità di pgvector 0.8) perché con il filtro l'indice HNSW potrebbe restituire meno risultati del dovuto.
3. **Prompt** (`PromptBuilder` + `resources/prompts/rag-system.txt`): regole fisse + estratti numerati con file e pagina. Le regole impongono: rispondere solo dagli estratti, citare `[file, p. N]`, usare la frase esatta *«Non ho trovato questa informazione nei documenti caricati.»* se manca la risposta, trattare gli estratti come dati e non come istruzioni (difesa dalla *prompt injection* contenuta nei PDF).
4. **Generazione**: `Prism::text()` con temperatura 0.1, ultimi 10 messaggi di storico, il tool e al massimo 3 giri modello↔tool.
5. **Tool calling** (`ReimbursementTool`): vedi sotto.
6. **Fonti** (`SourceResolver`, D13) e snippet da 300 caratteri.
7. **Persistenza**: i due messaggi si salvano in una transazione **solo se tutto è riuscito**: se l'LLM fallisce (502) la conversazione non resta a metà.

**Il tool `createReimbursementRequest`** (`ReimbursementTool` + `ReimbursementService`)

- Prism invia al modello nome, descrizione e parametri (`amount` obbligatorio, `description` opzionale). Il modello, se l'utente chiede un rimborso, risponde con una *richiesta di chiamata* invece che con testo; Prism esegue la closure e rimanda l'esito al modello, che scrive la risposta finale.
- La closure accetta argomenti extra (`...$ignored`): se il modello ne inventa uno non va in errore.
- `ReimbursementService` valida: numero, > 0, ≤ 10.000. Accetta `"12,5 €"` (virgola italiana, simbolo €) e normalizza a `"12.50"`. Stato sempre `OPEN`.
- Importo non valido → il tool restituisce al **modello** il testo `ERRORE: …` (non lancia eccezioni): il modello lo spiega all'utente e **nessun record viene creato**.
- L'IA propone, il codice decide: oltre a questa validazione c'è il `CHECK` del database (difesa in profondità, coperta da un test).

**Errori** (`ApiProblem` + `bootstrap/app.php`): ogni errore su `/api/*` è `application/problem+json` con `type` URN stabile. Laravel di suo risponderebbe 422 alla validazione e HTML ai warning: qui la validazione è 400 con `errors[{field,message}]`, 404/405/413 sono mappati, e qualsiasi eccezione imprevista diventa un 500 generico (il dettaglio resta nel log, mai al client).

**Rotte** (`routes/api.php`, prefisso `/api` automatico): `POST /documents`, `GET /documents`, `POST /chat`, `GET /reimbursements`. Il `page` di `GET /reimbursements` è in base 0 come da contratto, mentre `forPage()` di Laravel è in base 1: la conversione è esplicita nel controller.

**Cosa è stato verificato** (e come)

- 52 test, 1188 asserzioni, **su PostgreSQL 18 + pgvector 0.8.2 reali** (Ollama simulato; la prova con modelli veri è in 5.6): ogni codice del contratto (200/201/400/404/405/409/413/415/422/502), la forma esatta dei JSON, il retrieval vettoriale, il tool con importo valido/non valido/oltre il tetto/con virgola, lo storico, CORS, il vincolo CHECK del DB.
- Prova sul **server vero** via `curl`: lista vuota, 404, 400, 413 (21 MB e 50 MB), 415, e **502 con Ollama spento** sia in upload sia in chat. Questa prova ha trovato 3 difetti che i test non vedevano (D20, il controllo dell'upload rifiutato da PHP, il warning PHP che sporcava il JSON).
- Le migration girano sia su DB già inizializzato dall'init SQL (nessuna modifica) sia su DB vuoto.

**Cosa NON è stato verificato**: **OpenAI** (il provider è configurabile ma mai chiamato; con `AI_PROVIDER=openai` le dimensioni vengono ridotte a 768 via parametro `dimensions`). Ollama invece è stato provato con modelli veri: vedi 5.6.

**Limiti noti**: ingestione sincrona (un PDF molto grande può impiegare minuti e bloccare un worker), PDF scansionati non supportati (serve OCR), citazione con più pagine insieme (`p. 4, 5`) riconosciuta solo per la prima, nessuna autenticazione.

### 5.6 Collaudo dal vivo con modelli reali (Ollama in Docker, solo CPU)

**Setup**: `docker compose --profile ollama --profile laravel up -d`; modelli scaricati: `nomic-embed-text` (274 MB), `llama3.2` (2,0 GB), `qwen2.5:7b` (4,7 GB). PDF di prova: [`backend-laravel/tests/Fixtures/policy_rimborsi.pdf`](../backend-laravel/tests/Fixtures/policy_rimborsi.pdf) (2 pagine: limite cena 50 €, limite hotel 150 €). Docker ha 7,7 GB di RAM: due modelli grandi insieme non ci stanno (`ollama stop <modello>` per scaricarne uno).

**Ingestione reale**: `POST /api/documents` → 201 in ~2 s, 2 chunk, embedding da **768 dimensioni** con `nomic-embed-text` (conferma che la colonna `VECTOR(768)` è giusta).

**Risultati** (prompt identici; ogni riga è una chiamata vera a `POST /api/chat`):

| Prova | `llama3.2` (3B) | `qwen2.5:7b` (finale, soglia 0.62) |
|---|---|---|
| "Qual è il limite massimo per una cena?" | ✅ risposta e fonte (a volte senza "€") | ✅ "50€ a persona [policy_rimborsi.pdf, p. 2]", fonte score 0,84 |
| "Quanto posso spendere per l'hotel a notte?" | ❌ scriveva il tool call **come testo JSON** all'utente | ✅ "150€ a notte [policy_rimborsi.pdf, p. 2]" (dopo D22) |
| "Aprimi un rimborso da 40€ per cena" | ✅ creato; ma risposta finale = frase di rifiuto e descrizione inventata | ✅ creato 40,00 EUR; conferma corretta |
| "Posso farmi rimborsare 200€ per una cena?" | ❌ **creò un rimborso da 200 €** | ✅ nessuna azione (barriera D21) |
| "Quanto costa un volo per la luna?" | ✅ rifiuto | ✅ rifiuto |
| Domanda sul limite cena (senza richiesta di rimborso) | ❌ la prima volta **creò un rimborso da 50 €** | ✅ nessuna azione |
| Tempi su CPU | 6–38 s | 7–84 s (la prima include il caricamento del modello) |

**Cosa ha insegnato il collaudo** (nessuno di questi punti era visibile nei test con modello simulato):

1. I modelli piccoli **eseguono azioni non richieste**. Il prompt non basta: servono barriere nel codice (D21).
2. Una soglia di similarità scelta "a occhio" (0.70) era sbagliata: va **calibrata con embedding reali** (D22).
3. Con contesto vuoto un modello può **inventare un nome di file** nella citazione (D23).
4. Gli **esempi nella descrizione dei parametri del tool vengono copiati**: "es. cena col cliente" finiva nel database. Rimosso.
5. Dopo un tool riuscito un modello può rispondere con il rifiuto (D23).

**Limiti rimasti** (osservati, non risolti):
- "Posso farmi rimborsare 200€ per una cena?" con `qwen2.5:7b` risponde col rifiuto + una citazione, invece di dire che il limite è 50 €. Non crea nulla (che è l'importante), ma la risposta è migliorabile.
- Domande molto brevi ("Serve la ricevuta?") restano sotto soglia (0,53).
- Corpus di prova minuscolo (1 PDF, 2 chunk): la soglia 0.62 va riverificata con documenti veri e più numerosi.
- `llama3.2` resta supportato cambiando `AI_CHAT_MODEL`, ma **non è raccomandato** per il tool calling.

### 5.7 `frontend-angular/` — guida al codice

**Come usarlo**

```bash
cd frontend-angular
npm install                  # la prima volta
npm start                    # ng serve su http://localhost:4200
npm test                     # 64 test (Vitest)
npm run build                # build di produzione (~214 kB, 60 kB compressi)
```

Serve il backend acceso: `docker compose --profile ollama --profile laravel up -d` (Ollama è opzionale per sfogliare l'interfaccia, necessario per chat e upload). **Per passare a Spring Boot** si usa il **selettore Backend nell'intestazione** (D42): cambia l'URL a runtime, ricarica documenti e rimborsi e ricorda la scelta in `localStorage`; gli URL e il backend di partenza sono in [`src/environments/environment.ts`](../frontend-angular/src/environments/environment.ts) (`backends`, `defaultBackend`). Vedi anche 5.8.

**Struttura**

```
frontend-angular/src/
├── environments/environment.ts      elenco backend (Laravel/Spring) + default + utente demo
├── styles.css                       @import "tailwindcss"
└── app/
    ├── app.ts / app.html            shell: intestazione + griglia a 3 colonne
    ├── app.config.ts                provideHttpClient(withFetch()), nessun router
    ├── core/
    │   ├── api/
    │   │   ├── models.ts            tipi TypeScript = specchio di docs/api/openapi.yaml
    │   │   ├── api.service.ts       un metodo per endpoint, nessuna logica
    │   │   ├── api-error.ts         ApiError + toApiError() (problem+json -> messaggio)
    │   └── state/                   store a Signals
    │       ├── backend.store.ts     backend selezionato (Signal) + localStorage
    │       ├── chat.store.ts · documents.store.ts · reimbursements.store.ts
    ├── features/
    │   ├── backend/     backend-switch    (selettore nell'intestazione)
    │   ├── documents/   documents-panel   (colonna sinistra)
    │   ├── chat/        chat-panel · chat-message   (centro)
    │   └── reimbursements/ reimbursements-panel     (destra)
    ├── shared/          format.pipes.ts (money, dateTime, fileSize) · status-badge.ts
    └── testing/         test-backend.ts (provider comuni dei test)
```

**Come funziona**

- **Colonna sinistra — `DocumentsPanel` + `DocumentsStore`.** La dropzone gestisce `dragover/dragleave/drop` (con `preventDefault`, altrimenti il browser apre il PDF in un'altra scheda) ed è anche un pulsante accessibile da tastiera (Invio/Spazio) che apre il selettore file. Ogni file passa da `validatePdf()`; poi `POST /api/documents` come `FormData` (il browser aggiunge da solo il boundary del multipart). Per ogni file mostra "Indicizzazione in corso…", poi "Documento indicizzato" (sparisce da solo dopo 4 s) oppure l'errore (resta finché non lo chiudi). Dopo **ogni** tentativo ricarica la lista: se il backend ha registrato un documento `FAILED`, si vede subito. L'elenco mostra nome, stato (badge), pagine, frammenti, dimensione, data.
- **Colonna centrale — `ChatPanel` + `ChatMessageView` + `ChatStore`.** Messaggio utente a destra, risposta dell'IA a sinistra. Mentre si attende la risposta compare l'indicatore a tre puntini (e il campo si svuota e l'invio si blocca). Sotto la risposta: i **badge delle fonti** "nomefile.pdf · p. N" (cliccandoli si legge il frammento citato e la pertinenza in %), e, se l'IA ha eseguito un'azione, una **scheda verde** "Richiesta di rimborso creata: 40,00 €". Invio invia, Maiusc+Invio va a capo; la chat scorre da sola in fondo. "Nuova conversazione" azzera `conversationId`. A chat vuota propone tre domande di esempio cliccabili. Se il server risponde 404 per una conversazione scomparsa (DB ricreato), la chat ne inizia una nuova e lo dice.
- **Colonna destra — `ReimbursementsPanel` + `ReimbursementsStore`.** Tabella (data, importo `40,00 €`, descrizione, stato con badge) ordinata come la manda il server (più recente prima). **Si aggiorna da sola**: quando `ChatStore` riceve una risposta con `actions`, chiama `reimbursements.refresh(ids)`; la riga nuova resta evidenziata in verde per 4 secondi. C'è anche un pulsante "Aggiorna". Mostra le 50 più recenti e il totale.
- **Selettore Backend** (`BackendSwitch`, intestazione, aggiunto al Passo 5): due pulsanti "Laravel | Spring Boot" con ruolo `radiogroup` (frecce da tastiera), l'host del backend scelto e un pallino di stato (ambra = connessione in corso, verde = raggiungibile, rosso = non raggiungibile, in base all'ultimo caricamento dell'elenco documenti). `BackendStore` tiene il backend selezionato in un Signal; `ApiService` ne legge l'URL a **ogni** chiamata; `DocumentsPanel` e `ReimbursementsPanel` ricaricano con un `effect` quando l'URL cambia. Le risposte superate (richiesta lenta del backend precedente) si scartano. La scelta vive in `localStorage` (`eaa.backend`) e, se non valida o non disponibile, si ricade su `defaultBackend` / primo elemento. La chat resta com'è: la conversazione continua sull'altro backend perché `chat_messages` è condivisa.
- **Chip suggeriti** (`ChatPanel`, Passo 5): quattro chip sopra la casella di testo — 📄 *Limite massimo per la cena?*, ✅ *Segna come eseguito il rimborso #1* (dal Passo 9, al posto di 🏨 *Massimale rimborso hotel?*), 💶 *Apri rimborso da 40€ per cena*, ❓ *Posso farmi rimborsare 200€?* — che mostrano cosa sa fare l'IA (una domanda RAG e tre casi d'azione, di cui uno che NON deve fare nulla). Un clic invia direttamente la frase completa (D43); sono disabilitati durante l'attesa. L'icona è decorativa (`aria-hidden`). Sostituiscono i tre suggerimenti dello stato vuoto.
- **Stato vuoto / errore in ogni pannello**: spiegazione cosa fare, errore leggibile e "Riprova" se il backend non risponde.
- **Responsive**: da 1024 px in su tre colonne affiancate a tutta altezza; sotto, le colonne si impilano. Su schermo stretto la tabella scorre in orizzontale dentro il suo pannello.
- **Accessibilità di base**: `aria-live` sulla chat e sugli upload, etichette sui campi, `role="status"` sull'indicatore, focus visibile, tabella con `<caption>` e `scope`.

**Un dettaglio di Tailwind che morde**: le classi vanno scritte **per intero** nel codice (`'border-indigo-500 bg-indigo-50'`), mai composte con stringhe (`'border-' + colore`), perché Tailwind genera solo quelle che trova scritte. Per questo i badge e le bolle usano mappe di classi complete.

**Come è stato verificato**

- **69 test** (Vitest, `ng test`; 46 al Passo 3, +18 al Passo 5 per selettore, ricarico dei pannelli, risposte superate e chip, +5 al Passo 9 per badge degli stati, numero `#N`, scheda di aggiornamento e avviso a video): conversione degli errori, i tre store (invio chat, ricaricamento della tabella dopo un'azione, evidenziazione e sua scadenza, 404, errori 502/0, upload sequenziale, validazione), i componenti (badge delle fonti, scheda azione, XSS, indicatore di caricamento, drag & drop, tasto Invio) e la dashboard intera.
- **Prova end-to-end in un browser vero** (Playwright + Chromium headless, **backend Laravel reale con Ollama `qwen2.5:7b`**): 21 controlli, tutti superati, nessun errore in console né richiesta fallita. Ha coperto: 3 colonne e intestazione; trascinamento e rilascio di un PDF con indicizzazione reale; indicatore di caricamento; badge "policy_rimborsi.pdf · p. 2" con frammento; "Aprimi un rimborso da 40€ per cena" → scheda azione **e tabella passata da 1 a 2 righe senza ricaricare la pagina**, riga evidenziata e poi normalizzata; PDF duplicato (409 reale); file non PDF; backend irraggiungibile (errore + "Riprova", e chat che resta utilizzabile). Il test ha trovato un difetto reale: **su schermo stretto il contenuto usciva dalla pagina** (colonna di griglia non limitata): corretto con `grid-cols-[minmax(0,1fr)]`, verificato a 390 px e 820 px senza overflow.
- **Passo 5, E2E dei due ritocchi** (browser vero, Laravel + Spring + Ollama reali, 24 controlli superati): primo avvio su Laravel e nessuna scelta salvata; 4 chip sopra la casella; clic su Spring → documenti e rimborsi ricaricati da `:8080` e nessuna chiamata a `:8000`; scelta salvata e mantenuta dopo il ricaricamento; ogni chip invia la sua frase (risposte finte per non occupare il modello); chip reale su Spring (nessun rimborso creato); poi cambio su Laravel e chip reale con **la stessa conversazione** (`conversationId` condiviso) e fonte citata; nessun overflow orizzontale a 390 e 820 px (e intestazione verificata fino a 360 px). Difetto trovato dai test unitari: `event.currentTarget` è nullo dopo la gestione dell'evento (focus con le frecce nel selettore). Difetto trovato dall'E2E: su 390 px "Spring Boot" andava a capo nel pulsante (ora `whitespace-nowrap` e sottotitolo nascosto sotto 640 px).
- Gli screenshot e lo script E2E sono stati tenuti **fuori dal repo** (cartella temporanea). Lo script non è tra i test del progetto.

**Limiti noti**

- La **descrizione** del rimborso può essere parafrasata dal modello (`qwen2.5:7b` ha scritto "Cena per lavoro" a fronte di "per cena"): è un limite del backend/modello, non del frontend (che mostra ciò che riceve).
- Nessun polling: la tabella non vede rimborsi creati da altri client finché non si preme "Aggiorna".
- Nessun filtro per documento nella chat (il contratto supporta `documentIds`, la UI non lo espone).
- Nessuna paginazione della tabella (si mostrano le 50 più recenti).
- Nessuna eliminazione di documenti/rimborsi né approvazione: il contratto non le prevede.
- Provata con Spring Boot al Passo 4 (stesso E2E, tutti i controlli superati): vedi 5.8.

### 5.8 `backend-springboot/` — guida al codice

**Come avviarlo**

```bash
docker compose --profile ollama --profile springboot up -d     # API su http://localhost:8080
cd backend-springboot && ./mvnw test                           # 67 test (serve: docker compose up -d postgres)
```

Per usarlo dal frontend: scegliere **Spring Boot** nel selettore Backend dell'intestazione. Stesso database e stesso contratto di Laravel: **i due backend vedono gli stessi documenti, chunk e rimborsi** e si possono alternare senza reindicizzare.

**pom.xml** — parent `spring-boot-starter-parent` 4.2.0, BOM `spring-ai-bom` 2.1.0; dipendenze: `spring-boot-starter-webmvc`, `spring-boot-starter-data-jpa` + `postgresql`, `spring-ai-starter-model-ollama` e `-openai`, `spring-ai-vector-store`, `spring-ai-pdf-document-reader`, `spring-boot-starter-actuator` (solo `/actuator/health`). **application.yml**: variabili d'ambiente con gli stessi nomi di Laravel; `spring.ai.model.chat/embedding: ollama`; blocco OpenAI **commentato** con le istruzioni per lo switch (D36).

**Struttura** (`src/main/java/it/portfolio/eaa/`)

```
├── EaaApplication.java
├── config/      EaaProperties (parametri RAG/upload/rimborsi/CORS), WebConfig (CORS), AiConfig (ChatClient)
├── common/      ApiProblem (errori di dominio), ApiExceptionHandler (@RestControllerAdvice), Iso (formato data)
├── document/    IndexedDocument + repository JPA · DocumentController · DocumentIngestionService
│                PdfTextExtractor · DocumentDto
├── rag/         DocumentChunkVectorStore (implements VectorStore) · EmbeddingService · RetrievedChunk
├── chat/        ChatController · ChatService · PromptBuilder · SourceResolver · ChatMessageRepository
│                ReimbursementTools (@Bean @Description) · ReimbursementToolHandler · ReimbursementToolArgs · DTO
└── reimbursement/  ReimbursementRequest + ReimbursementRepository (JPA) · ReimbursementService · Controller · Dto
```

**Ingestione — `POST /api/documents`** (`DocumentController` → `DocumentIngestionService`)

1. Controllo del file: assente → 400; oltre 20 MB → 413; non PDF → 415, guardando i **magic bytes** `%PDF-` e non l'estensione (come Laravel).
2. SHA-256; duplicato `READY`/`PROCESSING` → **409**, `FAILED` → si sostituisce e si riprova; la UNIQUE sull'hash protegge dalle richieste concorrenti (`DataIntegrityViolationException` → 409). Stesse regole di Laravel (D15).
3. `PdfTextExtractor`: **`PagePdfDocumentReader`** (un `Document` per pagina, metadata `page_number`), numero di pagine totale da PDFBox, testo ripulito (NUL, caratteri di controllo, spazi). PDF corrotto o scansione → 422.
4. **`TokenTextSplitter`** con chunk da 500 token e **50 di overlap** (supportato in Spring AI 2.1) e conteggio con token reali (JTokkit): i chunk ereditano il numero di pagina e non attraversano le pagine. Misurato dal vivo: 476–493 token per chunk.
5. `EmbeddingService`: embedding a lotti da 32 con l'`EmbeddingModel` di Spring AI; errori del provider → **502**; controlla la dimensione dei vettori (768) contro lo schema.
6. **Prima tutti gli embedding, poi una sola transazione** (`vectorStore.upsert(...)` + documento `READY`): o il documento è completo o non c'è nulla (D16). Qualunque errore → documento `FAILED` con il motivo.

**`DocumentChunkVectorStore`** (D33) — implementa `VectorStore`: `add` (calcola e salva), `upsert` (embedding già pronti: usato dall'ingestione), `delete`, `similaritySearch`. La ricerca fa `ORDER BY embedding <=> :q LIMIT :k` (distanza coseno, usa l'indice HNSW), score = `1 - distanza`, soglia applicata dopo la query, filtro `document_id IN (...)` tradotto dall'espressione di filtro di Spring AI, `SET LOCAL hnsw.iterative_scan` di pgvector 0.8 per i risultati filtrati.

**Chat — `POST /api/chat`** (`ChatService`) — stesso flusso di Laravel: conversazione (404 se ignota) → retrieval (top-5, soglia 0.62) → prompt (`SystemMessage` + storico + messaggio, D35) → `ChatClient` con il tool e il `ToolContext` → conferma/rifiuto/citazione inventata (D23) → `SourceResolver` (D13) → persistenza dei due messaggi in una transazione **solo a esito positivo**.

**Il tool `createReimbursementRequest`** (richiesta del Passo 4: `@Bean @Description` collegato al repository JPA)

- `ReimbursementTools`: un `@Bean` `BiFunction<ReimbursementToolArgs, ToolContext, String>` con `@Description` (il testo che il modello legge per decidere QUANDO usare il tool) e un secondo bean `ToolCallback` (`FunctionToolCallback`) che lo incapsula leggendo la descrizione dal `@Description` (D34).
- `ReimbursementToolArgs`: record con `@JsonPropertyDescription` → lo schema JSON dei parametri inviato al modello. `amount` è una `String` (il modello può mandare `40`, `"40"` o `"12,5 €"`), **senza esempi** nelle descrizioni (i modelli piccoli li copiano).
- `ReimbursementToolHandler`: le due barriere D21 (verbo di apertura esplicito + importo scritto dall'utente), poi `ReimbursementService` → `ReimbursementRepository.saveAndFlush`. Un importo non valido torna al **modello** come `ERRORE: …` e non crea nulla. Stato sempre `OPEN`, utente `demo-user`.
- `ReimbursementService.normalizeAmount`: `BigDecimal` a 2 decimali, accetta `12,5 €`; tetto 10.000; stessi messaggi di Laravel. Il `CHECK` del DB è l'ultima difesa.

**Errori** (`ApiProblem` + `ApiExceptionHandler`, `@RestControllerAdvice`): ogni errore è `application/problem+json` con gli stessi `type`/`title`/status di Laravel; copre 400 (anche JSON malformato), 404, 405, 413, 415, 422, 502 e un 500 generico senza dettagli. **DTO**: record con `String` per id/date/importi (`Iso.utc`, `"50.00"`), campi sempre presenti (null compresi).

**Come è stato verificato**

- **67 test** (`./mvnw test`): 12 documenti, 21+2 chat, 10 rimborsi, e unitari (fonti, barriere del tool, normalizzazione importi, prompt identico a Laravel, magic bytes, nomi file, formato vettore). Postgres/pgvector reali + finto Ollama HTTP (D40).
- **Prova dal vivo con Ollama reale** (`qwen2.5:7b`, container `--profile springboot`): upload (201) e duplicato (409) sull'indice creato da Laravel; limite cena con fonte e score identici a Laravel; "Aprimi un rimborso da 40€ per cena" → tool eseguito e riga salvata come `demo-user`; "Posso farmi rimborsare 200€ per una cena?" → nessuna azione; domanda sull'hotel (soglia 0.62); fuori tema → rifiuto; **nuovo PDF indicizzato da Spring** (PDFBox + splitter + embedding reali, 6 chunk, 768 dimensioni) e ritrovato con una domanda.
- **Parità con Laravel**: 15 richieste confrontate fianco a fianco (porta 8000 vs 8080) — dati reali e tutti gli errori di validazione/404/405 — **tutte identiche**; 6 casi di upload reali (nessun file, `.txt`, exe rinominato, PDF corrotto, 21 MB, 50 MB) → stessi status e stessi `type`.
- **Frontend Angular invariato** (solo `environment.ts` su `:8080`): lo stesso E2E del Passo 3 in un browser vero, **17 controlli superati** (3 colonne, drag & drop, indicatore, badge fonte con frammento, scheda azione, tabella aggiornata da sola, riga evidenziata) + 4 casi di errore (duplicato 409, non PDF, backend spento). Poi `environment.ts` è tornato su Laravel (`:8000`, come da Passo 3).

**Difetti trovati e corretti solo grazie alle prove**: l'app non partiva senza chiave OpenAI con i due starter (D36); il timeout di 180 s era troppo stretto su CPU con chunk lunghi (D37); la mia verifica E2E controllava prima del rendering (corse tra test e UI, non dell'app).

**Limiti noti**

- Su CPU (Ollama in Docker) con documenti lunghi le risposte sono lente: fino a ~200 s con 5 chunk da ~490 token. Con Ollama nativo su Mac (GPU) sarebbe molto più rapido.
- Come in Laravel, `qwen2.5:7b` è variabile: nello stesso E2E una volta non ha chiamato il tool (rifiuto), alla ripetizione sì. Con "Posso farmi rimborsare 200€?" risponde col rifiuto invece di dire il limite (non crea nulla).
- Un'unica citazione `[file, p. N]` abbina **tutti** i chunk di quella pagina (anche 5 badge uguali): regola D13 invariata, identica a Laravel.
- Tool: `spring.ai.tools.limits` e retry sono globali di Spring AI; il tool può essere chiamato fino a 3 volte per richiesta.
- Nessun `ChatMemory` di Spring AI: lo storico è letto da `chat_messages` (condiviso con Laravel).
- L'immagine Docker pesa ~800 MB (JRE 21 + jar); non è ottimizzata (niente layer di Spring Boot / jlink).
- Non provato con OpenAI (il blocco in `application.yml` è commentato).

---

## 6. Registro dei passi

### Passo 0 — Impostazione iniziale
- Prima proposta: solo Spring Boot 3 + Angular, con architettura e schema.
- Corretto su richiesta: Spring Boot non era più l'ultima versione (oggi 4.1 con Spring AI 2.0).

### Passo 1 — Monorepo, infrastruttura e contratto (completato)
- Ristrutturato in monorepo con tre cartelle applicative.
- Creati `docker-compose.yml`, `db/init/01-init.sql`, `docs/api/openapi.yaml` (sezione 5).
- Verifiche fatte: `docker compose config` valido. **Non** sono stati avviati i container né validato l'OpenAPI con un tool.
- *Verifica aggiunta durante il Passo 2*: il compose è stato avviato davvero (PostgreSQL 18.4, pgvector 0.8.2) e l'init SQL ha creato le 4 tabelle. L'OpenAPI resta non validato con un linter.

### Passo 2 — Backend Laravel (completato)
- Creato `backend-laravel/` con Laravel 13.17 (via Docker, perché il PHP locale è 8.2). Librerie: `prism-php/prism`, `smalot/pdfparser`, `pgvector/pgvector`.
- Implementati: migration idempotenti, ingestione PDF, retrieval vettoriale, chat RAG con fonti, tool calling per i rimborsi, le 4 rotte del contratto, errori problem+json, CORS. Dettaglio in [5.5](#55-backend-laravel--guida-al-codice).
- Aggiunti: `Dockerfile` e servizio `backend-laravel` (profile `laravel`) nel compose; 52 test (dopo il collaudo dal vivo).
- Pulizia dello scheletro Laravel: rimossi utenti, sessioni/cache/code su DB, rotte web, asset Vite/JS e i file `AGENTS.md`/`CLAUDE.md` generati dallo scheletro (chiedevano di installare Laravel Boost: non richiesto, fuori scopo).
- Difetti trovati e corretti **solo grazie alle prove**: `Prism` va importato dalla *facade* (`Prism\Prism\Facades\Prism`); il chunker saltava il testo senza spazi; `artisan serve` ignora l'ambiente Docker (D20); il controllo di `upload_max_filesize` non scattava su richieste vere; i warning PHP rovinavano la risposta JSON; i timestamp Laravel erano senza microsecondi (ora `timestamptz(6)`, come l'init SQL).
- Un test sul server vero ha lasciato un record `FAILED` nel DB di sviluppo: rimosso. Il DB di sviluppo è tornato vuoto.

### Passo 2b — Collaudo dal vivo del backend Laravel (completato)
- Avviato lo stack completo con Ollama; scaricati `nomic-embed-text`, `llama3.2`, `qwen2.5:7b`. Generato `tests/Fixtures/policy_rimborsi.pdf` e provato upload + chat con `curl` (dettaglio e tabella di confronto in [5.6](#56-collaudo-dal-vivo-con-modelli-reali-ollama-in-docker-solo-cpu)).
- Esito: **funziona** con `qwen2.5:7b` (citazione con fonte, tool chiamato, record salvato su DB). `llama3.2` si è rivelato inaffidabile per il tool calling → sostituito (D24).
- Difetti trovati dal vivo e corretti: rimborsi creati su semplici domande (D21), soglia di similarità sbagliata (D22), citazioni inventate e risposta di rifiuto dopo un'azione (D23), esempio copiato dalla descrizione del parametro.
- Test saliti da 46 a 52. Dati di prova lasciati nel DB di sviluppo: il documento `policy_rimborsi.pdf` e 1 rimborso da 40 € (creato dalla prova finale).

### Passo 3 — Frontend Angular (completato)
- Creato `frontend-angular/` con Angular 22.3 (standalone, Signals, zoneless) e Tailwind CSS 4.3: dashboard a 3 colonne (documenti con drag & drop, chat con badge delle fonti e indicatore di caricamento, tabella rimborsi che si aggiorna quando l'IA esegue l'azione). Nessun login: utente demo preimpostato (solo visualizzato, D28). Dettaglio in [5.7](#57-frontend-angular--guida-al-codice).
- `environment.ts` con `apiUrl: 'http://localhost:8000/api'` e la riga commentata per Spring Boot (`:8080`) (D27).
- 46 test unitari + collaudo end-to-end in un browser vero contro Laravel e Ollama reali (21 controlli superati). Difetto trovato e corretto: overflow orizzontale su schermo stretto.
- Decisioni D25–D31. Correzione: la versione di Angular nella tabella (22.1 → 22.3).
- Ho scaricato un Chromium di Playwright (~100 MB, in `~/Library/Caches/ms-playwright`) **fuori dal progetto** solo per la prova E2E; non è una dipendenza del repo.
- Dati di prova E2E rimossi dal DB di sviluppo (restano `policy_rimborsi.pdf` e 1 rimborso da 40 €).

### Passo 4 — Backend Spring Boot (completato)
- Creato `backend-springboot/` con Spring Boot 4.2.0, Spring AI 2.1.0, Java 21 (D32): `pom.xml`, `application.yml` (variabili d'ambiente, Ollama di default, OpenAI commentato), ingestione con `PagePdfDocumentReader` + `TokenTextSplitter` + `VectorStore`, tool con `@Bean @Description`, controller con DTO e `@RestControllerAdvice`. Dettaglio in [5.8](#58-backend-springboot--guida-al-codice).
- Aggiunti: `Dockerfile` multi-stage e servizio `backend-springboot` (profile `springboot`, porta 8080) nel compose; 67 test.
- Stesso database e stesso contratto di Laravel: dimostrato con 15 confronti fianco a fianco e 6 casi di upload reali, tutti identici; i due backend condividono l'indice vettoriale (D33).
- **Frontend Angular provato su `:8080`** con lo stesso E2E del Passo 3 (17 + 4 controlli superati). `environment.ts` è stato poi riportato su Laravel (default del Passo 3); la riga per Spring è commentata e il commento non dice più "Passo 4".
- Decisioni D32–D41. Correzioni al diario: versioni di Spring Boot (4.1 → 4.2.0) e Spring AI (2.0.x → 2.1.0).
- Nota sulla richiesta: "Spring Boot 3.x" è stato trattato come refuso (D32).
- Dati di prova nel DB di sviluppo: `policy_rimborsi.pdf` e 1 rimborso da 40 € (creato dalla prova E2E su Spring).

### Passo 4b — Ritocchi alla UI: selettore backend e chip suggeriti (completato)
- **Selettore Backend** nell'intestazione, a runtime, con `localStorage` e ricarico immediato di documenti e rimborsi (D27 aggiornata, D42). **Chip suggeriti** sopra la casella di testo (D43). Dettaglio in [5.7](#57-frontend-angular--guida-al-codice).
- `environment.ts` ora elenca i backend (`backends`, `defaultBackend`); rimosso il token `API_URL`, sostituito da `BACKENDS`/`DEFAULT_BACKEND_ID`.
- 64 test Vitest; E2E in browser vero con tutti e due i backend e Ollama reali (24 controlli).

### Passo 5 — README finale e `.gitignore` (completato)
- Scritto il [`README.md`](../README.md) principale **in inglese**, senza licenza open-source (solo "All rights reserved"): panoramica "multi-backend RAG & agentic action system", segnaposto `![Demo Enterprise AI Assistant](docs/demo.mp4)` in cima, due diagrammi Mermaid (architettura e sequenza di un turno di chat con azione), decisioni architetturali e di sicurezza (indice vettoriale condiviso, due barriere sul tool calling, Ollama locale/nativo o OpenAI), tabella comparativa PHP/Laravel vs Java/Spring Boot, Quickstart con comandi esatti, verifica del `.gitignore`, sezione "Quality & verification" e limiti onesti.
- **Verificato** (non solo scritto): i due diagrammi Mermaid sono stati renderizzati in un browser (sintassi valida); i comandi di controllo `.gitignore` sono stati eseguiti con un repository git temporaneo fuori dal progetto, incluso un **test negativo** (un `.env` e un `.log` forzati vengono segnalati); il comando `docker compose --profile ollama --profile laravel --profile springboot up -d --build` è stato eseguito davvero; il percorso container→host per Ollama nativo è stato provato con un servizio di prova.
- Aggiunto il `.gitignore` radice (D44) e `OLLAMA_BASE_URL` / `OPENAI_API_KEY` nel compose (D45).
- Numeri nel README misurati: 52 + 67 + 64 = 183 test; immagini 826 MB (Laravel) e 803 MB (Spring); ≈ 2,1 k righe di codice applicativo ciascuno; avvio di Spring ≈ 4 s.
- Il video di demo è stato poi registrato (Passo 6). Nota: GitHub non riproduce un `.mp4` del repository con la sintassi immagine di Markdown; per la riproduzione nella pagina conviene trascinare il video nell'editor del README su github.com (genera un link `user-attachments`) oppure usare un tag `<video>`.

### Passo 6 — Video di demo e primo commit (completato)
- Avviato lo stack con `docker compose --profile ollama --profile laravel --profile springboot up -d` e il frontend (`ng serve`); modello scaldato con una chiamata su ciascun backend (il primo caricamento su CPU dura oltre 80 s) e database riportato a uno stato pulito (cancellati i **miei** dati di prova: messaggi di chat e rimborsi).
- **Registrazione** con Playwright (`recordVideo` 1280×720) di una sessione scriptata, al primo tentativo: chip *Limite massimo per la cena?* → risposta con fonte; clic sulla badge → frammento citato; chip *Apri rimborso da 40€ per cena* → riga evidenziata, attesa di 3 s; selettore Laravel → Spring Boot → pallino verde, stessi documenti e stessa riga. Lo script ha un sistema di tentativi (il modello a volte non chiama il tool) e **verifica** durante la registrazione che la riga mostri 40,00 € e che i dati restino identici dopo il cambio di backend.
- Per un risultato "naturale": cursore e click disegnati da un overlay (il video di Playwright non mostra il puntatore), movimenti che scivolano verso l'elemento, didascalie discrete in basso a sinistra. Le **attese del modello su CPU (~265 s su 292 s di registrazione) sono accelerate ×8 in post-produzione**, con la didascalia *"Local model on CPU — waiting time sped up"* per dichiararlo: nessuna risposta è simulata.
- **Conversione** WebM → MP4 H.264 con ffmpeg: nel sistema non c'è, e quello di Playwright non ha l'encoder H.264, quindi si è usato un ffmpeg statico (`imageio-ffmpeg`) in un ambiente virtuale temporaneo **fuori dal progetto**. Risultato: `docs/demo.mp4`, 1280×720, 25 fps, 62 s, **1,39 MB** (limite 10 MB), senza audio. Fotogrammi controllati a vista.
- Osservazione sul contenuto: il modello ha scritto la descrizione "Cena per lavoro" a fronte di "per cena" (parafrasi, limite già noto); nel video si vede.
- **Git**: `git init -b main`, `git add .`, primo commit (212 file, identità presa dalla configurazione globale). Controlli prima del commit: nessun `.env`/dipendenza/log/temporaneo tracciato; solo `.env.example` (con `OPENAI_API_KEY` vuota); nessuna corrispondenza con pattern di chiavi (il controllo è stato provato con un caso positivo, dopo aver scoperto in precedenza che un controllo "grep vuoto" può essere un falso positivo); unico file > 1 MB il video. Le password di sviluppo (`eaa`) sono i default dichiarati del compose, non segreti.
- Container Docker e frontend fermati a fine lavoro.

### Passo 7 — Pubblicazione su GitHub e README in italiano (completato)
- Aggiunto il remoto `origin` e fatto il push di `main` (repository **privato**). L'utente ha poi sostituito sul remoto il video con un link cliccabile (`user-attachments`): allineato con `git pull --ff-only`.
- Creato [`README.it.md`](../README.it.md), traduzione **completa** del README inglese: stessa struttura (10 sezioni, 14 blocchi di codice, 31 righe di tabella), stesso link del video, stessi diagrammi Mermaid con etichette tradotte (sintassi verificata renderizzandoli), comandi equivalenti. In cima a ciascun file il link per cambiare lingua (`🇮🇹 Leggi in Italiano` / `🇬🇧 Read in English`).
- Controlli automatici eseguiti sulla parità dei due file: struttura, link relativi esistenti, ancore del sommario, comandi `bash`.
- Se si modifica un README va aggiornato anche l'altro.

### Passo 8 — Sinonimi naturali per il tool dei rimborsi (completato)
- **Segnalazione dell'utente**, provando la chat dal vivo: "aggiungi un rimborso 20 euro per taxi", "allora un rimborso per hotel da 120 euro", "aggiungi un rimborso 20 euro per hotel" non creavano il rimborso e davano "non ho trovato…". Causa: barriera sull'intento e prompt troppo stretti (D46).
- Implementato in **entrambi i backend**: classe `ReimbursementIntent` (Laravel: PHP/PCRE, Spring: Java), regola 5 del prompt e descrizione del tool ampliate (prompt identico nei due backend, verificato da `PromptParityTest`).
- Scoperto durante la prova dal vivo e corretto nello stesso passo (D47): il modello a volte **narra** la chiamata al tool con un risultato inventato e l'utente riceveva una **conferma falsa**; aggiunta la barriera "conferma falsa" e il **fallback deterministico** per gli ordini non ambigui.
- Test: Laravel 52 → **131**, Spring 67 → **82**, Angular 64 (totale **277**). La stessa tabella di frasi è in entrambi i backend. Casi di flusso: le tre frasi dell'utente, conferma falsa sostituita, fallback con tetto superato, fallback che NON scatta per domande/ipotesi/importi negativi.
- **Prova dal vivo** (Ollama reale, `qwen2.5:7b`) su entrambi i backend: le 3 frasi creano il rimborso e due domande ("Posso farmi rimborsare 200€ per una cena?", "Qual è il limite per l'hotel a notte?") non creano nulla: **5 su 5 su Laravel e 5 su 5 su Spring**. Per confronto, prima del fallback la frase senza verbo creava il rimborso solo 3 volte su 11.
- **Errore mio da segnalare**: in una misura precedente della latenza ho cancellato dal DB i rimborsi e i messaggi degli ultimi 15 minuti, ma l'utente stava provando l'app nello stesso momento e sono stati cancellati anche i suoi. In questo passo ho cancellato **solo** i record i cui ID avevo registrato io.
- README (inglese e italiano) aggiornati: descrizione della barriera, nuove difese, numeri dei test.

### Passo 9 — Secondo tool: aggiornare lo stato di un rimborso (completato)
- **Richiesta**: tool `updateReimbursementStatus` in entrambi i backend (ID o parola della descrizione; stati APPROVED, REJECTED, EXECUTED), stessi guardrail (solo verbi espliciti di aggiornamento, mai su domande), `GET /api/reimbursements` che restituisce lo stato aggiornato; frontend con badge colorati e tabella aggiornata, un chip che mostra la funzione; documentazione, verifica, commit e push. Osservazione dell'utente nello stesso messaggio: meglio usare i verbi, senza verbo ci vuole la barriera o una risposta concreta, magari come avviso a video (D48).
- **Backend, identico nei due stack** (D49, D50): enum con `EXECUTED` (+ etichette italiane e `isFinal`), colonna `number`, `ReimbursementService::updateStatus`, `ReimbursementIntent` esteso (aggiornamento, stato richiesto, ordini diretti, frase senza verbo, conferme false di aggiornamento), `ActionDto`/`ChatActions` con `number` e `status` e tipo `REIMBURSEMENT_UPDATED`, campo `warning` nella risposta della chat, prompt di sistema riscritto **identico** nei due backend (11 regole, verificato da `PromptParityTest`), conferme costruite dal codice con testo uguale.
- **Contratto** (`docs/api/openapi.yaml`): `Reimbursement.number`, `ReimbursementStatus` + `EXECUTED`, `Action` con `number`/`status` e tipo `REIMBURSEMENT_UPDATED`, `ChatResponse.warning` (sempre presente, nullo se non serve); esempi per aggiornamento e frase senza verbo. Non validato con un linter.
- **Frontend**: badge *In attesa* (ambra), *Approvato* (verde), *Rifiutato* (rosso), *Eseguito* (azzurro); colonna "N." con `#1, #2…`; scheda dell'azione "Rimborso #2 aggiornato: Eseguito"; avviso ambra `role="alert"` quando `warning` è valorizzato; la tabella si ricarica e la riga resta evidenziata dopo **qualsiasi** azione (il collegamento chat → tabella era già generico); chip 🏨 sostituito da ✅ *Segna come eseguito il rimborso #1*. Le etichette passano da *Approvata/Rifiutata* ad *Approvato/Rifiutato* (come da richiesta).
- **Test**: Laravel 131 → **214** (1.478 asserzioni), Spring 82 → **97**, Angular 64 → **69** (totale **380**). La stessa tabella di ~110 frasi (creazioni, domande, aggiornamenti, frasi senza verbo, conferme false) è nei test unitari di entrambi i backend e passa identica in PCRE e in Java. Casi di flusso nuovi: aggiornamento per numero e per parola, domanda sullo stato che non modifica nulla, modello che sceglie il rimborso o lo stato sbagliato, rete di sicurezza, riferimento ambiguo / inesistente / stato finale / stato già impostato, conferma falsa di aggiornamento, frase senza verbo (nessuna chiamata al modello, 2 messaggi salvati), `warning` nullo nelle risposte normali, `GET /api/reimbursements` con `status` e `number`, filtro `status=EXECUTED`.
- **Prova dal vivo** (Ollama reale, `qwen2.5:7b`, entrambi i backend, righe di prova `zzprova*`): frase senza verbo → avviso immediato e nessuna azione; "A che punto è il rimborso #N?" → nessuna modifica; "Segna come eseguito il rimborso #N" → EXECUTED; "Approva il rimborso della zzprovaB…" → APPROVED; `GET /api/reimbursements` mostra i nuovi stati su `:8000` e su `:8080`. Le righe di prova sono state eliminate (i rimborsi #1 e #2 dell'utente non sono stati toccati).
- **Errori miei da segnalare**: (1) il primo script di prova dal vivo catturava anche la riga `INSERT 0 1` di psql nel numero del rimborso, quindi 2 richieste su 4 erano malformate e la pulizia fallì; ho eliminato solo le 4 righe di prova rimaste (#3–#6, riconosciute dal prefisso `zzprova`) e rilanciato il collaudo corretto. (2) Nel test Spring `ReimbursementApiTest` avevo duplicato il controllo delle chiavi (errore di compilazione dei test, corretto subito).
- **Non verificato**: build visiva nel browser vero (Playwright) del nuovo badge/avviso — coperti dai test Vitest ma non da un E2E in questo passo; OpenAPI senza linter.
- README (inglese e italiano) aggiornati: due tool, quattro stati con badge, obbligo del verbo con avviso a video, migrazione dello schema, numero dei test.

### Passo 10 — Nome fittizio nel PDF di prova

- **Perché**: il PDF `backend-laravel/tests/Fixtures/policy_rimborsi.pdf` citava un nome d'azienda di fantasia molto comune, che potrebbe coincidere con un'azienda reale. Sostituito con **"Azienda Esempio S.r.l."**, chiaramente generico.
- **Come**: il PDF è scritto a mano (stream non compresso). Cambiando il testo sono cambiate le lunghezze, quindi ho ricalcolato `/Length` degli stream, gli offset della tabella `xref` e `startxref` con uno script temporaneo (non versionato). Il file passa da 1312 a 1323 byte.
- **Verifica**: riletto con `smalot/pdfparser` (lo stesso parser del backend): 2 pagine, testo identico a prima tranne il nome. Nessun altro file del repo conteneva quel nome (`git grep`). I test Laravel/Spring/Angular **non** sono stati rilanciati: nessuno legge questo file (usano PDF generati o dati finti), ma non l'ho verificato eseguendoli, perché i container sono fermi e il PHP del Mac è 8.2 (serve 8.4).
- **Nota**: il documento già caricato nel DB di sviluppo dell'utente (`policy_rimborsi.pdf`) contiene ancora il vecchio testo; non è stato toccato. Per aggiornarlo basta rieliminarlo e ricaricarlo dall'interfaccia.

### Passo 11 — Tabella rimborsi più larga e nuovo video di demo

- **Problema segnalato dall'utente**: nella tabella dei rimborsi la colonna "Stato" era tagliata ("In atte…", "Esegu…"). Causa: con la colonna "N." aggiunta al Passo 9 il contenuto superava la larghezza fissa del pannello (27 rem) e il resto finiva nello scroll orizzontale nascosto. Lo avevo introdotto io e non l'avevo notato: i test di componente non misurano le larghezze.
- **Correzione**: colonne della pagina da `21rem / 1fr / 27rem` a `20rem / 1fr / 30rem`; margini laterali ridotti nella tabella (`pl-3`/`pr-3`) e descrizione troncata a 6,5 rem. **Verifica nel browser vero** (Playwright) a 1024, 1280 e 1440 px, anche nel caso peggiore (riga clone con `#1234`, `10.000,00 €`, "Approvato"): nessun overflow (`scrollWidth` = `clientWidth` = 480). I 69 test Angular passano ancora.
- **Nuovo `docs/demo.mp4`** (1280×720, ~55 s, 0,75 MB, senza audio), registrato con lo stesso metodo del Passo 6 (Playwright `recordVideo` + cursore disegnato + didascalie; ogni attesa del modello (da 2 a 7 minuti reali) compressa a **3 secondi** con la didascalia che lo dichiara; una prima versione con attese solo accelerate ×12 durava 107 s ed era troppo lenta da guardare; ffmpeg statico di `imageio-ffmpeg` fuori dal progetto). Il copione è nuovo: domanda con fonte → creazione di un rimborso → **aggiornamento di stato con "Segna come eseguito il rimborso #N"** (badge "Eseguito") → **frase senza verbo con avviso ambra** → cambio backend Laravel → Spring Boot con gli stessi dati. Fotogrammi controllati a vista.
- **Differenza dal Passo 6**: lo script vecchio cancellava `chat_messages` e `reimbursement_requests` prima di registrare. Ora **non cancella nulla**: i rimborsi dell'utente restano e le righe create dalla registrazione sono state eliminate dopo, riconosciute dalla conversazione che le ha create.
- **Problemi incontrati**: (1) due tentativi falliti per timeout. Il passo "crea rimborso" fa **due** chiamate al modello (decisione del tool, poi risposta) e su CPU la seconda può superare `PRISM_REQUEST_TIMEOUT=180`: l'API risponde 502, **il rimborso però è già stato creato** e i messaggi non vengono salvati. Per la registrazione ho alzato il timeout a 600 s con un file compose temporaneo fuori dal progetto e poi ripristinato il container. È un limite reale del backend su CPU (rimborso creato ma errore all'utente), **non corretto** in questo passo. (2) La chat usata dall'utente durante la registrazione rallenta tutto: Ollama su CPU serve una richiesta alla volta.
- **Errori miei da segnalare**: avevo detto all'utente che non potevo registrare il video, mentre nel diario (Passo 6) risultava che l'avevo già fatto; e le prime registrazioni lasciarono righe di prova (#11–#14, #16, #17) che ho eliminato una per una dopo averne verificato la conversazione di origine. Il rimborso #15 ("Tax per il viaggio di lavoro") è dell'utente e non è stato toccato.
- **Nel video si vedono dati dell'utente**: il documento "Politica Rimborso Spese.pdf" e il rimborso #15. Non sono stati modificati.
- **Il README** contiene il video come link `user-attachments` di GitHub (Passo 6): sostituire `docs/demo.mp4` nel repository **non** aggiorna il player; va ricaricato il nuovo video nell'editor del README su github.com.

### Passo 12 — *(da definire: lo indica l'utente)*

---

## 7. Cose ancora da decidere o verificare

- Provider LLM di default: **Ollama** (locale, gratis) oppure **OpenAI**.
- Validare `openapi.yaml` con un linter OpenAPI e idealmente testare i backend contro di esso.
- Provare il backend Laravel con **OpenAI** (Ollama è già stato collaudato, vedi 5.6) e con PDF veri più numerosi/lunghi, per riverificare la soglia 0.62 (D22).
- Provare **OpenAI** su entrambi i backend (il blocco Spring è commentato, quello Laravel è via `.env`): i modelli cloud dovrebbero risolvere le incertezze di `qwen2.5:7b` sul tool.
- Un test automatico di **parità tra i due backend** (oggi è una prova manuale con script temporaneo): stesse richieste a `:8000` e `:8080` e confronto delle risposte.
- Valutare se tenere la prova E2E (Playwright) come test del repo: oggi è uno script temporaneo, non versionato.
- Pubblicare su GitHub: creare il repository **privato**, `git remote add origin ...` e `git push -u origin main`; poi, nel README su github.com, trascinare `docs/demo.mp4` per ottenere il player (vedi Passo 5).
