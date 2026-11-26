import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { App } from './app';
import { BACKEND_STORAGE_KEY, BackendOption } from './core/state/backend.store';
import { provideTestBackend } from './testing/test-backend';

const LARAVEL = 'http://localhost:8000/api';
const SPRING = 'http://localhost:8080/api';
const TWO: readonly BackendOption[] = [
  { id: 'laravel', label: 'Laravel', apiUrl: LARAVEL },
  { id: 'spring', label: 'Spring Boot', apiUrl: SPRING },
];

const docs = (name: string) => ({
  items: [{ id: 'd', filename: name, sizeBytes: 1, pageCount: 1, chunkCount: 1, status: 'READY', errorMessage: null, createdAt: '2026-11-25T14:16:22Z' }],
});
const reimbursements = (amount: string) => ({
  items: [{ id: 'r', amount, currency: 'EUR', description: 'x', status: 'OPEN', requestedBy: 'demo-user', conversationId: null, createdAt: '2026-11-25T14:17:11Z' }],
  page: 0, size: 50, totalItems: 1, totalPages: 1,
});

/** Cambio di backend a runtime: i pannelli si ricaricano dal backend scelto e quello vecchio non interferisce. */
describe('Cambio di backend (integrazione)', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<App>;
  const html = () => fixture.nativeElement as HTMLElement;
  const option = (index: number) => html().querySelectorAll<HTMLButtonElement>('[data-testid="backend-option"]')[index];

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ providers: [...provideTestBackend(TWO)] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  async function start(): Promise<void> {
    fixture = TestBed.createComponent(App);
    await fixture.whenStable();
  }

  it('all\'avvio carica da Laravel; scegliendo Spring Boot ricarica documenti e rimborsi da :8080', async () => {
    await start();
    http.expectOne(`${LARAVEL}/documents`).flush(docs('da-laravel.pdf'));
    http.expectOne(`${LARAVEL}/reimbursements?page=0&size=50`).flush(reimbursements('40.00'));
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(html().textContent).toContain('da-laravel.pdf');
    });

    option(1).click();
    await fixture.whenStable();

    // Subito dopo il clic partono le richieste al NUOVO backend, e solo a quello.
    http.expectOne(`${SPRING}/documents`).flush(docs('da-spring.pdf'));
    http.expectOne(`${SPRING}/reimbursements?page=0&size=50`).flush(reimbursements('99.00'));
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(html().textContent).toContain('da-spring.pdf');
      expect(html().textContent).not.toContain('da-laravel.pdf');
      expect(html().textContent).toContain('99,00');
    });
    expect(localStorage.getItem(BACKEND_STORAGE_KEY)).toBe('spring');
  });

  it('una scelta salvata viene usata già al primo caricamento', async () => {
    localStorage.setItem(BACKEND_STORAGE_KEY, 'spring');

    await start();

    http.expectOne(`${SPRING}/documents`).flush(docs('da-spring.pdf'));
    http.expectOne(`${SPRING}/reimbursements?page=0&size=50`).flush(reimbursements('1.00'));
    http.expectNone(`${LARAVEL}/documents`);
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(option(1).getAttribute('aria-checked')).toBe('true');
    });
  });

  it('la risposta lenta del vecchio backend arriva dopo il cambio e viene scartata', async () => {
    await start();
    const oldDocs = http.expectOne(`${LARAVEL}/documents`); // ancora in attesa
    const oldRefunds = http.expectOne(`${LARAVEL}/reimbursements?page=0&size=50`);

    option(1).click();
    await fixture.whenStable();
    http.expectOne(`${SPRING}/documents`).flush(docs('da-spring.pdf'));
    http.expectOne(`${SPRING}/reimbursements?page=0&size=50`).flush(reimbursements('99.00'));

    // Ora arrivano, in ritardo, le risposte di Laravel: non devono sovrascrivere quelle di Spring.
    oldDocs.flush(docs('da-laravel.pdf'));
    oldRefunds.flush(reimbursements('40.00'));

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(html().textContent).toContain('da-spring.pdf');
      expect(html().textContent).toContain('99,00');
    });
    expect(html().textContent).not.toContain('da-laravel.pdf');
    expect(html().textContent).not.toContain('40,00');
  });

  it('se il backend scelto non risponde mostra l\'errore e il pallino diventa rosso', async () => {
    await start();
    http.expectOne(`${LARAVEL}/documents`).flush(docs('a.pdf'));
    http.expectOne(`${LARAVEL}/reimbursements?page=0&size=50`).flush(reimbursements('1.00'));

    option(1).click();
    await fixture.whenStable();
    http.expectOne(`${SPRING}/documents`).error(new ProgressEvent('error'), { status: 0 });
    http.expectOne(`${SPRING}/reimbursements?page=0&size=50`).error(new ProgressEvent('error'), { status: 0 });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(html().textContent).toContain('Impossibile contattare il server');
      expect(html().querySelector('[data-testid="backend-host"] .bg-red-500')).not.toBeNull();
    });
  });
});
