import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Reimbursement } from '../../core/api/models';
import { ReimbursementsStore } from '../../core/state/reimbursements.store';
import { provideTestBackend } from '../../testing/test-backend';
import { ReimbursementsPanel } from './reimbursements-panel';

const BASE = 'http://api.test';
const LIST = `${BASE}/reimbursements?page=0&size=50`;

function row(number: number, status: Reimbursement['status'], description: string): Reimbursement {
  return {
    id: `r${number}`, number, amount: '40.00', currency: 'EUR', description, status,
    requestedBy: 'demo-user', conversationId: null, createdAt: '2026-11-25T14:17:11Z',
  };
}

function page(items: Reimbursement[]) {
  return { items, page: 0, size: 50, totalItems: items.length, totalPages: 1 };
}

describe('ReimbursementsPanel', () => {
  let fixture: ComponentFixture<ReimbursementsPanel>;
  let http: HttpTestingController;
  const html = () => fixture.nativeElement as HTMLElement;
  const rows = () => Array.from(html().querySelectorAll<HTMLElement>('[data-testid="reimbursement-row"]'));

  beforeEach(async () => {
    TestBed.configureTestingModule({ providers: [...provideTestBackend()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ReimbursementsPanel);
    await fixture.whenStable();
    http.expectOne(LIST).flush(page([row(1, 'OPEN', 'cena'), row(2, 'EXECUTED', 'taxi'), row(3, 'APPROVED', 'hotel'), row(4, 'REJECTED', 'treno')]));
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(rows()).toHaveLength(4);
    });
  });

  afterEach(() => http.verify());

  it('mostra il numero del rimborso (#1, #2...) che l\'utente scrive in chat', () => {
    const numbers = rows().map((r) => r.querySelector('[data-testid="reimbursement-number"]')?.textContent?.trim());

    expect(numbers).toEqual(['#1', '#2', '#3', '#4']);
  });

  it('mostra un badge colorato per ogni stato: In attesa, Eseguito, Approvato, Rifiutato', () => {
    const badges = rows().map((r) => r.querySelector('app-status-badge span')!);

    expect(badges.map((b) => b.textContent?.trim())).toEqual(['In attesa', 'Eseguito', 'Approvato', 'Rifiutato']);
    expect(badges[0].className).toContain('bg-amber-50');
    expect(badges[1].className).toContain('bg-sky-50');
    expect(badges[2].className).toContain('bg-emerald-50');
    expect(badges[3].className).toContain('bg-red-50');
  });

  it('dopo un aggiornamento di stato la tabella si ricarica e la riga cambia badge', async () => {
    const store = TestBed.inject(ReimbursementsStore);

    const promise = store.refresh(['r1']);
    http.expectOne(LIST).flush(page([row(1, 'EXECUTED', 'cena'), row(2, 'EXECUTED', 'taxi'), row(3, 'APPROVED', 'hotel'), row(4, 'REJECTED', 'treno')]));
    await promise;
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(rows()[0].querySelector('app-status-badge')?.textContent?.trim()).toBe('Eseguito');
    });
    expect(store.highlightedIds().has('r1')).toBe(true);
  });
});
