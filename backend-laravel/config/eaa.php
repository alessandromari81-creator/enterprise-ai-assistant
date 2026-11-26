<?php

/*
|--------------------------------------------------------------------------
| Configurazione applicativa di Enterprise AI Assistant
|--------------------------------------------------------------------------
| Tutti i parametri RAG sono volutamente identici a quelli del backend Spring
| Boot (decisione D8 in docs/PROJECT_LOG.md): le risposte dei due stack devono
| essere confrontabili.
*/

return [

    'ai' => [
        // Provider gestito da Prism: "ollama" (locale) oppure "openai".
        'provider' => env('AI_PROVIDER', 'ollama'),

        'chat_model' => env('AI_CHAT_MODEL', 'llama3.2'),

        'embedding_model' => env('AI_EMBEDDING_MODEL', 'nomic-embed-text'),

        // DEVE coincidere con VECTOR(n) nello schema SQL. Con OpenAI viene
        // richiesta esplicitamente al provider (parametro "dimensions").
        'embedding_dimensions' => (int) env('AI_EMBEDDING_DIMENSIONS', 768),

        // Quanti chunk inviare al provider in una sola chiamata di embedding.
        'embedding_batch_size' => (int) env('AI_EMBEDDING_BATCH_SIZE', 32),

        // Bassa: vogliamo risposte fedeli ai documenti, non creative.
        'temperature' => (float) env('AI_TEMPERATURE', 0.1),

        // Giri massimi modello -> tool -> modello in una singola richiesta.
        'max_steps' => 3,
    ],

    'rag' => [
        // Dimensione dei chunk espressa in token. Non esiste un tokenizer
        // nativo in PHP: i token sono stimati con chars_per_token.
        'chunk_size_tokens' => (int) env('RAG_CHUNK_SIZE_TOKENS', 500),
        'chunk_overlap_tokens' => (int) env('RAG_CHUNK_OVERLAP_TOKENS', 50),
        'chars_per_token' => 4,

        // Retrieval: i k chunk più simili con similarità coseno >= min_score.
        'top_k' => (int) env('RAG_TOP_K', 5),
        'min_score' => (float) env('RAG_MIN_SCORE', 0.62),

        // Quanti messaggi precedenti della conversazione rimandare al modello.
        'history_messages' => 10,

        // Lunghezza massima dello snippet restituito in sources[].
        'snippet_length' => 300,
    ],

    'upload' => [
        'max_bytes' => 20 * 1024 * 1024, // 20 MB, come da contratto
    ],

    'reimbursement' => [
        'max_amount' => 10000, // coerente con il CHECK nello schema SQL
        'currency' => 'EUR',
        'default_user' => 'demo-user',
    ],

];
