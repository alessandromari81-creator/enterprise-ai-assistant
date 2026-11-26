import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideTestBackend } from './testing/test-backend';
import { App } from './app';

const BASE = 'http://api.test';

describe('App (dashboard a 3 colonne)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        ...provideTestBackend(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render() {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    // All'avvio i pannelli di sinistra e destra caricano i loro dati.
    http.expectOne(`${BASE}/documents`).flush({
      items: [
        {
          id: 'd1', filename: 'policy_rimborsi.pdf', sizeBytes: 1312, pageCount: 2, chunkCount: 2,
          status: 'READY', errorMessage: null, createdAt: '2026-11-25T14:16:22Z',
        },
      ],
    });
    http.expectOne(`${BASE}/reimbursements?page=0&size=50`).flush({
      items: [
        {
          id: 'r1', number: 1, amount: '40.00', currency: 'EUR', description: 'cena', status: 'OPEN',
          requestedBy: 'demo-user', conversationId: null, createdAt: '2026-11-25T14:17:11Z',
        },
      ],
      page: 0, size: 50, totalItems: 1, totalPages: 1,
    });
    // Le risposte si trasformano in stato negli store con una Promise: si attende che la UI le mostri.
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('policy_rimborsi.pdf');
    });
    return fixture;
  }

  it('mostra le tre colonne: documenti, chat, rimborsi', async () => {
    const fixture = await render();
    const html = fixture.nativeElement as HTMLElement;

    const titles = Array.from(html.querySelectorAll('h2')).map((h) => h.textContent?.trim());
    expect(titles).toEqual(['Documenti', 'Chat con i documenti', 'Richieste di rimborso']);
  });

  it('mostra l\'utente demo e il selettore del backend con il suo host', async () => {
    const fixture = await render();
    const html = fixture.nativeElement as HTMLElement;

    expect(html.textContent).toContain('Utente Demo');
    expect(html.querySelectorAll('[data-testid="backend-option"]')).toHaveLength(1);
    expect(html.querySelector('[data-testid="backend-host"]')?.textContent).toContain('api.test');
  });

  it('elenca i documenti e le richieste di rimborso caricati dal server', async () => {
    const fixture = await render();
    const html = fixture.nativeElement as HTMLElement;

    expect(html.textContent).toContain('policy_rimborsi.pdf');
    expect(html.querySelectorAll('[data-testid="reimbursement-row"]')).toHaveLength(1);
    expect(html.textContent).toContain('40,00');
  });
});
