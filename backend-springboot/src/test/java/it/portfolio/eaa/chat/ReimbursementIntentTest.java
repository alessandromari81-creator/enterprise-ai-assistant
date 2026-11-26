package it.portfolio.eaa.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Barriera sull'INTENTO dei due tool dei rimborsi: creare un rimborso, aggiornarne lo stato, frasi senza verbo
 * (avviso), conferme false. La STESSA tabella di frasi è nei test di Laravel (ReimbursementIntentTest.php): le due
 * implementazioni devono comportarsi in modo identico.
 */
class ReimbursementIntentTest {

    record Ordine(String message, String amount, String description) {}

    record Stato(String message, String status) {}

    record Aggiornamento(String message, String status, Long number, String keyword) {}

    record Avviso(String message, String suggestion) {}

    private static final List<String> CREAZIONI = List.of(
            "Apri un rimborso da 40€ per cena",
            "Aprimi un rimborso da 40€ per cena",
            "aggiungi un rimborso 20 euro per taxi",
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
            "Open a 40 euro reimbursement for dinner",
            "Add a reimbursement of 20 euro for taxi",
            "Mettimi un rimborso da 35 euro per il taxi",
            "Segna un rimborso da 20 euro per taxi");

    private static final List<String> NON_CREAZIONI = List.of(
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
            "Don't create a reimbursement of 40 euro",
            "allora un rimborso per hotel da 120 euro",
            "un rimborso da 50€ per cena",
            "Rimborso hotel 120 euro",
            "Segna come eseguito il rimborso #2",
            "Approva il rimborso della cena");

    private static final List<String> AGGIORNAMENTI = List.of(
            "Segna come eseguito il rimborso #2",
            "Approva il rimborso della cena",
            "Rifiuta il rimborso #3",
            "Esegui il rimborso #1",
            "Aggiorna il rimborso #2 come approvato",
            "Chiudi il rimborso #2",
            "Metti il rimborso #2 come approvato",
            "Approva #2",
            "Per favore, approvami il rimborso della cena",
            "Paga il rimborso #1",
            "Segna come eseguito il rimborso della cena",
            "Mark refund #2 as paid",
            "Approve the refund #2");

    private static final List<String> NON_AGGIORNAMENTI = List.of(
            "A che punto è il rimborso #2?",
            "Posso approvare il rimborso #2?",
            "Qual è lo stato del rimborso #1?",
            "Non approvare il rimborso #2",
            "Se approvo il rimborso #2, cosa succede?",
            "Il rimborso #2 è stato approvato",
            "Segna un rimborso da 20 euro per taxi",
            "Aggiungi un rimborso da 20 euro per taxi",
            "Quanto vale il rimborso #2?",
            "Ciao",
            "",
            "Approva la proposta commerciale");

    private static final List<Stato> STATI = List.of(
            new Stato("Segna come eseguito il rimborso #2", "EXECUTED"),
            new Stato("Approva il rimborso della cena", "APPROVED"),
            new Stato("Rifiuta il rimborso #3", "REJECTED"),
            new Stato("Chiudi il rimborso #2", "EXECUTED"),
            new Stato("Paga il rimborso #1", "EXECUTED"),
            new Stato("Approva e poi esegui il rimborso #2", null),
            new Stato("Aggiorna il rimborso #2", null),
            new Stato("Respingi il rimborso #4", "REJECTED"));

    private static final List<Ordine> ORDINI_DIRETTI = List.of(
            new Ordine("aggiungi un rimborso 20 euro per taxi", "20.00", "taxi"),
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
            "",
            "allora un rimborso per hotel da 120 euro");

    private static final List<Aggiornamento> AGGIORNAMENTI_DIRETTI = List.of(
            new Aggiornamento("Segna come eseguito il rimborso #1", "EXECUTED", 1L, null),
            new Aggiornamento("Approva il rimborso della cena", "APPROVED", null, "cena"),
            new Aggiornamento("esegui il rimborso del taxi", "EXECUTED", null, "taxi"),
            new Aggiornamento("Rifiuta il rimborso n. 3", "REJECTED", 3L, null),
            new Aggiornamento("Approva il rimborso per l'hotel", "APPROVED", null, "hotel"));

