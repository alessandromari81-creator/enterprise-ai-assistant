<?php

namespace Tests\Support;

use Illuminate\Http\UploadedFile;

/**
 * Genera PDF minimali e VALIDI per i test, senza librerie né file binari nel repo.
 * Solo testo ASCII (font Helvetica standard): basta per verificare l'estrazione pagina per pagina.
 */
final class PdfFactory
{
    /**
     * @param  list<string>  $pages  il testo di ciascuna pagina (stringa vuota = pagina senza testo)
     */
    public static function make(array $pages): string
    {
        // Oggetti: 1 = catalogo, 2 = elenco pagine, 3 = font, poi per ogni pagina (4+2i) e il suo contenuto (5+2i).
        $objects = [];
        $kids = [];
        foreach ($pages as $i => $_) {
            $kids[] = (4 + 2 * $i).' 0 R';
        }

        $objects[1] = '<< /Type /Catalog /Pages 2 0 R >>';
        $objects[2] = '<< /Type /Pages /Kids ['.implode(' ', $kids).'] /Count '.count($pages).' >>';
        $objects[3] = '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>';

        foreach ($pages as $i => $text) {
            $pageId = 4 + 2 * $i;
            $contentId = 5 + 2 * $i;

            $objects[$pageId] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents {$contentId} 0 R "
                .'/Resources << /Font << /F1 3 0 R >> >> >>';

            $stream = self::contentStream($text);
            $objects[$contentId] = '<< /Length '.strlen($stream)." >>\nstream\n{$stream}\nendstream";
        }

        $pdf = "%PDF-1.4\n";
        $offsets = [];
        foreach ($objects as $id => $body) {
            $offsets[$id] = strlen($pdf);
            $pdf .= "{$id} 0 obj\n{$body}\nendobj\n";
        }

        $xref = strlen($pdf);
        $count = count($objects) + 1;
        $pdf .= "xref\n0 {$count}\n0000000000 65535 f \n";
        foreach ($offsets as $offset) {
            $pdf .= sprintf("%010d 00000 n \n", $offset);
        }
        $pdf .= "trailer\n<< /Size {$count} /Root 1 0 R >>\nstartxref\n{$xref}\n%%EOF\n";

        return $pdf;
    }

    /** Scrive il PDF in un file temporaneo e lo restituisce come upload "reale" (con rilevamento mime sul contenuto). */
    public static function upload(array $pages, string $name = 'policy-rimborsi.pdf'): UploadedFile
    {
        return self::uploadContent(self::make($pages), $name);
    }

    public static function uploadContent(string $content, string $name): UploadedFile
    {
        $path = tempnam(sys_get_temp_dir(), 'eaa');
        file_put_contents($path, $content);

        // $test = true: Laravel accetta il file anche se non arriva da un vero POST HTTP.
        return new UploadedFile($path, $name, null, null, true);
    }

    /** Operatori PDF: testo a capo ogni ~90 caratteri (nessuna riga deve uscire dalla pagina). */
    private static function contentStream(string $text): string
    {
        if (trim($text) === '') {
            return '';
        }

        $lines = explode("\n", wordwrap($text, 90, "\n", true));
        $ops = "BT\n/F1 11 Tf\n14 TL\n40 750 Td\n";
        foreach ($lines as $line) {
            $escaped = str_replace(['\\', '(', ')'], ['\\\\', '\\(', '\\)'], $line);
            $ops .= "({$escaped}) Tj\nT*\n";
        }

        return $ops.'ET';
    }
}
