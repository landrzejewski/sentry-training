package pl.training.sentry.module04;

import io.sentry.Sentry;
import io.sentry.SentryOptions;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import pl.training.sentry.support.TrainingSentry;

import java.util.HashMap;
import java.util.Map;

/**
 * Wersja orders-api na Spring Boot ze starterem {@code sentry-spring-boot-4-starter}.
 *
 * <p>W artefakcie leży {@code git.properties}
 * zapisany przez build (git-commit-id-maven-plugin), a Spring Boot wystawia go jako bean
 * {@code GitProperties}. Starter Sentry, po zastosowaniu wszystkich beanów
 * {@code Sentry.OptionsConfiguration}, sprawdza, czy release jest {@code null}. Jeśli tak i opcja
 * {@code sentry.use-git-commit-id-as-release} ma wartość domyślną {@code true}, ustawia release na
 * {@code git.commit.id}, czyli goły SHA. CI rejestruje tymczasem {@code orders-api@<SHA>}
 * i w Sentry powstają dwa niezależne release.</p>
 *
 * <p>Aplikacja nie skanuje pakietu: jedyne beany to te zadeklarowane tutaj i konfiguracja
 * automatyczna. Serwer HTTP jest wyłączony, bo scenariusz wywołuje endpoint bezpośrednio.</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class OrdersApiSpringApp {

    /** Nazwa release w konwencji CI, oparta na tej samej rewizji co {@code git.properties}. */
    public static final String CI_RELEASE = "orders-api@8f24c7a4f735cb1dd0a4c2159a9db15fc6b7c728";

    @Bean
    Sentry.OptionsConfiguration<SentryOptions> trainingSentryOptions() {
        return options -> {
            // Starter wiąże właściwości sentry.* z opcjami, zanim wywoła ten callback, więc tu
            // widać release z konfiguracji aplikacji (albo null, gdy aplikacja go nie ustawiła).
            String configuredRelease = options.getRelease();
            TrainingSentry.applyTrainingDefaults(options, "module04");
            // applyTrainingDefaults nadaje release szkoleniowy. Przywracamy decyzję aplikacji,
            // żeby starter zachował się tak jak w usłudze bez szkoleniowych dodatków.
            options.setRelease(configuredRelease);
        };
    }

    @Bean
    CouponEndpoint couponEndpoint() {
        return new CouponEndpoint();
    }

    /**
     * Startuje aplikację.
     *
     * @param sentryRelease wartość {@code sentry.release} albo {@code null}, gdy aplikacja jej nie
     *                      ustawia; w usłudze trafia do {@code application.properties} z builda,
     *                      np. przez filtrowanie zasobów Maven ({@code sentry.release=@sentry.release@})
     * @param extraSources  dodatkowe klasy konfiguracji, np. przechwytywanie eventów w testach
     */
    public static ConfigurableApplicationContext start(String sentryRelease, Class<?>... extraSources) {
        Map<String, Object> properties = new HashMap<>();
        // Starter Sentry włącza się tylko przy ustawionym sentry.dsn.
        properties.put("sentry.dsn", TrainingSentry.dsn());
        properties.put("spring.info.git.location", "classpath:pl/training/sentry/module04/git.properties");
        properties.put("spring.main.web-application-type", "none");
        properties.put("spring.main.banner-mode", "off");
        properties.put("spring.main.log-startup-info", "false");
        properties.put("logging.level.root", "warn");
        // Integracja Logback (moduł 5) jest w projekcie, a starter rejestruje jej appender sam.
        // Ten moduł jej nie omawia, więc jest wyłączona.
        properties.put("sentry.logging.enabled", "false");
        if (sentryRelease != null) {
            properties.put("sentry.release", sentryRelease);
        }
        Class<?>[] sources = new Class<?>[extraSources.length + 1];
        sources[0] = OrdersApiSpringApp.class;
        System.arraycopy(extraSources, 0, sources, 1, extraSources.length);
        return new SpringApplicationBuilder(sources).properties(properties).run();
    }
}
