package pl.training.sentry.checkoutapi;

import io.sentry.Sentry;
import io.sentry.SentryOptions;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import pl.training.sentry.support.TrainingSentry;

import java.net.URI;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Pokazuje backendową połowę trace z aplikacji Angular {@code frontend/checkout-web}.
 *
 * <p>Minimalna aplikacja Spring Boot z {@code sentry-spring-boot-4-starter}: jeden endpoint
 * checkoutu i polityka CORS ({@link CheckoutCors}). Starter odczytuje {@code sentry-trace}
 * i {@code baggage} z requestu przeglądarki i tworzy transakcję {@code http.server} w tym samym
 * trace co kliknięcie użytkownika.</p>
 *
 * <p>Uruchomienie (port 8095, na nim czeka frontend):</p>
 * <pre>
 * ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.checkoutapi.CheckoutApiApplication
 * SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.checkoutapi.CheckoutApiApplication
 * </pre>
 */
@SpringBootApplication
public class CheckoutApiApplication {

    /**
     * Release backendu w tej samej konwencji {@code komponent@wersja+build} co frontend
     * ({@code checkout-web@1.0.0+local}). Osobny prefiks komponentu, bo release jest globalny
     * w organizacji, a wspólna wersja i build pozwalają zestawić obie strony jednego wdrożenia.
     * PRODUKCJA: wartość wylicza raz pipeline i przekazuje przez {@code SENTRY_RELEASE}.
     */
    public static final String DEFAULT_RELEASE = "checkout-api@1.0.0+local";

    /** Originy frontendu: serwer deweloperski Angular i serwer zbudowanej aplikacji. */
    public static final String[] FRONTEND_ORIGINS = {"http://localhost:4200", "http://localhost:4300"};

    /** Uruchomiona aplikacja: adres serwera i zamknięcie kontekstu Spring. */
    public record Running(ConfigurableApplicationContext context, URI baseUri) implements AutoCloseable {
        @Override
        public void close() {
            Sentry.flush(5_000);
            context.close();
        }
    }

    public static void main(String[] args) {
        Running running = start(8095, options -> {
        });
        System.out.println("checkout-api działa: " + running.baseUri() + " (Ctrl+C kończy)");
    }

    /**
     * @param port       port HTTP; 0 wybiera wolny port (testy)
     * @param customizer dodatkowe ustawienia SDK nakładane na końcu (testy podmieniają transport)
     */
    public static Running start(int port, Consumer<SentryOptions> customizer) {
        SpringApplication application = new SpringApplication(CheckoutApiApplication.class);
        application.setDefaultProperties(Map.of(
                // Starter startuje tylko przy ustawionej właściwości sentry.dsn. W trybie offline
                // dostaje DSN zastępczy, a transport konsolowy niczego nie wysyła.
                "sentry.dsn", TrainingSentry.dsn(),
                // Bez stawki starter nie tworzy transakcji, więc przeglądarka zobaczyłaby w trace
                // tylko własną połowę. Błędy trafiałyby do Sentry niezależnie od tej wartości.
                "sentry.traces-sample-rate", "1.0",
                "server.port", String.valueOf(port),
                "spring.main.banner-mode", "off",
                "logging.level.root", "warn",
                // Tomcat loguje każdy nieobsłużony wyjątek z kontrolera ze stack trace. Wyłączone
                // tylko po to, żeby konsola pokazywała to, co trafia do Sentry. W produkcji tego
                // logu się nie wyłącza.
                "logging.level.org.apache.catalina.core.ContainerBase", "off"
        ));
        application.addInitializers(context ->
                context.getBeanFactory().registerSingleton("checkoutApiSentryCustomizer", customizer));
        ConfigurableApplicationContext context = application.run();
        int actualPort = Integer.parseInt(context.getEnvironment().getProperty("local.server.port", "0"));
        return new Running(context, URI.create("http://localhost:" + actualPort));
    }

    @Bean
    Sentry.OptionsConfiguration<SentryOptions> trainingSentryOptions(Consumer<SentryOptions> checkoutApiSentryCustomizer) {
        return options -> {
            // Tag training.module=frontend mają też eventy przeglądarki, więc jeden filtr w Sentry
            // pokazuje obie strony przykładu.
            TrainingSentry.applyTrainingDefaults(options, "frontend");
            if (System.getenv("SENTRY_RELEASE") == null) {
                options.setRelease(DEFAULT_RELEASE);
            }
            checkoutApiSentryCustomizer.accept(options);
        };
    }
}
