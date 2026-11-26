package it.portfolio.eaa.reimbursement;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository JPA delle richieste di rimborso: è ciò su cui scrive il tool dell'IA (via ReimbursementService). */
public interface ReimbursementRepository extends JpaRepository<ReimbursementRequest, UUID> {

    Page<ReimbursementRequest> findByStatus(ReimbursementStatus status, Pageable pageable);

    Optional<ReimbursementRequest> findByNumber(Long number);

    /** Rimborsi la cui descrizione contiene la parola (senza distinguere maiuscole), in ordine di numero. */
    List<ReimbursementRequest> findByDescriptionContainingIgnoreCaseOrderByNumberAsc(String keyword);
}
