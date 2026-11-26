package it.portfolio.eaa.reimbursement;

import it.portfolio.eaa.common.Iso;

/**
 * Forma "Reimbursement" del contratto. Note: l'importo è una STRINGA con 2 decimali ("50.00", mai un numero)
 * e la colonna source_conversation_id esce come `conversationId`.
 */
public record ReimbursementDto(
        String id,
        String amount,
        String currency,
        String description,
        String status,
        String requestedBy,
        String conversationId,
        String createdAt) {

    public static ReimbursementDto from(ReimbursementRequest r) {
        return new ReimbursementDto(
                r.getId().toString(),
                r.getAmount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                r.getCurrency(),
                r.getDescription(),
                r.getStatus().name(),
                r.getRequestedBy(),
                r.getSourceConversationId() == null ? null : r.getSourceConversationId().toString(),
                Iso.utc(r.getCreatedAt()));
    }
}
