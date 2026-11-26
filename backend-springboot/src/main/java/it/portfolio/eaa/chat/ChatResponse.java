package it.portfolio.eaa.chat;

import java.util.List;

/** Risposta di POST /api/chat (schema ChatResponse del contratto). `warning` è null salvo l'avviso "manca il verbo". */
public record ChatResponse(
        String conversationId,
        String messageId,
        String answer,
        List<SourceDto> sources,
        List<ActionDto> actions,
        String warning,
        String createdAt) {}
