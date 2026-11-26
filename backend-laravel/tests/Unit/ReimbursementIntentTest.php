<?php

namespace Tests\Unit;

use App\Services\Chat\ReimbursementIntent;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/**
 * Barriera sull'INTENTO dei due tool dei rimborsi (creare / aggiornare): quali frasi sono ordini e quali sono
 * domande, ipotesi o frasi senza verbo esplicito. La STESSA tabella di frasi è nei test di Spring Boot
 * (ReimbursementIntentTest.java): le due implementazioni devono comportarsi in modo identico.
 */
class ReimbursementIntentTest extends TestCase
{
    /** @return list<array{string}> */
    public static function creare(): array
    {
        return [
            ['Apri un rimborso da 40€ per cena'],
            ['Aprimi un rimborso da 40€ per cena'],
            ['aggiungi un rimborso 20 euro per taxi'],
            ['aggiungi un rimborso 20 euro per hotel'],
            ['Crea una richiesta di rimborso da 50 euro'],
            ['Inserisci un rimborso di 30€ per il parcheggio'],
            ['Registra un rimborso da 15,50 euro per il taxi'],
            ['Richiedi un rimborso da 80€ per l\'hotel'],
            ['Invia una richiesta di rimborso da 60 euro'],
            ['Puoi aprirmi un rimborso da 40€?'],
            ['Vorrei creare una richiesta di rimborso da 40 euro'],
            ['Per favore, aggiungimi un rimborso da 25 euro per taxi'],
            ['Fammi un rimborso da 40€ per cena'],
            ['Mi fai un rimborso da 40 euro per cena?'],
            ['Ok, aggiungi un rimborso da 20 euro per taxi'],
            ['Sì, aprilo pure'],
            ['Apri un rimborso per la cena di ieri'],
            ['Open a 40 euro reimbursement for dinner'],
            ['Add a reimbursement of 20 euro for taxi'],
            ['Mettimi un rimborso da 35 euro per il taxi'],
            ['Segna un rimborso da 20 euro per taxi'],
        ];
    }

    /** @return list<array{string}> */
    public static function non_creare(): array
    {
        return [
            ['Posso farmi rimborsare 200€ per una cena?'],
            ['Qual è il limite per l\'hotel?'],
            ['Qual è il limite massimo per una cena?'],
            ['Quanto posso spendere per l\'hotel a notte?'],
            ['Posso aprire un rimborso da 40€?'],
            ['Come apro un rimborso?'],
            ['Se apro un rimborso da 40€, quanto ci mette?'],
            ['Il rimborso hotel è di 150 euro'],
            ['Non aprire nessun rimborso'],
            ['Che rimborso spetta per un hotel da 120 euro?'],
            ['Mi spetta un rimborso per l\'hotel?'],
            ['Un rimborso per hotel da 120 euro è possibile?'],
            ['Ho diritto a un rimborso da 120 euro per l\'hotel?'],
            ['Quanto è il rimborso per il taxi?'],
            ['Allora posso farmi rimborsare 50 euro per il taxi?'],
            ['Mi rimborsate 200€ per una cena?'],
            ['Ciao'],
            [''],
            ['Is there a reimbursement for 120 euro hotels?'],
            ['Don\'t create a reimbursement of 40 euro'],
            ['allora un rimborso per hotel da 120 euro'],
            ['un rimborso da 50€ per cena'],
            ['Rimborso hotel 120 euro'],
            ['Segna come eseguito il rimborso #2'],
            ['Approva il rimborso della cena'],
        ];
    }

    /** @return list<array{string}> */
    public static function aggiornare(): array
    {
        return [
            ['Segna come eseguito il rimborso #2'],
            ['Approva il rimborso della cena'],
            ['Rifiuta il rimborso #3'],
            ['Esegui il rimborso #1'],
            ['Aggiorna il rimborso #2 come approvato'],
            ['Chiudi il rimborso #2'],
            ['Metti il rimborso #2 come approvato'],
            ['Approva #2'],
            ['Per favore, approvami il rimborso della cena'],
            ['Paga il rimborso #1'],
            ['Segna come eseguito il rimborso della cena'],
            ['Mark refund #2 as paid'],
            ['Approve the refund #2'],
        ];
    }

