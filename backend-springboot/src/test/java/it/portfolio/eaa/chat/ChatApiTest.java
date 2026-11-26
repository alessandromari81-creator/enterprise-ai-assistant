package it.portfolio.eaa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.portfolio.eaa.support.FakeOllama;
import it.portfolio.eaa.support.IntegrationTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/** Contratto di POST /api/chat: RAG con fonti, tool calling, errori. Stessi casi del backend Laravel. */
class ChatApiTest extends IntegrationTest {

    private static final String TOOL = "createReimbursementRequest";
    private static final String STATUS_TOOL = "updateReimbursementStatus";

    /** Indicizza una policy (con embedding finti) e poi prepara le risposte che darà la chat. */
    private void seedPolicy(String... chatResponses) throws Exception {
        uploadPdf("policy.pdf", List.of(
                        "Introduzione alla politica aziendale sulle trasferte.",
                        "Il taxi e rimborsabile fino a 40 euro a corsa. Serve la ricevuta."))
                .andExpect(status().isCreated());
        ollama.queueChat(chatResponses);
    }

    private ResultActions chat(Map<String, Object> payload) throws Exception {
        return mvc.perform(post("/api/chat").contentType("application/json").content(JSON.writeValueAsString(payload)));
    }

    private ResultActions chat(String message) throws Exception {
        return chat(Map.of("message", message));
    }

    /** I messaggi con ruolo "tool" inviati al modello: contengono l'esito del tool eseguito dal codice. */
    private List<String> toolMessagesSentToModel() {
        List<String> result = new ArrayList<>();
        for (JsonNode request : ollama.chatRequests()) {
            request.get("messages").forEach(m -> {
                if ("tool".equals(m.path("role").asString())) {
                    result.add(m.path("content").asString());
                }
            });
        }
        return result;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    // --- RAG -------------------------------------------------------------------------------------------------

    @Test
    void rispostaRagConCitazioneEFonti() throws Exception {
        seedPolicy(FakeOllama.chatText("Il limite per il taxi è di 40 euro a corsa [policy.pdf, p. 2]."));

        JsonNode response = body(chat("Qual è il limite per il taxi?").andExpect(status().isOk()));

        assertThat(response.propertyNames()).containsExactlyInAnyOrder(
                "conversationId", "messageId", "answer", "sources", "actions", "warning", "createdAt");
        assertThat(response.get("warning").isNull()).isTrue();
        assertThat(response.get("actions")).isEmpty();
        assertThat(response.get("sources")).hasSize(1);
        JsonNode source = response.get("sources").get(0);
        assertThat(source.propertyNames()).containsExactlyInAnyOrder("chunkId", "documentId", "filename", "page", "score", "snippet");
        assertThat(source.get("filename").asString()).isEqualTo("policy.pdf");
        assertThat(source.get("page").asInt()).isEqualTo(2);
        assertThat(source.get("score").asDouble()).isBetween(0.2, 1.0);
        assertThat(source.get("snippet").asString()).contains("taxi");
        assertThat(response.get("createdAt").asString()).matches("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ$");

        // Il prompt inviato al modello contiene gli estratti con file e pagina e le regole anti-allucinazione.
        JsonNode firstRequest = ollama.chatRequests().get(0);
        JsonNode system = firstRequest.get("messages").get(0);
        assertThat(system.get("role").asString()).isEqualTo("system");
        assertThat(system.get("content").asString())
                .contains("file: policy.pdf, pagina: 2")
                .contains("ESCLUSIVAMENTE")
                .contains("Il taxi e rimborsabile");
        // ...e il tool è dichiarato al modello con la sua descrizione (@Description) e i suoi parametri.
        List<String> toolNames = new ArrayList<>();
        firstRequest.get("tools").forEach(t -> toolNames.add(t.get("function").get("name").asString()));
        assertThat(toolNames).containsExactlyInAnyOrder(TOOL, STATUS_TOOL);
        assertThat(firstRequest.get("tools").get(0).get("function").get("description").asString()).contains("rimborso");
    }

    @Test
    void ilTestoDelModelloConParentesiGraffeNonRompeIlTemplate() throws Exception {
        // Regressione: un messaggio con { } non deve essere interpretato come variabile di template.
        seedPolicy(FakeOllama.chatText("Risposta con {parentesi} [policy.pdf, p. 2]."));

        JsonNode response = body(chat("Il taxi costa {importo} euro?").andExpect(status().isOk()));

        assertThat(response.get("answer").asString()).contains("{parentesi}");
    }

    @Test
    void iSinonimiNaturaliDiCreazioneApronoIlRimborso() throws Exception {
        // Frasi reali segnalate dall'utente: prima il modello rispondeva "non ho trovato nei documenti".
        seedPolicy();
        Object[][] cases = {
                {"aggiungi un rimborso 20 euro per taxi", 20, "20.00"},
                {"aggiungi un rimborso 20 euro per hotel", 20, "20.00"},
        };

        for (Object[] c : cases) {
            ollama.queueChat(FakeOllama.chatToolCall(TOOL, Map.of("amount", c[1])), FakeOllama.chatText("Fatto."));

            chat((String) c[0]).andExpect(status().isOk()).andExpect(jsonPath("$.actions[0].amount").value((String) c[2]));
        }

        assertThat(count("reimbursement_requests")).isEqualTo(2);
    }

    @Test
    void ilPromptAmmetteTuttiIVerbiNaturaliELaFormaBreve() throws Exception {
        seedPolicy(FakeOllama.chatText("ok"));

        chat("aggiungi un rimborso 20 euro per taxi").andExpect(status().isOk());

        assertThat(ollama.chatRequests().get(0).get("messages").get(0).get("content").asString())
                .contains("aggiungi").contains("updateReimbursementStatus").contains("con un verbo esplicito").contains("non rispondere MAI con questa frase");
    }

    @Test
    void unaConfermaFalsaDelModelloVieneSostituitaDaUnMessaggioOnesto() throws Exception {
        // Caso reale: il modello NON chiama il tool ma scrive a parole la chiamata e un risultato inventato.
        seedPolicy(FakeOllama.chatText(
                "Creo una richiesta di rimborso per hotel da 120 euro. Lo strumento ha risposto: {\"reimbursement_request_id\": \"RR123456\", \"amount\": 120.0} "
                        + "Il tuo rimborso è stato registrato con successo."));

        chat("Apri un rimborso per la cena di ieri")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(ChatService.NOT_CREATED))
                .andExpect(jsonPath("$.actions").isEmpty())
                .andExpect(jsonPath("$.sources").isEmpty());

        assertThat(count("reimbursement_requests")).isZero();
    }

