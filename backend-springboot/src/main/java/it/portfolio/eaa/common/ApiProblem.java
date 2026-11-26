package it.portfolio.eaa.common;

import java.util.List;

/**
 * Errore di dominio che si traduce in una risposta RFC 9457 (application/problem+json), come
 * richiede il contratto (docs/api/openapi.yaml, schema "Problem"). Stessi `type`, `title` e status
 * del backend Laravel: Angular distingue i casi dal `type` URN, non dal testo di `detail`.
 *
 * Le factory statiche sono l'unico modo di crearla, così status e type restano allineati al contratto.
 * {@link ApiExceptionHandler} la trasforma in risposta HTTP.
 */
public class ApiProblem extends RuntimeException {

    /** Un campo non valido (solo per il 400). */
    public record FieldError(String field, String message) {}

    private final String type;
    private final String title;
    private final int status;
    private final List<FieldError> errors;

    private ApiProblem(String type, String title, int status, String detail, List<FieldError> errors, Throwable cause) {
        super(detail, cause);
        this.type = type;
        this.title = title;
        this.status = status;
        this.errors = errors;
    }

    public static ApiProblem validation(List<FieldError> errors) {
        return new ApiProblem("urn:eaa:error:validation", "Bad Request", 400, "La richiesta non è valida.", errors, null);
    }

    public static ApiProblem notFound(String detail) {
        return new ApiProblem("urn:eaa:error:not-found", "Not Found", 404, detail, null, null);
    }

    public static ApiProblem duplicateDocument(String detail) {
        return new ApiProblem("urn:eaa:error:duplicate-document", "Conflict", 409, detail, null, null);
    }

    public static ApiProblem fileTooLarge() {
        return new ApiProblem("urn:eaa:error:file-too-large", "Payload Too Large", 413,
                "Il file supera la dimensione massima di 20 MB.", null, null);
    }

    public static ApiProblem unsupportedMediaType() {
        return new ApiProblem("urn:eaa:error:unsupported-media-type", "Unsupported Media Type", 415,
                "Sono accettati solo file PDF (application/pdf).", null, null);
    }

    public static ApiProblem unreadablePdf(String detail) {
        return new ApiProblem("urn:eaa:error:unreadable-pdf", "Unprocessable Content", 422, detail, null, null);
    }

    public static ApiProblem llmUnavailable(String detail, Throwable cause) {
        return new ApiProblem("urn:eaa:error:llm-unavailable", "Bad Gateway", 502, detail, null, cause);
    }

    public static ApiProblem internal() {
        return new ApiProblem("urn:eaa:error:internal", "Internal Server Error", 500, "Errore interno del server.", null, null);
    }

    /** Errore HTTP generico del framework (405, 415...), nello stesso formato. */
    public static ApiProblem http(int status, String title, String detail) {
        return new ApiProblem("urn:eaa:error:http-" + status, title, status, detail, null, null);
    }

    public String problemType() {
        return type;
    }

    public String title() {
        return title;
    }

    public int status() {
        return status;
    }

    public String detail() {
        return getMessage();
    }

    public List<FieldError> errors() {
        return errors;
    }
}
