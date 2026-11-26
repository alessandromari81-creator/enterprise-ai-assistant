<?php

namespace App\Services\Chat;

use App\Enums\ChatRole;
use App\Enums\ReimbursementStatus;
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
use InvalidArgumentException;
use Prism\Prism\Exceptions\PrismException;
use Prism\Prism\Facades\Prism;
use Prism\Prism\ValueObjects\Messages\AssistantMessage;
use Prism\Prism\ValueObjects\Messages\UserMessage;

/**
 * Orchestra un turno di chat: RAG + eventuali azioni (tool calling).
 *
 *   0. frase che SEMBRA un ordine ma senza verbo esplicito -> avviso immediato, nessuna azione, nessun modello
 *   1. risolve la conversazione (nuova o esistente)
 *   2. embedding della domanda -> ricerca vettoriale (top-k, soglia di similarità)
 *   3. system prompt con estratti + storico + messaggio -> modello, con i due tool disponibili
 *   4. reti di sicurezza (ordine diretto non eseguito dal modello, conferme false, citazioni inventate)
 *   5. calcola sources[], actions[], warning e salva i due messaggi
 */
class ChatService
{
    /** Risposta onesta quando il modello dichiara un rimborso creato ma nessun rimborso è stato salvato. */
    public const NOT_CREATED = 'Non sono riuscito a registrare il rimborso: nessuna richiesta è stata creata. '
        .'Riprova indicando importo e motivo, ad esempio «Apri un rimborso da 40 € per cena».';

