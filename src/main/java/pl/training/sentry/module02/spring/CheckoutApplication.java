package pl.training.sentry.module02.spring;

import io.sentry.IScopes;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.web.context.support.StandardServletEnvironment;
import pl.training.sentry.module02.PaymentGateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * checkout-api jako aplikacja Spring Boot z {@code sentry-spring-boot-4-starter}.
 *
 * <p>W odróżnieniu od czystej Javy SDK inicjalizuje starter, a nie kod aplikacji: konfiguracja to
 * właściwości {@code sentry.*} (pliki w {@code src/main/resources/module02}) i beany
 * ({@link SentryTrainingConfiguration}, {@link SentryPrivacyConfiguration}). Bramka płatności jest
 * w trakcie awarii, więc każda płatność kończy się błędem 500, który raportuje integracja Spring.</p>
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import({CheckoutController.class, SentryTrainingConfiguration.class})
public class CheckoutApplication {

    @Bean
    PaymentGateway paymentGateway() {
        return PaymentGateway.resettingConnection();
    }

    /**
     * Uruchamia aplikację na losowym porcie.
     *
     * <p>Zmienne środowiskowe podajemy jawnie zamiast brać je z procesu, żeby wynik scenariuszy nie
     * zależał od shella uczestnika (tryb online szkolenia eksportuje {@code SENTRY_DSN}). Źródło
     * dostaje standardową nazwę {@code systemEnvironment}, więc Spring Boot mapuje je dokładnie
     * tak jak prawdziwe zmienne: {@code SENTRY_DSN} staje się właściwością {@code sentry.dsn}.</p>
     *
     * @param profile              aktywny profil albo {@code null}
     * @param environmentVariables zmienne środowiskowe procesu aplikacji
     * @param extraConfiguration   dodatkowe klasy konfiguracji, np. {@link SentryPrivacyConfiguration}
     */
    public static Running start(String profile, Map<String, String> environmentVariables, Class<?>... extraConfiguration) {
        StandardServletEnvironment environment = new StandardServletEnvironment();
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.<String, Object>copyOf(environmentVariables)));

        List<Class<?>> sources = new ArrayList<>(List.of(CheckoutApplication.class));
        sources.addAll(List.of(extraConfiguration));

        SpringApplicationBuilder application = new SpringApplicationBuilder(sources.toArray(Class<?>[]::new))
                .environment(environment)
                // Własny katalog konfiguracji, żeby pliki modułu 2 nie mieszały się z innymi modułami.
                .properties("spring.config.location=classpath:/module02/");
        if (profile != null) {
            application.profiles(profile);
        }
        return new Running(application.run());
    }

    /** Uruchomiona aplikacja. Zamknięcie kontekstu zamyka też SDK zainicjalizowane przez starter. */
    public record Running(ConfigurableApplicationContext context) implements AutoCloseable {

        public int port() {
            return ((WebServerApplicationContext) context).getWebServer().getPort();
        }

        /** Czy auto-konfiguracja Sentry się uruchomiła (bean {@code IScopes} tworzy tylko ona). */
        public boolean sentryAutoConfigured() {
            return context.getBeanNamesForType(IScopes.class).length > 0;
        }

        @Override
        public void close() {
            // Starter rejestruje IScopes jako bean z metodą close(), którą Spring wywołuje przy
            // zamknięciu kontekstu, a ona zamyka SDK (Sentry.close) i wysyła zaległe eventy.
            context.close();
        }
    }
}
