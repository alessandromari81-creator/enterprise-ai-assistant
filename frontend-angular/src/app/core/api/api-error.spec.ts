import { HttpErrorResponse } from '@angular/common/http';
import { ApiError, toApiError } from './api-error';

describe('toApiError', () => {
  it('usa il detail del problem+json e conserva status e type', () => {
    const error = new HttpErrorResponse({
      status: 409,
      error: {
        type: 'urn:eaa:error:duplicate-document',
        title: 'Conflict',
        status: 409,
        detail: 'Questo PDF è già stato caricato come "a.pdf".',
      },
    });

    const result = toApiError(error);

    expect(result.message).toBe('Questo PDF è già stato caricato come "a.pdf".');
    expect(result.status).toBe(409);
    expect(result.type).toBe('urn:eaa:error:duplicate-document');
  });

  it('status 0 (server spento o CORS) -> messaggio chiaro', () => {
    const result = toApiError(new HttpErrorResponse({ status: 0 }));

    expect(result.status).toBe(0);
    expect(result.message).toContain('Impossibile contattare il server');
  });

  it('risposta senza problem+json (es. HTML di un proxy) -> messaggio generico con lo status', () => {
    const result = toApiError(new HttpErrorResponse({ status: 500, error: '<html>boom</html>' }));

    expect(result.message).toBe('Errore del server (500).');
    expect(result.type).toBeNull();
  });

  it('un ApiError passa invariato', () => {
    const original = new ApiError('x', 404, 'urn:eaa:error:not-found');

    expect(toApiError(original)).toBe(original);
  });

  it('un errore qualsiasi diventa un messaggio generico', () => {
    expect(toApiError(new Error('boh')).status).toBe(-1);
  });
});
