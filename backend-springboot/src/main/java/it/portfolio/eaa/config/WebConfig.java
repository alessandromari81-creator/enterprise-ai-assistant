package it.portfolio.eaa.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS: permette ad Angular (ng serve su http://localhost:4200) di chiamare l'API. */
@Configuration
class WebConfig implements WebMvcConfigurer {

    private final EaaProperties properties;

    WebConfig(EaaProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(properties.cors().allowedOrigins().stream().map(String::trim).toArray(String[]::new))
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(false);
    }
}
