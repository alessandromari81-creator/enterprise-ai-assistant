package it.portfolio.eaa.chat;

import java.util.List;

/** Risposta di POST /api/chat (schema ChatResponse del contratto). */
public record ChatResponse(
        String conversationId,
        String messageId,
        String answer,
        List<SourceDto> sources,
        List<ActionDto> actions,
        String createdAt) {}
