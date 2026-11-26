package it.portfolio.eaa.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Il system prompt deve essere IDENTICO nei due backend (decisione D8): è ciò che rende confrontabili le
 * risposte di Laravel e di Spring. Se qualcuno modifica il prompt in uno solo, questo test fallisce.
 */
class PromptParityTest {

    @Test
    void ilPromptDiSpringEUgualeAQuelloDiLaravel() throws Exception {
        Path laravel = Path.of("../backend-laravel/resources/prompts/rag-system.txt");
        assumeTrue(Files.exists(laravel), "backend-laravel non presente accanto a backend-springboot");

        String spring = new ClassPathResource("prompts/rag-system.txt").getContentAsString(StandardCharsets.UTF_8);

        assertThat(spring).isEqualTo(Files.readString(laravel));
    }
}
