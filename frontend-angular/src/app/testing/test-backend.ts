import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { BACKENDS, BackendOption } from '../core/state/backend.store';

/** URL base del backend finto usato dai test. */
export const TEST_API = 'http://api.test';

/**
 * Provider comuni dei test: HttpClient finto (HttpTestingController) e UN backend di prova.
 * Così i test non dipendono da environment.ts né da localStorage.
 */
export function provideTestBackend(backends?: readonly BackendOption[]): (Provider | EnvironmentProviders)[] {
  return [
    provideHttpClient(),
    provideHttpClientTesting(),
    { provide: BACKENDS, useValue: backends ?? [{ id: 'test', label: 'Test', apiUrl: TEST_API }] },
  ];
}
