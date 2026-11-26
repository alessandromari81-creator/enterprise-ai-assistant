package it.portfolio.eaa.chat;

import static org.assertj.core.api.Assertions.assertThat;

import it.portfolio.eaa.rag.RetrievedChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceResolverTest {

    private final SourceResolver resolver = new SourceResolver();

    private static RetrievedChunk chunk(String file, int page) {
        return new RetrievedChunk("c-" + file + page, "d-" + file, file, page, "testo", 0.9);
    }

    @Test
    void restituisceSoloIChunkCitati() {
        var retrieved = List.of(chunk("policy.pdf", 4), chunk("policy.pdf", 5), chunk("manuale.pdf", 1));

        var sources = resolver.resolve("Il limite è 40 € [policy.pdf, p. 4].", retrieved, false);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).page()).isEqualTo(4);
    }

    @Test
    void riconosceLeVariantiDiCitazione() {
        var retrieved = List.of(chunk("policy.pdf", 4));

        for (String citation : List.of("[policy.pdf, p. 4]", "[policy.pdf, pag. 4]", "[policy.pdf p.4]", "(policy.pdf, pagina 4)")) {
            assertThat(resolver.resolve("Risposta " + citation, retrieved, false)).as(citation).hasSize(1);
        }
    }

    @Test
    void pagina14NonContaComeCitazioneDellaPagina1() {
        var retrieved = List.of(chunk("policy.pdf", 1), chunk("policy.pdf", 14));

        var sources = resolver.resolve("Vedi [policy.pdf, p. 14].", retrieved, false);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).page()).isEqualTo(14);
    }

    @Test
    void senzaCitazioneLeggibileRestituisceIChunkConsultati() {
        assertThat(resolver.resolve("Il limite è 40 €.", List.of(chunk("policy.pdf", 4)), false)).hasSize(1);
    }

    @Test
    void ilRifiutoNonHaFonti() {
        assertThat(resolver.resolve(PromptBuilder.REFUSAL, List.of(chunk("policy.pdf", 4)), false)).isEmpty();
    }

    @Test
    void unAzioneEseguitaNonHaFonti() {
        assertThat(resolver.resolve("Ho aperto la richiesta.", List.of(chunk("policy.pdf", 4)), true)).isEmpty();
    }

    @Test
    void senzaChunkRecuperatiNonCiSonoFonti() {
        assertThat(resolver.resolve("Qualsiasi cosa [policy.pdf, p. 4]", List.of(), false)).isEmpty();
    }
}
