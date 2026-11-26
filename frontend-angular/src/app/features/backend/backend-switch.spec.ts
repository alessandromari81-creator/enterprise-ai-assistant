import { ComponentFixture, TestBed } from '@angular/core/testing';
import { BACKEND_STORAGE_KEY, BackendOption } from '../../core/state/backend.store';
import { provideTestBackend } from '../../testing/test-backend';
import { BackendSwitch } from './backend-switch';

const TWO: readonly BackendOption[] = [
  { id: 'laravel', label: 'Laravel', apiUrl: 'http://localhost:8000/api' },
  { id: 'spring', label: 'Spring Boot', apiUrl: 'http://localhost:8080/api' },
];

describe('BackendSwitch', () => {
  let fixture: ComponentFixture<BackendSwitch>;
  const html = () => fixture.nativeElement as HTMLElement;
  const options = () => Array.from(html().querySelectorAll<HTMLButtonElement>('[data-testid="backend-option"]'));

  beforeEach(async () => {
    localStorage.clear();
    TestBed.configureTestingModule({ providers: [...provideTestBackend(TWO)] });
    fixture = TestBed.createComponent(BackendSwitch);
    await fixture.whenStable();
  });

  afterEach(() => localStorage.clear());

  it('mostra Laravel e Spring Boot, con Laravel selezionato e il suo host', () => {
    expect(options().map((o) => o.textContent?.trim())).toEqual(['Laravel', 'Spring Boot']);
    expect(options().map((o) => o.getAttribute('aria-checked'))).toEqual(['true', 'false']);
    expect(html().querySelector('[data-testid="backend-host"]')?.textContent).toContain('localhost:8000');
  });

  it('il gruppo è un radiogroup accessibile', () => {
    expect(html().querySelector('[role="radiogroup"]')).not.toBeNull();
    expect(options().every((o) => o.getAttribute('role') === 'radio')).toBe(true);
  });

  it('cliccando Spring Boot cambia selezione, host e salva la scelta', async () => {
    options()[1].click();
    await fixture.whenStable();

    expect(options().map((o) => o.getAttribute('aria-checked'))).toEqual(['false', 'true']);
    expect(html().querySelector('[data-testid="backend-host"]')?.textContent).toContain('localhost:8080');
    expect(localStorage.getItem(BACKEND_STORAGE_KEY)).toBe('spring');
  });

  it('le frecce spostano la selezione tra i due backend', async () => {
    const group = html().querySelector('[role="radiogroup"]')!;

    group.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true }));
    await fixture.whenStable();
    expect(options()[1].getAttribute('aria-checked')).toBe('true');

    group.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true, cancelable: true }));
    await fixture.whenStable();
    expect(options()[0].getAttribute('aria-checked')).toBe('true');
  });
});
