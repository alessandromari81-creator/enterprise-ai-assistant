<?php

namespace Tests\Feature;

use App\Enums\ReimbursementStatus;
use App\Models\ReimbursementRequest;
use Illuminate\Database\QueryException;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

/** Contratto di GET /api/reimbursements e comportamento generale degli errori su /api/*. */
class ReimbursementApiTest extends TestCase
{
    use RefreshDatabase;

    private function make(string $amount, ReimbursementStatus $status = ReimbursementStatus::Open, ?string $at = null): ReimbursementRequest
    {
        return ReimbursementRequest::query()->create([
            'amount' => $amount, 'currency' => 'EUR', 'description' => "Spesa {$amount}",
            'status' => $status, 'requested_by' => 'demo-user',
            'source_conversation_id' => '5d1c1d2a-8a4b-4e3f-b0a1-77a1d2c0f001',
            'created_at' => $at ?? now(),
        ]);
    }

    public function test_elenco_vuoto(): void
    {
        $this->getJson('/api/reimbursements')->assertOk()->assertExactJson([
            'items' => [], 'page' => 0, 'size' => 20, 'totalItems' => 0, 'totalPages' => 0,
        ]);
    }

    public function test_forma_esatta_di_un_elemento(): void
    {
        $this->make('50');

        $item = $this->getJson('/api/reimbursements')->assertOk()->json('items.0');

        $this->assertSame(
            ['amount', 'conversationId', 'createdAt', 'currency', 'description', 'id', 'requestedBy', 'status'],
            collect($item)->keys()->sort()->values()->all(),
        );
        $this->assertSame('50.00', $item['amount'], 'Importo stringa con 2 decimali');
        $this->assertSame('OPEN', $item['status']);
        $this->assertSame('5d1c1d2a-8a4b-4e3f-b0a1-77a1d2c0f001', $item['conversationId']);
        $this->assertMatchesRegularExpression('/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/', $item['createdAt']);
    }

    public function test_paginazione_base_zero_e_ordine_decrescente(): void
    {
        foreach ([1, 2, 3, 4, 5] as $i) {
            $this->make((string) $i, at: now()->addSeconds($i)->toIso8601String());
        }

        $page0 = $this->getJson('/api/reimbursements?size=2&page=0')->assertOk();
        $page2 = $this->getJson('/api/reimbursements?size=2&page=2')->assertOk();

        $this->assertSame(['5.00', '4.00'], array_column($page0->json('items'), 'amount'));
        $this->assertSame(['1.00'], array_column($page2->json('items'), 'amount'));
        $page0->assertJsonPath('totalItems', 5)->assertJsonPath('totalPages', 3)->assertJsonPath('page', 0)->assertJsonPath('size', 2);
    }

    public function test_filtro_per_stato(): void
    {
        $this->make('10');
        $this->make('20', ReimbursementStatus::Approved);

        $response = $this->getJson('/api/reimbursements?status=APPROVED')->assertOk();

        $this->assertSame(['20.00'], array_column($response->json('items'), 'amount'));
        $response->assertJsonPath('totalItems', 1);
    }

    public function test_parametri_non_validi_rispondono_400(): void
    {
        foreach (['status=BOH', 'page=-1', 'size=0', 'size=101', 'page=abc'] as $query) {
            $this->getJson("/api/reimbursements?{$query}")
                ->assertStatus(400)
                ->assertHeader('Content-Type', 'application/problem+json')
                ->assertJson(['type' => 'urn:eaa:error:validation']);
        }
    }

    public function test_il_database_rifiuta_importi_fuori_limite_anche_se_il_codice_sbagliasse(): void
    {
        $this->expectException(QueryException::class);

        $this->make('10000.01');
    }

    public function test_rotta_inesistente_risponde_404_problem_json(): void
    {
        $this->getJson('/api/non-esiste')
            ->assertStatus(404)
            ->assertHeader('Content-Type', 'application/problem+json')
            ->assertJson(['type' => 'urn:eaa:error:not-found']);
    }

    public function test_metodo_non_consentito_risponde_405_problem_json(): void
    {
        $this->deleteJson('/api/reimbursements')
            ->assertStatus(405)
            ->assertHeader('Content-Type', 'application/problem+json')
            ->assertJsonPath('status', 405);
    }

    public function test_cors_consente_angular_in_sviluppo(): void
    {
        $this->options('/api/reimbursements', [], [
            'Origin' => 'http://localhost:4200',
            'Access-Control-Request-Method' => 'GET',
        ])->assertHeader('Access-Control-Allow-Origin', 'http://localhost:4200');
    }
}
