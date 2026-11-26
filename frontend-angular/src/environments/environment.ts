/**
 * Configurazione dell'app.
 *
 * Il frontend parla con UNO dei backend alla volta, e tutti espongono lo stesso contratto API
 * (docs/api/openapi.yaml). Quale usare si sceglie a RUNTIME dal selettore nell'intestazione: la scelta
 * viene salvata in localStorage e ricordata alla visita successiva. Qui si elencano i backend disponibili
 * e quello di partenza (usato finché l'utente non ne sceglie un altro).
 */
export const environment = {
  /** Backend selezionabili dal selettore. Per aggiungerne uno basta una riga. */
  backends: [
    { id: 'laravel', label: 'Laravel', apiUrl: 'http://localhost:8000/api' },
    { id: 'spring', label: 'Spring Boot', apiUrl: 'http://localhost:8080/api' },
  ],

  /** Backend usato al primo avvio (id di una voce di `backends`). */
  defaultBackend: 'laravel',

  /**
   * Utente demo preimpostato (nessun login). Solo per la visualizzazione: il contratto API non
   * prevede un identificativo utente, è il backend a registrare le richieste come "demo-user" (D10).
   */
  demoUser: { id: 1, name: 'Utente Demo' },
};
