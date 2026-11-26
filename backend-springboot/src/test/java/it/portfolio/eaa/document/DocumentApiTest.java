package it.portfolio.eaa.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.portfolio.eaa.support.IntegrationTest;
import it.portfolio.eaa.support.PdfFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** Contratto di POST /api/documents e GET /api/documents (stessi casi dei test del backend Laravel). */
class DocumentApiTest extends IntegrationTest {

    private static final List<String> PAGES = List.of(
            "Politica rimborsi. Il taxi e rimborsabile fino a 40 euro a corsa con ricevuta.",
            "Le cene con clienti sono rimborsabili fino a 80 euro a persona.");

    @Test
    void caricaEIndicizzaUnPdf() throws Exception {
        MvcResult result = uploadPdf("policy-rimborsi.pdf", PAGES)
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.filename").value("policy-rimborsi.pdf"))
                .andExpect(jsonPath("$.pageCount").value(2))
                .andExpect(jsonPath("$.chunkCount").value(2))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.errorMessage").value((Object) null))
                .andReturn();

        JsonNode doc = json(result);

        // Forma ESATTA del contratto: né campi in più, né in meno, nessun wrapper "data".
        Set<String> keys = new TreeSet<>();
        doc.propertyNames().forEach(keys::add);
        assertThat(keys).containsExactly("chunkCount", "createdAt", "errorMessage", "filename", "id", "pageCount", "sizeBytes", "status");
        assertThat(doc.get("createdAt").asString()).matches("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ$");

        // I chunk sono nel database, con pagina e vettore a 768 dimensioni.
        List<Integer> pages = jdbc.queryForList(
                "SELECT page_number FROM document_chunks WHERE document_id = ?::uuid ORDER BY chunk_index", Integer.class, doc.get("id").asString());
        assertThat(pages).containsExactly(1, 2);
        Integer dims = jdbc.queryForObject("SELECT vector_dims(embedding) FROM document_chunks LIMIT 1", Integer.class);
        assertThat(dims).isEqualTo(768);
        String firstChunk = jdbc.queryForObject("SELECT content FROM document_chunks ORDER BY chunk_index LIMIT 1", String.class);
        assertThat(firstChunk).contains("taxi");
    }

    @Test
    void unPdfLungoProduceChunkCheNonAttraversanoLePagine() throws Exception {
        StringBuilder longPage = new StringBuilder();
        for (int i = 1; i <= 400; i++) {
            longPage.append("Regola numero ").append(i).append(" della policy aziendale sui rimborsi. ");
        }

        MvcResult result = uploadPdf("lungo.pdf", List.of(longPage.toString(), "Seconda pagina breve."))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(json(result).get("chunkCount").asInt()).isGreaterThan(2);
        Integer onPage2 = jdbc.queryForObject("SELECT count(*) FROM document_chunks WHERE page_number = 2", Integer.class);
        assertThat(onPage2).isEqualTo(1);

        // Ogni chunk resta entro ~500 token (la dimensione è in token, non in caratteri).
        Integer maxTokens = jdbc.queryForObject("SELECT max(token_count) FROM document_chunks", Integer.class);
        assertThat(maxTokens).isLessThanOrEqualTo(520);
    }

    @Test
    void loStessoPdfDueVolteRisponde409() throws Exception {
        uploadPdf("policy.pdf", PAGES).andExpect(status().isCreated());

        uploadPdf("altro-nome.pdf", PAGES)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:eaa:error:duplicate-document"))
                .andExpect(jsonPath("$.status").value(409));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents", Integer.class)).isEqualTo(1);
    }

    @Test
    void fileMancanteRisponde400ConErrors() throws Exception {
        mvc.perform(multipart("/api/documents"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:eaa:error:validation"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.errors[0].field").value("file"));
    }

    @Test
    void richiestaNonMultipartRisponde400() throws Exception {
        // Come il backend Laravel: un POST JSON senza file è "campo file mancante", non un errore di media type.
        mvc.perform(post("/api/documents").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:eaa:error:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("file"));
    }

    @Test
    void unFileNonPdfRisponde415() throws Exception {
        upload("note.txt", "solo testo".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.type").value("urn:eaa:error:unsupported-media-type"));
    }

    @Test
    void unEseguibileRinominatoPdfRisponde415() throws Exception {
        // Il controllo guarda il contenuto (magic bytes), non l'estensione.
        byte[] exe = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 'b', 'i', 'n'};

        upload("malware.pdf", exe).andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void unFileOltre20MbRisponde413() throws Exception {
        byte[] big = new byte[21 * 1024 * 1024];
        System.arraycopy("%PDF-1.4".getBytes(StandardCharsets.US_ASCII), 0, big, 0, 8);

        upload("enorme.pdf", big)
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.type").value("urn:eaa:error:file-too-large"));
    }

    @Test
    void unPdfCorrottoRisponde422ERestaVisibileComeFailed() throws Exception {
        upload("rotto.pdf", "%PDF-1.4\nnon sono davvero un pdf".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:eaa:error:unreadable-pdf"));

        assertThat(jdbc.queryForObject("SELECT status FROM documents", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_chunks", Integer.class)).isZero();
    }

    @Test
    void unPdfSenzaTestoEstraibileRisponde422() throws Exception {
        uploadPdf("scansione.pdf", List.of("", ""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:eaa:error:unreadable-pdf"));
    }

    @Test
    void seIlProviderEmbeddingEGiuRisponde502ESiPuoRiprovare() throws Exception {
        ollama.embeddingDown();

        uploadPdf("policy.pdf", PAGES)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("urn:eaa:error:llm-unavailable"));

        assertThat(jdbc.queryForObject("SELECT status FROM documents", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_message FROM documents", String.class)).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_chunks", Integer.class))
                .as("Nessun chunk parziale: la scrittura è atomica").isZero();

        // Un documento FAILED non blocca il ricaricamento dello stesso file (niente 409).
        ollama.reset();
        uploadPdf("policy.pdf", PAGES).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents", Integer.class)).isEqualTo(1);
    }

    @Test
    void elencaIDocumentiDalPiuRecente() throws Exception {
        uploadPdf("a.pdf", List.of("Primo documento")).andExpect(status().isCreated());
        uploadPdf("b.pdf", List.of("Secondo documento")).andExpect(status().isCreated());

        MvcResult result = mvc.perform(get("/api/documents")).andExpect(status().isOk()).andReturn();

        List<String> names = new ArrayList<>();
        json(result).get("items").forEach(n -> names.add(n.get("filename").asString()));
        assertThat(names).containsExactly("b.pdf", "a.pdf");
    }
}
