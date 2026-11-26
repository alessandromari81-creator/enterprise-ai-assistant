import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideTestBackend } from '../../testing/test-backend';
import { ChatResponse } from '../api/models';
import { ChatStore } from './chat.store';
import { ReimbursementsStore } from './reimbursements.store';

const BASE = 'http://api.test';

function response(overrides: Partial<ChatResponse> = {}): ChatResponse {
  return {
    conversationId: 'c1',
    messageId: 'm1',
    answer: 'Il limite è 50€ [policy.pdf, p. 2].',
    sources: [
      {
        chunkId: 'k1',
        documentId: 'd1',
        filename: 'policy.pdf',
        page: 2,
        score: 0.84,
        snippet: 'cena 50€',
      },
    ],
    actions: [],
    warning: null,
    createdAt: '2026-11-25T14:17:11Z',
    ...overrides,
  };
}

describe('ChatStore', () => {
  let store: ChatStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        ...provideTestBackend(),
      ],
    });
    store = TestBed.inject(ChatStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('invia il messaggio, mostra la risposta con le fonti e ricorda la conversazione', async () => {
    const promise = store.send('  Qual è il limite per una cena?  ');

    // L'indicatore di caricamento è attivo mentre si attende la risposta.
    expect(store.sending()).toBe(true);
    const request = http.expectOne(`${BASE}/chat`);
    expect(request.request.body).toEqual({
      message: 'Qual è il limite per una cena?',
      conversationId: null,
    });
    request.flush(response());
    await promise;

    expect(store.sending()).toBe(false);
    expect(store.conversationId()).toBe('c1');
    const [user, assistant] = store.messages();
    expect(user).toMatchObject({ role: 'user', text: 'Qual è il limite per una cena?' });
    expect(assistant.role).toBe('assistant');
    expect(assistant.sources[0]).toMatchObject({ filename: 'policy.pdf', page: 2 });
  });

  it('i messaggi successivi riusano il conversationId', async () => {
    let promise = store.send('uno');
    http.expectOne(`${BASE}/chat`).flush(response());
    await promise;

    promise = store.send('due');
    const request = http.expectOne(`${BASE}/chat`);
    expect(request.request.body.conversationId).toBe('c1');
    request.flush(response());
    await promise;
  });

  it('quando l\'IA esegue un\'azione ricarica la tabella dei rimborsi e evidenzia la nuova riga', async () => {
    const reimbursements = TestBed.inject(ReimbursementsStore);

    const promise = store.send('Aprimi un rimborso da 40€ per cena');
    http.expectOne(`${BASE}/chat`).flush(
      response({
        answer: 'Ho aperto la richiesta.',
        sources: [],
        actions: [
          {
            type: 'REIMBURSEMENT_CREATED',
            reimbursementId: 'r1',
            number: 1,
            amount: '40.00',
            currency: 'EUR',
            description: 'cena',
            status: 'OPEN',
          },
        ],
      }),
    );

    // Subito dopo la risposta parte il GET dei rimborsi (collegamento chat -> tabella).
    await vi.waitFor(() => http.expectOne(`${BASE}/reimbursements?page=0&size=50`).flush({
      items: [
        {
          id: 'r1', number: 1, amount: '40.00', currency: 'EUR', description: 'cena', status: 'OPEN',
          requestedBy: 'demo-user', conversationId: 'c1', createdAt: '2026-11-25T14:17:11Z',
        },
      ],
      page: 0, size: 50, totalItems: 1, totalPages: 1,
    }));
    await promise;

    expect(reimbursements.items().map((r) => r.id)).toEqual(['r1']);
    expect(reimbursements.highlightedIds().has('r1')).toBe(true);
    expect(store.messages()[1].actions).toHaveLength(1);
  });

  it('una risposta senza azioni NON ricarica i rimborsi', async () => {
    const promise = store.send('domanda');
    http.expectOne(`${BASE}/chat`).flush(response());
    await promise;

    http.expectNone(`${BASE}/reimbursements?page=0&size=50`);
  });

  it('mostra l\'errore del backend come messaggio dell\'assistente e sblocca l\'invio', async () => {
    const promise = store.send('ciao');
    http.expectOne(`${BASE}/chat`).flush(
      {
        type: 'urn:eaa:error:llm-unavailable',
        title: 'Bad Gateway',
        status: 502,
        detail: 'Il modello linguistico non è raggiungibile o ha risposto con un errore.',
      },
      { status: 502, statusText: 'Bad Gateway' },
    );
    await promise;

    const last = store.messages().at(-1)!;
    expect(last.error).toBe(true);
    expect(last.text).toContain('non è raggiungibile');
    expect(store.sending()).toBe(false);
  });

  it('se la conversazione non esiste più (404) riparte da una nuova', async () => {
    let promise = store.send('uno');
    http.expectOne(`${BASE}/chat`).flush(response());
    await promise;
    expect(store.conversationId()).toBe('c1');

    promise = store.send('due');
    http.expectOne(`${BASE}/chat`).flush(
      { type: 'urn:eaa:error:not-found', title: 'Not Found', status: 404, detail: 'Conversazione non trovata.' },
      { status: 404, statusText: 'Not Found' },
    );
    await promise;

    expect(store.conversationId()).toBeNull();
    expect(store.messages().at(-1)!.text).toContain('Ne inizio una nuova');
  });

  it('ignora messaggi vuoti e un secondo invio mentre si attende la risposta', async () => {
    await store.send('   ');
    http.expectNone(`${BASE}/chat`);

    const promise = store.send('primo');
    await store.send('secondo'); // ignorato: c'è già una richiesta in corso
    http.expectOne(`${BASE}/chat`).flush(response());
    await promise;

    expect(store.messages().filter((m) => m.role === 'user')).toHaveLength(1);
  });

  it('reset svuota la chat e la conversazione', async () => {
    const promise = store.send('uno');
    http.expectOne(`${BASE}/chat`).flush(response());
    await promise;

    store.reset();

    expect(store.messages()).toEqual([]);
    expect(store.conversationId()).toBeNull();
  });
});