    private static final List<String> NON_AGGIORNAMENTI_DIRETTI = List.of(
            "Aggiorna il rimborso #2",
            "Approva il rimborso",
            "Qual è lo stato del rimborso #2?",
            "Approva e poi esegui il rimborso #2",
            "",
            "Posso approvare il rimborso #2?");

    private static final List<Avviso> AVVISI = List.of(
            new Avviso("allora un rimborso per hotel da 120 euro", "Aggiungi un rimborso da 120 € per hotel"),
            new Avviso("un rimborso da 50€ per cena", "Aggiungi un rimborso da 50 € per cena"),
            new Avviso("Rimborso hotel 120 euro", "Aggiungi un rimborso da 120 €"),
            new Avviso("rimborso #2 eseguito", "Segna come eseguito il rimborso #2"),
            new Avviso("il rimborso della cena approvato", "Approva il rimborso della cena"),
            new Avviso("rimborso #3 rifiutato", "Rifiuta il rimborso #3"));

    private static final List<String> SENZA_AVVISO = List.of(
            "Ciao",
            "Qual è il limite per l'hotel?",
            "Posso farmi rimborsare 200€?",
            "Il rimborso hotel è di 150 euro",
            "Aggiungi un rimborso da 20 euro per taxi",
            "Segna come eseguito il rimborso #2",
            "Ho speso 40 euro per una cena",
            "Rimborso #2?",
            "");

    private static final List<String> DICHIARA_CREAZIONE = List.of(
            "Creo una richiesta di rimborso per hotel da 120 euro.  Lo strumento ha risposto:  {   \"reimbursement_request_id\": \"RR123456\",   \"amount\": 120.0 }  Il tuo rimborso è stato registrato con successo.",
            "La tua richiesta di rimborso è stata creata con successo.",
            "Ho aperto la richiesta di rimborso di 40 euro.",
            "Rimborso registrato: ID RR123456.",
            "Aggiungo un rimborso da 20 euro per taxi.");

    private static final List<String> NON_DICHIARA_CREAZIONE = List.of(
            "Quale importo vuoi rimborsare?",
            "La richiesta non è stata creata perché l'importo supera il massimo.",
            "Il limite per una cena è di 50 euro [policy.pdf, p. 2].",
            "Non ho trovato questa informazione nei documenti caricati.",
            "Indicami l'importo del rimborso, per favore.",
            "");

    private static final List<String> DICHIARA_AGGIORNAMENTO = List.of(
            "Ho approvato il rimborso #2.",
            "Il rimborso #2 è stato segnato come eseguito.",
            "Rimborso aggiornato: RR123456",
            "Ho aggiornato lo stato del rimborso.");

    private static final List<String> NON_DICHIARA_AGGIORNAMENTO = List.of(
            "Quale rimborso vuoi approvare?",
            "Il rimborso non è stato aggiornato perché è già eseguito.",
            "Il limite per una cena è di 50 euro [policy.pdf, p. 2].",
            "Indicami il numero del rimborso, per favore.",
            "");

    @Test
    void riconosceLeRichiesteDiCreazione() {
        for (String m : CREAZIONI) {
            assertThat(ReimbursementIntent.isActionRequest(m)).as(m).isTrue();
        }
    }

    @Test
    void bloccaDomandeIpotesiNegazioniAffermazioniEFrasiSenzaVerbo() {
        for (String m : NON_CREAZIONI) {
            assertThat(ReimbursementIntent.isActionRequest(m)).as(m).isFalse();
        }
    }

