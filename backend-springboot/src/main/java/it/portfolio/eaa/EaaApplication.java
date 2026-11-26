package it.portfolio.eaa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Enterprise AI Assistant — backend Spring Boot.
 *
 * Implementa lo stesso contratto API del backend Laravel (docs/api/openapi.yaml) sopra lo stesso
 * database PostgreSQL + pgvector: il frontend Angular può usare l'uno o l'altro cambiando solo l'URL.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class EaaApplication {

    public static void main(String[] args) {
        SpringApplication.run(EaaApplication.class, args);
    }
}
