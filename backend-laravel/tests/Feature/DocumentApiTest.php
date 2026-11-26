<?php

namespace Tests\Feature;

use App\Models\Document;
use App\Models\DocumentChunk;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\UploadedFile;
use Tests\Support\FakeAi;
use Tests\Support\PdfFactory;
use Tests\TestCase;

/** Contratto di POST /api/documents e GET /api/documents. */
class DocumentApiTest extends TestCase
{
    use RefreshDatabase;

    private const PAGES = [
        'Politica rimborsi. Il taxi e rimborsabile fino a 40 euro a corsa con ricevuta.',
        'Le cene con clienti sono rimborsabili fino a 80 euro a persona.',
    ];

    public function test_carica_e_indicizza_un_pdf(): void
    {
        FakeAi::fake();

        $response = $this->postJson('/api/documents', ['file' => PdfFactory::upload(self::PAGES)]);

        $response->assertCreated()
            ->assertHeader('Content-Type', 'application/json')
            ->assertJson(['filename' => 'policy-rimborsi.pdf', 'pageCount' => 2, 'chunkCount' => 2, 'status' => 'READY', 'errorMessage' => null]);

        // Forma ESATTA del contratto: né campi in più, né in meno, nessun wrapper "data".
        $this->assertSame(
            ['chunkCount', 'createdAt', 'errorMessage', 'filename', 'id', 'pageCount', 'sizeBytes', 'status'],
            collect($response->json())->keys()->sort()->values()->all(),
        );
        $this->assertMatchesRegularExpression('/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/', $response->json('createdAt'));

        // I chunk sono nel database con pagina e vettore a 768 dimensioni.
        $chunks = DocumentChunk::query()->where('document_id', $response->json('id'))->orderBy('chunk_index')->get();
        $this->assertCount(2, $chunks);
        $this->assertSame([1, 2], $chunks->pluck('page_number')->all());
        $this->assertCount(768, $chunks[0]->embedding->toArray());
        $this->assertStringContainsString('taxi', $chunks[0]->content);
    }

    public function test_un_pdf_lungo_produce_piu_chunk_che_non_attraversano_le_pagine(): void
    {
        FakeAi::fake();
        $longPage = implode(' ', array_map(fn ($i) => "Regola numero {$i} della policy aziendale sui rimborsi.", range(1, 150)));

        $response = $this->postJson('/api/documents', ['file' => PdfFactory::upload([$longPage, 'Seconda pagina breve.'])]);

        $response->assertCreated();
        $this->assertGreaterThan(2, $response->json('chunkCount'));
        $this->assertSame(1, DocumentChunk::query()->where('page_number', 2)->count(), 'La pagina 2 ha un solo chunk');
    }

    public function test_lo_stesso_pdf_due_volte_risponde_409(): void
    {
        FakeAi::fake();
        $this->postJson('/api/documents', ['file' => PdfFactory::upload(self::PAGES)])->assertCreated();

        $this->postJson('/api/documents', ['file' => PdfFactory::upload(self::PAGES, 'altro-nome.pdf')])
            ->assertStatus(409)
            ->assertHeader('Content-Type', 'application/problem+json')
            ->assertJson(['type' => 'urn:eaa:error:duplicate-document', 'status' => 409]);

        $this->assertSame(1, Document::query()->count());
    }

    public function test_file_mancante_risponde_400_con_errors(): void
    {
        $this->postJson('/api/documents', [])
            ->assertStatus(400)
            ->assertHeader('Content-Type', 'application/problem+json')
            ->assertJson(['type' => 'urn:eaa:error:validation', 'title' => 'Bad Request', 'status' => 400])
            ->assertJsonPath('errors.0.field', 'file')
            ->assertJsonStructure(['type', 'title', 'status', 'detail', 'errors' => [['field', 'message']]]);
    }

    public function test_un_file_non_pdf_risponde_415(): void
    {
        $this->postJson('/api/documents', ['file' => PdfFactory::uploadContent('solo testo', 'note.txt')])
            ->assertStatus(415)
            ->assertJson(['type' => 'urn:eaa:error:unsupported-media-type']);
    }

