package it.portfolio.eaa.chat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.portfolio.eaa.support.FakeOllama;
import it.portfolio.eaa.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.ResultActions;

/** Con soglia di similarità altissima nessun chunk viene recuperato: il contesto è vuoto. */
@SpringBootTest(properties = "eaa.rag.min-score=0.99")
class ChatNoContextTest extends IntegrationTest {

    private ResultActions chat(String message) throws Exception {
        return mvc.perform(post("/api/chat").contentType("application/json")
                .content(JSON.writeValueAsString(java.util.Map.of("message", message))));
    }

    @Test
    void senzaEstrattiPertinentiIlModelloRiceveContestoVuotoENonCiSonoFonti() throws Exception {
        uploadPdf("policy.pdf", List.of("Il taxi e rimborsabile fino a 40 euro.")).andExpect(status().isCreated());
        ollama.queueChat(FakeOllama.chatText("Non ho trovato questa informazione nei documenti caricati."));

        chat("Quanto costa un volo per la luna?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources").isEmpty())
                .andExpect(jsonPath("$.answer").value("Non ho trovato questa informazione nei documenti caricati."));

        org.assertj.core.api.Assertions.assertThat(ollama.chatRequests().get(0).get("messages").get(0).get("content").asString())
                .contains("(nessun estratto pertinente trovato)");
    }

    @Test
    void unaCitazioneSenzaEstrattiRecuperatiEInventataEVieneSostituitaDalRifiuto() throws Exception {
        // Caso reale: con contesto vuoto un modello citò "prenotazione_aeroporto.pdf", un file mai caricato.
        uploadPdf("policy.pdf", List.of("Il taxi e rimborsabile fino a 40 euro.")).andExpect(status().isCreated());
        ollama.queueChat(FakeOllama.chatText("Il limite è 120 euro [prenotazione_aeroporto.pdf, p. 2]."));

        chat("Quanto costa l'hotel?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Non ho trovato questa informazione nei documenti caricati."))
                .andExpect(jsonPath("$.sources").isEmpty());
    }
}
