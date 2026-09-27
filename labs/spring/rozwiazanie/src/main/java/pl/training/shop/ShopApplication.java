package pl.training.shop;

import io.sentry.Sentry;
import io.sentry.SentryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ShopApplication {

    private static final Logger log = LoggerFactory.getLogger(ShopApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(ShopApplication.class, args);
    }

    /**
     * Krok 2: punkt kontrolny przy starcie. Brak DSN nie powoduje błędu ani ostrzeżenia,
     * aplikacja po prostu nic nie wysyła. Jedna linia w logu pokazuje, co faktycznie działa.
     */
    @Bean
    ApplicationRunner sentryStartupCheck() {
        return args -> {
            SentryOptions options = Sentry.getCurrentScopes().getOptions();
            if (Sentry.isEnabled()) {
                log.info("Sentry włączone: environment={}, release={}", options.getEnvironment(), options.getRelease());
            } else {
                log.warn("Sentry wyłączone: brak DSN (ustaw zmienną SENTRY_DSN)");
            }
        };
    }
}
