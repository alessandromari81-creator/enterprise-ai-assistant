package it.portfolio.eaa.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Parametri del tool, come li compila il modello. Le annotazioni Jackson generano lo schema JSON che Spring AI
 * invia al modello (nome, tipo, descrizione, obbligatorietà di ogni parametro).
 *
 * `amount` è una String e non un numero: il modello può mandare 40, "40" oppure "12,5 €", e il codice li
 * normalizza tutti (ReimbursementService). Niente ESEMPI nelle descrizioni dei parametri: i modelli piccoli li
 * copiano (provato dal vivo: "es. cena col cliente" finiva nel database anche se l'utente non lo aveva scritto).
 */
public record ReimbursementToolArgs(
        @JsonProperty(required = true)
        @JsonPropertyDescription("Importo in euro, numero positivo scritto dall'utente.")
        String amount,

        @JsonPropertyDescription("Motivo della spesa, con le parole dell'utente. Ometti questo parametro se l'utente non lo indica.")
        String description) {}
