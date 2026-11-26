package it.portfolio.eaa.reimbursement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.portfolio.eaa.config.EaaProperties;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Normalizzazione e validazione dell'importo: stesse regole e stessi messaggi del backend Laravel. */
class ReimbursementServiceTest {

    private final ReimbursementService service = new ReimbursementService(null, new EaaProperties(
            new EaaProperties.Ai(768, 32),
            new EaaProperties.Rag(500, 50, 5, 0.62, 10, 300),
            new EaaProperties.Upload(20L * 1024 * 1024),
            new EaaProperties.Reimbursement(new BigDecimal("10000"), "EUR", "demo-user"),
            new EaaProperties.Cors(List.of("http://localhost:4200"))));

    @Test
    void normalizzaAdueDecimali() {
        assertThat(service.normalizeAmount("40")).isEqualByComparingTo("40.00").extracting(BigDecimal::scale).isEqualTo(2);
        assertThat(service.normalizeAmount("12.5")).hasToString("12.50");
        assertThat(service.normalizeAmount("0.005")).hasToString("0.01");
    }

    @Test
    void accettaVirgolaDecimaleSimboloEuroESpazi() {
        assertThat(service.normalizeAmount("12,5 €")).hasToString("12.50");
        assertThat(service.normalizeAmount("50 EUR")).hasToString("50.00");
        assertThat(service.normalizeAmount(" 1 200,50 ")).hasToString("1200.50");
    }

    @Test
    void rifiutaImportiNonPositivi() {
        assertThatThrownBy(() -> service.normalizeAmount("0")).hasMessage("L'importo deve essere maggiore di zero.");
        assertThatThrownBy(() -> service.normalizeAmount("-5")).hasMessage("L'importo deve essere maggiore di zero.");
    }

    @Test
    void rifiutaImportiOltreIlTetto() {
        assertThat(service.normalizeAmount("10000")).hasToString("10000.00");
        assertThatThrownBy(() -> service.normalizeAmount("10000.01"))
                .hasMessage("L'importo supera il massimo consentito di 10000.00 EUR per richiesta.");
    }

    @Test
    void rifiutaValoriNonNumerici() {
        assertThatThrownBy(() -> service.normalizeAmount("abc")).hasMessage("L'importo non è un numero valido.");
        assertThatThrownBy(() -> service.normalizeAmount(null)).hasMessage("L'importo non è un numero valido.");
    }
}
