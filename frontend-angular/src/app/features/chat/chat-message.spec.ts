import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ChatMessage } from '../../core/state/chat.store';
import { ChatMessageView } from './chat-message';

const BASE: ChatMessage = {
  id: 1,
  role: 'assistant',
  text: 'Il limite è 50€ [policy.pdf, p. 2].',
  sources: [
    {
      chunkId: 'k1',
      documentId: 'd1',
      filename: 'policy.pdf',
      page: 2,
      score: 0.8425,
      snippet: 'Il limite massimo per una cena è di 50€ a persona.',
    },
  ],
  actions: [],
  warning: null,
  error: false,
};

describe('ChatMessageView', () => {
  let fixture: ComponentFixture<ChatMessageView>;
  const el = () => fixture.nativeElement as HTMLElement;

  async function render(message: ChatMessage): Promise<void> {
    fixture = TestBed.createComponent(ChatMessageView);
    fixture.componentRef.setInput('message', message);
    await fixture.whenStable();
  }

  it('mostra una badge per ogni fonte con nome file e pagina', async () => {
    await render(BASE);

    const badges = el().querySelectorAll('[data-testid="source-badge"]');
    expect(badges).toHaveLength(1);
    expect(badges[0].textContent).toContain('policy.pdf · p. 2');
  });

  it('cliccando la badge si legge il frammento citato, e ricliccando si chiude', async () => {
    await render(BASE);
    const badge = el().querySelector<HTMLButtonElement>('[data-testid="source-badge"]')!;

    badge.click();
    await fixture.whenStable();
    const snippet = el().querySelector('[data-testid="source-snippet"]');
    expect(snippet?.textContent).toContain('limite massimo per una cena');
    expect(snippet?.textContent).toContain('pertinenza 84%');

    badge.click();
    await fixture.whenStable();
    expect(el().querySelector('[data-testid="source-snippet"]')).toBeNull();
  });

  it('mostra la scheda dell\'azione eseguita dall\'IA con importo formattato', async () => {
    await render({
      ...BASE,
      text: 'Ho aperto la richiesta.',
      sources: [],
      actions: [
        {
          type: 'REIMBURSEMENT_CREATED',
          reimbursementId: '01a21314-5296-70cc',
          number: 3,
          amount: '40.00',
          currency: 'EUR',
          description: 'cena',
          status: 'OPEN',
        },
      ],
    });

    const card = el().querySelector('[data-testid="action-card"]');
    expect(card?.textContent).toContain('Richiesta di rimborso #3 creata');
    expect(card?.textContent).toContain('40,00');
    expect(card?.textContent).toContain('cena');
    expect(el().querySelector('[data-testid="source-badge"]')).toBeNull();
  });

  it('mostra la scheda di un rimborso aggiornato con il nuovo stato', async () => {
    await render({
      ...BASE,
      text: 'Fatto.',
      sources: [],
      actions: [
        {
          type: 'REIMBURSEMENT_UPDATED',
          reimbursementId: '01a21314-5296-70cc',
          number: 2,
          amount: '40.00',
          currency: 'EUR',
          description: 'cena',
          status: 'EXECUTED',
        },
      ],
    });

    const card = el().querySelector('[data-testid="action-card"]');
    expect(card?.textContent).toContain('Rimborso #2 aggiornato: Eseguito');
    expect(card?.textContent).toContain('40,00');
  });

  it('mostra l\'avviso a video quando il backend lo restituisce, e non altrimenti', async () => {
    await render({ ...BASE, sources: [], warning: 'Manca un verbo esplicito: nessuna azione eseguita.' });

    const alert = el().querySelector('[data-testid="warning-alert"]');
    expect(alert?.textContent).toContain('Manca un verbo esplicito');
    expect(alert?.getAttribute('role')).toBe('alert');

    await render(BASE);
    expect(el().querySelector('[data-testid="warning-alert"]')).toBeNull();
  });

  it('il testo del modello è mostrato come testo, mai interpretato come HTML', async () => {
    await render({ ...BASE, sources: [], text: '<img src=x onerror="alert(1)"><script>alert(2)</script>' });

    expect(el().querySelector('img')).toBeNull();
    expect(el().querySelector('script')).toBeNull();
    expect(el().textContent).toContain('<script>alert(2)</script>');
  });

  it('un messaggio di errore ha lo stile di errore', async () => {
    await render({ ...BASE, error: true, sources: [], text: 'Impossibile contattare il server.' });

    expect(el().querySelector('.bg-red-50')).not.toBeNull();
  });
});
