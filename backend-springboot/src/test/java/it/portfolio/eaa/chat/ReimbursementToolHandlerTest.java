package it.portfolio.eaa.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Le due barriere del tool (decisione D21): l'importo deve averlo scritto l'utente. */
class ReimbursementToolHandlerTest {

    @Test
    void riconosceImportiScrittiDallUtenteInVariFormati() {
        assertThat(ReimbursementToolHandler.amountWrittenByUser("50", List.of("Aprimi un rimborso da 50€"))).isTrue();
        assertThat(ReimbursementToolHandler.amountWrittenByUser("12.5", List.of("rimborso da 12,5 euro"))).isTrue();
        assertThat(ReimbursementToolHandler.amountWrittenByUser("12,50", List.of("rimborso da 12.50"))).isTrue();
        assertThat(ReimbursementToolHandler.amountWrittenByUser("50 €", List.of("rimborso 50 euro"))).isTrue();
        assertThat(ReimbursementToolHandler.amountWrittenByUser("10000.01", List.of("rimborso da 10000,01"))).isTrue();
    }

    @Test
    void rifiutaImportiNonScrittiDallUtente() {
        assertThat(ReimbursementToolHandler.amountWrittenByUser("50", List.of("Apri un rimborso per la cena di ieri"))).isFalse();
        assertThat(ReimbursementToolHandler.amountWrittenByUser("50", List.of("rimborso da 40€"))).isFalse();
        assertThat(ReimbursementToolHandler.amountWrittenByUser("5", List.of("rimborso da 50€"))).as("5 non è 50").isFalse();
    }

    @Test
    void cifreSoloAParoleNonValgono() {
        assertThat(ReimbursementToolHandler.amountWrittenByUser("50", List.of("Aprimi un rimborso da cinquanta euro"))).isFalse();
    }

    @Test
    void valoriNonNumericiONulliSonoRifiutati() {
        assertThat(ReimbursementToolHandler.amountWrittenByUser("abc", List.of("rimborso da 50"))).isFalse();
        assertThat(ReimbursementToolHandler.amountWrittenByUser(null, List.of("rimborso da 50"))).isFalse();
    }

    @Test
    void siConsultanoAncheIMessaggiPrecedenti() {
        assertThat(ReimbursementToolHandler.amountWrittenByUser("40", List.of("Ho speso 40 euro", "Sì, aprilo pure"))).isTrue();
    }
}
