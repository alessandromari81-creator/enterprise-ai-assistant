<?php

namespace App\Services\Chat;

use App\Enums\ChatRole;
use App\Exceptions\ApiProblem;
use App\Models\ChatMessage;
use App\Services\Rag\ChunkRetriever;
use App\Services\Rag\EmbeddingService;
use App\Services\Rag\RetrievedChunk;
use App\Services\ReimbursementService;
use App\Support\Iso;
use Illuminate\Http\Client\HttpClientException;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;
use Prism\Prism\Exceptions\PrismException;
use Prism\Prism\Facades\Prism;
use Prism\Prism\ValueObjects\Messages\AssistantMessage;
use Prism\Prism\ValueObjects\Messages\UserMessage;

/**
 * Orchestra un turno di chat: RAG + eventuale azione (tool calling).
 *
 *   1. risolve la conversazione (nuova o esistente)
 *   2. embedding della domanda -> ricerca vettoriale (top-k, soglia di similarità)
 *   3. system prompt con estratti + storico + messaggio -> modello, con il tool disponibile
 *   4. calcola sources[] e actions[] e salva i due messaggi
 */
class ChatService
{
    public function __construct(
        private readonly EmbeddingService $embeddings,
        private readonly ChunkRetriever $retriever,
        private readonly PromptBuilder $prompts,
        private readonly SourceResolver $sourceResolver,
        private readonly ReimbursementService $reimbursements,
    ) {}

    /**
     * @param  list<string>|null  $documentIds
     * @return array{
     *     conversationId: string, messageId: string, answer: string,
     *     sources: list<array<string, mixed>>, actions: list<array<string, mixed>>, createdAt: string
     * }
     *
     * @throws ApiProblem 404 conversazione sconosciuta, 502 provider LLM non raggiungibile
     */
    public function reply(string $message, ?string $conversationId, ?array $documentIds): array
    {
        $conversationId = $this->resolveConversation($conversationId);
        $userMessageAt = now();

        // --- 1. Retrieval -----------------------------------------------------------------
        $chunks = $this->retriever->search(
            $this->embeddings->embed($message),
            (int) config('eaa.rag.top_k'),
            (float) config('eaa.rag.min_score'),
            $documentIds,
        );

        // --- 2. Generazione (con tool) ----------------------------------------------------
        $history = $this->history($conversationId);
        $tool = new ReimbursementTool($this->reimbursements, $conversationId, $this->userTexts($history, $message), $message);

        try {
            $response = Prism::text()
                ->using((string) config('eaa.ai.provider'), (string) config('eaa.ai.chat_model'))
                ->withSystemPrompt($this->prompts->system($chunks))
                ->withMessages([...$history, new UserMessage($message)])
                ->withTools([$tool->build()])
                ->withMaxSteps((int) config('eaa.ai.max_steps'))
                ->usingTemperature((float) config('eaa.ai.temperature'))
                ->asText();
        } catch (PrismException|HttpClientException $e) {
            throw ApiProblem::llmUnavailable('Il modello linguistico non è raggiungibile o ha risposto con un errore.', $e);
        }

        // --- 3. Esito: azioni, risposta, fonti --------------------------------------------
        $actions = $tool->actions();
        $answer = trim($response->text);

        // Dopo un'azione riuscita la risposta deve confermarla. I modelli piccoli sbagliano in due modi
        // (visti dal vivo con llama3.2): testo vuoto, oppure la frase di rifiuto "non ho trovato nei
        // documenti" perché gli estratti non parlano di rimborsi. In entrambi i casi si usa una
        // conferma deterministica costruita dal codice con i dati realmente salvati.
        if ($actions !== [] && ($answer === '' || str_contains($answer, PromptBuilder::REFUSAL))) {
            $answer = $this->confirmation($actions[0]);
        }
        // Citazione inventata: senza estratti recuperati nessuna risposta può citare un documento.
        // Visto dal vivo con qwen2.5:7b: contesto vuoto -> "[prenotazione_aeroporto.pdf, p. 2]", un
        // file mai caricato. Meglio il rifiuto onesto di una fonte falsa.
        if ($chunks === [] && $actions === [] && preg_match('/\[[^\]]*\.pdf\s*,?\s*(?:pagina|pag\.?|p\.?)\s*\d+/iu', $answer)) {
            $answer = PromptBuilder::REFUSAL;
        }

        if ($answer === '') {
            $answer = PromptBuilder::REFUSAL;
        }

        $sources = array_map(
            fn (RetrievedChunk $c) => $this->sourceToArray($c),
            $this->sourceResolver->resolve($answer, $chunks, $actions !== []),
        );

        // --- 4. Persistenza (solo a esito positivo: se l'LLM fallisce non salviamo mezze conversazioni)
        $assistant = DB::transaction(function () use ($conversationId, $message, $userMessageAt, $answer, $sources, $actions) {
            ChatMessage::query()->create([
                'conversation_id' => $conversationId,
                'role' => ChatRole::User,
                'content' => $message,
                'created_at' => $userMessageAt,
            ]);

            return ChatMessage::query()->create([
                'conversation_id' => $conversationId,
                'role' => ChatRole::Assistant,
                'content' => $answer,
                'sources' => $sources,
                'actions' => $actions,
                'created_at' => now(),
            ]);
        });

        return [
            'conversationId' => $conversationId,
            'messageId' => $assistant->id,
            'answer' => $answer,
            'sources' => $sources,
            'actions' => $actions,
            'createdAt' => Iso::utc($assistant->created_at),
        ];
    }

