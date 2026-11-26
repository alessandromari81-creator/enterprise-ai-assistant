package it.portfolio.eaa.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DocumentHelpersTest {

    @Test
    void normalizzaIlTestoEstrattoDalPdf() {
        // NUL (rifiutato da PostgreSQL) e altri caratteri di controllo spariscono; spazi e righe vuote si comprimono.
        String raw = "Riga  uno\u0000 con   spazi \n\n\n\nRiga\tdue \u0007fine  ";

        assertThat(PdfTextExtractor.normalize(raw)).isEqualTo("Riga uno con spazi\n\nRiga due fine");
        assertThat(PdfTextExtractor.normalize(null)).isEmpty();
        assertThat(PdfTextExtractor.normalize("  \n \t ")).isEmpty();
    }

    @Test
    void riconosceUnPdfDaiMagicBytesNonDallEstensione() {
        assertThat(DocumentController.looksLikePdf("%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII))).isTrue();
        assertThat(DocumentController.looksLikePdf("\n\n%PDF-1.4".getBytes(StandardCharsets.US_ASCII))).as("qualche byte prima è ammesso").isTrue();
        assertThat(DocumentController.looksLikePdf("MZ\u0090 programma.pdf".getBytes(StandardCharsets.ISO_8859_1))).isFalse();
        assertThat(DocumentController.looksLikePdf(new byte[0])).isFalse();
    }

    @Test
    void ilNomeFileDiventaSoloIlNomeSenzaPercorsi() {
        assertThat(DocumentIngestionService.safeFilename("C:\\utenti\\mario\\policy.pdf")).isEqualTo("policy.pdf");
        assertThat(DocumentIngestionService.safeFilename("../../etc/passwd.pdf")).isEqualTo("passwd.pdf");
        assertThat(DocumentIngestionService.safeFilename(null)).isEqualTo("documento.pdf");
        assertThat(DocumentIngestionService.safeFilename("a".repeat(300))).hasSize(255);
    }
}
