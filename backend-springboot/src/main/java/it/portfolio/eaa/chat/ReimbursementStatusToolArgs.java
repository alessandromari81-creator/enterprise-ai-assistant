package it.portfolio.eaa.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Parametri del tool updateReimbursementStatus, come li compila il modello. `number` è una String per tollerare
 * sia 2 sia "2" sia "#2"; il codice lo normalizza. Niente ESEMPI nelle descrizioni: i modelli piccoli li copiano.
 */
public record ReimbursementStatusToolArgs(
        @JsonProperty(required = true)
        @JsonPropertyDescription("Nuovo stato richiesto dall'utente: APPROVED (approvato), REJECTED (rifiutato) oppure EXECUTED (eseguito, pagato, chiuso).")
        String status,

        @JsonPropertyDescription("Numero del rimborso (quello dopo il #), solo se l'utente lo ha scritto.")
        String number,

        @JsonPropertyDescription("Una parola della descrizione del rimborso, solo se l'utente non ha indicato il numero.")
        String keyword) {}
