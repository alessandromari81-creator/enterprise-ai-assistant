import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { ReimbursementStatus } from '../../core/api/models';
import { ChatMessage } from '../../core/state/chat.store';
import { MoneyPipe } from '../../shared/format.pipes';

/**
 * Un singolo messaggio della chat: bolla di testo, schede delle azioni eseguite dall'IA e
 * badge delle fonti (nome file + pagina). Cliccando un badge si legge il frammento citato.
 *
 * Il testo si mostra con l'interpolazione ({{ }}), che Angular protegge da HTML/script:
 * mai innerHTML con testo proveniente dal modello o dai PDF.
 */
@Component({
  selector: 'app-chat-message',
  imports: [MoneyPipe],
  templateUrl: './chat-message.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChatMessageView {
  readonly message = input.required<ChatMessage>();

  protected readonly isUser = computed(() => this.message().role === 'user');

  /** Classi della bolla in base a chi scrive. Scritte per intero (Tailwind). */
  protected readonly bubbleClass = computed(() => {
    const m = this.message();
    if (m.role === 'user') return 'bg-indigo-600 text-white';
    if (m.error) return 'bg-red-50 text-red-800 ring-1 ring-inset ring-red-200';
    return 'bg-slate-100 text-slate-800';
  });

  /** chunkId della fonte di cui si sta leggendo il frammento (una alla volta). */
  protected readonly expandedChunkId = signal<string | null>(null);

  protected readonly expandedSource = computed(() =>
    this.message().sources.find((s) => s.chunkId === this.expandedChunkId()) ?? null,
  );

  protected toggle(chunkId: string): void {
    this.expandedChunkId.update((current) => (current === chunkId ? null : chunkId));
  }

  protected statusLabel(status: ReimbursementStatus): string {
    return { OPEN: 'In attesa', APPROVED: 'Approvato', REJECTED: 'Rifiutato', EXECUTED: 'Eseguito' }[status];
  }

  /** 0.8425 -> "84%". */
  protected percent(score: number): string {
    return `${Math.round(score * 100)}%`;
  }
}
