package it.portfolio.eaa.chat;

/** Forma "Action" del contratto: azione eseguita dall'IA tramite tool calling (oggi solo la creazione di un rimborso). */
public record ActionDto(String type, String reimbursementId, String amount, String currency, String description) {

    public static final String REIMBURSEMENT_CREATED = "REIMBURSEMENT_CREATED";
}
