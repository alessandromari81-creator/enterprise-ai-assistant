package it.portfolio.eaa.reimbursement;

/** Stato di una richiesta di rimborso. Nascono sempre OPEN: l'approvazione è umana. */
public enum ReimbursementStatus {
    OPEN,
    APPROVED,
    REJECTED
}
