import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideTestBackend } from '../../testing/test-backend';
import { ChatPanel } from './chat-panel';

const BASE = 'http://api.test';

describe('ChatPanel', () => {
  let fixture: ComponentFixture<ChatPanel>;
  let http: HttpTestingController;
  const html = () => fixture.nativeElement as HTMLElement;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [...provideTestBackend(),],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ChatPanel);
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  function type(text: string): HTMLTextAreaElement {
    const textarea = html().querySelector('textarea')!;
    textarea.value = text;
    textarea.dispatchEvent(new Event('input'));
    return textarea;
  }

  it('il pulsante Invia è disabilitato finché non c\'è testo', async () => {
    const button = html().querySelector<HTMLButtonElement>('button[type="submit"]')!;
    expect(button.disabled).toBe(true);

    type('ciao');
    await fixture.whenStable();

    expect(button.disabled).toBe(false);
  });

  it('mostra l\'indicatore di caricamento finché la risposta non arriva, poi la risposta con le fonti', async () => {
    type('Qual è il limite per una cena?');
    await fixture.whenStable();
    html().querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    await fixture.whenStable();

    // In attesa: indicatore visibile, campo svuotato, invio bloccato.
    expect(html().querySelector('[data-testid="typing-indicator"]')).not.toBeNull();
    expect(html().querySelector('textarea')!.value).toBe('');
    expect(html().querySelector<HTMLButtonElement>('button[type="submit"]')!.disabled).toBe(true);

    http.expectOne(`${BASE}/chat`).flush({
      conversationId: 'c1',
      messageId: 'm1',
      answer: 'Il limite è 50€ [policy.pdf, p. 2].',
      sources: [{ chunkId: 'k1', documentId: 'd1', filename: 'policy.pdf', page: 2, score: 0.84, snippet: 's' }],
      actions: [],
      createdAt: '2026-11-25T14:17:11Z',
    });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(html().querySelector('[data-testid="typing-indicator"]')).toBeNull();
      expect(html().querySelector('[data-testid="source-badge"]')?.textContent).toContain('policy.pdf · p. 2');
    });
  });

  it('Invio invia il messaggio, Maiusc+Invio no', async () => {
    const textarea = type('prima riga');
    await fixture.whenStable();

    textarea.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', shiftKey: true, cancelable: true }));
    await fixture.whenStable();
    http.expectNone(`${BASE}/chat`);

    textarea.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));
    await fixture.whenStable();
    http.expectOne(`${BASE}/chat`).flush({
      conversationId: 'c1', messageId: 'm1', answer: 'ok', sources: [], actions: [], createdAt: '2026-11-25T14:17:11Z',
    });
    await vi.waitFor(() => fixture.detectChanges());
  });

  it('mostra 4 chip suggeriti, con icona ed etichetta, sopra la casella di testo', () => {
    const chips = Array.from(html().querySelectorAll<HTMLElement>('[data-testid="quick-prompt"]'));
    // L'icona è decorativa (aria-hidden): si legge solo l'etichetta. La spaziatura tra i due è del layout.
    const parts = chips.map((c) => [c.querySelector('[aria-hidden="true"]')?.textContent, c.querySelector('span:not([aria-hidden])')?.textContent]);

    expect(parts).toEqual([
      ['📄', 'Limite massimo per la cena?'],
      ['🏨', 'Massimale rimborso hotel?'],
      ['💶', 'Apri rimborso da 40€ per cena'],
      ['❓', 'Posso farmi rimborsare 200€?'],
    ]);
    const form = html().querySelector('form')!;
    expect(form.querySelectorAll('[data-testid="quick-prompt"]')).toHaveLength(4);
    // I chip stanno SOPRA la casella di testo nel DOM.
    expect(form.querySelector('[data-testid="quick-prompt"]')!.compareDocumentPosition(form.querySelector('textarea')!) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy();
  });

  it('ogni chip invia direttamente in chat la sua frase completa', async () => {
    const expected = [
      'Qual è il limite massimo per una cena?',
      "Quanto posso spendere per l'hotel a notte?",
      'Aprimi un rimborso da 40€ per cena',
      'Posso farmi rimborsare 200€ per una cena?',
    ];

    for (let i = 0; i < expected.length; i++) {
      html().querySelectorAll<HTMLButtonElement>('[data-testid="quick-prompt"]')[i].click();
      await fixture.whenStable();

      const request = http.expectOne(`${BASE}/chat`);
      expect(request.request.body.message).toBe(expected[i]);
      request.flush({ conversationId: 'c1', messageId: 'm' + i, answer: 'ok', sources: [], actions: [], createdAt: '2026-11-25T14:17:11Z' });
      await vi.waitFor(() => {
        fixture.detectChanges();
        expect(html().querySelector('[data-testid="typing-indicator"]')).toBeNull();
      });
    }
  });

  it('i chip sono disabilitati mentre si attende la risposta', async () => {
    html().querySelector<HTMLButtonElement>('[data-testid="quick-prompt"]')!.click();
    await fixture.whenStable();

    const chips = Array.from(html().querySelectorAll<HTMLButtonElement>('[data-testid="quick-prompt"]'));
    expect(chips.every((c) => c.disabled)).toBe(true);

    http.expectOne(`${BASE}/chat`).flush({ conversationId: 'c1', messageId: 'm', answer: 'ok', sources: [], actions: [], createdAt: '2026-11-25T14:17:11Z' });
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(Array.from(html().querySelectorAll<HTMLButtonElement>('[data-testid="quick-prompt"]')).every((c) => !c.disabled)).toBe(true);
    });
  });

  it('clic su un chip suggerito invia la domanda completa', async () => {
    html().querySelector<HTMLButtonElement>('[data-testid="quick-prompt"]')!.click();
    await fixture.whenStable();

    const request = http.expectOne(`${BASE}/chat`);
    expect(request.request.body.message).toBe('Qual è il limite massimo per una cena?');
    request.flush({
      conversationId: 'c1', messageId: 'm1', answer: 'ok', sources: [], actions: [], createdAt: '2026-11-25T14:17:11Z',
    });
    await vi.waitFor(() => fixture.detectChanges());
  });
});