    public function test_un_eseguibile_rinominato_pdf_risponde_415(): void
    {
        // Il controllo guarda il contenuto (magic bytes), non l'estensione.
        $this->postJson('/api/documents', ['file' => PdfFactory::uploadContent("MZ\x90\x00\x03\x00\x00\x00binario", 'malware.pdf')])
            ->assertStatus(415);
    }

    public function test_un_file_oltre_20_mb_risponde_413(): void
    {
        $this->postJson('/api/documents', ['file' => UploadedFile::fake()->create('enorme.pdf', 21 * 1024, 'application/pdf')])
            ->assertStatus(413)
            ->assertJson(['type' => 'urn:eaa:error:file-too-large']);
    }

    public function test_un_upload_scartato_da_php_per_upload_max_filesize_risponde_413(): void
    {
        // PHP scarta il file (error = UPLOAD_ERR_INI_SIZE, nessun contenuto): è ciò che accade in
        // produzione con un PDF tra upload_max_filesize e il limite del contratto, o oltre.
        $path = tempnam(sys_get_temp_dir(), 'eaa');
        $file = new UploadedFile($path, 'enorme.pdf', 'application/pdf', UPLOAD_ERR_INI_SIZE, true);

        $this->postJson('/api/documents', ['file' => $file])
            ->assertStatus(413)
            ->assertJson(['type' => 'urn:eaa:error:file-too-large']);
    }

    public function test_un_pdf_corrotto_risponde_422_e_resta_visibile_come_failed(): void
    {
        FakeAi::fake();

        $this->postJson('/api/documents', ['file' => PdfFactory::uploadContent("%PDF-1.4\nnon sono davvero un pdf", 'rotto.pdf')])
            ->assertStatus(422)
            ->assertJson(['type' => 'urn:eaa:error:unreadable-pdf']);

        $this->assertSame('FAILED', Document::query()->first()->status->value);
        $this->assertSame(0, DocumentChunk::query()->count());
    }

    public function test_un_pdf_senza_testo_estraibile_risponde_422(): void
    {
        FakeAi::fake();

        $this->postJson('/api/documents', ['file' => PdfFactory::upload(['', ''])])
            ->assertStatus(422)
            ->assertJson(['type' => 'urn:eaa:error:unreadable-pdf']);
    }

    public function test_se_il_provider_embedding_e_giu_risponde_502_e_si_puo_riprovare(): void
    {
        FakeAi::fakeEmbeddingDown();

        $this->postJson('/api/documents', ['file' => PdfFactory::upload(self::PAGES)])
            ->assertStatus(502)
            ->assertJson(['type' => 'urn:eaa:error:llm-unavailable']);

        $failed = Document::query()->first();
        $this->assertSame('FAILED', $failed->status->value);
        $this->assertNotNull($failed->error_message);
        $this->assertSame(0, DocumentChunk::query()->count(), 'Nessun chunk parziale: la scrittura è atomica');

        // Un documento FAILED non blocca il ricaricamento dello stesso file (niente 409).
        FakeAi::fake();
        $this->postJson('/api/documents', ['file' => PdfFactory::upload(self::PAGES)])->assertCreated();
        $this->assertSame(1, Document::query()->count());
    }

    public function test_elenca_i_documenti_dal_piu_recente(): void
    {
        FakeAi::fake();
        $this->postJson('/api/documents', ['file' => PdfFactory::upload(['Primo documento'], 'a.pdf')])->assertCreated();
        $this->postJson('/api/documents', ['file' => PdfFactory::upload(['Secondo documento'], 'b.pdf')])->assertCreated();

        $response = $this->getJson('/api/documents')->assertOk()->assertJsonStructure(['items' => [['id', 'filename', 'status']]]);

        $this->assertSame(['b.pdf', 'a.pdf'], array_column($response->json('items'), 'filename'));
    }
}
