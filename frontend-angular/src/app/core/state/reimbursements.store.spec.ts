import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideTestBackend } from '../../testing/test-backend';
import { vi } from 'vitest';
import { Reimbursement } from '../api/models';
import { ReimbursementsStore } from './reimbursements.store';

const ROW: Reimbursement = {
  id: 'r1',
  number: 1,
  amount: '40.00',
  currency: 'EUR',
  description: 'cena',
  status: 'OPEN',
  requestedBy: 'demo-user',
  conversationId: null,
  createdAt: '2026-11-25T14:17:11Z',
};

describe('ReimbursementsStore', () => {
  let store: ReimbursementsStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        ...provideTestBackend(),
      ],
    });
    store = TestBed.inject(ReimbursementsStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  it('carica la pagina 0 con size 50 e popola la tabella', async () => {
    const promise = store.refresh();

    const request = http.expectOne('http://api.test/reimbursements?page=0&size=50');
    request.flush({ items: [ROW], page: 0, size: 50, totalItems: 1, totalPages: 1 });
    await promise;

    expect(store.items()).toEqual([ROW]);
    expect(store.totalItems()).toBe(1);
    expect(store.loading()).toBe(false);
    expect(store.error()).toBeNull();
  });

  it('evidenzia le righe nuove per qualche secondo, poi toglie l\'evidenza', async () => {
    vi.useFakeTimers();

    const promise = store.refresh(['r1']);
    http
      .expectOne('http://api.test/reimbursements?page=0&size=50')
      .flush({ items: [ROW], page: 0, size: 50, totalItems: 1, totalPages: 1 });
    await promise;

    expect(store.highlightedIds().has('r1')).toBe(true);

    vi.advanceTimersByTime(4001);

    expect(store.highlightedIds().size).toBe(0);
  });

  it('mostra un errore leggibile se il server non risponde', async () => {
    const promise = store.refresh();
    http
      .expectOne('http://api.test/reimbursements?page=0&size=50')
      .error(new ProgressEvent('error'), { status: 0 });
    await promise;

    expect(store.error()).toContain('Impossibile contattare il server');
    expect(store.loading()).toBe(false);
  });
});
