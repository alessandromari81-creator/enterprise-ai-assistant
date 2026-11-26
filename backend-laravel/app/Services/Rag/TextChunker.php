<?php

namespace App\Services\Rag;

/**
 * Divide il testo di UNA pagina in chunk di dimensione massima fissa con sovrapposizione.
 *
 * Perché serve la sovrapposizione: se una regola ("il taxi è rimborsabile fino a 40 €")
 * cade a cavallo di due chunk, ripetere gli ultimi ~50 token del chunk precedente
 * all'inizio del successivo evita di perderne il senso.
 *
 * Perché si taglia su confini naturali: un chunk che finisce a metà frase produce un
 * embedding peggiore. Si cerca quindi, nell'ultimo 40% della finestra, il confine migliore
 * in quest'ordine: paragrafo, a capo, fine frase, punto e virgola, spazio.
 *
 * Le dimensioni sono in CARATTERI perché in PHP non c'è un tokenizer nativo:
 * ~4 caratteri = 1 token (stima ragionevole per italiano/inglese). Il backend Spring usa
 * token reali: la differenza è minima ma non zero (vedi PROJECT_LOG).
 */
class TextChunker
{
    private const SEPARATORS = ["\n\n", "\n", '. ', '; ', ' '];

    public function __construct(
        private readonly int $maxChars,
        private readonly int $overlapChars,
    ) {
        if ($maxChars < 100 || $overlapChars < 0 || $overlapChars >= intdiv($maxChars, 2)) {
            throw new \InvalidArgumentException('Parametri di chunking non validi: overlap deve essere < metà del chunk.');
        }
    }

    /** Costruisce il chunker dai parametri in config/eaa.php (token -> caratteri). */
    public static function fromConfig(): self
    {
        $cpt = (int) config('eaa.rag.chars_per_token');

        return new self(
            (int) config('eaa.rag.chunk_size_tokens') * $cpt,
            (int) config('eaa.rag.chunk_overlap_tokens') * $cpt,
        );
    }

    /** Stima dei token di un testo (usata solo per la colonna token_count). */
    public static function estimateTokens(string $text): int
    {
        return (int) ceil(mb_strlen($text) / max(1, (int) config('eaa.rag.chars_per_token')));
    }

    /** @return list<string> */
    public function split(string $text): array
    {
        $text = trim($text);
        $length = mb_strlen($text);

        if ($length === 0) {
            return [];
        }
        if ($length <= $this->maxChars) {
            return [$text];
        }

        $chunks = [];
        $start = 0;

        while ($start < $length) {
            $end = min($start + $this->maxChars, $length);
            if ($end < $length) {
                $end = $this->findBreak($text, $start, $end);
            }

            $chunk = trim(mb_substr($text, $start, $end - $start));
            if ($chunk !== '') {
                $chunks[] = $chunk;
            }

            if ($end >= $length) {
                break;
            }

            // Il chunk successivo riparte $overlapChars indietro, ma allineato all'inizio di una parola.
            $next = $this->alignToWordStart($text, $end - $this->overlapChars, $end);
            // Garanzia di avanzamento: non si torna mai indietro rispetto all'inizio corrente.
            $start = $next > $start ? $next : $end;
        }

        return $chunks;
    }

    /** Cerca il confine naturale migliore nell'ultimo 40% della finestra [start, end). */
    private function findBreak(string $text, int $start, int $end): int
    {
        $window = mb_substr($text, $start, $end - $start);
        $minPosition = intdiv($this->maxChars * 6, 10);

        foreach (self::SEPARATORS as $separator) {
            $position = mb_strrpos($window, $separator);
            if ($position !== false && $position >= $minPosition) {
                return $start + $position + mb_strlen($separator);
            }
        }

        return $end; // nessun confine naturale: taglio netto
    }

    /**
     * Avanza fino a una posizione in cui il carattere precedente è uno spazio (inizio parola),
     * senza mai superare $limit (la fine del chunk appena prodotto). Se in quel tratto non c'è
     * nessuno spazio (testo senza spazi, es. una lunga stringa continua) si usa la posizione
     * originale: meglio iniziare a metà parola che saltare il testo.
     */
    private function alignToWordStart(string $text, int $position, int $limit): int
    {
        $raw = $position;

        while ($position < $limit && ! preg_match('/\s/u', mb_substr($text, $position - 1, 1))) {
            $position++;
        }

        return $position < $limit ? $position : $raw;
    }
}