    @Test
    void seIlModelloChiedeLImportoMancanteLaRispostaRestaIntatta() throws Exception {
        seedPolicy(FakeOllama.chatText("Quale importo vuoi rimborsare per la cena di ieri?"));

        chat("Apri un rimborso per la cena di ieri")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Quale importo vuoi rimborsare per la cena di ieri?"));
    }

    @Test
    void seIlModelloNonChiamaIlToolUnOrdineNonAmbiguoVieneEseguitoDalBackend() throws Exception {
        // "aggiungi un rimborso da 120 euro per hotel": il modello NARRA la chiamata invece di farla. Il backend crea
        // il rimborso con lo stesso servizio e le stesse barriere (importo scritto dall'utente, tetto, stato OPEN).
        seedPolicy(FakeOllama.chatText("Creo una richiesta di rimborso per hotel da 120 euro. Lo strumento ha risposto: {\"id\": \"RR123456\"}"));

        JsonNode response = body(chat("aggiungi un rimborso da 120 euro per hotel").andExpect(status().isOk()));

        JsonNode action = response.get("actions").get(0);
        assertThat(action.get("amount").asString()).isEqualTo("120.00");
        assertThat(action.get("description").asString()).isEqualTo("hotel");
        assertThat(response.get("answer").asString()).contains("120,00 EUR").doesNotContain("RR123456");
        assertThat(response.get("sources")).isEmpty();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, source_conversation_id::text AS conv FROM reimbursement_requests WHERE id = ?::uuid", action.get("reimbursementId").asString());
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat(row.get("conv")).isEqualTo(response.get("conversationId").asString());
    }

