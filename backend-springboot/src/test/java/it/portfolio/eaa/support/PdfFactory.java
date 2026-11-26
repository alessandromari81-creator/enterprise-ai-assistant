package it.portfolio.eaa.support;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Genera PDF minimali e VALIDI per i test, senza librerie né file binari nel repo.
 * Solo testo ASCII (font Helvetica standard): basta per verificare l'estrazione pagina per pagina.
 */
public final class PdfFactory {

    private PdfFactory() {}

    /** @param pages il testo di ciascuna pagina (stringa vuota = pagina senza testo) */
    public static byte[] make(List<String> pages) {
        // Oggetti: 1 = catalogo, 2 = elenco pagine, 3 = font, poi per ogni pagina (4+2i) e il suo contenuto (5+2i).
        List<String> objects = new ArrayList<>();
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            kids.append(4 + 2 * i).append(" 0 R ");
        }
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        objects.add("<< /Type /Pages /Kids [" + kids.toString().strip() + "] /Count " + pages.size() + " >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        for (int i = 0; i < pages.size(); i++) {
            int contentId = 5 + 2 * i;
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents " + contentId
                    + " 0 R /Resources << /Font << /F1 3 0 R >> >> >>");
            String stream = contentStream(pages.get(i));
            objects.add("<< /Length " + stream.length() + " >>\nstream\n" + stream + "\nendstream");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        write(out, "%PDF-1.4\n");
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            write(out, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
        }
        int xref = out.size();
        write(out, "xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        for (int offset : offsets) {
            write(out, "%010d 00000 n \n".formatted(offset));
        }
        write(out, "trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    /** Operatori PDF: testo a capo ogni ~90 caratteri (nessuna riga deve uscire dalla pagina). */
    private static String contentStream(String text) {
        if (text.isBlank()) {
            return "";
        }
        StringBuilder ops = new StringBuilder("BT\n/F1 11 Tf\n14 TL\n40 750 Td\n");
        for (String line : wrap(text, 90)) {
            String escaped = line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
            ops.append('(').append(escaped).append(") Tj\nT*\n");
        }
        return ops.append("ET").toString();
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}
