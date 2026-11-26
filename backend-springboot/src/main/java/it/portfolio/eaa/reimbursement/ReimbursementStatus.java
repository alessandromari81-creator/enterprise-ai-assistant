package it.portfolio.eaa.reimbursement;

/**
 * Stato di una richiesta di rimborso. Nascono sempre OPEN: l'approvazione è umana.
 * EXECUTED ("Eseguito") e REJECTED sono stati finali.
 */
public enum ReimbursementStatus {
    OPEN("In attesa"),
    APPROVED("Approvato"),
    REJECTED("Rifiutato"),
    EXECUTED("Eseguito");

    private final String label;

    ReimbursementStatus(String label) {
        this.label = label;
    }

    /** Etichetta italiana, la stessa che mostra il frontend. */
    public String label() {
        return label;
    }

    /** Uno stato finale non si può più cambiare. */
    public boolean isFinal() {
        return this == REJECTED || this == EXECUTED;
    }
}
