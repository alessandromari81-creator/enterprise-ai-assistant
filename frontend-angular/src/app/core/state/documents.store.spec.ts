import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideTestBackend } from '../../testing/test-backend';
import { IndexedDocument } from '../api/models';
import { DocumentsStore, MAX_UPLOAD_BYTES, validatePdf } from './documents.store';

const BASE = 'http://api.test';

const DOC: IndexedDocument = {
  id: 'd1',
  filename: 'policy.pdf',
  sizeBytes: 1312,
  pageCount: 2,
  chunkCount: 2,
  status: 'READY',
  errorMessage: null,
  createdAt: '2026-11-25T14:16:22Z',
};

const pdf = (name = 'policy.pdf') => new File(['%PDF-1.4'], name, { type: 'application/pdf' });

describe('validatePdf', () => {
  it('accetta un PDF', () => {
    expect(validatePdf(pdf())).toBeNull();
  });

  it('rifiuta i file che non sono PDF', () => {
    expect(validatePdf(new File(['x'], 'note.txt', { type: 'text/plain' }))).toContain('solo file PDF');
  });

  it('rifiuta i file oltre 20 MB', () => {
    const big = new File([new ArrayBuffer(MAX_UPLOAD_BYTES + 1)], 'big.pdf', { type: 'application/pdf' });

    expect(validatePdf(big)).toContain('20 MB');
  });

  it('rifiuta i file vuoti', () => {
    expect(validatePdf(new File([], 'vuoto.pdf', { type: 'application/pdf' }))).toContain('vuoto');
  });
});

describe('DocumentsStore', () => {
  let store: DocumentsStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        ...provideTestBackend(),
      ],
    });
    store = TestBed.inject(DocumentsStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('carica l\'elenco dei documenti', async () => {
    const promise = store.load();
    http.expectOne(`${BASE}/documents`).flush({ items: [DOC] });
    await promise;

    expect(store.documents()).toEqual([DOC]);
    expect(store.readyCount()).toBe(1);
  });

  it('segnala l\'errore di caricamento dell\'elenco', async () => {
    const promise = store.load();
    http.expectOne(`${BASE}/documents`).error(new ProgressEvent('error'), { status: 0 });
    await promise;

    expect(store.loadError()).toContain('Impossibile contattare il server');
  });

  it('carica un PDF in multipart (campo "file") e poi ricarica l\'elenco', async () => {
    const promise = store.upload([pdf()]);

    const post = http.expectOne(`${BASE}/documents`);
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toBeInstanceOf(FormData);
    expect((post.request.body as FormData).get('file')).toBeInstanceOf(File);
    expect(store.isUploading()).toBe(true);
    post.flush(DOC, { status: 201, statusText: 'Created' });

    await vi.waitFor(() => http.expectOne(`${BASE}/documents`).flush({ items: [DOC] }));
    await promise;

    expect(store.uploads()[0].status).toBe('done');
    expect(store.documents()).toEqual([DOC]);
    expect(store.isUploading()).toBe(false);
  });

  it('un file non PDF viene rifiutato subito, senza chiamare il server', async () => {
    await store.upload([new File(['x'], 'foto.png', { type: 'image/png' })]);

    http.expectNone(`${BASE}/documents`);
    expect(store.uploads()[0]).toMatchObject({ status: 'error', error: 'Sono accettati solo file PDF.' });
  });

  it('mostra il motivo dell\'errore del server (es. PDF duplicato) e ricarica comunque l\'elenco', async () => {
    const promise = store.upload([pdf()]);
    http.expectOne(`${BASE}/documents`).flush(
      {
        type: 'urn:eaa:error:duplicate-document',
        title: 'Conflict',
        status: 409,
        detail: 'Questo PDF è già stato caricato come "policy.pdf".',
      },
      { status: 409, statusText: 'Conflict' },
    );

    await vi.waitFor(() => http.expectOne(`${BASE}/documents`).flush({ items: [DOC] }));
    await promise;

    expect(store.uploads()[0]).toMatchObject({
      status: 'error',
      error: 'Questo PDF è già stato caricato come "policy.pdf".',
    });
  });

  it('carica più file uno alla volta, non in parallelo', async () => {
    const promise = store.upload([pdf('a.pdf'), pdf('b.pdf')]);

    // Finché il primo non finisce, il secondo non parte.
    const first = http.expectOne(`${BASE}/documents`);
    first.flush(DOC, { status: 201, statusText: 'Created' });
    await vi.waitFor(() => http.expectOne(`${BASE}/documents`).flush({ items: [DOC] })); // GET lista

    await vi.waitFor(() => {
      const second = http.expectOne((r) => r.method === 'POST');
      second.flush(DOC, { status: 201, statusText: 'Created' });
    });
    await vi.waitFor(() => http.expectOne(`${BASE}/documents`).flush({ items: [DOC] }));
    await promise;

    expect(store.uploads().map((u) => u.status)).toEqual(['done', 'done']);
  });
});
