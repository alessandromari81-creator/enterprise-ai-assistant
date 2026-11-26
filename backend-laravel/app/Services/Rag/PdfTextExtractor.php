<?php

namespace App\Services\Rag;

use App\Exceptions\ApiProblem;
use Smalot\PdfParser\Config;
use Smalot\PdfParser\Parser;
use Throwable;

/**
 * Estrae il testo di un PDF PAGINA PER PAGINA con smalot/pdfparser.
 *
 * Il testo per pagina è indispensabile: i chunk ereditano il numero di pagina,
 * ed è ciò che permette di citare "[file.pdf, p. 4]" nelle risposte.
 *
 * Limite noto: pdfparser legge solo il testo vettoriale. I PDF che sono scansioni
 * (immagini) non producono testo -> l'ingestione risponde 422 "unreadable-pdf" (serve OCR).
 */
class PdfTextExtractor
{
    /**
     * `pages` contiene solo le pagine con testo; `pageCount` le conta tutte.
     *
     * @return array{pageCount: int, pages: list<array{number: int, text: string}>}
     *
     * @throws ApiProblem 422 se il file non è un PDF leggibile
     */
    public function extract(string $path): array
    {
        try {
            $config = new Config;
            // Non serve il contenuto delle immagini: risparmia molta memoria sui PDF grandi.
            $config->setRetainImageContent(false);

            $pdf = (new Parser([], $config))->parseFile($path);
            $rawPages = $pdf->getPages();
        } catch (Throwable) {
            throw ApiProblem::unreadablePdf('Il file non è un PDF valido, oppure è corrotto o protetto da password.');
        }

        $pages = [];
        foreach ($rawPages as $index => $page) {
            try {
                $text = $this->normalize($page->getText());
            } catch (Throwable) {
                $text = ''; // una pagina illeggibile non deve far fallire tutto il documento
            }

            if ($text !== '') {
                $pages[] = ['number' => $index + 1, 'text' => $text];
            }
        }

        return ['pageCount' => count($rawPages), 'pages' => $pages];
    }

    /**
     * Ripulisce il testo estratto:
     *  - forza UTF-8 valido (mb_scrub);
     *  - rimuove i caratteri di controllo, in particolare il byte NUL che PostgreSQL rifiuta;
     *  - comprime spazi e righe vuote multiple.
     */
    private function normalize(string $text): string
    {
        $text = mb_scrub($text, 'UTF-8');
        $text = preg_replace('/[^\P{C}\n\t]+/u', '', $text) ?? '';
        $text = str_replace("\t", ' ', $text);
        $text = preg_replace('/[ ]{2,}/', ' ', $text) ?? '';
        $text = preg_replace('/ ?\n ?/', "\n", $text) ?? '';
        $text = preg_replace('/\n{3,}/', "\n\n", $text) ?? '';

        return trim($text);
    }
}
