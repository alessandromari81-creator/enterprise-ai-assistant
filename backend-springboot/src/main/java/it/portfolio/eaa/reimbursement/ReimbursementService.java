package it.portfolio.eaa.reimbursement;

import it.portfolio.eaa.config.EaaProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.stereotype.Service;

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
