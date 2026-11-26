package it.portfolio.eaa.reimbursement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.portfolio.eaa.support.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.JsonNode;

/** Contratto di GET /api/reimbursements e comportamento generale degli errori e del CORS su /api/*. */
class ReimbursementApiTest extends IntegrationTest {

    private static final String CONV = "5d1c1d2a-8a4b-4e3f-b0a1-77a1d2c0f001";

    private void insert(String amount, String status, Instant createdAt) {
        jdbc.update("""
                INSERT INTO reimbursement_requests (amount, currency, description, status, requested_by, source_conversation_id, created_at)
                VALUES (?::numeric, 'EUR', ?, ?, 'demo-user', ?::uuid, ?)
                """, amount, "Spesa " + amount, status, CONV, java.sql.Timestamp.from(createdAt));
    }

    @Test
    void elencoVuoto() throws Exception {
        JsonNode response = body(mvc.perform(get("/api/reimbursements")).andExpect(status().isOk()));

        assertThat(response).isEqualTo(JSON.readTree("{\"items\":[],\"page\":0,\"size\":20,\"totalItems\":0,\"totalPages\":0}"));
    }

    @Test
    void formaEsattaDiUnElemento() throws Exception {
        insert("50", "OPEN", Instant.now());

        JsonNode item = body(mvc.perform(get("/api/reimbursements")).andExpect(status().isOk())).get("items").get(0);

        Set<String> keys = new TreeSet<>();
        item.propertyNames().forEach(keys::add);
        assertThat(keys).containsExactly("amount", "conversationId", "createdAt", "currency", "description", "id", "requestedBy", "status");
        assertThat(item.get("amount").isString()).as("Importo stringa, mai numero").isTrue();
        assertThat(item.get("amount").asString()).isEqualTo("50.00");
        assertThat(item.get("status").asString()).isEqualTo("OPEN");
        assertThat(item.get("requestedBy").asString()).isEqualTo("demo-user");
        assertThat(item.get("conversationId").asString()).isEqualTo(CONV);
        assertThat(item.get("createdAt").asString()).matches("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ$");
    }

    @Test
    void paginazioneBaseZeroEOrdineDecrescente() throws Exception {
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        for (int i = 1; i <= 5; i++) {
            insert(String.valueOf(i), "OPEN", base.plusSeconds(i));
        }

        JsonNode page0 = body(mvc.perform(get("/api/reimbursements?size=2&page=0")).andExpect(status().isOk()));
        JsonNode page2 = body(mvc.perform(get("/api/reimbursements?size=2&page=2")).andExpect(status().isOk()));

        assertThat(amounts(page0)).containsExactly("5.00", "4.00");
        assertThat(amounts(page2)).containsExactly("1.00");
        assertThat(page0.get("totalItems").asInt()).isEqualTo(5);
        assertThat(page0.get("totalPages").asInt()).isEqualTo(3);
        assertThat(page0.get("page").asInt()).isZero();
        assertThat(page0.get("size").asInt()).isEqualTo(2);
    }

    @Test
    void filtroPerStato() throws Exception {
        insert("10", "OPEN", Instant.now());
        insert("20", "APPROVED", Instant.now());

        JsonNode response = body(mvc.perform(get("/api/reimbursements?status=APPROVED")).andExpect(status().isOk()));

        assertThat(amounts(response)).containsExactly("20.00");
        assertThat(response.get("totalItems").asInt()).isEqualTo(1);
    }

    @Test
    void parametriNonValidiRispondono400() throws Exception {
        for (String query : List.of("status=BOH", "page=-1", "size=0", "size=101", "page=abc")) {
            mvc.perform(get("/api/reimbursements?" + query))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.type").value("urn:eaa:error:validation"));
        }
    }

    @Test
    void ilDatabaseRifiutaImportiFuoriLimiteAncheSeIlCodiceSbagliasse() {
        assertThatThrownBy(() -> insert("10000.01", "OPEN", Instant.now())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rottaInesistenteRisponde404ProblemJson() throws Exception {
        mvc.perform(get("/api/non-esiste"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:eaa:error:not-found"));
    }

    @Test
    void metodoNonConsentitoRisponde405ProblemJson() throws Exception {
        mvc.perform(delete("/api/reimbursements"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.type").value("urn:eaa:error:http-405"));
    }

    @Test
    void corsConsenteAngularInSviluppo() throws Exception {
        mvc.perform(options("/api/reimbursements")
                        .header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"));
    }

    @Test
    void corsNonConsenteUnOrigineSconosciuto() throws Exception {
        mvc.perform(options("/api/reimbursements")
                        .header("Origin", "http://sito-sconosciuto.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    private static List<String> amounts(JsonNode page) {
        List<String> result = new ArrayList<>();
        page.get("items").forEach(i -> result.add(i.get("amount").asString()));
        return result;
    }
}
