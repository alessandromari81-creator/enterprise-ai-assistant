import { InjectionToken, Injectable, computed, inject, signal } from '@angular/core';
import { environment } from '../../../environments/environment';

/** Un backend selezionabile (tutti implementano lo stesso contratto API). */
export interface BackendOption {
  id: string;
  label: string;
  /** URL base dell'API, es. "http://localhost:8000/api". */
  apiUrl: string;
}

/** Elenco dei backend. Essendo un token, i test lo sostituiscono senza toccare l'ambiente. */
export const BACKENDS = new InjectionToken<readonly BackendOption[]>('BACKENDS', {
  providedIn: 'root',
  factory: () => environment.backends,
});

/** Id del backend di partenza, usato finché non c'è una scelta salvata. */
export const DEFAULT_BACKEND_ID = new InjectionToken<string>('DEFAULT_BACKEND_ID', {
  providedIn: 'root',
  factory: () => environment.defaultBackend,
});

/** Chiave di localStorage in cui si ricorda la scelta. */
export const BACKEND_STORAGE_KEY = 'eaa.backend';

/**
 * Backend attualmente in uso.
 *
 * `apiUrl` è un Signal: ApiService lo legge a OGNI chiamata, quindi cambiare backend ha effetto immediato.
 * I pannelli osservano `apiUrl` e ricaricano i propri dati quando cambia (vedi i `effect` nei componenti).
 *
 * Non dipende dagli altri store: sono loro a dipendere da questo (nessun ciclo).
 */
@Injectable({ providedIn: 'root' })
export class BackendStore {
  readonly options = inject(BACKENDS);
  private readonly defaultId = inject(DEFAULT_BACKEND_ID);

  readonly selectedId = signal(this.initialId());

  readonly selected = computed(
    () => this.options.find((option) => option.id === this.selectedId()) ?? this.options[0],
  );
  readonly apiUrl = computed(() => this.selected().apiUrl);
  /** Solo host e porta (es. "localhost:8080"), per mostrarlo in modo compatto. */
  readonly host = computed(() => new URL(this.apiUrl()).host);

  /** Cambia backend e ricorda la scelta. Un id sconosciuto viene ignorato. */
  select(id: string): void {
    if (id === this.selectedId() || !this.options.some((option) => option.id === id)) {
      return;
    }
    this.selectedId.set(id);
    try {
      localStorage.setItem(BACKEND_STORAGE_KEY, id);
    } catch {
      // localStorage non disponibile (navigazione privata, quota piena): la scelta vale solo per questa sessione.
    }
  }

  /** Ordine di preferenza: scelta salvata (se ancora valida) -> backend di default -> primo dell'elenco. */
  private initialId(): string {
    let stored: string | null = null;
    try {
      stored = localStorage.getItem(BACKEND_STORAGE_KEY);
    } catch {
      // vedi sopra
    }
    for (const candidate of [stored, this.defaultId]) {
      if (candidate && this.options.some((option) => option.id === candidate)) {
        return candidate;
      }
    }
    return this.options[0].id;
  }
}