    /** Nuova conversazione se assente; 404 se l'id non corrisponde a nessuna chat esistente. */
    private function resolveConversation(?string $conversationId): string
    {
        if ($conversationId === null) {
            return (string) Str::uuid();
        }

        if (! ChatMessage::query()->where('conversation_id', $conversationId)->exists()) {
            throw ApiProblem::notFound('Conversazione non trovata.');
        }

        return $conversationId;
    }

    /**
     * Ultimi N messaggi della conversazione, dal più vecchio al più recente.
     *
     * @return list<UserMessage|AssistantMessage>
     */
    private function history(string $conversationId): array
    {
        return ChatMessage::query()
            ->where('conversation_id', $conversationId)
            ->orderByDesc('created_at')
            ->orderByDesc('id')
            ->limit((int) config('eaa.rag.history_messages'))
            ->get()
            ->reverse()
            ->map(fn (ChatMessage $m) => $m->role === ChatRole::User
                ? new UserMessage($m->content)
                : new AssistantMessage($m->content))
            ->values()
            ->all();
    }

    /**
     * Conferma standard di una richiesta di rimborso creata.
     *
     * @param  array{amount: string, currency: string, description: ?string, reimbursementId: string}  $action
     */
    private function confirmation(array $action): string
    {
        return sprintf(
            'Ho aperto la richiesta di rimborso di %s %s%s. Identificativo: %s. Resta in stato OPEN in attesa di approvazione.',
            number_format((float) $action['amount'], 2, ',', '.'),
            $action['currency'],
            $action['description'] !== null ? " ({$action['description']})" : '',
            $action['reimbursementId'],
        );
    }

    /**
     * I soli messaggi scritti dall'UTENTE (storico + corrente): servono al tool dei rimborsi per
     * verificare che l'importo non sia stato inventato dal modello.
     *
     * @param  list<UserMessage|AssistantMessage>  $history
     * @return list<string>
     */
    private function userTexts(array $history, string $message): array
    {
        $texts = [];
        foreach ($history as $m) {
            if ($m instanceof UserMessage) {
                $texts[] = $m->content;
            }
        }
        $texts[] = $message;

        return $texts;
    }

    /** @return array<string, mixed> forma di Source nel contratto */
    private function sourceToArray(RetrievedChunk $chunk): array
    {
        $max = (int) config('eaa.rag.snippet_length');
        $snippet = preg_replace('/\s+/u', ' ', $chunk->content) ?? $chunk->content;
        if (mb_strlen($snippet) > $max) {
            $snippet = rtrim(mb_substr($snippet, 0, $max - 1)).'…';
        }

        return [
            'chunkId' => $chunk->chunkId,
            'documentId' => $chunk->documentId,
            'filename' => $chunk->filename,
            'page' => $chunk->page,
            'score' => $chunk->score,
            'snippet' => $snippet,
        ];
    }
}
