package it.portfolio.eaa.document;

import it.portfolio.eaa.common.ApiProblem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

/**
 * Estrae il testo di un PDF PAGINA PER PAGINA con PagePdfDocumentReader di Spring AI (basato su Apache PDFBox).
 *
 * Il testo per pagina è indispensabile: i chunk ereditano il numero di pagina, ed è ciò che permette
 * di citare "[file.pdf, p. 4]" nelle risposte.
 *
 * Limite noto (identico a Laravel): si legge solo il testo vettoriale. I PDF che sono scansioni (immagini)
 * non producono testo e l'ingestione risponde 422 "unreadable-pdf": servirebbe un OCR.
 */
@Component
class PdfTextExtractor {

    /** Una pagina con testo. {@code number} è in base 1. */
    record Page(int number, String text) {}

    /** {@code pages} contiene solo le pagine con testo; {@code pageCount} le conta tutte. */
    record Extracted(int pageCount, List<Page> pages) {}

    /**
     * @throws ApiProblem 422 se il file non è un PDF leggibile
     */
    Extracted extract(byte[] pdfBytes) {
        Path tempFile = null;
        try {
            // Il reader lavora su una Resource con un File vero: si passa da un file temporaneo.
            tempFile = Files.createTempFile("eaa-upload-", ".pdf");
            Files.write(tempFile, pdfBytes);

            int pageCount;
            try (PDDocument pdf = Loader.loadPDF(tempFile.toFile())) {
                pageCount = pdf.getNumberOfPages();
            }

            // pagesPerDocument = 1 (default): un Document per pagina, con metadata "page_number" in base 1.
            PagePdfDocumentReader reader =
                    new PagePdfDocumentReader(new FileSystemResource(tempFile), PdfDocumentReaderConfig.defaultConfig());

            List<Page> pages = new ArrayList<>();
            for (Document pageDocument : reader.get()) {
                String text = normalize(pageDocument.getText());
                if (!text.isEmpty()) {
                    int number = ((Number) pageDocument.getMetadata().get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER)).intValue();
                    pages.add(new Page(number, text));
                }
            }
            return new Extracted(pageCount, pages);
        } catch (IOException | RuntimeException e) {
            throw ApiProblem.unreadablePdf("Il file non è un PDF valido, oppure è corrotto o protetto da password.");
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // file temporaneo: lo ripulirà il sistema
                }
            }
        }
    }

    /**
     * Ripulisce il testo estratto (stesse regole del backend Laravel):
     *  - rimuove i caratteri di controllo, in particolare NUL che PostgreSQL rifiuta, e le surrogate spaiate;
     *  - comprime spazi e righe vuote multiple.
     */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replaceAll("[\\p{C}&&[^\\n\\t]]", "")
                .replace('\t', ' ')
                .replaceAll(" {2,}", " ")
                .replaceAll(" ?\\n ?", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }
}
