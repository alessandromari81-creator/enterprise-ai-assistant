package it.portfolio.eaa.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.ResultActions;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base dei test di integrazione: applicazione Spring completa + PostgreSQL/pgvector reali + finto Ollama.
 * Ogni test parte da tabelle vuote e da un finto Ollama azzerato.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    protected static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    protected final FakeOllama ollama = FakeOllama.get();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.ensureSchema();
        registry.add("spring.datasource.url", TestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::username);
        registry.add("spring.datasource.password", TestDatabase::password);
        // I modelli sono SEMPRE simulati: l'app parla con il finto Ollama locale.
        registry.add("spring.ai.ollama.base-url", () -> FakeOllama.get().baseUrl());
    }

    @BeforeEach
    void cleanState() {
        jdbc.execute("TRUNCATE chat_messages, reimbursement_requests, document_chunks, documents CASCADE");
        ollama.reset();
    }

    // --- Helper ---------------------------------------------------------------------------------------------

    protected ResultActions upload(String filename, byte[] content) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.multipart("/api/documents")
                .file(new MockMultipartFile("file", filename, "application/pdf", content)));
    }

    protected ResultActions uploadPdf(String filename, List<String> pages) throws Exception {
        return upload(filename, PdfFactory.make(pages));
    }

    protected static JsonNode body(ResultActions result) throws Exception {
        return json(result.andReturn());
    }

    protected static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }
}