    @Test
    void ilFallbackRispettaIlTettoESpiegaPercheNonHaCreatoNulla() throws Exception {
        seedPolicy(FakeOllama.chatText("Fatto."));

        JsonNode response = body(chat("Apri un rimborso da 20000 euro").andExpect(status().isOk()));

        assertThat(response.get("actions")).isEmpty();
        assertThat(response.get("answer").asString()).contains("supera il massimo consentito");
        assertThat(count("reimbursement_requests")).isZero();
    }

    @Test
    void ilFallbackNonScattaPerDomandeIpotesiOImportiNegativi() throws Exception {
        seedPolicy();

        for (String message : List.of("Posso farmi rimborsare 200€ per una cena?", "Apri un rimborso per meno 5 euro", "Qual è il limite per l'hotel a notte?")) {
            ollama.queueChat(FakeOllama.chatText("Ok."));

            chat(message).andExpect(status().isOk()).andExpect(jsonPath("$.actions").isEmpty());
        }

        assertThat(count("reimbursement_requests")).isZero();
    }

    @Test
    void conversazioneContinuaELoStoricoArrivaAlModello() throws Exception {
        seedPolicy(FakeOllama.chatText("Prima risposta [policy.pdf, p. 2]."), FakeOllama.chatText("Seconda risposta."));

        String conversationId = body(chat("Qual è il limite per il taxi?").andExpect(status().isOk())).get("conversationId").asString();
        JsonNode second = body(chat(Map.of("message", "E per le cene?", "conversationId", conversationId)).andExpect(status().isOk()));

        assertThat(second.get("conversationId").asString()).isEqualTo(conversationId);
        assertThat(count("chat_messages")).isEqualTo(4);

        List<String> contents = new ArrayList<>();
        ollama.chatRequests().get(1).get("messages").forEach(m -> contents.add(m.get("content").asString()));
        assertThat(contents).contains("Qual è il limite per il taxi?", "Prima risposta [policy.pdf, p. 2].", "E per le cene?");
    }