    /** @return list<array{string}> */
    public static function non_aggiornare(): array
    {
        return [
            ['A che punto è il rimborso #2?'],
            ['Posso approvare il rimborso #2?'],
            ['Qual è lo stato del rimborso #1?'],
            ['Non approvare il rimborso #2'],
            ['Se approvo il rimborso #2, cosa succede?'],
            ['Il rimborso #2 è stato approvato'],
            ['Segna un rimborso da 20 euro per taxi'],
            ['Aggiungi un rimborso da 20 euro per taxi'],
            ['Quanto vale il rimborso #2?'],
            ['Ciao'],
            [''],
            ['Approva la proposta commerciale'],
        ];
    }

    /** @return list<array{string, ?string}> */
    public static function stati(): array
    {
        return [
            ['Segna come eseguito il rimborso #2', 'EXECUTED'],
            ['Approva il rimborso della cena', 'APPROVED'],
            ['Rifiuta il rimborso #3', 'REJECTED'],
            ['Chiudi il rimborso #2', 'EXECUTED'],
            ['Paga il rimborso #1', 'EXECUTED'],
            ['Approva e poi esegui il rimborso #2', null],
            ['Aggiorna il rimborso #2', null],
            ['Respingi il rimborso #4', 'REJECTED'],
        ];
    }

    /** @return list<array{string, string, ?string}> */
    public static function ordini_di_creazione(): array
    {
        return [
            ['aggiungi un rimborso 20 euro per taxi', '20.00', 'taxi'],
            ['aggiungi un rimborso 20 euro per hotel', '20.00', 'hotel'],
            ['Apri un rimborso da 40€ per cena', '40.00', 'cena'],
            ['Registra un rimborso da 15,50 euro per il taxi', '15.50', 'taxi'],
            ['Per favore, aggiungimi un rimborso da 25 euro per taxi', '25.00', 'taxi'],
            ['Per favore, apri un rimborso da 25 euro', '25.00', null],
            ['Apri un rimborso da 40€', '40.00', null],
            ['Richiedi un rimborso da 80€ per l\'hotel', '80.00', 'hotel'],
        ];
    }

    /** @return list<array{string}> */
    public static function non_ordini_di_creazione(): array
    {
        return [
            ['Apri un rimborso per la cena di ieri'],
            ['Apri un rimborso da 40 o 50 euro'],
            ['Posso farmi rimborsare 200€?'],
            ['Apri un rimborso per meno 5 euro'],
            ['Apri un rimborso di -5 euro'],
            ['Sì, aprilo pure'],
            ['Qual è il limite per l\'hotel?'],
            [''],
            ['allora un rimborso per hotel da 120 euro'],
        ];
    }

    /** @return list<array{string, string, ?int, ?string}> */
    public static function ordini_di_aggiornamento(): array
    {
        return [
            ['Segna come eseguito il rimborso #1', 'EXECUTED', 1, null],
            ['Approva il rimborso della cena', 'APPROVED', null, 'cena'],
            ['esegui il rimborso del taxi', 'EXECUTED', null, 'taxi'],
            ['Rifiuta il rimborso n. 3', 'REJECTED', 3, null],
            ['Approva il rimborso per l\'hotel', 'APPROVED', null, 'hotel'],
        ];
    }

    /** @return list<array{string}> */
    public static function non_ordini_di_aggiornamento(): array
    {
        return [
            ['Aggiorna il rimborso #2'],
            ['Approva il rimborso'],
            ['Qual è lo stato del rimborso #2?'],
            ['Approva e poi esegui il rimborso #2'],
            [''],
            ['Posso approvare il rimborso #2?'],
        ];
    }

