import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { DocumentStatus } from '../../core/api/models';
import { BackendStore } from '../../core/state/backend.store';
import { DocumentsStore } from '../../core/state/documents.store';
import { DateTimePipe, FileSizePipe } from '../../shared/format.pipes';
import { BadgeTone, StatusBadge } from '../../shared/status-badge';

/**
 * Colonna sinistra: dropzone per caricare PDF + elenco dei documenti indicizzati.
 * La logica (upload, stato) sta in DocumentsStore: qui solo gestione degli eventi del browser.
 */
@Component({
  selector: 'app-documents-panel',
  imports: [StatusBadge, DateTimePipe, FileSizePipe],
  templateUrl: './documents-panel.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DocumentsPanel {
  protected readonly store = inject(DocumentsStore);
  private readonly backend = inject(BackendStore);

  /** true mentre l'utente trascina un file sopra la dropzone (per evidenziarla). */
  protected readonly dragging = signal(false);

  /** Classi che cambiano con il trascinamento. Scritte per intero: Tailwind le trova solo così. */
  protected readonly dropzoneClass = computed(() =>
    this.dragging() ? 'border-indigo-500 bg-indigo-50' : 'border-slate-300 hover:bg-slate-50',
  );

  private readonly picker = viewChild.required<ElementRef<HTMLInputElement>>('picker');

  constructor() {
    // Carica all'avvio e RICARICA ogni volta che l'utente cambia backend (apiUrl è un Signal).
    // untracked: il caricamento legge/scrive altri Signal e non deve diventare una dipendenza dell'effect.
    effect(() => {
      this.backend.apiUrl();
      untracked(() => void this.store.load());
    });
  }

  protected openPicker(): void {
    this.picker().nativeElement.click();
  }

  protected onDragOver(event: DragEvent): void {
    // Senza preventDefault il browser non accetta il drop e apre il PDF in una nuova scheda.
    event.preventDefault();
    this.dragging.set(true);
  }

  protected onDragLeave(): void {
    this.dragging.set(false);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    void this.store.upload(Array.from(event.dataTransfer?.files ?? []));
  }

  protected onPick(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    // Svuota l'input: così si può scegliere di nuovo lo stesso file dopo un errore.
    input.value = '';
    void this.store.upload(files);
  }

  protected statusLabel(status: DocumentStatus): string {
    return { READY: 'Indicizzato', PROCESSING: 'In elaborazione', FAILED: 'Errore' }[status];
  }

  protected statusTone(status: DocumentStatus): BadgeTone {
    return { READY: 'green', PROCESSING: 'amber', FAILED: 'red' }[status] as BadgeTone;
  }
}
