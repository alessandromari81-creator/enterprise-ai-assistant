package it.portfolio.eaa.reimbursement;

import it.portfolio.eaa.config.EaaProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Logica di business dei rimborsi. È l'UNICO punto che scrive su reimbursement_requests: il tool
 * dell'IA e qualunque altro chiamante passano da qui.
 *
 * Principio di sicurezza: l'IA PROPONE, il codice DECIDE. L'importo proposto dal modello viene validato
 * qui (positivo, entro il tetto) e, in ultima istanza, dal CHECK del database.
 */
@Service
public class ReimbursementService {

    private final ReimbursementRepository repository;
    private final EaaProperties properties;

    public ReimbursementService(ReimbursementRepository repository, EaaProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * @param rawAmount valore grezzo dal modello: "40", "12,5", "12.50", "50 €"...
     * @throws IllegalArgumentException con un messaggio leggibile dal modello e dall'utente
     */
    public ReimbursementRequest create(String rawAmount, String description, UUID conversationId) {
        BigDecimal amount = normalizeAmount(rawAmount);

        String cleanDescription = description == null ? null : description.strip();

        ReimbursementRequest request = new ReimbursementRequest();
        request.setAmount(amount);
        request.setCurrency(properties.reimbursement().currency());
        request.setDescription(cleanDescription == null || cleanDescription.isEmpty()
                ? null
                : cleanDescription.substring(0, Math.min(cleanDescription.length(), 500)));
        request.setStatus(ReimbursementStatus.OPEN); // sempre OPEN: l'approvazione è umana
        request.setRequestedBy(properties.reimbursement().defaultUser());
        request.setSourceConversationId(conversationId);

        return repository.saveAndFlush(request);
    }

    /**
     * Aggiorna lo stato di un rimborso ESISTENTE, indicato dal numero (#2) oppure da una parola della descrizione.
     * È l'UNICO punto che modifica lo stato. Regole: un rimborso già nello stato richiesto non si "aggiorna";
     * EXECUTED e REJECTED sono stati finali. Stessi messaggi del backend Laravel.
     *
     * @throws IllegalArgumentException con un messaggio leggibile dal modello e dall'utente
     */
    @Transactional
    public ReimbursementRequest updateStatus(Long number, String keyword, ReimbursementStatus newStatus) {
        ReimbursementRequest target = findTarget(number, keyword);

        if (target.getStatus() == newStatus) {
            throw new IllegalArgumentException("Il rimborso #%d è già «%s».".formatted(target.getNumber(), newStatus.label()));
        }
        if (target.getStatus().isFinal()) {
            throw new IllegalArgumentException("Il rimborso #%d è già «%s» e non può più essere modificato."
                    .formatted(target.getNumber(), target.getStatus().label()));
        }

        target.setStatus(newStatus);
        return repository.saveAndFlush(target);
    }

    private ReimbursementRequest findTarget(Long number, String keyword) {
        if (number != null) {
            return repository.findByNumber(number)
                    .orElseThrow(() -> new IllegalArgumentException("Non esiste nessun rimborso con numero " + number + "."));
        }

        String word = keyword == null ? "" : keyword.strip();
        if (word.isEmpty()) {
            throw new IllegalArgumentException("Indica il numero del rimborso (quello dopo il #) oppure una parola della sua descrizione.");
        }

        List<ReimbursementRequest> matches = repository.findByDescriptionContainingIgnoreCaseOrderByNumberAsc(word);
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("Nessun rimborso ha «" + word + "» nella descrizione.");
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Più rimborsi corrispondono a «%s»: %s. Indica il numero del rimborso.".formatted(
                    word, matches.stream().map(r -> "#" + r.getNumber()).collect(Collectors.joining(", "))));
        }
        return matches.get(0);
    }

    /**
     * Converte l'importo in BigDecimal a 2 decimali, evitando errori di virgola mobile.
     * Accetta la virgola decimale italiana e il simbolo €. Stesse regole e stessi messaggi del backend Laravel.
     */
    BigDecimal normalizeAmount(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("L'importo non è un numero valido.");
        }

        String cleaned = raw.replace(" ", "").replace("€", "").replace("EUR", "").replace("eur", "").replace(',', '.');

        BigDecimal value;
        try {
            value = new BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("L'importo non è un numero valido.");
        }

        BigDecimal max = properties.reimbursement().maxAmount();

        if (value.signum() <= 0) {
            throw new IllegalArgumentException("L'importo deve essere maggiore di zero.");
        }
        if (value.compareTo(max) > 0) {
            throw new IllegalArgumentException(String.format(
                    "L'importo supera il massimo consentito di %s %s per richiesta.",
                    max.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    properties.reimbursement().currency()));
        }

        return value;
    }
}
