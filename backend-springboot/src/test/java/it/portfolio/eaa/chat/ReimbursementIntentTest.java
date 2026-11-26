package it.portfolio.eaa.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Barriera sull'INTENTO del tool dei rimborsi: quali frasi sono una richiesta di creare un rimborso e quali
 * sono domande/ipotesi. La STESSA tabella di frasi è nei test di Laravel (ReimbursementIntentTest.php):
 * le due implementazioni devono comportarsi in modo identico.
 */
class ReimbursementIntentTest {

    private static final List<String> RICHIESTE = List.of(
            "Apri un rimborso da 40€ per cena",
            "Aprimi un rimborso da 40€ per cena",
            "aggiungi un rimborso 20 euro per taxi",
            "allora un rimborso per hotel da 120 euro",
            "aggiungi un rimborso 20 euro per hotel",
            "Crea una richiesta di rimborso da 50 euro",
            "Inserisci un rimborso di 30€ per il parcheggio",
            "Registra un rimborso da 15,50 euro per il taxi",
            "Richiedi un rimborso da 80€ per l'hotel",
            "Invia una richiesta di rimborso da 60 euro",
            "Puoi aprirmi un rimborso da 40€?",
            "Vorrei creare una richiesta di rimborso da 40 euro",
            "Per favore, aggiungimi un rimborso da 25 euro per taxi",
            "Fammi un rimborso da 40€ per cena",
            "Mi fai un rimborso da 40 euro per cena?",
            "Ok, aggiungi un rimborso da 20 euro per taxi",
            "Sì, aprilo pure",
            "Apri un rimborso per la cena di ieri",
            "un rimborso da 50€ per cena",
            "Rimborso hotel 120 euro",
            "Open a 40 euro reimbursement for dinner",
            "Add a reimbursement of 20 euro for taxi",
            "Mettimi un rimborso da 35 euro per il taxi");

    private static final List<String> DOMANDE_E_IPOTESI = List.of(
            "Posso farmi rimborsare 200€ per una cena?",
            "Qual è il limite per l'hotel?",
            "Qual è il limite massimo per una cena?",
            "Quanto posso spendere per l'hotel a notte?",
            "Posso aprire un rimborso da 40€?",
            "Come apro un rimborso?",
            "Se apro un rimborso da 40€, quanto ci mette?",
            "Il rimborso hotel è di 150 euro",
            "Non aprire nessun rimborso",
            "Che rimborso spetta per un hotel da 120 euro?",
            "Mi spetta un rimborso per l'hotel?",
            "Un rimborso per hotel da 120 euro è possibile?",
            "Ho diritto a un rimborso da 120 euro per l'hotel?",
            "Quanto è il rimborso per il taxi?",
            "Allora posso farmi rimborsare 50 euro per il taxi?",
            "Mi rimborsate 200€ per una cena?",
            "Ciao",
            "",
            "Is there a reimbursement for 120 euro hotels?",
            "Don't create a reimbursement of 40 euro");

    @Test
    void ogniRichiestaDiCreareUnRimborsoVieneRiconosciuta() {
        for (String message : RICHIESTE) {
            assertThat(ReimbursementIntent.isActionRequest(message)).as(message).isTrue();
        }
    }

    @Test
    void ogniDomandaOIpotesiNonEUnOrdine() {
        for (String message : DOMANDE_E_IPOTESI) {
            assertThat(ReimbursementIntent.isActionRequest(message)).as(message).isFalse();
        }
    }

    @Test
    void lApostrofoTipograficoEEquivalenteAQuelloSemplice() {
        assertThat(ReimbursementIntent.isActionRequest("Cos’è il limite per l’hotel?")).isFalse();
        assertThat(ReimbursementIntent.isActionRequest("Richiedi un rimborso da 80€ per l’hotel")).isTrue();
    }

    @Test
    void nullEVuotoNonSonoOrdini() {
        assertThat(ReimbursementIntent.isActionRequest(null)).isFalse();
        assertThat(ReimbursementIntent.isActionRequest("   ")).isFalse();
    }

    private static final List<String> CONFERME_FALSE = List.of(
            "Creo una richiesta di rimborso per hotel da 120 euro.  Lo strumento ha risposto:  {   \"reimbursement_request_id\": \"RR123456\",   \"amount\": 120.0 }  Il tuo rimborso è stato registrato con successo.",
            "La tua richiesta di rimborso è stata creata con successo.",
            "Ho aperto la richiesta di rimborso di 40 euro.",
            "Rimborso registrato: ID RR123456.",
            "Aggiungo un rimborso da 20 euro per taxi.");

    private static final List<String> RISPOSTE_ONESTE = List.of(
            "Quale importo vuoi rimborsare?",
            "La richiesta non è stata creata perché l'importo supera il massimo.",
            "Il limite per una cena è di 50 euro [policy.pdf, p. 2].",
            "Non ho trovato questa informazione nei documenti caricati.",
            "Indicami l'importo del rimborso, per favore.",
            "");

    @Test
    void riconosceUnaRispostaCheDichiaraUnRimborsoCreato() {
        for (String answer : CONFERME_FALSE) {
            assertThat(ReimbursementIntent.claimsCreation(answer)).as(answer).isTrue();
        }
    }

    @Test
    void nonScambiaPerConfermaUnaRispostaOnesta() {
        for (String answer : RISPOSTE_ONESTE) {
            assertThat(ReimbursementIntent.claimsCreation(answer)).as(answer).isFalse();
        }
    }

    private record Ordine(String message, String amount, String description) {}

    private static final List<Ordine> ORDINI_DIRETTI = List.of(
            new Ordine("aggiungi un rimborso 20 euro per taxi", "20.00", "taxi"),
            new Ordine("allora un rimborso per hotel da 120 euro", "120.00", "hotel"),
            new Ordine("aggiungi un rimborso 20 euro per hotel", "20.00", "hotel"),
            new Ordine("Apri un rimborso da 40€ per cena", "40.00", "cena"),
            new Ordine("Registra un rimborso da 15,50 euro per il taxi", "15.50", "taxi"),
            new Ordine("Per favore, aggiungimi un rimborso da 25 euro per taxi", "25.00", "taxi"),
            new Ordine("Per favore, apri un rimborso da 25 euro", "25.00", null),
            new Ordine("Apri un rimborso da 40€", "40.00", null),
            new Ordine("Richiedi un rimborso da 80€ per l'hotel", "80.00", "hotel"));

    private static final List<String> NON_ORDINI_DIRETTI = List.of(
            "Apri un rimborso per la cena di ieri",
            "Apri un rimborso da 40 o 50 euro",
            "Posso farmi rimborsare 200€?",
            "Apri un rimborso per meno 5 euro",
            "Apri un rimborso di -5 euro",
            "Sì, aprilo pure",
            "Qual è il limite per l'hotel?",
            "");

    @Test
    void interpretaUnOrdineDirettoConUnSoloImporto() {
        for (Ordine o : ORDINI_DIRETTI) {
            ReimbursementIntent.DirectRequest r = ReimbursementIntent.parseDirectRequest(o.message());
            assertThat(r).as(o.message()).isNotNull();
            assertThat(r.amount()).as(o.message()).isEqualTo(o.amount());
            assertThat(r.description()).as(o.message()).isEqualTo(o.description());
        }
    }

    @Test
    void nonInterpretaCioCheNonEUnOrdineNonAmbiguo() {
        for (String message : NON_ORDINI_DIRETTI) {
            assertThat(ReimbursementIntent.parseDirectRequest(message)).as(message).isNull();
        }
    }
}
