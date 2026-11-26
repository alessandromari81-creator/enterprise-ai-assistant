package it.portfolio.eaa.reimbursement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.generator.EventType;

/**
 * Richiesta di rimborso (tabella reimbursement_requests), creata dal tool createReimbursementRequest
 * invocato dal modello. L'importo è un BigDecimal (mai double) e nel JSON esce come stringa "50.00".
 */
@Entity
@Table(name = "reimbursement_requests")
public class ReimbursementRequest {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    /** Numero progressivo mostrato all'utente (#1, #2...): lo assegna il database (identity), Hibernate lo rilegge. */
    @Column(insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    private Long number;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReimbursementStatus status = ReimbursementStatus.OPEN;

    @Column(name = "requested_by", nullable = false)
    private String requestedBy;

    /** Chat da cui l'IA ha creato la richiesta (tracciabilità). */
    @Column(name = "source_conversation_id")
    private UUID sourceConversationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
    }

    public UUID getId() { return id; }
    public long getNumber() { return number == null ? 0 : number; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public ReimbursementStatus getStatus() { return status; }
    public void setStatus(ReimbursementStatus status) { this.status = status; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; }
    public UUID getSourceConversationId() { return sourceConversationId; }
    public void setSourceConversationId(UUID sourceConversationId) { this.sourceConversationId = sourceConversationId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
