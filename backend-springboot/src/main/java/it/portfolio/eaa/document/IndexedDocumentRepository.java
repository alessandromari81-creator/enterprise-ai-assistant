package it.portfolio.eaa.document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IndexedDocumentRepository extends JpaRepository<IndexedDocument, UUID> {

    Optional<IndexedDocument> findByContentHash(String contentHash);

    /** Il più recente per primo; a parità di istante l'id (UUIDv7, ordinabile) fa da spareggio. */
    List<IndexedDocument> findAllByOrderByCreatedAtDescIdDesc();
}