    @Test
    void riconosceGliOrdiniDiAggiornamentoDiStato() {
        for (String m : AGGIORNAMENTI) {
            assertThat(ReimbursementIntent.isStatusUpdateRequest(m)).as(m).isTrue();
            assertThat(ReimbursementIntent.isActionRequest(m)).as("non è una creazione: " + m).isFalse();
        }
    }

    @Test
    void bloccaLeDomandeSulloStatoELeFrasiSenzaVerboDiAggiornamento() {
        for (String m : NON_AGGIORNAMENTI) {
            assertThat(ReimbursementIntent.isStatusUpdateRequest(m)).as(m).isFalse();
        }
    }

    @Test
    void ilNuovoStatoLoDecideIlTestoDellUtente() {
        for (Stato s : STATI) {
            assertThat(ReimbursementIntent.requestedStatus(s.message())).as(s.message()).isEqualTo(s.status());
        }
    }

    @Test
    void interpretaUnOrdineDirettoDiCreazioneConUnSoloImporto() {
        for (Ordine o : ORDINI_DIRETTI) {
            ReimbursementIntent.DirectRequest r = ReimbursementIntent.parseDirectRequest(o.message());
            assertThat(r).as(o.message()).isNotNull();
            assertThat(r.amount()).as(o.message()).isEqualTo(o.amount());
            assertThat(r.description()).as(o.message()).isEqualTo(o.description());
        }
    }

    @Test
    void nonInterpretaCioCheNonEUnOrdineDiCreazioneNonAmbiguo() {
        for (String m : NON_ORDINI_DIRETTI) {
            assertThat(ReimbursementIntent.parseDirectRequest(m)).as(m).isNull();
        }
    }

    @Test
    void interpretaUnOrdineDirettoDiAggiornamento() {
        for (Aggiornamento a : AGGIORNAMENTI_DIRETTI) {
            ReimbursementIntent.DirectUpdate u = ReimbursementIntent.parseDirectUpdate(a.message());
            assertThat(u).as(a.message()).isNotNull();
            assertThat(u.status()).as(a.message()).isEqualTo(a.status());
            assertThat(u.number()).as(a.message()).isEqualTo(a.number());
            assertThat(u.keyword()).as(a.message()).isEqualTo(a.keyword());
        }
    }

    @Test
    void nonInterpretaUnAggiornamentoAmbiguoOIncompleto() {
        for (String m : NON_AGGIORNAMENTI_DIRETTI) {
            assertThat(ReimbursementIntent.parseDirectUpdate(m)).as(m).isNull();
        }
    }

    @Test
    void leFrasiSenzaVerboProduconoUnAvvisoConcreto() {
        for (Avviso a : AVVISI) {
            ReimbursementIntent.Hint hint = ReimbursementIntent.verblessHint(a.message());
            assertThat(hint).as(a.message()).isNotNull();
            assertThat(hint.warning()).isEqualTo(ReimbursementIntent.HINT_WARNING);
            assertThat(hint.answer()).as(a.message()).contains("manca un verbo esplicito").contains("«" + a.suggestion() + "»");
        }
    }

    @Test
    void ilRestoNonProduceAvvisi() {
        for (String m : SENZA_AVVISO) {
            assertThat(ReimbursementIntent.verblessHint(m)).as(m).isNull();
        }
    }

    @Test
    void smascheraLeConfermeFalse() {
        for (String a : DICHIARA_CREAZIONE) {
            assertThat(ReimbursementIntent.claimsCreation(a)).as(a).isTrue();
        }
        for (String a : NON_DICHIARA_CREAZIONE) {
            assertThat(ReimbursementIntent.claimsCreation(a)).as(a).isFalse();
        }
        for (String a : DICHIARA_AGGIORNAMENTO) {
            assertThat(ReimbursementIntent.claimsUpdate(a)).as(a).isTrue();
        }
        for (String a : NON_DICHIARA_AGGIORNAMENTO) {
            assertThat(ReimbursementIntent.claimsUpdate(a)).as(a).isFalse();
        }
    }
}
