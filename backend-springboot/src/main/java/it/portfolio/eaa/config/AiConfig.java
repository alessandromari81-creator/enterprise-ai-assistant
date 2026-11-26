package it.portfolio.eaa.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Il ChatClient è l'API di alto livello di Spring AI per parlare con il modello (prompt, storico, tool).
 * Il ChatModel concreto (Ollama o OpenAI) lo crea l'autoconfigurazione in base a spring.ai.model.chat;
 * temperatura e nome del modello vengono dalle proprietà spring.ai.<provider>.chat.options.*.
 */
@Configuration
class AiConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
