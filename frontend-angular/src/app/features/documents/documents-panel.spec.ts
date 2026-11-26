import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideTestBackend } from '../../testing/test-backend';
import { DocumentsPanel } from './documents-panel';

const BASE = 'http://api.test';

describe('DocumentsPanel', () => {
  let fixture: ComponentFixture<DocumentsPanel>;
  let http: HttpTestingController;
  const html = () => fixture.nativeElement as HTMLElement;
  const dropzone = () => html().querySelector<HTMLElement>('[role="button"]')!;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [...provideTestBackend(),],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(DocumentsPanel);
    await fixture.whenStable();
    http.expectOne(`${BASE}/documents`).flush({ items: [] });
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(html().textContent).toContain('Nessun documento');
    });
  });

  afterEach(() => http.verify());

  /** jsdom non ha DataTransfer: si simula l'evento di drop con i file che servono al componente. */
  function dropEvent(files: File[]): Event {
    const event = new Event('drop', { bubbles: true, cancelable: true });
    Object.defineProperty(event, 'dataTransfer', { value: { files } });
    return event;
  }

  it('evidenzia la dropzone durante il trascinamento e la ripristina all\'uscita', async () => {
    dropzone().dispatchEvent(new Event('dragover', { cancelable: true }));
    await fixture.whenStable();
    expect(dropzone().className).toContain('border-indigo-500');
    expect(html().textContent).toContain('Rilascia per caricare');

    dropzone().dispatchEvent(new Event('dragleave'));
    await fixture.whenStable();
    expect(dropzone().className).toContain('border-slate-300');
  });

  it('trascinando un PDF lo carica con POST /documents', async () => {
    const file = new File(['%PDF-1.4'], 'policy.pdf', { type: 'application/pdf' });

    dropzone().dispatchEvent(dropEvent([file]));
    await fixture.whenStable();

    const post = http.expectOne(`${BASE}/documents`);
    expect(post.request.method).toBe('POST');
    expect((post.request.body as FormData).get('file')).toBeInstanceOf(File);
    post.flush(
      { id: 'd1', filename: 'policy.pdf', sizeBytes: 8, pageCount: 1, chunkCount: 1, status: 'READY', errorMessage: null, createdAt: '2026-11-25T14:16:22Z' },
      { status: 201, statusText: 'Created' },
    );
    await vi.waitFor(() => http.expectOne(`${BASE}/documents`).flush({
      items: [{ id: 'd1', filename: 'policy.pdf', sizeBytes: 8, pageCount: 1, chunkCount: 1, status: 'READY', errorMessage: null, createdAt: '2026-11-25T14:16:22Z' }],
    }));

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(html().textContent).toContain('Indicizzato');
      expect(html().textContent).toContain('1 pag.');
    });
  });

  it('un file non PDF mostra l\'errore senza chiamare il server', async () => {
    dropzone().dispatchEvent(dropEvent([new File(['x'], 'foto.png', { type: 'image/png' })]));

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(html().textContent).toContain('Sono accettati solo file PDF.');
    });
    http.expectNone(`${BASE}/documents`);
  });
});
