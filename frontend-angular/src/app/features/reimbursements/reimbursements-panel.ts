import { ChangeDetectionStrategy, Component, effect, inject, untracked } from '@angular/core';
import { ReimbursementStatus } from '../../core/api/models';
import { BackendStore } from '../../core/state/backend.store';
import { ReimbursementsStore } from '../../core/state/reimbursements.store';
import { DateTimePipe, MoneyPipe } from '../../shared/format.pipes';
import { BadgeTone, StatusBadge } from '../../shared/status-badge';

/**
 * Colonna destra: tabella delle richieste di rimborso.
 * Si aggiorna da sola: ChatStore chiama `store.refresh()` quando l'IA crea o aggiorna un rimborso, e la riga
 * interessata resta evidenziata per qualche secondo (`highlightedIds`).
 */
@Component({
  selector: 'app-reimbursements-panel',
  imports: [StatusBadge, MoneyPipe, DateTimePipe],
  templateUrl: './reimbursements-panel.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReimbursementsPanel {
  protected readonly store = inject(ReimbursementsStore);
  private readonly backend = inject(BackendStore);

  constructor() {
    // Carica all'avvio e RICARICA ogni volta che l'utente cambia backend (apiUrl è un Signal).
    effect(() => {
      this.backend.apiUrl();
      untracked(() => void this.store.refresh());
    });
  }

  protected statusLabel(status: ReimbursementStatus): string {
    return { OPEN: 'In attesa', APPROVED: 'Approvato', REJECTED: 'Rifiutato', EXECUTED: 'Eseguito' }[status];
  }

  protected statusTone(status: ReimbursementStatus): BadgeTone {
    return { OPEN: 'amber', APPROVED: 'green', REJECTED: 'red', EXECUTED: 'blue' }[status] as BadgeTone;
  }
}
