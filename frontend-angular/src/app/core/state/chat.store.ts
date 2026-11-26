import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { toApiError } from '../api/api-error';
import { ApiService } from '../api/api.service';
import { ChatAction, Source } from '../api/models';
import { ReimbursementsStore } from './reimbursements.store';

/** Un messaggio della conversazione, già pronto per essere mostrato. */
export interface ChatMessage {
  id: number;
  role: 'user' | 'assistant';
  text: string;
  /** Fonti (file + pagina) usate dall'IA. Vuoto per i messaggi dell'utente. */
  sources: Source[];
  /** Azioni eseguite dall'IA con questo messaggio (es. rimborso creato). */
  actions: ChatAction[];
  /** Avviso da mostrare a video sotto la risposta (es. manca il verbo esplicito). */
  warning: string | null;
  /** true se il messaggio è un errore (backend irraggiungibile, ecc.), non una risposta dell'IA. */
  error: boolean;
}

/**
 * Stato della chat.
 *
 * Quando la risposta contiene azioni (l'IA ha aperto o aggiornato un rimborso) ricarica la tabella dei rimborsi:
 * è il collegamento "chat → tabella in tempo reale".
 */
@Injectable({ providedIn: 'root' })
export class ChatStore {
  private readonly api = inject(ApiService);
  private readonly reimbursements = inject(ReimbursementsStore);
  private nextMessageId = 1;

  readonly messages = signal<ChatMessage[]>([]);
  /** Assente finché non si invia il primo messaggio: la assegna il server. */
  readonly conversationId = signal<string | null>(null);
  /** true mentre si attende la risposta (mostra l'indicatore di caricamento). */
  readonly sending = signal(false);

  async send(rawText: string): Promise<void> {
    const text = rawText.trim();
    if (text === '' || this.sending()) {
      return;
    }

    this.push({ role: 'user', text, sources: [], actions: [], warning: null, error: false });
    this.sending.set(true);

    try {
      const response = await firstValueFrom(
        this.api.chat({ message: text, conversationId: this.conversationId() }),
      );

      this.conversationId.set(response.conversationId);
      this.push({
        role: 'assistant',
        text: response.answer,
        sources: response.sources,
        actions: response.actions,
        warning: response.warning ?? null,
        error: false,
      });

      if (response.actions.length > 0) {
        await this.reimbursements.refresh(response.actions.map((a) => a.reimbursementId));
      }
    } catch (error) {
      const apiError = toApiError(error);
      let message = apiError.message;

      // Conversazione sparita lato server (es. database ricreato): se ne riparte una nuova.
      if (apiError.status === 404 && this.conversationId() !== null) {
        this.conversationId.set(null);
        message = 'La conversazione non esiste più sul server. Ne inizio una nuova: riprova a inviare il messaggio.';
      }

      this.push({ role: 'assistant', text: message, sources: [], actions: [], warning: null, error: true });
    } finally {
      this.sending.set(false);
    }
  }

  /** Svuota la chat e riparte da una nuova conversazione. */
  reset(): void {
    this.messages.set([]);
    this.conversationId.set(null);
  }

  private push(message: Omit<ChatMessage, 'id'>): void {
    this.messages.update((list) => [...list, { ...message, id: this.nextMessageId++ }]);
  }
}
