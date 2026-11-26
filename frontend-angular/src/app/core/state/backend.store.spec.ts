import { TestBed } from '@angular/core/testing';
import { BACKEND_STORAGE_KEY, BACKENDS, BackendOption, BackendStore, DEFAULT_BACKEND_ID } from './backend.store';

const TWO: readonly BackendOption[] = [
  { id: 'laravel', label: 'Laravel', apiUrl: 'http://localhost:8000/api' },
  { id: 'spring', label: 'Spring Boot', apiUrl: 'http://localhost:8080/api' },
];

function create(defaultId = 'laravel', backends: readonly BackendOption[] = TWO): BackendStore {
  TestBed.configureTestingModule({
    providers: [
      { provide: BACKENDS, useValue: backends },
      { provide: DEFAULT_BACKEND_ID, useValue: defaultId },
    ],
  });
  return TestBed.inject(BackendStore);
}

describe('BackendStore', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it('parte dal backend di default', () => {
    const store = create('laravel');

    expect(store.selectedId()).toBe('laravel');
    expect(store.apiUrl()).toBe('http://localhost:8000/api');
    expect(store.host()).toBe('localhost:8000');
  });

  it('una scelta salvata in localStorage vince sul default', () => {
    localStorage.setItem(BACKEND_STORAGE_KEY, 'spring');

    const store = create('laravel');

    expect(store.selectedId()).toBe('spring');
    expect(store.apiUrl()).toBe('http://localhost:8080/api');
  });

  it('una scelta salvata non più valida viene ignorata', () => {
    localStorage.setItem(BACKEND_STORAGE_KEY, 'backend-che-non-esiste');

    expect(create('spring').selectedId()).toBe('spring');
  });

  it('se anche il default non è valido usa il primo backend', () => {
    expect(create('boh').selectedId()).toBe('laravel');
  });

  it('select cambia l\'URL base subito e ricorda la scelta', () => {
    const store = create('laravel');

    store.select('spring');

    expect(store.apiUrl()).toBe('http://localhost:8080/api');
    expect(store.host()).toBe('localhost:8080');
    expect(localStorage.getItem(BACKEND_STORAGE_KEY)).toBe('spring');
  });

  it('un id sconosciuto viene ignorato e non viene salvato', () => {
    const store = create('laravel');

    store.select('boh');

    expect(store.selectedId()).toBe('laravel');
    expect(localStorage.getItem(BACKEND_STORAGE_KEY)).toBeNull();
  });

  it('se localStorage non è disponibile l\'app funziona lo stesso (scelta solo per la sessione)', () => {
    const store = create('laravel');
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('quota', 'QuotaExceededError');
    });

    expect(() => store.select('spring')).not.toThrow();
    expect(store.selectedId()).toBe('spring');

    spy.mockRestore();
  });
});
