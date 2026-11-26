import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { toApiError } from '../api/api-error';
import { ApiService } from '../api/api.service';
import { Reimbursement } from '../api/models';

/** Quante richieste mostrare nella tabella (il contratto ne ammette fino a 100 per pagina). */
const PAGE_SIZE = 50;

/** Per quanto tempo una riga appena creata dall'IA resta evidenziata. */
const HIGHLIGHT_MS = 4000;

/**
 * Stato della tabella "Richieste di rimborso".
 *
 * È un servizio condiviso (providedIn root) perché lo usano due punti: il pannello di destra,
 * che lo mostra, e la chat, che lo aggiorna quando l'IA esegue l'azione.
 */
@Injectable({ providedIn: 'root' })
export class ReimbursementsStore {
  private readonly api = inject(ApiService);
  private highlightTimer: ReturnType<typeof setTimeout> | undefined;
  /** Numero dell'ultima richiesta di caricamento: le risposte di quelle precedenti si scartano. */
  private run = 0;

  readonly items = signal<Reimbursement[]>([]);
  /** Totale sul server (può essere maggiore di `items().length`). */
  readonly totalItems = signal(0);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  /** ID delle righe da evidenziare perché appena create. */
  readonly highlightedIds = signal<ReadonlySet<string>>(new Set());

  /**
   * Ricarica la tabella dal server.
   * @param newIds ID dei rimborsi appena creati dall'IA: vengono evidenziati per qualche secondo.
   */
  async refresh(newIds: readonly string[] = []): Promise<void> {
    const run = ++this.run;
    this.loading.set(true);
    this.error.set(null);

    try {
      const page = await firstValueFrom(this.api.listReimbursements({ page: 0, size: PAGE_SIZE }));
      // Se nel frattempo è partito un altro caricamento (es. l'utente ha cambiato backend) questa
      // risposta è superata: applicarla mostrerebbe dati del backend sbagliato.
      if (run !== this.run) {
        return;
      }
      this.items.set(page.items);
      this.totalItems.set(page.totalItems);
      if (newIds.length > 0) {
        this.highlight(newIds);
      }
    } catch (error) {
      if (run === this.run) {
        this.error.set(toApiError(error).message);
      }
    } finally {
      if (run === this.run) {
        this.loading.set(false);
      }
    }
  }

  private highlight(ids: readonly string[]): void {
    clearTimeout(this.highlightTimer);
    this.highlightedIds.set(new Set(ids));
    this.highlightTimer = setTimeout(() => this.highlightedIds.set(new Set()), HIGHLIGHT_MS);
  }
}
