package it.portfolio.eaa.chat;

import it.portfolio.eaa.common.ApiProblem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/chat. Il controller valida la richiesta (stesse regole del backend Laravel) e delega a ChatService.
 *
 * Regole: message obbligatorio, max 4000 caratteri, spazi ai bordi rimossi; conversationId opzionale e UUID;
 * documentIds opzionale, elenco di UUID senza duplicati. Una richiesta non valida risponde 400 con errors[{field, message}].
 */
@RestController
@RequestMapping("/api/chat")
class ChatController {

    private static final int MAX_MESSAGE_LENGTH = 4000;

    /** UUID in forma canonica (UUID.fromString accetta anche forme abbreviate: qui no). */
    private static final Pattern UUID_FORMAT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final ChatService chat;

    ChatController(ChatService chat) {
        this.chat = chat;
    }

    @PostMapping
    ChatResponse chat(@RequestBody ChatRequest request) {
        List<ApiProblem.FieldError> errors = new ArrayList<>();

        String message = request.message() == null ? "" : request.message().strip();
        if (message.isEmpty()) {
            errors.add(new ApiProblem.FieldError("message", "Il campo message è obbligatorio."));
        } else if (message.codePointCount(0, message.length()) > MAX_MESSAGE_LENGTH) {
            errors.add(new ApiProblem.FieldError("message", "Il campo message non può superare " + MAX_MESSAGE_LENGTH + " caratteri."));
        }

        UUID conversationId = null;
        if (request.conversationId() != null && !request.conversationId().isBlank()) {
            conversationId = parseUuid(request.conversationId().strip());
            if (conversationId == null) {
                errors.add(new ApiProblem.FieldError("conversationId", "Il campo conversationId deve essere un UUID valido."));
            }
        }

        List<UUID> documentIds = null;
        if (request.documentIds() != null) {
            documentIds = new ArrayList<>();
            Set<UUID> seen = new HashSet<>();
            for (int i = 0; i < request.documentIds().size(); i++) {
                String field = "documentIds." + i;
                String raw = request.documentIds().get(i);
                if (raw == null || raw.isBlank()) {
                    errors.add(new ApiProblem.FieldError(field, "Il campo " + field + " è obbligatorio."));
                    continue;
                }
                UUID id = parseUuid(raw.strip());
                if (id == null) {
                    errors.add(new ApiProblem.FieldError(field, "Il campo " + field + " deve essere un UUID valido."));
                } else if (!seen.add(id)) {
                    errors.add(new ApiProblem.FieldError(field, "Il campo " + field + " ha un valore duplicato."));
                } else {
                    documentIds.add(id);
                }
            }
        }

        if (!errors.isEmpty()) {
            throw ApiProblem.validation(errors);
        }
        return chat.reply(message, conversationId, documentIds);
    }

    private static UUID parseUuid(String value) {
        return UUID_FORMAT.matcher(value).matches() ? UUID.fromString(value) : null;
    }
}
