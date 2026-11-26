import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { toApiError } from '../api/api-error';
import { ApiService } from '../api/api.service';
import { IndexedDocument } from '../api/models';

/** Limite del contratto: 20 MB. Il controllo lato client dà un errore immediato, ma l'ultima parola è del server. */
export const MAX_UPLOAD_BYTES = 20 * 1024 * 1024;

/** Quanto resta visibile il messaggio "caricato" prima di sparire da solo. */
const DONE_VISIBLE_MS = 4000;

/** Un caricamento in corso o appena concluso, mostrato sotto la dropzone. */
export interface UploadItem {
  id: number;
  name: string;
  status: 'uploading' | 'done' | 'error';
  error?: string;
}

/** Controllo lato client prima dell'upload. Restituisce il motivo del rifiuto, oppure null se va bene. */
export function validatePdf(file: File): string | null {
  const isPdf = file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf');
  if (!isPdf) {
    return 'Sono accettati solo file PDF.';
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    return 'Il file supera la dimensione massima di 20 MB.';
  }
  if (file.size === 0) {
    return 'Il file è vuoto.';
  }
  return null;
}

/** Stato del pannello "Documenti": elenco dei PDF indicizzati e dei caricamenti in corso. */
@Injectable({ providedIn: 'root' })
export class DocumentsStore {
  private readonly api = inject(ApiService);
  private nextUploadId = 1;
  /** Numero dell'ultima richiesta di caricamento dell'elenco: le risposte superate si scartano. */
  private run = 0;

  readonly documents = signal<IndexedDocument[]>([]);
  readonly loading = signal(false);
  readonly loadError = signal<string | null>(null);
  readonly uploads = signal<UploadItem[]>([]);

  readonly isUploading = computed(() => this.uploads().some((u) => u.status === 'uploading'));
  readonly readyCount = computed(
    () => this.documents().filter((d) => d.status === 'READY').length,
  );

  async load(): Promise<void> {
    const run = ++this.run;
    this.loading.set(true);
    this.loadError.set(null);

    try {
      const documents = await firstValueFrom(this.api.listDocuments());
      // Risposta superata (es. nel frattempo l'utente ha cambiato backend): non va mostrata.
      if (run === this.run) {
        this.documents.set(documents);
      }
    } catch (error) {
      if (run === this.run) {
        this.loadError.set(toApiError(error).message);
      }
    } finally {
      if (run === this.run) {
        this.loading.set(false);
      }
    }
  }

  /**
   * Carica i file UNO ALLA VOLTA (l'indicizzazione è sincrona e può durare secondi: in parallelo
   * si rischia di saturare il backend). Dopo ogni tentativo ricarica la lista: se il server ha
   * registrato il documento come FAILED (es. Ollama spento), l'utente lo vede subito.
   */
  async upload(files: readonly File[]): Promise<void> {
    for (const file of files) {
      const id = this.nextUploadId++;

      const problem = validatePdf(file);
      if (problem) {
        this.addUpload({ id, name: file.name, status: 'error', error: problem });
        continue;
      }

      this.addUpload({ id, name: file.name, status: 'uploading' });

      try {
        await firstValueFrom(this.api.uploadDocument(file));
        this.patchUpload(id, { status: 'done' });
        setTimeout(() => this.dismiss(id), DONE_VISIBLE_MS);
      } catch (error) {
        this.patchUpload(id, { status: 'error', error: toApiError(error).message });
      }

      await this.load();
    }
  }

  dismiss(id: number): void {
    this.uploads.update((list) => list.filter((u) => u.id !== id));
  }

  private addUpload(item: UploadItem): void {
    this.uploads.update((list) => [item, ...list]);
  }

  private patchUpload(id: number, patch: Partial<UploadItem>): void {
    this.uploads.update((list) => list.map((u) => (u.id === id ? { ...u, ...patch } : u)));
  }
}
