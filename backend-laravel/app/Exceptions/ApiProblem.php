<?php

namespace App\Exceptions;

use Illuminate\Http\JsonResponse;
use RuntimeException;
use Throwable;

/**
 * Errore di dominio che si traduce in una risposta RFC 9457 (application/problem+json),
 * come richiesto dal contratto (docs/api/openapi.yaml, schema "Problem").
 *
 * Ogni errore ha un `type` URN STABILE: è quello che Angular usa per distinguere i casi,
 * non il testo di `detail` (che può cambiare).
 *
 * Le factory statiche sono l'unico modo di crearla, così status e type restano allineati al contratto.
 */
class ApiProblem extends RuntimeException
{
    /**
     * @param  list<array{field: string, message: string}>|null  $errors  solo per il 400
     */
    private function __construct(
        public readonly string $problemType,
        public readonly string $title,
        public readonly int $status,
        public readonly string $detail,
        public readonly ?array $errors = null,
        ?Throwable $previous = null,
    ) {
        parent::__construct($detail, $status, $previous);
    }

    /** @param list<array{field: string, message: string}> $errors */
    public static function validation(array $errors, string $detail = 'La richiesta non è valida.'): self
    {
        return new self('urn:eaa:error:validation', 'Bad Request', 400, $detail, $errors);
    }

    public static function notFound(string $detail): self
    {
        return new self('urn:eaa:error:not-found', 'Not Found', 404, $detail);
    }

    public static function duplicateDocument(string $detail): self
    {
        return new self('urn:eaa:error:duplicate-document', 'Conflict', 409, $detail);
    }

    public static function fileTooLarge(string $detail = 'Il file supera la dimensione massima di 20 MB.'): self
    {
        return new self('urn:eaa:error:file-too-large', 'Payload Too Large', 413, $detail);
    }

    public static function unsupportedMediaType(string $detail = 'Sono accettati solo file PDF (application/pdf).'): self
    {
        return new self('urn:eaa:error:unsupported-media-type', 'Unsupported Media Type', 415, $detail);
    }

    public static function unreadablePdf(string $detail): self
    {
        return new self('urn:eaa:error:unreadable-pdf', 'Unprocessable Content', 422, $detail);
    }

    public static function llmUnavailable(string $detail, ?Throwable $previous = null): self
    {
        return new self('urn:eaa:error:llm-unavailable', 'Bad Gateway', 502, $detail, null, $previous);
    }

    public static function internal(string $detail = 'Errore interno del server.'): self
    {
        return new self('urn:eaa:error:internal', 'Internal Server Error', 500, $detail);
    }

    /** Usato da Laravel quando l'eccezione arriva al gestore: produce la risposta problem+json. */
    public function render(): JsonResponse
    {
        $body = [
            'type' => $this->problemType,
            'title' => $this->title,
            'status' => $this->status,
            'detail' => $this->detail,
        ];

        if ($this->errors !== null) {
            $body['errors'] = $this->errors;
        }

        return response()->json($body, $this->status, ['Content-Type' => 'application/problem+json']);
    }

    /**
     * Gli errori 4xx sono "normali" (input sbagliato): non vanno nel log come errori.
     * Per i 5xx restituisco false, così Laravel li registra con il suo reporter standard.
     */
    public function report(): bool
    {
        return $this->status < 500;
    }
}
