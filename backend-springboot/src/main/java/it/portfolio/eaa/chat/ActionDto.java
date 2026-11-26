package it.portfolio.eaa.chat;

import it.portfolio.eaa.reimbursement.ReimbursementRequest;

/**
 * Forma "Action" del contratto: azione eseguita dall'IA tramite tool calling (creazione o aggiornamento di stato
 * di un rimborso). La costruzione e la frase di conferma stanno qui: una sola definizione per i tool e per le
 * reti di sicurezza di ChatService.
 */
public record ActionDto(String type, String reimbursementId, long number, String amount, String currency, String description, String status) {

    public static final String REIMBURSEMENT_CREATED = "REIMBURSEMENT_CREATED";
    public static final String REIMBURSEMENT_UPDATED = "REIMBURSEMENT_UPDATED";

    static ActionDto created(ReimbursementRequest r) {
        return of(REIMBURSEMENT_CREATED, r);
    }

    static ActionDto updated(ReimbursementRequest r) {
        return of(REIMBURSEMENT_UPDATED, r);
    }

    private static ActionDto of(String type, ReimbursementRequest r) {
        return new ActionDto(type, r.getId().toString(), r.getNumber(), r.getAmount().toPlainString(), r.getCurrency(),
                r.getDescription(), r.getStatus().name());
    }

    /** Conferma costruita dal codice con i dati realmente salvati (stesso testo del backend Laravel). */
    String confirmation() {
        // "#,##0.00" con simboli italiani: 1.000,00 (come number_format di PHP nel backend Laravel).
        String formatted = new java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ITALY))
                .format(new java.math.BigDecimal(amount));
        String label = it.portfolio.eaa.reimbursement.ReimbursementStatus.valueOf(status).label();

        if (REIMBURSEMENT_UPDATED.equals(type)) {
            return "Ho aggiornato il rimborso #%d (%s, %s %s): ora è «%s».".formatted(
                    number, description != null ? description : "senza descrizione", formatted, currency, label);
        }
        return "Ho aperto la richiesta di rimborso #%d di %s %s%s. Identificativo: %s. Resta in stato OPEN in attesa di approvazione."
                .formatted(number, formatted, currency, description != null ? " (" + description + ")" : "", reimbursementId);
    }
}