    /** @return list<array{string, string}> */
    public static function senza_verbo(): array
    {
        return [
            ['allora un rimborso per hotel da 120 euro', 'Aggiungi un rimborso da 120 € per hotel'],
            ['un rimborso da 50€ per cena', 'Aggiungi un rimborso da 50 € per cena'],
            ['Rimborso hotel 120 euro', 'Aggiungi un rimborso da 120 €'],
            ['rimborso #2 eseguito', 'Segna come eseguito il rimborso #2'],
            ['il rimborso della cena approvato', 'Approva il rimborso della cena'],
            ['rimborso #3 rifiutato', 'Rifiuta il rimborso #3'],
        ];
    }

    /** @return list<array{string}> */
    public static function senza_avviso(): array
    {
        return [
            ['Ciao'],
            ['Qual è il limite per l\'hotel?'],
            ['Posso farmi rimborsare 200€?'],
            ['Il rimborso hotel è di 150 euro'],
            ['Aggiungi un rimborso da 20 euro per taxi'],
            ['Segna come eseguito il rimborso #2'],
            ['Ho speso 40 euro per una cena'],
            ['Rimborso #2?'],
            [''],
        ];
    }

    /** @return list<array{string}> */
    public static function conferme_false_creazione(): array
    {
        return [
            ['Creo una richiesta di rimborso per hotel da 120 euro.  Lo strumento ha risposto:  {   "reimbursement_request_id": "RR123456",   "amount": 120.0 }  Il tuo rimborso è stato registrato con successo.'],
            ['La tua richiesta di rimborso è stata creata con successo.'],
            ['Ho aperto la richiesta di rimborso di 40 euro.'],
            ['Rimborso registrato: ID RR123456.'],
            ['Aggiungo un rimborso da 20 euro per taxi.'],
        ];
    }

    /** @return list<array{string}> */
    public static function risposte_oneste_creazione(): array
    {
        return [
            ['Quale importo vuoi rimborsare?'],
            ['La richiesta non è stata creata perché l\'importo supera il massimo.'],
            ['Il limite per una cena è di 50 euro [policy.pdf, p. 2].'],
            ['Non ho trovato questa informazione nei documenti caricati.'],
            ['Indicami l\'importo del rimborso, per favore.'],
            [''],
        ];
    }

    /** @return list<array{string}> */
    public static function conferme_false_aggiornamento(): array
    {
        return [
            ['Ho approvato il rimborso #2.'],
            ['Il rimborso #2 è stato segnato come eseguito.'],
            ['Rimborso aggiornato: RR123456'],
            ['Ho aggiornato lo stato del rimborso.'],
        ];
    }

    /** @return list<array{string}> */
    public static function risposte_oneste_aggiornamento(): array
    {
        return [
            ['Quale rimborso vuoi approvare?'],
            ['Il rimborso non è stato aggiornato perché è già eseguito.'],
            ['Il limite per una cena è di 50 euro [policy.pdf, p. 2].'],
            ['Indicami il numero del rimborso, per favore.'],
            [''],
        ];
    }

    #[DataProvider('creare')]
    public function test_e_un_ordine_di_creare_un_rimborso(string $message): void
    {
        $this->assertTrue(ReimbursementIntent::isActionRequest($message), $message);
    }

    #[DataProvider('non_creare')]
    public function test_non_e_un_ordine_di_creare_un_rimborso(string $message): void
    {
        $this->assertFalse(ReimbursementIntent::isActionRequest($message), $message);
    }

    #[DataProvider('aggiornare')]
    public function test_e_un_ordine_di_aggiornare_lo_stato(string $message): void
    {
        $this->assertTrue(ReimbursementIntent::isStatusUpdateRequest($message), $message);
        $this->assertFalse(ReimbursementIntent::isActionRequest($message), 'un aggiornamento non è una creazione: '.$message);
    }

