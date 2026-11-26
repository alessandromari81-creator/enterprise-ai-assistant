package it.portfolio.eaa.chat;

import it.portfolio.eaa.reimbursement.ReimbursementService;
import java.util.function.BiFunction;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

/**
 * Registrazione dei due tool dei rimborsi (Function Calling): "createReimbursementRequest" e "updateReimbursementStatus".
 *
 * Sono due bean:
 *  1. la FUNZIONE, un @Bean con @Description: il testo di @Description è ciò che il modello legge per capire
 *     QUANDO usare il tool. Riceve gli argomenti (ReimbursementToolArgs) e il ToolContext con i dati della
 *     richiesta corrente (conversazione, messaggi dell'utente, raccolta delle azioni eseguite);
 *  2. il ToolCallback che la incapsula con nome, descrizione e schema dei parametri.
 *
 * In Spring AI 2.x una funzione-bean non si può più richiamare "per nome" da ChatClient (era
 * .toolNames("...") nella 1.x): va passata come ToolCallback con .toolCallbacks(...) — lo fa ChatService.
 * La funzione parla con il database SOLO tramite ReimbursementService (e quindi ReimbursementRepository JPA).
 */
@Configuration
class ReimbursementTools {

    static final String TOOL_NAME = "createReimbursementRequest";

    @Bean(TOOL_NAME)
    @Description("Apre una richiesta di rimborso spese per l'utente. Da usare quando l'utente chiede di aprire, "
            + "aggiungere, creare, inserire o registrare una richiesta di rimborso (anche in forma breve e imperativa) "
            + "e ha indicato l'importo. Non va usato per domande sui limiti, sui permessi o ipotetiche.")
    BiFunction<ReimbursementToolArgs, ToolContext, String> createReimbursementRequest(ReimbursementService service) {
        ReimbursementToolHandler handler = new ReimbursementToolHandler(service);
        return handler::execute;
    }

    static final String STATUS_TOOL_NAME = "updateReimbursementStatus";

    @Bean(STATUS_TOOL_NAME)
    @Description("Aggiorna lo stato di un rimborso già esistente (approvato, rifiutato o eseguito). "
            + "Da usare quando l'utente ordina di segnare, approvare, rifiutare, eseguire, aggiornare o chiudere un rimborso, "
            + "indicandolo con il numero (quello dopo il #) oppure con una parola della sua descrizione. "
            + "Non va usato per domande.")
    BiFunction<ReimbursementStatusToolArgs, ToolContext, String> updateReimbursementStatus(ReimbursementService service) {
        ReimbursementStatusToolHandler handler = new ReimbursementStatusToolHandler(service);
        return handler::execute;
    }

    @Bean
    ToolCallback updateReimbursementStatusTool(
            BiFunction<ReimbursementStatusToolArgs, ToolContext, String> updateReimbursementStatus,
            ApplicationContext context) {

        String description = context.findAnnotationOnBean(STATUS_TOOL_NAME, Description.class).value();

        return FunctionToolCallback.builder(STATUS_TOOL_NAME, updateReimbursementStatus)
                .description(description)
                .inputType(ReimbursementStatusToolArgs.class)
                .build();
    }

    @Bean
    ToolCallback createReimbursementRequestTool(
            BiFunction<ReimbursementToolArgs, ToolContext, String> createReimbursementRequest,
            ApplicationContext context) {

        // La descrizione si legge dal @Description del bean: un'unica fonte di verità.
        String description = context.findAnnotationOnBean(TOOL_NAME, Description.class).value();

        return FunctionToolCallback.builder(TOOL_NAME, createReimbursementRequest)
                .description(description)
                .inputType(ReimbursementToolArgs.class)
                .build();
    }
}
