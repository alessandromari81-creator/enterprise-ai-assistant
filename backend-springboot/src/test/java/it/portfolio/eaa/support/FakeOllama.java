package it.portfolio.eaa.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finto server Ollama per i test: NESSUNA chiamata di rete reale, nessun modello da scaricare.
 * È un vero server HTTP locale (JDK) e l'app usa il VERO client Ollama di Spring AI contro di lui:
 * si collaudano per davvero la serializzazione, il tool calling e la gestione degli errori.
 *
 * Gli embedding finti sono "bag of words hashing": ogni parola incrementa una delle 768 componenti
 * (indice = crc32(parola) % 768). Due testi con parole in comune hanno similarità coseno alta, quanto
 * basta per testare il retrieval vettoriale in modo deterministico, ma su pgvector vero.
 * (Stesso algoritmo del FakeAi dei test Laravel.)
 */
public final class FakeOllama {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final FakeOllama INSTANCE = new FakeOllama();

    private final HttpServer server;
    private final Deque<String> chatResponses = new ArrayDeque<>();
    private final List<JsonNode> chatRequests = new ArrayList<>();
    private volatile boolean embeddingDown;
    private volatile boolean chatDown;

    private FakeOllama() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/api/embed", this::embed);
        server.createContext("/api/chat", this::chat);
        server.start();
    }

    public static FakeOllama get() {
        return INSTANCE;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Riporta il finto server allo stato iniziale (da chiamare prima di ogni test). */
    public synchronized void reset() {
        chatResponses.clear();
        chatRequests.clear();
        embeddingDown = false;
        chatDown = false;
    }

    /** Accoda le risposte che la chat darà, nell'ordine. */
    public synchronized void queueChat(String... responses) {
        for (String response : responses) {
            chatResponses.add(response);
        }
    }

    public void embeddingDown() {
        embeddingDown = true;
    }

    public void chatDown() {
        chatDown = true;
    }

    /** Le richieste /api/chat ricevute (per verificare prompt, storico e risultati dei tool). */
    public synchronized List<JsonNode> chatRequests() {
        return List.copyOf(chatRequests);
    }

    // --- Costruzione delle risposte nel formato di Ollama ------------------------------------------------------

    /** Risposta di sola testo. */
    public static String chatText(String text) {
        return toJson(Map.of(
                "model", "qwen2.5:7b",
                "message", Map.of("role", "assistant", "content", text),
                "done", true,
                "done_reason", "stop"));
    }

    /** Risposta in cui il modello chiede di eseguire un tool. */
    public static String chatToolCall(String tool, Map<String, Object> arguments) {
        return toJson(Map.of(
                "model", "qwen2.5:7b",
                "message", Map.of(
                        "role", "assistant",
                        "content", "",
                        "tool_calls", List.of(Map.of("function", Map.of("name", tool, "arguments", arguments)))),
                "done", true,
                "done_reason", "stop"));
    }

    /** Vettore finto di un testo (768 dimensioni). */
    public static float[] vector(String text) {
        float[] v = new float[768];
        for (String word : text.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (word.isEmpty()) {
                continue;
            }
            CRC32 crc = new CRC32();
            crc.update(word.getBytes(StandardCharsets.UTF_8));
            v[(int) (crc.getValue() % 768)] += 1f;
        }
        float sum = 0;
        for (float x : v) {
            sum += x;
        }
        if (sum == 0) {
            v[0] = 1f; // il vettore nullo non ha direzione: il coseno non sarebbe definito
        }
        return v;
    }

    // --- Handler ----------------------------------------------------------------------------------------------

    private void embed(HttpExchange exchange) throws IOException {
        if (embeddingDown) {
            reply(exchange, 500, "boom");
            return;
        }
        JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        List<float[]> embeddings = new ArrayList<>();
        JsonNode input = request.get("input");
        if (input.isArray()) {
            input.forEach(n -> embeddings.add(vector(n.asString())));
        } else {
            embeddings.add(vector(input.asString()));
        }
        reply(exchange, 200, toJson(Map.of("model", "nomic-embed-text", "embeddings", embeddings, "prompt_eval_count", 1)));
    }

    private void chat(HttpExchange exchange) throws IOException {
        JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        String response;
        synchronized (this) {
            chatRequests.add(request);
            response = chatResponses.poll();
        }
        if (chatDown || response == null) {
            reply(exchange, 500, chatDown ? "boom" : "sequenza di risposte vuota");
            return;
        }
        reply(exchange, 200, response);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String toJson(Object value) {
        return JSON.writeValueAsString(value);
    }
}