    @Test
    void conversazioneSconosciutaRisponde404() throws Exception {
        ollama.queueChat(FakeOllama.chatText("ok"));

        chat(Map.of("message", "ciao", "conversationId", "5d1c1d2a-8a4b-4e3f-b0a1-77a1d2c0f001"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:eaa:error:not-found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void validazioneDellaRichiestaRisponde400() throws Exception {
        Map<String, Object> bigMessage = Map.of("message", "a".repeat(4001));
        Object[][] cases = {
                {Map.of(), "message"},
                {Map.of("message", ""), "message"},
                {Map.of("message", "   "), "message"},
                {bigMessage, "message"},
                {Map.of("message", "ciao", "conversationId", "non-un-uuid"), "conversationId"},
                {Map.of("message", "ciao", "documentIds", List.of("non-un-uuid")), "documentIds.0"},
        };

        for (Object[] c : cases) {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) c[0];
            chat(payload)
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.type").value("urn:eaa:error:validation"))
                    .andExpect(jsonPath("$.errors[0].field").value((String) c[1]));
        }
    }

    @Test
    void corpoNonJsonRisponde400() throws Exception {
        mvc.perform(post("/api/chat").contentType("application/json").content("{non json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:eaa:error:validation"));
    }

    @Test
    void seIlModelloEIrraggiungibileRisponde502ENonSalvaNulla() throws Exception {
        seedPolicy();
        ollama.chatDown();

        chat("ciao")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("urn:eaa:error:llm-unavailable"));

        assertThat(count("chat_messages")).isZero();
    }

    @Test
    void seIlProviderEmbeddingEGiuLaChatRisponde502() throws Exception {
        ollama.embeddingDown();

        chat("ciao").andExpect(status().isBadGateway()).andExpect(jsonPath("$.type").value("urn:eaa:error:llm-unavailable"));
    }

    @Test
    void documentIdsRestringeIlRetrieval() throws Exception {
        seedPolicy(FakeOllama.chatText("Non ho trovato questa informazione nei documenti caricati."));

        // Un id valido ma di un documento inesistente: nessun chunk eleggibile, contesto vuoto.
        chat(Map.of("message", "Limite taxi?", "documentIds", List.of("0b8f3c1e-6a53-4f0e-9d0c-2f6f1a7c9e11")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources").isEmpty());
        assertThat(ollama.chatRequests().get(0).get("messages").get(0).get("content").asString())
                .contains("(nessun estratto pertinente trovato)");
    }

    @Test
    void documentIdsDelDocumentoGiustoLoTrova() throws Exception {
        seedPolicy(FakeOllama.chatText("Il limite è 40 euro [policy.pdf, p. 2]."));
        String documentId = jdbc.queryForObject("SELECT id::text FROM documents", String.class);

        chat(Map.of("message", "Qual è il limite per il taxi?", "documentIds", List.of(documentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources[0].documentId").value(documentId));
    }

    // --- Aggiornamento dello stato (tool updateReimbursementStatus) ----------------------------------------

    /** Crea un rimborso di prova direttamente nel DB e ne restituisce il numero assegnato dal database. */
    private long makeRefund(String description, String status) {
        return jdbc.queryForObject(
                "INSERT INTO reimbursement_requests (amount, currency, description, status, requested_by) "
                        + "VALUES (40.00, 'EUR', ?, ?, 'demo-user') RETURNING number", Long.class, description, status);
    }

    private String statusOf(long number) {
        return jdbc.queryForObject("SELECT status FROM reimbursement_requests WHERE number = ?", String.class, number);
    }

    @Test
    void segnaComeEseguitoUnRimborsoIndicatoDalNumero() throws Exception {
        long n = makeRefund("cena", "OPEN");
        seedPolicy(FakeOllama.chatToolCall(STATUS_TOOL, Map.of("status", "EXECUTED", "number", n)), FakeOllama.chatText("Fatto."));

        JsonNode response = body(chat("Segna come eseguito il rimborso #" + n).andExpect(status().isOk()));

        JsonNode action = response.get("actions").get(0);
        assertThat(action.get("type").asString()).isEqualTo("REIMBURSEMENT_UPDATED");
        assertThat(action.get("number").asLong()).isEqualTo(n);
        assertThat(action.get("status").asString()).isEqualTo("EXECUTED");
        assertThat(statusOf(n)).isEqualTo("EXECUTED");
        assertThat(response.get("sources")).isEmpty();
        assertThat(response.get("warning").isNull()).isTrue();

        // GET /api/reimbursements restituisce lo stato aggiornato.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/reimbursements"))
                .andExpect(jsonPath("$.items[0].status").value("EXECUTED"))
                .andExpect(jsonPath("$.items[0].number").value(n));
    }

    @Test
    void approvaUnRimborsoIndicatoDaUnaParolaDellaDescrizione() throws Exception {
        long hotel = makeRefund("hotel", "OPEN");
        long cena = makeRefund("cena", "OPEN");
        seedPolicy(FakeOllama.chatToolCall(STATUS_TOOL, Map.of("status", "APPROVED", "keyword", "cena")), FakeOllama.chatText("Fatto."));

        chat("Approva il rimborso della cena")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[0].type").value("REIMBURSEMENT_UPDATED"))
                .andExpect(jsonPath("$.actions[0].status").value("APPROVED"));

        assertThat(statusOf(cena)).isEqualTo("APPROVED");
        assertThat(statusOf(hotel)).isEqualTo("OPEN");
    }

    @Test
    void unaDomandaSulloStatoNonModificaNulla() throws Exception {
        long n = makeRefund("cena", "OPEN");
        seedPolicy(FakeOllama.chatToolCall(STATUS_TOOL, Map.of("status", "APPROVED", "number", n)), FakeOllama.chatText("Il rimborso è in attesa."));

        chat("A che punto è il rimborso #" + n + "?").andExpect(status().isOk()).andExpect(jsonPath("$.actions").isEmpty());

        assertThat(statusOf(n)).isEqualTo("OPEN");
        assertThat(toolMessagesSentToModel()).anyMatch(m -> m.contains("è una domanda"));
    }

    @Test
    void ilModelloNonPuoScegliereUnAltroRimborsoOUnAltroStato() throws Exception {
        long hotel = makeRefund("hotel", "OPEN");
        long cena = makeRefund("cena", "OPEN");
        // L'utente dice "approva la cena", il modello prova a RIFIUTARE l'hotel (numero mai scritto dall'utente).
        seedPolicy(FakeOllama.chatToolCall(STATUS_TOOL, Map.of("status", "REJECTED", "number", hotel)), FakeOllama.chatText("Ok."));

        chat("Approva il rimborso della cena").andExpect(status().isOk());

        assertThat(statusOf(hotel)).as("L'hotel non va toccato").isEqualTo("OPEN");
        assertThat(statusOf(cena)).as("La rete di sicurezza applica lo stato scritto dall'utente").isEqualTo("APPROVED");
        assertThat(toolMessagesSentToModel()).anyMatch(m -> m.contains("non compare nel messaggio"));
    }

    @Test
    void seIlModelloNonChiamaIlToolDiAggiornamentoLoEseguePoiIlBackend() throws Exception {
        long n = makeRefund("cena", "OPEN");
        seedPolicy(FakeOllama.chatText("Ho approvato il rimborso #" + n + "."));

        JsonNode response = body(chat("Approva il rimborso #" + n).andExpect(status().isOk()));

        assertThat(response.get("actions").get(0).get("type").asString()).isEqualTo("REIMBURSEMENT_UPDATED");
        assertThat(statusOf(n)).isEqualTo("APPROVED");
        assertThat(response.get("answer").asString()).contains("Approvato");
    }

    @Test
    void riferimentiAmbiguiInesistentiOStatiFinaliNonModificanoNulloESpieganoPerche() throws Exception {
        makeRefund("cena di lavoro", "OPEN");
        makeRefund("cena con il cliente", "OPEN");
        long executed = makeRefund("taxi", "EXECUTED");
        seedPolicy();

        String[][] cases = {
                {"Approva il rimborso della cena", "Più rimborsi corrispondono"},
                {"Approva il rimborso #99", "Non esiste nessun rimborso con numero 99"},
                {"Rifiuta il rimborso #" + executed, "non può più essere modificato"},
                {"Segna come eseguito il rimborso #" + executed, "è già «Eseguito»"},
        };
        for (String[] c : cases) {
            ollama.queueChat(FakeOllama.chatText("Ok."));

            JsonNode response = body(chat(c[0]).andExpect(status().isOk()));

            assertThat(response.get("actions")).as(c[0]).isEmpty();
            assertThat(response.get("answer").asString()).as(c[0]).contains(c[1]);
        }
        assertThat(statusOf(executed)).isEqualTo("EXECUTED");
    }

    @Test
    void unaConfermaFalsaDiAggiornamentoVieneSostituitaDaUnMessaggioOnesto() throws Exception {
        long n = makeRefund("cena", "OPEN");
        // "Aggiorna il rimborso #n": manca lo stato, non si può eseguire; il modello però dichiara di averlo fatto.
        seedPolicy(FakeOllama.chatText("Ho aggiornato il rimborso #" + n + " come richiesto."));

        chat("Aggiorna il rimborso #" + n)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(ChatService.NOT_UPDATED))
                .andExpect(jsonPath("$.actions").isEmpty());
        assertThat(statusOf(n)).isEqualTo("OPEN");
    }

    @Test
    void unaFraseSenzaVerboNonEsegueNullaNonInterpellaIlModelloEMostraUnAvviso() throws Exception {
        // Nessuna risposta accodata a FakeOllama: se il modello venisse interpellato il test fallirebbe.
        JsonNode response = body(chat("allora un rimborso per hotel da 120 euro").andExpect(status().isOk()));

        assertThat(response.get("warning").asString()).isEqualTo(ReimbursementIntent.HINT_WARNING);
        assertThat(response.get("answer").asString())
                .contains("manca un verbo esplicito").contains("«Aggiungi un rimborso da 120 € per hotel»");
        assertThat(response.get("actions")).isEmpty();
        assertThat(response.get("sources")).isEmpty();
        assertThat(count("reimbursement_requests")).isZero();
        assertThat(ollama.chatRequests()).isEmpty();
        assertThat(count("chat_messages")).as("i due messaggi sono salvati").isEqualTo(2);
    }

    @Test
    void senzaVerboAncheLAggiornamentoMostraUnAvviso() throws Exception {
        chat("rimborso #2 eseguito")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warning").value(ReimbursementIntent.HINT_WARNING))
                .andExpect(jsonPath("$.actions").isEmpty());
    }

    // --- Tool calling: rimborsi ------------------------------------------------------------------------------

    @Test
    void ilModelloApreUnaRichiestaDiRimborsoTramiteTool() throws Exception {
        seedPolicy(
                FakeOllama.chatToolCall(TOOL, Map.of("amount", 50, "description", "Cena col cliente")),
                FakeOllama.chatText("Ho aperto la richiesta di rimborso di 50,00 €."));

        JsonNode response = body(chat("Apri una richiesta di rimborso per 50€ per la cena col cliente").andExpect(status().isOk()));

        JsonNode action = response.get("actions").get(0);
        assertThat(action.propertyNames()).containsExactlyInAnyOrder("type", "reimbursementId", "number", "amount", "currency", "description", "status");
        assertThat(action.get("status").asString()).isEqualTo("OPEN");
        assertThat(action.get("type").asString()).isEqualTo("REIMBURSEMENT_CREATED");
        assertThat(action.get("amount").asString()).isEqualTo("50.00");
        assertThat(action.get("currency").asString()).isEqualTo("EUR");
        assertThat(action.get("description").asString()).isEqualTo("Cena col cliente");
        assertThat(response.get("sources")).as("Un'azione non ha fonti").isEmpty();

        // Il record è davvero nel database, collegato alla conversazione, con l'utente demo.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT amount::text AS amount, status, requested_by, source_conversation_id::text AS conv FROM reimbursement_requests WHERE id = ?::uuid",
                action.get("reimbursementId").asString());
        assertThat(row.get("amount")).isEqualTo("50.00");
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat(row.get("requested_by")).isEqualTo("demo-user");
        assertThat(row.get("conv")).isEqualTo(response.get("conversationId").asString());

        // Il modello ha ricevuto l'esito del tool nella seconda chiamata.
        assertThat(toolMessagesSentToModel()).anyMatch(m -> m.contains("OK: richiesta di rimborso #"));
    }

    @Test
    void ilModelloPuoMandareLImportoComeStringa() throws Exception {
        seedPolicy(FakeOllama.chatToolCall(TOOL, Map.of("amount", "40")), FakeOllama.chatText("Fatto."));

        chat("Aprimi un rimborso da 40€ per cena").andExpect(status().isOk()).andExpect(jsonPath("$.actions[0].amount").value("40.00"));
    }

    @Test
    void unImportoNonValidoSceltoDalModelloNonCreaNulla() throws Exception {
        seedPolicy(FakeOllama.chatToolCall(TOOL, Map.of("amount", -5)), FakeOllama.chatText("L'importo non è valido."));

        chat("Apri un rimborso per meno 5 euro").andExpect(status().isOk()).andExpect(jsonPath("$.actions").isEmpty());

        assertThat(count("reimbursement_requests")).isZero();
        assertThat(toolMessagesSentToModel()).anyMatch(m -> m.contains("ERRORE"));
    }

    @Test
    void unImportoOltreIlTettoNonCreaNulla() throws Exception {
        seedPolicy(FakeOllama.chatToolCall(TOOL, Map.of("amount", "10000.01")), FakeOllama.chatText("Supera il massimo consentito."));

        chat("Apri un rimborso da 10000,01 euro").andExpect(status().isOk()).andExpect(jsonPath("$.actions").isEmpty());

        assertThat(count("reimbursement_requests")).isZero();
        assertThat(toolMessagesSentToModel()).anyMatch(m -> m.contains("supera il massimo consentito di 10000.00 EUR"));
    }

    @Test
    void importoConVirgolaDecimaleEIlSimboloEuroVieneNormalizzato() throws Exception {
        seedPolicy(FakeOllama.chatToolCall(TOOL, Map.of("amount", "12,5 €")), FakeOllama.chatText("Fatto."));

        chat("Apri un rimborso da 12,5 euro").andExpect(status().isOk()).andExpect(jsonPath("$.actions[0].amount").value("12.50"));
    }

    @Test
    void unImportoInventatoDalModelloNonCreaNulla() throws Exception {
        // Caso reale visto con un modello piccolo: la cifra veniva presa dai documenti, mai scritta dall'utente.
        seedPolicy(
                FakeOllama.chatToolCall(TOOL, Map.of("amount", 50, "description", "cena")),
                FakeOllama.chatText("Il limite per una cena è di 50 euro [policy.pdf, p. 2]."));

        chat("Apri un rimborso per la cena di ieri").andExpect(status().isOk()).andExpect(jsonPath("$.actions").isEmpty());

        assertThat(count("reimbursement_requests")).as("Nessun rimborso senza importo scritto dall'utente").isZero();
        assertThat(toolMessagesSentToModel()).anyMatch(m -> m.contains("non compare nei messaggi"));
    }

    @Test
    void unaDomandaNonEUnOrdineIlToolNonVieneEseguito() throws Exception {
        // Caso reale con un modello piccolo: "Posso farmi rimborsare 200€ per una cena?" aprì un rimborso da 200 €.
        // L'importo c'è (l'ha scritto l'utente) ma il messaggio non CHIEDE di aprire nulla: è una domanda.
        seedPolicy(
                FakeOllama.chatToolCall(TOOL, Map.of("amount", 200, "description", "cena")),
                FakeOllama.chatText("Il limite per una cena è di 40 euro [policy.pdf, p. 2]."));

        chat("Posso farmi rimborsare 200€ per una cena?").andExpect(status().isOk()).andExpect(jsonPath("$.actions").isEmpty());

        assertThat(count("reimbursement_requests")).isZero();
        assertThat(toolMessagesSentToModel()).anyMatch(m -> m.contains("è una domanda"));
    }

    @Test
    void leRichiesteCortesiConVerboDiAperturaSonoAccettate() throws Exception {
        seedPolicy();

        for (String message : List.of("Puoi aprirmi un rimborso da 40€?", "Vorrei creare una richiesta di rimborso da 40 euro", "Registra un rimborso da 40€")) {
            ollama.queueChat(FakeOllama.chatToolCall(TOOL, Map.of("amount", 40)), FakeOllama.chatText("Fatto."));

            chat(message).andExpect(status().isOk()).andExpect(jsonPath("$.actions[0].amount").value("40.00"));
        }
    }

    @Test
    void dopoUnAzioneRiuscitaIlRifiutoDelModelloVieneSostituitoDallaConferma() throws Exception {
        // Caso reale: tool eseguito correttamente, ma testo finale = "non ho trovato nei documenti".
        seedPolicy(
                FakeOllama.chatToolCall(TOOL, Map.of("amount", 40, "description", "cena")),
                FakeOllama.chatText("Non ho trovato questa informazione nei documenti caricati."));

        JsonNode response = body(chat("Aprimi un rimborso da 40€ per cena").andExpect(status().isOk()));

        String answer = response.get("answer").asString();
        assertThat(answer).doesNotContain("Non ho trovato").contains("40,00 EUR").contains("(cena)")
                .contains(response.get("actions").get(0).get("reimbursementId").asString());
        assertThat(response.get("sources")).isEmpty();
    }

    @Test
    void lImportoScrittoInUnMessaggioPrecedenteDellUtenteEValido() throws Exception {
        seedPolicy(
                FakeOllama.chatText("Quale importo vuoi rimborsare?"),
                FakeOllama.chatToolCall(TOOL, Map.of("amount", 40)),
                FakeOllama.chatText("Fatto."));

        String conversationId = body(chat("Ho speso 40 euro per una cena, mi serve una mano").andExpect(status().isOk()))
                .get("conversationId").asString();

        chat(Map.of("message", "Sì, aprilo pure", "conversationId", conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[0].amount").value("40.00"));
    }

    @Test
    void ilModelloCheInventaArgomentiExtraNonVaInErrore() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("amount", 40);
        args.put("description", "cena");
        args.put("urgenza", "alta");
        seedPolicy(FakeOllama.chatToolCall(TOOL, args), FakeOllama.chatText("Fatto."));

        chat("Aprimi un rimborso da 40€ per cena").andExpect(status().isOk()).andExpect(jsonPath("$.actions[0].amount").value("40.00"));
    }
}
