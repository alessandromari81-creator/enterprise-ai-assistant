<?php

namespace Tests\Feature;

use App\Models\ChatMessage;
use App\Models\ReimbursementRequest;
use App\Services\Chat\ChatService;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\Client\Request;
use Illuminate\Support\Facades\Http;
use Tests\Support\FakeAi;
use Tests\Support\PdfFactory;
use Tests\TestCase;

/** Contratto di POST /api/chat: RAG con fonti, tool calling, errori. */
class ChatApiTest extends TestCase
{
    use RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();

        // Gli embedding finti hanno similarità più bassa di quelli reali: soglia ridotta SOLO nei test.
        config(['eaa.rag.min_score' => 0.2]);
    }

    /** Indicizza una policy (con embedding finti) e poi prepara le risposte della chat. */
    private function seedPolicy(array $chatResponses): void
    {
        FakeAi::fake();
        $this->postJson('/api/documents', ['file' => PdfFactory::upload([
            'Introduzione alla politica aziendale sulle trasferte.',
            'Il taxi e rimborsabile fino a 40 euro a corsa. Serve la ricevuta.',
        ], 'policy.pdf')])->assertCreated();

        FakeAi::fake($chatResponses);
    }

    public function test_risposta_rag_con_citazione_e_fonti(): void
    {
        $this->seedPolicy([FakeAi::chatText('Il limite per il taxi è di 40 euro a corsa [policy.pdf, p. 2].')]);

        $response = $this->postJson('/api/chat', ['message' => 'Qual è il limite per il taxi?'])->assertOk();

        $response->assertJsonStructure([
            'conversationId', 'messageId', 'answer', 'createdAt',
            'sources' => [['chunkId', 'documentId', 'filename', 'page', 'score', 'snippet']],
            'actions',
        ]);
        $this->assertSame([], $response->json('actions'));
        $this->assertSame(1, count($response->json('sources')));
        $this->assertSame('policy.pdf', $response->json('sources.0.filename'));
        $this->assertSame(2, $response->json('sources.0.page'));
        $this->assertGreaterThan(0.2, $response->json('sources.0.score'));
        $this->assertLessThanOrEqual(1, $response->json('sources.0.score'));
        $this->assertStringContainsString('taxi', $response->json('sources.0.snippet'));

        // Il prompt inviato al modello contiene gli estratti con file e pagina e le regole anti-allucinazione.
        Http::assertSent(function (Request $request) {
            if (! str_ends_with($request->url(), '/api/chat')) {
                return false;
            }
            $system = $request->data()['messages'][0]['content'];

            return str_contains($system, 'file: policy.pdf, pagina: 2')
                && str_contains($system, 'ESCLUSIVAMENTE')
                && str_contains($system, 'Il taxi e rimborsabile');
        });
    }

    public function test_senza_estratti_pertinenti_il_modello_riceve_contesto_vuoto_e_non_ci_sono_fonti(): void
    {
        $this->seedPolicy([FakeAi::chatText('Non ho trovato questa informazione nei documenti caricati.')]);
        config(['eaa.rag.min_score' => 0.99]); // nessun chunk può raggiungere questa soglia

        $response = $this->postJson('/api/chat', ['message' => 'Quanto costa un volo per la luna?'])->assertOk();

        $this->assertSame([], $response->json('sources'));
        $this->assertStringContainsString('Non ho trovato', $response->json('answer'));
        Http::assertSent(fn (Request $r) => str_ends_with($r->url(), '/api/chat')
            && str_contains($r->data()['messages'][0]['content'], '(nessun estratto pertinente trovato)'));
    }

    public function test_una_citazione_senza_estratti_recuperati_e_inventata_e_viene_sostituita_dal_rifiuto(): void
    {
        $this->seedPolicy([FakeAi::chatText('Il limite è 120 euro [prenotazione_aeroporto.pdf, p. 2].')]);
        config(['eaa.rag.min_score' => 0.99]); // nessun chunk recuperato

        $response = $this->postJson('/api/chat', ['message' => 'Quanto costa l\'hotel?'])->assertOk();

        $this->assertSame('Non ho trovato questa informazione nei documenti caricati.', $response->json('answer'));
        $this->assertSame([], $response->json('sources'));
    }

    public function test_il_modello_apre_una_richiesta_di_rimborso_tramite_tool(): void
    {
        $this->seedPolicy([
            FakeAi::chatToolCall('createReimbursementRequest', ['amount' => 50, 'description' => 'Cena col cliente']),
            FakeAi::chatText('Ho aperto la richiesta di rimborso di 50,00 €.'),
        ]);

        $response = $this->postJson('/api/chat', ['message' => 'Apri una richiesta di rimborso per 50€ per la cena col cliente'])->assertOk();

        $action = $response->json('actions.0');
        $this->assertSame('REIMBURSEMENT_CREATED', $action['type']);
        $this->assertSame('50.00', $action['amount']);
        $this->assertSame('EUR', $action['currency']);
        $this->assertSame('Cena col cliente', $action['description']);
        $this->assertSame([], $response->json('sources'), 'Un\'azione non ha fonti');

        // Il record è davvero nel database, collegato alla conversazione.
        $row = ReimbursementRequest::query()->findOrFail($action['reimbursementId']);
        $this->assertSame('50.00', $row->amount);
        $this->assertSame('OPEN', $row->status->value);
        $this->assertSame($response->json('conversationId'), $row->source_conversation_id);

        // Il modello ha ricevuto l'esito del tool nella seconda chiamata.
        Http::assertSent(fn (Request $r) => str_ends_with($r->url(), '/api/chat')
            && collect($r->data()['messages'])->contains(fn ($m) => ($m['role'] ?? '') === 'tool' && str_contains($m['content'], 'OK: richiesta di rimborso creata')));
    }

    public function test_un_importo_non_valido_scelto_dal_modello_non_crea_nulla(): void
    {
        $this->seedPolicy([
            FakeAi::chatToolCall('createReimbursementRequest', ['amount' => -5]),
            FakeAi::chatText('L\'importo non è valido: indicami una cifra maggiore di zero.'),
        ]);

        $response = $this->postJson('/api/chat', ['message' => 'Apri un rimborso per meno 5 euro'])->assertOk();

        $this->assertSame([], $response->json('actions'));
        $this->assertSame(0, ReimbursementRequest::query()->count());
        Http::assertSent(fn (Request $r) => str_ends_with($r->url(), '/api/chat')
            && collect($r->data()['messages'])->contains(fn ($m) => ($m['role'] ?? '') === 'tool' && str_contains($m['content'], 'ERRORE')));
    }

    public function test_una_domanda_non_e_un_ordine_il_tool_non_viene_eseguito(): void
    {
        // Caso reale con llama3.2: "Posso farmi rimborsare 200€ per una cena?" ha creato un rimborso da 200 €.
        // L'importo c'è (l'ha scritto l'utente) ma il messaggio non CHIEDE di aprire nulla: è una domanda.
        $this->seedPolicy([
            FakeAi::chatToolCall('createReimbursementRequest', ['amount' => 200, 'description' => 'cena']),
            FakeAi::chatText('Il limite per una cena è di 40 euro [policy.pdf, p. 2].'),
        ]);

        $response = $this->postJson('/api/chat', ['message' => 'Posso farmi rimborsare 200€ per una cena?'])->assertOk();

        $this->assertSame([], $response->json('actions'));
        $this->assertSame(0, ReimbursementRequest::query()->count());
        Http::assertSent(fn (Request $r) => str_ends_with($r->url(), '/api/chat')
            && collect($r->data()['messages'])->contains(fn ($m) => ($m['role'] ?? '') === 'tool' && str_contains($m['content'], 'è una domanda')));
    }

    public function test_le_richieste_cortesi_con_verbo_di_apertura_sono_accettate(): void
    {
        $this->seedPolicy([]);

        foreach (['Puoi aprirmi un rimborso da 40€?', 'Vorrei creare una richiesta di rimborso da 40 euro', 'Registra un rimborso da 40€'] as $message) {
            FakeAi::fake([
                FakeAi::chatToolCall('createReimbursementRequest', ['amount' => 40]),
                FakeAi::chatText('Fatto.'),
            ]);

            $this->postJson('/api/chat', ['message' => $message])->assertOk()->assertJsonPath('actions.0.amount', '40.00');
        }
    }

    public function test_un_importo_inventato_dal_modello_non_crea_nulla(): void
    {
        // Caso reale osservato con llama3.2: a una semplice domanda sul limite delle cene il modello
        // ha chiamato il tool con "50", cifra presa dai documenti e mai scritta dall'utente.
        $this->seedPolicy([
            FakeAi::chatToolCall('createReimbursementRequest', ['amount' => 50, 'description' => 'cena']),
            FakeAi::chatText('Il limite per una cena è di 50 euro [policy.pdf, p. 2].'),
        ]);

        $response = $this->postJson('/api/chat', ['message' => 'Apri un rimborso per la cena di ieri'])->assertOk();

        $this->assertSame([], $response->json('actions'));
        $this->assertSame(0, ReimbursementRequest::query()->count(), 'Nessun rimborso senza importo scritto dall\'utente');
        Http::assertSent(fn (Request $r) => str_ends_with($r->url(), '/api/chat')
            && collect($r->data()['messages'])->contains(fn ($m) => ($m['role'] ?? '') === 'tool' && str_contains($m['content'], 'non compare nei messaggi')));
    }

    public function test_dopo_un_azione_riuscita_il_rifiuto_del_modello_viene_sostituito_dalla_conferma(): void
    {
        // Caso reale con llama3.2: tool eseguito correttamente, ma testo finale = "non ho trovato nei documenti".
        $this->seedPolicy([
            FakeAi::chatToolCall('createReimbursementRequest', ['amount' => 40, 'description' => 'cena']),
            FakeAi::chatText('Non ho trovato questa informazione nei documenti caricati.'),
        ]);

        $response = $this->postJson('/api/chat', ['message' => 'Aprimi un rimborso da 40€ per cena'])->assertOk();

        $id = $response->json('actions.0.reimbursementId');
        $this->assertStringNotContainsString('Non ho trovato', $response->json('answer'));
        $this->assertStringContainsString('40,00 EUR', $response->json('answer'));
        $this->assertStringContainsString($id, $response->json('answer'));
        $this->assertSame([], $response->json('sources'));
    }

    public function test_l_importo_scritto_in_un_messaggio_precedente_dell_utente_e_valido(): void
    {
        $this->seedPolicy([
            FakeAi::chatText('Quale importo vuoi rimborsare?'),
            FakeAi::chatToolCall('createReimbursementRequest', ['amount' => 40]),
            FakeAi::chatText('Fatto.'),
        ]);

        $first = $this->postJson('/api/chat', ['message' => 'Ho speso 40 euro per una cena, vorrei un rimborso'])->assertOk();
        $this->postJson('/api/chat', ['message' => 'Sì, aprilo pure', 'conversationId' => $first->json('conversationId')])
            ->assertOk()
            ->assertJsonPath('actions.0.amount', '40.00');
    }

    public function test_un_importo_oltre_il_tetto_non_crea_nulla(): void
    {
        $this->seedPolicy([
            FakeAi::chatToolCall('createReimbursementRequest', ['amount' => 10000.01]),
            FakeAi::chatText('Supera il massimo consentito.'),
        ]);

        $this->postJson('/api/chat', ['message' => 'Apri un rimborso da 10000,01 euro'])->assertOk()->assertJsonPath('actions', []);
        $this->assertSame(0, ReimbursementRequest::query()->count());
    }

    public function test_importo_con_virgola_decimale_viene_normalizzato(): void
    {
        $this->seedPolicy([
            FakeAi::chatToolCall('createReimbursementRequest', ['amount' => '12,5 €']),
            FakeAi::chatText('Fatto.'),
        ]);

        $this->postJson('/api/chat', ['message' => 'Apri un rimborso da 12,5 euro'])->assertOk()->assertJsonPath('actions.0.amount', '12.50');
    }

    public function test_i_sinonimi_naturali_di_creazione_aprono_il_rimborso(): void
    {
        // Frasi reali segnalate dall'utente: prima il modello rispondeva "non ho trovato nei documenti".
        $this->seedPolicy([]);

        foreach ([
            ['aggiungi un rimborso 20 euro per taxi', 20],
            ['allora un rimborso per hotel da 120 euro', 120],
            ['aggiungi un rimborso 20 euro per hotel', 20],
        ] as [$message, $amount]) {
            FakeAi::fake([
                FakeAi::chatToolCall('createReimbursementRequest', ['amount' => $amount]),
                FakeAi::chatText('Fatto.'),
            ]);

            $this->postJson('/api/chat', ['message' => $message])
                ->assertOk()
                ->assertJsonPath('actions.0.amount', number_format($amount, 2, '.', ''));
        }

        $this->assertSame(3, ReimbursementRequest::query()->count());
    }

    public function test_il_prompt_ammette_tutti_i_verbi_naturali_e_la_forma_breve(): void
    {
        $this->seedPolicy([FakeAi::chatText('ok')]);

        $this->postJson('/api/chat', ['message' => 'aggiungi un rimborso 20 euro per taxi'])->assertOk();

        Http::assertSent(function (Request $r) {
            if (! str_ends_with($r->url(), '/api/chat')) {
                return false;
            }
            $system = $r->data()['messages'][0]['content'];

            return str_contains($system, 'aggiungi') && str_contains($system, 'anche senza verbo')
                && str_contains($system, 'non rispondere MAI con questa frase');
        });
    }

    public function test_una_conferma_falsa_del_modello_viene_sostituita_da_un_messaggio_onesto(): void
    {
        // Caso reale: il modello NON chiama il tool ma scrive a parole la chiamata e un risultato inventato.
        $this->seedPolicy([FakeAi::chatText(
            'Creo una richiesta di rimborso per hotel da 120 euro. Lo strumento ha risposto: {"reimbursement_request_id": "RR123456", "amount": 120.0} '
            .'Il tuo rimborso è stato registrato con successo.'
        )]);

        $response = $this->postJson('/api/chat', ['message' => 'Apri un rimborso per la cena di ieri'])->assertOk();

        $this->assertSame(ChatService::NOT_CREATED, $response->json('answer'));
        $this->assertSame([], $response->json('actions'));
        $this->assertSame([], $response->json('sources'));
        $this->assertSame(0, ReimbursementRequest::query()->count());
    }

    public function test_se_il_modello_chiede_l_importo_mancante_la_risposta_resta_intatta(): void
    {
        $this->seedPolicy([FakeAi::chatText('Quale importo vuoi rimborsare per la cena di ieri?')]);

        $this->postJson('/api/chat', ['message' => 'Apri un rimborso per la cena di ieri'])
            ->assertOk()
            ->assertJsonPath('answer', 'Quale importo vuoi rimborsare per la cena di ieri?');
    }

    public function test_se_il_modello_non_chiama_il_tool_un_ordine_non_ambiguo_viene_eseguito_dal_backend(): void
    {
        // "allora un rimborso per hotel da 120 euro": il modello NARRA la chiamata invece di farla. Il backend crea
        // il rimborso con lo stesso servizio e le stesse barriere (importo scritto dall'utente, tetto, stato OPEN).
        $this->seedPolicy([FakeAi::chatText('Creo una richiesta di rimborso per hotel da 120 euro. Lo strumento ha risposto: {"id": "RR123456"}')]);

        $response = $this->postJson('/api/chat', ['message' => 'allora un rimborso per hotel da 120 euro'])->assertOk();

        $action = $response->json('actions.0');
        $this->assertSame('120.00', $action['amount']);
        $this->assertSame('hotel', $action['description']);
        $this->assertStringContainsString('120,00 EUR', $response->json('answer'));
        $this->assertStringNotContainsString('RR123456', $response->json('answer'));
        $row = ReimbursementRequest::query()->findOrFail($action['reimbursementId']);
        $this->assertSame('OPEN', $row->status->value);
        $this->assertSame($response->json('conversationId'), $row->source_conversation_id);
        $this->assertSame([], $response->json('sources'));
    }

    public function test_il_fallback_rispetta_il_tetto_e_spiega_perche_non_ha_creato_nulla(): void
    {
        $this->seedPolicy([FakeAi::chatText('Fatto.')]);

        $response = $this->postJson('/api/chat', ['message' => 'Apri un rimborso da 20000 euro'])->assertOk();

        $this->assertSame([], $response->json('actions'));
        $this->assertStringContainsString('supera il massimo consentito', $response->json('answer'));
        $this->assertSame(0, ReimbursementRequest::query()->count());
    }

    public function test_il_fallback_non_scatta_per_domande_ipotesi_o_importi_negativi(): void
    {
        $this->seedPolicy([]);

        foreach (['Posso farmi rimborsare 200€ per una cena?', 'Apri un rimborso per meno 5 euro', 'Qual è il limite per l\'hotel a notte?'] as $message) {
            FakeAi::fake([FakeAi::chatText('Ok.')]);

            $this->postJson('/api/chat', ['message' => $message])->assertOk()->assertJsonPath('actions', []);
        }

        $this->assertSame(0, ReimbursementRequest::query()->count());
    }

    public function test_la_conversazione_continua_e_lo_storico_arriva_al_modello(): void
    {
        $this->seedPolicy([FakeAi::chatText('Prima risposta [policy.pdf, p. 2].'), FakeAi::chatText('Seconda risposta.')]);

        $first = $this->postJson('/api/chat', ['message' => 'Limite taxi?'])->assertOk();
        $conversationId = $first->json('conversationId');

        $second = $this->postJson('/api/chat', ['message' => 'E per le cene?', 'conversationId' => $conversationId])->assertOk();

        $this->assertSame($conversationId, $second->json('conversationId'));
        $this->assertSame(4, ChatMessage::query()->where('conversation_id', $conversationId)->count());

        Http::assertSent(function (Request $r) {
            if (! str_ends_with($r->url(), '/api/chat')) {
                return false;
            }
            $contents = array_column($r->data()['messages'], 'content');

            return in_array('Limite taxi?', $contents, true) && in_array('E per le cene?', $contents, true);
        });
    }

    public function test_conversazione_sconosciuta_risponde_404(): void
    {
        FakeAi::fake([FakeAi::chatText('ok')]);

        $this->postJson('/api/chat', ['message' => 'ciao', 'conversationId' => '5d1c1d2a-8a4b-4e3f-b0a1-77a1d2c0f001'])
            ->assertStatus(404)
            ->assertHeader('Content-Type', 'application/problem+json')
            ->assertJson(['type' => 'urn:eaa:error:not-found', 'status' => 404]);
    }

    public function test_validazione_della_richiesta_risponde_400(): void
    {
        FakeAi::fake();

        foreach ([
            [[], 'message'],
            [['message' => ''], 'message'],
            [['message' => '   '], 'message'],
            [['message' => str_repeat('a', 4001)], 'message'],
            [['message' => 'ciao', 'conversationId' => 'non-un-uuid'], 'conversationId'],
            [['message' => 'ciao', 'documentIds' => ['non-un-uuid']], 'documentIds.0'],
        ] as [$payload, $field]) {
            $this->postJson('/api/chat', $payload)
                ->assertStatus(400)
                ->assertJson(['type' => 'urn:eaa:error:validation'])
                ->assertJsonPath('errors.0.field', $field);
        }
    }

    public function test_se_il_modello_e_irraggiungibile_risponde_502_e_non_salva_nulla(): void
    {
        FakeAi::fakeChatDown();

        $this->postJson('/api/chat', ['message' => 'ciao'])
            ->assertStatus(502)
            ->assertJson(['type' => 'urn:eaa:error:llm-unavailable']);

        $this->assertSame(0, ChatMessage::query()->count());
    }

    public function test_documentids_restringe_il_retrieval(): void
    {
        $this->seedPolicy([FakeAi::chatText('Non ho trovato questa informazione nei documenti caricati.')]);

        // Un id valido ma di un documento inesistente: nessun chunk eleggibile, contesto vuoto.
        $this->postJson('/api/chat', [
            'message' => 'Limite taxi?',
            'documentIds' => ['0b8f3c1e-6a53-4f0e-9d0c-2f6f1a7c9e11'],
        ])->assertOk()->assertJsonPath('sources', []);
    }
}