    /** Risposta onesta quando il modello dichiara uno stato aggiornato ma nessun rimborso è stato modificato. */
    public const NOT_UPDATED = 'Non sono riuscito ad aggiornare il rimborso: nessuno stato è stato modificato. '
        .'Riprova indicando il numero, ad esempio «Segna come eseguito il rimborso #1».';

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
     *     sources: list<array<string, mixed>>, actions: list<array<string, mixed>>,
     *     warning: ?string, createdAt: string
     * }
     *
     * @throws ApiProblem 404 conversazione sconosciuta, 502 provider LLM non raggiungibile
     */
    public function reply(string $message, ?string $conversationId, ?array $documentIds): array
    {
        $conversationId = $this->resolveConversation($conversationId);
        $userMessageAt = now();

        // --- 0. Frase senza verbo esplicito -------------------------------------------------
        // "allora un rimborso per hotel da 120 euro", "rimborso #2 eseguito": non si esegue nulla e non si
        // interpella il modello. Si risponde subito dicendo concretamente quali verbi usare (e con un avviso).
        $hint = ReimbursementIntent::verblessHint($message);
        if ($hint !== null) {
            return $this->finish($conversationId, $message, $userMessageAt, $hint['answer'], [], [], $hint['warning']);
        }

        // --- 1. Retrieval -----------------------------------------------------------------
        $chunks = $this->retriever->search(
            $this->embeddings->embed($message),
            (int) config('eaa.rag.top_k'),
            (float) config('eaa.rag.min_score'),
            $documentIds,
        );

        // --- 2. Generazione (con i due tool) ----------------------------------------------
        $history = $this->history($conversationId);
        $createTool = new ReimbursementTool($this->reimbursements, $conversationId, $this->userTexts($history, $message), $message);
        $statusTool = new ReimbursementStatusTool($this->reimbursements, $message);

        try {
            $response = Prism::text()
                ->using((string) config('eaa.ai.provider'), (string) config('eaa.ai.chat_model'))
                ->withSystemPrompt($this->prompts->system($chunks))
                ->withMessages([...$history, new UserMessage($message)])
                ->withTools([$createTool->build(), $statusTool->build()])
                ->withMaxSteps((int) config('eaa.ai.max_steps'))
                ->usingTemperature((float) config('eaa.ai.temperature'))
                ->asText();
        } catch (PrismException|HttpClientException $e) {
            throw ApiProblem::llmUnavailable('Il modello linguistico non è raggiungibile o ha risposto con un errore.', $e);
        }

        // --- 3. Esito: azioni, risposta, fonti --------------------------------------------
        $actions = array_merge($createTool->actions(), $statusTool->actions());
        $answer = trim($response->text);
        $failedAction = false;

        // Rete di sicurezza: ORDINE ESPLICITO e non ambiguo, ma il modello non ha chiamato il tool (a volte lo
        // NARRA invece di eseguirlo). Il backend esegue la stessa operazione con lo stesso servizio e le stesse
        // barriere: l'importo / il riferimento / lo stato sono per costruzione quelli scritti dall'utente.
        if ($actions === []) {
            [$actions, $answer, $failedAction] = $this->safetyNet($message, $conversationId, $answer);
        }

        // Dopo un'azione riuscita la risposta deve confermarla. I modelli piccoli sbagliano in due modi: testo
        // vuoto, oppure la frase di rifiuto "non ho trovato nei documenti". In entrambi i casi si usa una conferma
        // deterministica costruita dal codice con i dati realmente salvati.
        if ($actions !== [] && ($answer === '' || str_contains($answer, PromptBuilder::REFUSAL))) {
            $answer = ChatActions::confirmation($actions[0]);
        }

        // Conferma FALSA: la richiesta era un ordine, nulla è stato salvato, ma la risposta dice il contrario
        // (il modello ha scritto a parole una chiamata al tool con un risultato inventato).
        if ($actions === [] && ! $failedAction) {
            if (ReimbursementIntent::isActionRequest($message) && ReimbursementIntent::claimsCreation($answer)) {
                $answer = self::NOT_CREATED;
                $failedAction = true;
            } elseif (ReimbursementIntent::isStatusUpdateRequest($message) && ReimbursementIntent::claimsUpdate($answer)) {
                $answer = self::NOT_UPDATED;
                $failedAction = true;
            }
        }

        // Citazione inventata: senza estratti recuperati nessuna risposta può citare un documento.
        if ($chunks === [] && $actions === [] && preg_match('/\[[^\]]*\.pdf\s*,?\s*(?:pagina|pag\.?|p\.?)\s*\d+/iu', $answer)) {
            $answer = PromptBuilder::REFUSAL;
        }

        if ($answer === '') {
            $answer = PromptBuilder::REFUSAL;
        }

        $sources = array_map(
            fn (RetrievedChunk $c) => $this->sourceToArray($c),
            $this->sourceResolver->resolve($answer, $chunks, $actions !== [] || $failedAction),
        );

        return $this->finish($conversationId, $message, $userMessageAt, $answer, $sources, $actions, null);
    }

    /**
     * Esegue direttamente un ordine esplicito e non ambiguo che il modello non ha eseguito.
     *
     * @return array{0: list<array<string, mixed>>, 1: string, 2: bool} [azioni, risposta, azione fallita]
     */
    private function safetyNet(string $message, string $conversationId, string $answer): array
    {
        // Creare un rimborso: un solo importo scritto dall'utente.
        $create = ReimbursementIntent::parseDirectRequest($message);
        if ($create !== null) {
            try {
                $created = $this->reimbursements->create($create['amount'], $create['description'], $conversationId);
                $action = ChatActions::created($created);

                return [[$action], ChatActions::confirmation($action), false];
            } catch (InvalidArgumentException $e) {
                return [[], 'Non è stato creato nessun rimborso: '.$e->getMessage(), true];
            }
        }

        // Aggiornare lo stato: un solo stato richiesto e un riferimento scritto dall'utente.
        $update = ReimbursementIntent::parseDirectUpdate($message);
        if ($update !== null) {
            try {
                $updated = $this->reimbursements->updateStatus($update['number'], $update['keyword'], ReimbursementStatus::from($update['status']));
                $action = ChatActions::updated($updated);

                return [[$action], ChatActions::confirmation($action), false];
            } catch (InvalidArgumentException $e) {
                return [[], 'Non è stato aggiornato nessun rimborso: '.$e->getMessage(), true];
            }
        }

        return [[], $answer, false];
    }

    /**
     * Salva i due messaggi (solo a esito positivo: se l'LLM fallisce non si salvano mezze conversazioni) e
     * compone la risposta del contratto.
     *
     * @param  list<array<string, mixed>>  $sources
     * @param  list<array<string, mixed>>  $actions
     * @return array<string, mixed>
     */
    private function finish(string $conversationId, string $message, $userMessageAt, string $answer, array $sources, array $actions, ?string $warning): array
    {
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
            'warning' => $warning,
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
     * I soli messaggi scritti dall'UTENTE (storico + corrente): servono al tool di creazione per verificare che
     * l'importo non sia stato inventato dal modello.
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
