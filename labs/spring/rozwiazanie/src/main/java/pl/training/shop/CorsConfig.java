package pl.training.shop;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Część Angular, krok A4: frontend z http://localhost:4200 wywołuje ten backend (inny origin).
 * Sentry SDK w przeglądarce dokleja sentry-trace i baggage, więc preflight musi je dopuścić.
 * Bez nich przeglądarka zablokuje request, zanim dotrze do kontrolera.
 */
@Configuration(proxyBeanMethods = false)
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:4200", "http://localhost:4300")
                .allowedMethods("GET")
                .allowedHeaders("sentry-trace", "baggage");
    }
}
