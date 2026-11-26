package it.portfolio.eaa.chat;

import java.util.List;

/**
 * Corpo di POST /api/chat, come arriva dal client. Si valida a mano in ChatController (non con Bean Validation)
 * per produrre errori nel formato del contratto, con il nome esatto del campo (es. "documentIds.0").
 */
public record ChatRequest(String message, String conversationId, List<String> documentIds) {}
