package it.portfolio.eaa.chat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Accesso alla tabella chat_messages (memoria di conversazione e audit delle azioni dell'IA).
 *
 * Si usa JdbcClient e non JPA perché le colonne sources/actions sono JSONB: con SQL esplicito
 * (CAST(... AS jsonb)) il comportamento è prevedibile e identico a quello scritto dal backend Laravel.
 */
@Repository
class ChatMessageRepository {

    /** Ruoli come scritti nel database (vincolo CHECK nello schema). */
    static final String ROLE_USER = "USER";
    static final String ROLE_ASSISTANT = "ASSISTANT";

    record HistoryMessage(String role, String content) {}

    private final JdbcClient jdbc;

    ChatMessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean conversationExists(UUID conversationId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM chat_messages WHERE conversation_id = :id)")
                .param("id", conversationId)
                .query(Boolean.class)
                .single();
    }

    /** Ultimi {@code limit} messaggi, dal più vecchio al più recente. */
    List<HistoryMessage> lastMessages(UUID conversationId, int limit) {
        List<HistoryMessage> latestFirst = new ArrayList<>(jdbc.sql("""
                        SELECT role, content FROM chat_messages
                        WHERE conversation_id = :id
                        ORDER BY created_at DESC, id DESC
                        LIMIT :limit
                        """)
                .param("id", conversationId)
                .param("limit", limit)
                .query((rs, n) -> new HistoryMessage(rs.getString("role"), rs.getString("content")))
                .list());
        Collections.reverse(latestFirst);
        return latestFirst;
    }

    /** @return l'id generato dal database */
    UUID insert(UUID conversationId, String role, String content, String sourcesJson, String actionsJson, Instant createdAt) {
        return jdbc.sql("""
                        INSERT INTO chat_messages (conversation_id, role, content, sources, actions, created_at)
                        VALUES (:conversationId, :role, :content, CAST(:sources AS jsonb), CAST(:actions AS jsonb), :createdAt)
                        RETURNING id
                        """)
                .param("conversationId", conversationId)
                .param("role", role)
                .param("content", content)
                .param("sources", sourcesJson)
                .param("actions", actionsJson)
                .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .query(UUID.class)
                .single();
    }
}