    #[DataProvider('non_aggiornare')]
    public function test_non_e_un_ordine_di_aggiornare_lo_stato(string $message): void
    {
        $this->assertFalse(ReimbursementIntent::isStatusUpdateRequest($message), $message);
    }

    #[DataProvider('stati')]
    public function test_lo_stato_richiesto_lo_decide_il_testo_dell_utente(string $message, ?string $expected): void
    {
        $this->assertSame($expected, ReimbursementIntent::requestedStatus($message), $message);
    }

    #[DataProvider('ordini_di_creazione')]
    public function test_interpreta_un_ordine_diretto_di_creazione(string $message, string $amount, ?string $description): void
    {
        $this->assertSame(['amount' => $amount, 'description' => $description], ReimbursementIntent::parseDirectRequest($message), $message);
    }

    #[DataProvider('non_ordini_di_creazione')]
    public function test_non_interpreta_cio_che_non_e_un_ordine_di_creazione(string $message): void
    {
        $this->assertNull(ReimbursementIntent::parseDirectRequest($message), $message);
    }

    #[DataProvider('ordini_di_aggiornamento')]
    public function test_interpreta_un_ordine_diretto_di_aggiornamento(string $message, string $status, ?int $number, ?string $keyword): void
    {
        $this->assertSame(['status' => $status, 'number' => $number, 'keyword' => $keyword], ReimbursementIntent::parseDirectUpdate($message), $message);
    }

    #[DataProvider('non_ordini_di_aggiornamento')]
    public function test_non_interpreta_cio_che_non_e_un_ordine_di_aggiornamento(string $message): void
    {
        $this->assertNull(ReimbursementIntent::parseDirectUpdate($message), $message);
    }

    #[DataProvider('senza_verbo')]
    public function test_una_frase_senza_verbo_produce_un_avviso_con_un_esempio_concreto(string $message, string $suggestion): void
    {
        $hint = ReimbursementIntent::verblessHint($message);

        $this->assertNotNull($hint, $message);
        $this->assertSame(ReimbursementIntent::HINT_WARNING, $hint['warning']);
        $this->assertStringContainsString('manca un verbo esplicito', $hint['answer']);
        $this->assertStringContainsString("«{$suggestion}»", $hint['answer'], $message);
    }

    #[DataProvider('senza_avviso')]
    public function test_gli_altri_messaggi_non_producono_avvisi(string $message): void
    {
        $this->assertNull(ReimbursementIntent::verblessHint($message), $message);
    }

    #[DataProvider('conferme_false_creazione')]
    public function test_riconosce_una_risposta_che_dichiara_un_rimborso_creato(string $answer): void
    {
        $this->assertTrue(ReimbursementIntent::claimsCreation($answer), $answer);
    }

    #[DataProvider('risposte_oneste_creazione')]
    public function test_non_scambia_per_conferma_di_creazione_una_risposta_onesta(string $answer): void
    {
        $this->assertFalse(ReimbursementIntent::claimsCreation($answer), $answer);
    }

    #[DataProvider('conferme_false_aggiornamento')]
    public function test_riconosce_una_risposta_che_dichiara_un_aggiornamento(string $answer): void
    {
        $this->assertTrue(ReimbursementIntent::claimsUpdate($answer), $answer);
    }

    #[DataProvider('risposte_oneste_aggiornamento')]
    public function test_non_scambia_per_conferma_di_aggiornamento_una_risposta_onesta(string $answer): void
    {
        $this->assertFalse(ReimbursementIntent::claimsUpdate($answer), $answer);
    }

    public function test_l_apostrofo_tipografico_e_equivalente_a_quello_semplice(): void
    {
        $this->assertFalse(ReimbursementIntent::isActionRequest('Cos’è il limite per l’hotel?'));
        $this->assertTrue(ReimbursementIntent::isActionRequest('Richiedi un rimborso da 80€ per l’hotel'));
    }
}
