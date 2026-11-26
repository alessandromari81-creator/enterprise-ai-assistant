package it.portfolio.eaa.chat;

import it.portfolio.eaa.common.ApiProblem;
import it.portfolio.eaa.common.Iso;
import it.portfolio.eaa.config.EaaProperties;
import it.portfolio.eaa.rag.DocumentChunkVectorStore;
import it.portfolio.eaa.rag.RetrievedChunk;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Orchestra un turno di chat: RAG + eventuale azione (tool calling). Stesso flusso del backend Laravel:
 *
 * <ol>
 *   <li>risolve la conversazione (nuova o esistente);</li>
 *   <li>retrieval: ricerca vettoriale (top-k, soglia di similarità) tramite il VectorStore;</li>
 *   <li>system prompt con gli estratti + storico + messaggio -> modello, con il tool a disposizione;</li>
 *   <li>calcola sources[] e actions[] e salva i due messaggi.</li>
 * </ol>
 */
@Service
class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** Una citazione "[qualcosa.pdf, p. N" nel testo della risposta. */
    private static final Pattern CITATION =
            Pattern.compile("\\[[^\\]]*\\.pdf\\s*,?\\s*(?:pagina|pag\\.?|p\\.?)\\s*\\d+", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final ChatClient chatClient;
    private final DocumentChunkVectorStore vectorStore;
    private final ChatMessageRepository messages;
    private final PromptBuilder prompts;
    private final SourceResolver sourceResolver;
    private final ToolCallback reimbursementTool;
    private final EaaProperties properties;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    ChatService(
            ChatClient chatClient,
            DocumentChunkVectorStore vectorStore,
            ChatMessageRepository messages,
            PromptBuilder prompts,
            SourceResolver sourceResolver,
            ToolCallback createReimbursementRequestTool,
            EaaProperties properties,
            TransactionTemplate tx,
            JsonMapper json) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
        this.messages = messages;
        this.prompts = prompts;
        this.sourceResolver = sourceResolver;
        this.reimbursementTool = createReimbursementRequestTool;
        this.properties = properties;
        this.tx = tx;
        this.json = json;
    }

    /**
     * @param documentIds se valorizzato restringe il retrieval a questi documenti; null/vuoto = tutti
     * @throws ApiProblem 404 conversazione sconosciuta, 502 provider LLM non raggiungibile
     */
    ChatResponse reply(String message, UUID requestedConversationId, List<UUID> documentIds) {
        UUID conversationId = resolveConversation(requestedConversationId);
        Instant userMessageAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // --- 1. Retrieval ---------------------------------------------------------------------
        List<RetrievedChunk> chunks = retrieve(message, documentIds);

        // --- 2. Generazione (con tool) --------------------------------------------------------
        List<Message> history = history(conversationId);
        List<String> userTexts = userTexts(history, message);
        List<ActionDto> actionsCollector = new CopyOnWriteArrayList<>();

        List<Message> prompt = new ArrayList<>();
        // Il system prompt va come SystemMessage e NON con .system(String): quest'ultimo è un template e le
        // parentesi graffe { } presenti nei documenti o nei messaggi verrebbero interpretate come variabili.
        prompt.add(new SystemMessage(prompts.system(chunks)));
        prompt.addAll(history);
        prompt.add(new UserMessage(message));

        String content;
        try {
            content = chatClient.prompt()
                    .messages(prompt)
                    .toolCallbacks(reimbursementTool)
                    // Dati della richiesta corrente per il tool: la funzione-bean è un singleton e non li vedrebbe.
                    .toolContext(Map.of(
                            ReimbursementToolHandler.CONVERSATION_ID, conversationId,
                            ReimbursementToolHandler.USER_TEXTS, userTexts,
                            ReimbursementToolHandler.CURRENT_MESSAGE, message,
                            ReimbursementToolHandler.ACTIONS, actionsCollector))
                    .call()
                    .content();
        } catch (RuntimeException e) {
            throw ApiProblem.llmUnavailable("Il modello linguistico non è raggiungibile o ha risposto con un errore.", e);
        }

        // --- 3. Esito: azioni, risposta, fonti ------------------------------------------------
        List<ActionDto> actions = List.copyOf(actionsCollector);
        String answer = content == null ? "" : content.strip();

        // Dopo un'azione riuscita la risposta deve confermarla. I modelli piccoli sbagliano in due modi (visti
        // dal vivo): testo vuoto, oppure la frase di rifiuto "non ho trovato nei documenti" perché gli estratti
        // non parlano di rimborsi. In entrambi i casi si usa una conferma costruita dal codice con i dati salvati.
        if (!actions.isEmpty() && (answer.isEmpty() || answer.contains(PromptBuilder.REFUSAL))) {
            answer = confirmation(actions.get(0));
        }

        // Citazione inventata: senza estratti recuperati nessuna risposta può citare un documento (visto dal
        // vivo: contesto vuoto -> "[prenotazione_aeroporto.pdf, p. 2]", un file mai caricato).
        if (chunks.isEmpty() && actions.isEmpty() && CITATION.matcher(answer).find()) {
            answer = PromptBuilder.REFUSAL;
        }
        if (answer.isEmpty()) {
            answer = PromptBuilder.REFUSAL;
        }

        List<SourceDto> sources = sourceResolver.resolve(answer, chunks, !actions.isEmpty()).stream()
                .map(this::toSource)
                .toList();

        // --- 4. Persistenza (solo a esito positivo: se l'LLM fallisce non si salvano mezze conversazioni)
        String finalAnswer = answer;
        Instant assistantAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID assistantId = tx.execute(status -> {
            messages.insert(conversationId, ChatMessageRepository.ROLE_USER, message, null, null, userMessageAt);
            return messages.insert(conversationId, ChatMessageRepository.ROLE_ASSISTANT, finalAnswer,
                    json.writeValueAsString(sources), json.writeValueAsString(actions), assistantAt);
        });

        return new ChatResponse(conversationId.toString(), assistantId.toString(), finalAnswer, sources, actions, Iso.utc(assistantAt));
    }

    /** Ricerca vettoriale: i k chunk più simili (similarità coseno >= soglia), sui soli documenti READY. */
    private List<RetrievedChunk> retrieve(String message, List<UUID> documentIds) {
        SearchRequest.Builder request = SearchRequest.builder()
                .query(message)
                .topK(properties.rag().topK())
                .similarityThreshold(properties.rag().minScore());

        if (documentIds != null && !documentIds.isEmpty()) {
            request.filterExpression(new FilterExpressionBuilder()
                    .in(DocumentChunkVectorStore.META_DOCUMENT_ID, new ArrayList<Object>(documentIds.stream().map(UUID::toString).toList()))
                    .build());
        }

        List<Document> hits = vectorStore.similaritySearch(request.build());
        return hits.stream().map(RetrievedChunk::from).toList();
    }

    /** Nuova conversazione se assente; 404 se l'id non corrisponde a nessuna chat esistente. */
    private UUID resolveConversation(UUID conversationId) {
        if (conversationId == null) {
            return UUID.randomUUID();
        }
        if (!messages.conversationExists(conversationId)) {
            throw ApiProblem.notFound("Conversazione non trovata.");
        }
        return conversationId;
    }

    /** Ultimi N messaggi della conversazione, dal più vecchio al più recente. */
    private List<Message> history(UUID conversationId) {
        return messages.lastMessages(conversationId, properties.rag().historyMessages()).stream()
                .<Message>map(m -> ChatMessageRepository.ROLE_USER.equals(m.role())
                        ? new UserMessage(m.content())
                        : new AssistantMessage(m.content()))
                .toList();
    }

    /** I soli messaggi scritti dall'UTENTE (storico + corrente): servono al tool per verificare l'importo. */
    private static List<String> userTexts(List<Message> history, String message) {
        List<String> texts = new ArrayList<>();
        for (Message m : history) {
            if (m instanceof UserMessage user) {
                texts.add(user.getText());
            }
        }
        texts.add(message);
        return List.copyOf(texts);
    }

    /** Conferma standard di una richiesta di rimborso creata. */
    private static String confirmation(ActionDto action) {
        // "#,##0.00" con simboli italiani: 1.000,00 (come number_format di PHP nel backend Laravel).
        String amount = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ITALY))
                .format(new java.math.BigDecimal(action.amount()));
        return "Ho aperto la richiesta di rimborso di %s %s%s. Identificativo: %s. Resta in stato OPEN in attesa di approvazione."
                .formatted(amount, action.currency(),
                        action.description() != null ? " (" + action.description() + ")" : "",
                        action.reimbursementId());
    }

    private SourceDto toSource(RetrievedChunk chunk) {
        int max = properties.rag().snippetLength();
        String snippet = chunk.content().replaceAll("\\s+", " ").strip();
        if (snippet.length() > max) {
            snippet = snippet.substring(0, max - 1).stripTrailing() + "…";
        }
        return new SourceDto(chunk.chunkId(), chunk.documentId(), chunk.filename(), chunk.page(), chunk.score(), snippet);
    }
}
