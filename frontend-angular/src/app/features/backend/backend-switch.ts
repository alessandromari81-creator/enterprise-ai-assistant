import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { BackendStore } from '../../core/state/backend.store';
import { DocumentsStore } from '../../core/state/documents.store';

/**
 * Selettore del backend nell'intestazione: Laravel (:8000) oppure Spring Boot (:8080).
 *
 * Cliccando si cambia l'URL base delle API A RUNTIME (BackendStore) e la scelta resta salvata in
 * localStorage. Il ricaricamento di documenti e rimborsi è automatico: i due pannelli osservano
 * `apiUrl` e si ricaricano da soli.
 *
 * Il pallino accanto all'host mostra lo stato dell'ultimo caricamento dal backend scelto:
 * grigio-ambra in corso, verde raggiungibile, rosso non raggiungibile.
 */
@Component({
  selector: 'app-backend-switch',
  templateUrl: './backend-switch.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BackendSwitch {
  protected readonly backend = inject(BackendStore);
  private readonly documents = inject(DocumentsStore);

  /** Classi del pallino di stato. Scritte per intero: Tailwind le trova solo così. */
  protected readonly dot = computed(() => {
    if (this.documents.loadError() !== null) {
      return { cls: 'bg-red-500', label: 'non raggiungibile' };
    }
    if (this.documents.loading()) {
      return { cls: 'bg-amber-400', label: 'connessione in corso' };
    }
    return { cls: 'bg-emerald-500', label: 'raggiungibile' };
  });

  protected select(id: string): void {
    this.backend.select(id);
  }

  /** Frecce sinistra/destra spostano la selezione, come in ogni gruppo di radio. */
  protected onKeydown(event: KeyboardEvent): void {
    const step = event.key === 'ArrowRight' || event.key === 'ArrowDown' ? 1 : event.key === 'ArrowLeft' || event.key === 'ArrowUp' ? -1 : 0;
    if (step === 0) {
      return;
    }
    event.preventDefault();
    // currentTarget va letto SUBITO: il browser lo azzera appena finisce la gestione dell'evento.
    const group = event.currentTarget as HTMLElement;
    const options = this.backend.options;
    const index = options.findIndex((o) => o.id === this.backend.selectedId());
    this.select(options[(index + step + options.length) % options.length].id);
    // Il focus segue la selezione (come in un radiogroup nativo): dopo che Angular ha aggiornato il DOM.
    queueMicrotask(() => group.querySelector<HTMLElement>('[aria-checked="true"]')?.focus());
  }
}
