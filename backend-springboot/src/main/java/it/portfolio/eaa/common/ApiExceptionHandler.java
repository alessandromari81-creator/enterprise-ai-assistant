package it.portfolio.eaa.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Gestione globale degli errori: OGNI errore dell'API esce come application/problem+json con un
 * `type` URN stabile, identico a quello del backend Laravel (decisione D5).
 *
 * Gli errori di dominio (ApiProblem) si convertono 1:1; quelli del framework (JSON malformato, metodo
 * non consentito, upload troppo grande...) si mappano sugli stessi tipi. Qualsiasi altra eccezione
 * diventa un 500 generico: il dettaglio resta nel log, mai nella risposta.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Corpo RFC 9457. `errors` compare solo per il 400, come nel backend Laravel. */
    record ProblemBody(
            String type,
            String title,
            int status,
            String detail,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<ApiProblem.FieldError> errors) {}

    @ExceptionHandler(ApiProblem.class)
    ResponseEntity<ProblemBody> handleProblem(ApiProblem problem) {
        // Gli errori 5xx (es. modello non raggiungibile) si registrano; i 4xx sono input sbagliato.
        if (problem.status() >= 500) {
            log.error("{} — {}", problem.problemType(), problem.detail(), problem.getCause());
        }
        return respond(problem);
    }

    /** Upload oltre i 20 MB (spring.servlet.multipart.max-file-size / max-request-size). */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemBody> handleTooLarge() {
        return respond(ApiProblem.fileTooLarge());
    }

    /** Campo "file" assente, oppure richiesta non multipart. */
    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    ResponseEntity<ProblemBody> handleMissingPart() {
        return respond(ApiProblem.validation(List.of(new ApiProblem.FieldError("file", "Il campo file è obbligatorio."))));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ProblemBody> handleMissingParameter(MissingServletRequestParameterException e) {
        return respond(ApiProblem.validation(List.of(
                new ApiProblem.FieldError(e.getParameterName(), "Il parametro " + e.getParameterName() + " è obbligatorio."))));
    }

    /** JSON malformato o con tipi sbagliati. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemBody> handleUnreadableBody() {
        return respond(ApiProblem.validation(List.of(
                new ApiProblem.FieldError("body", "Il corpo della richiesta non è un JSON valido."))));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemBody> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return respond(ApiProblem.http(405, "Method Not Allowed", "Metodo " + e.getMethod() + " non consentito."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemBody> handleMediaType() {
        return respond(ApiProblem.http(415, "Unsupported Media Type", "Tipo di contenuto non supportato."));
    }

    /** Rotta inesistente. */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemBody> handleNotFound() {
        return respond(ApiProblem.notFound("Risorsa non trovata."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemBody> handleUnexpected(Exception e) {
        log.error("Errore non gestito", e);
        return respond(ApiProblem.internal());
    }

    private static ResponseEntity<ProblemBody> respond(ApiProblem p) {
        return ResponseEntity.status(HttpStatus.valueOf(p.status()))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new ProblemBody(p.problemType(), p.title(), p.status(), p.detail(), p.errors()));
    }
}
