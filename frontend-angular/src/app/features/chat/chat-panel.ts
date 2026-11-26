import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { ChatStore } from '../../core/state/chat.store';
import { ChatMessageView } from './chat-message';

/** Limite del contratto per un messaggio. */
const MAX_MESSAGE_LENGTH = 4000;

/**
 * Colonna centrale: conversazione con i documenti.
 * Mostra l'indicatore di caricamento mentre si attende la risposta e scorre da solo in fondo.
 */
@Component({
  selector: 'app-chat-panel',
  imports: [ChatMessageView],
  templateUrl: './chat-panel.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChatPanel {
  protected readonly store = inject(ChatStore);
  protected readonly maxLength = MAX_MESSAGE_LENGTH;

  /** Testo che l'utente sta scrivendo. */
  protected readonly draft = signal('');
  protected readonly canSend = computed(() => this.draft().trim().length > 0 && !this.store.sending());

  /**
   * Suggerimenti sempre visibili sopra la casella di testo: mostrano a colpo d'occhio cosa sa fare l'IA
   * (domande sui documenti e azioni). `label` è ciò che si legge sul chip; `message` è la frase COMPLETA che
   * viene inviata in chat (domande corte come "Limite massimo per la cena?" recupererebbero peggio i documenti).
   */
  protected readonly quickPrompts = [
    { icon: '📄', label: 'Limite massimo per la cena?', message: 'Qual è il limite massimo per una cena?' },
    { icon: '✅', label: 'Segna come eseguito il rimborso #1', message: 'Segna come eseguito il rimborso #1' },
    { icon: '💶', label: 'Apri rimborso da 40€ per cena', message: 'Aprimi un rimborso da 40€ per cena' },
    { icon: '❓', label: 'Posso farmi rimborsare 200€?', message: 'Posso farmi rimborsare 200€ per una cena?' },
  ] as const;

  private readonly scroller = viewChild<ElementRef<HTMLElement>>('scroller');

  constructor() {
    // Dopo ogni rendering con nuovi messaggi (o con l'indicatore di caricamento) scorre in fondo.
    afterRenderEffect(() => {
      this.store.messages();
      this.store.sending();
      const el = this.scroller()?.nativeElement;
      if (el) {
        // scrollTop funziona ovunque (anche nei test con jsdom); l'animazione è in CSS (scroll-smooth).
        el.scrollTop = el.scrollHeight;
      }
    });
  }

  protected onInput(event: Event): void {
    this.draft.set((event.target as HTMLTextAreaElement).value);
  }

  /** Invio invia il messaggio; Maiusc+Invio va a capo. */
  protected onEnter(event: Event): void {
    if ((event as KeyboardEvent).shiftKey) {
      return;
    }
    event.preventDefault();
    void this.submit();
  }

  protected async submit(): Promise<void> {
    if (!this.canSend()) {
      return;
    }
    const text = this.draft();
    this.draft.set('');
    await this.store.send(text);
  }

  /** Clic su un chip: il testo parte direttamente in chat. */
  protected ask(message: string): void {
    void this.store.send(message);
  }
}
