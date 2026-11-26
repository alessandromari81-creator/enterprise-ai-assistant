import { HttpErrorResponse } from '@angular/common/http';
import { Problem } from './models';

/** Errore dell'API già pronto da mostrare all'utente. */
export class ApiError extends Error {
  constructor(
    message: string,
    /** Status HTTP; 0 = server irraggiungibile, -1 = errore non HTTP. */
    readonly status: number,
    /** `type` URN del problem+json, se presente (es. "urn:eaa:error:not-found"). */
    readonly type: string | null = null,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/**
 * Converte qualsiasi errore (di HttpClient o altro) in un ApiError con un messaggio leggibile.
 *
 * Entrambi i backend rispondono con application/problem+json e `detail` già in italiano:
 * lo si mostra così com'è. Il testo di `detail` serve all'utente; per la logica si usa `type`.
 */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return error;
  }

  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      // Nessuna risposta: backend spento, URL sbagliato o CORS non consentito.
      return new ApiError(
        'Impossibile contattare il server. Controlla che il backend sia avviato.',
        0,
      );
    }

    const body = error.error as Partial<Problem> | null;
    if (body && typeof body === 'object' && typeof body.detail === 'string') {
      return new ApiError(body.detail, error.status, body.type ?? null);
    }

    return new ApiError(`Errore del server (${error.status}).`, error.status);
  }

  return new ApiError('Si è verificato un errore imprevisto.', -1);
}
