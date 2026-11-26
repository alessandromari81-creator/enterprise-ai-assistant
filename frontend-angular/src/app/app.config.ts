import { provideHttpClient, withFetch } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';

/**
 * Configurazione globale. Nessun router: è una dashboard a pagina singola senza login.
 * `withFetch()` usa l'API fetch del browser al posto di XMLHttpRequest.
 */
export const appConfig: ApplicationConfig = {
  providers: [provideBrowserGlobalErrorListeners(), provideHttpClient(withFetch())],
};
