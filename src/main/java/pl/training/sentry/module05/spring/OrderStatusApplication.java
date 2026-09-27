package pl.training.sentry.module05.spring;

import io.sentry.Sentry;
import io.sentry.SentryOptions;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import pl.training.sentry.support.TrainingSentry;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Aplikacja Spring Boot order-status-api z {@code sentry-spring-boot-4-starter}.
 *
 * <p>Pokazuje, co starter robi bez kodu Sentry w kontrolerze: transakcje {@code http.server},
 * raport nieobsłużonych wyjątków i Structured Logs z Logback (scenariusz 7). SDK inicjalizuje
 * starter, a nie {@code TrainingSentry.init}: starter startuje przy ustawionej właściwości
 * {@code sentry.dsn}, a ustawienia szkoleniowe (environment, release, tag modułu, transport
 * konsolowy) dokłada bean {@code Sentry.OptionsConfiguration}.</p>
 *
 * <p>Z {@code sentry-logback} na classpath starter podpina też SentryAppender do root loggera,
 * a progi ustawiają właściwości {@code sentry.logging.*}.
 * Zwykły log SLF4J z kontrolera trafia więc do Sentry bez żadnego kodu Sentry. To samo w czystej
 * Javie robi {@code logback-sentry.xml} (pakiet {@code logback}).</p>
 */
@SpringBootApplication
public class OrderStatusApplication {

    /** Uruchomiona aplikacja: adres serwera i zamknięcie kontekstu Spring. */
    public record Running(ConfigurableApplicationContext context, URI baseUri) implements AutoCloseable {
        @Override
        public void close() {
            // Logi czekają w kolejce SDK do 5 s. Zamknięcie kontekstu i tak zamyka SDK
            // (Sentry.close opróżnia kolejkę), a flush wysyła je jeszcze przy działającej aplikacji.
            Sentry.flush(5_000);
            context.close();
        }
    }

    /**
     * @param customizer dodatkowe ustawienia SDK nakładane na końcu (testy podmieniają transport)
     */
    public static Running start(Consumer<SentryOptions> customizer) {
        SpringApplication application = new SpringApplication(OrderStatusApplication.class);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("sentry.dsn", TrainingSentry.dsn());
        // Bez stawki (albo beana samplera) tracing jest wyłączony: SentryTracingFilter
        // przepuszcza request bez transakcji, a błędy nadal trafiają do Sentry.
        properties.put("sentry.traces-sample-rate", "1.0");
        properties.put("sentry.logs.enabled", "true");
        // Z sentry-logback na classpath starter sam podpina SentryAppender do root loggera
        // (sentry.logging.enabled domyślnie true). Progi zapisane jawnie, z wartościami
        // domyślnymi appendera: event od ERROR, breadcrumb i Structured Log od INFO.
        properties.put("sentry.logging.minimum-event-level", "error");
        properties.put("sentry.logging.minimum-breadcrumb-level", "info");
        properties.put("sentry.logging.minimum-level", "info");
        properties.put("server.port", "0");
        properties.put("spring.main.banner-mode", "off");
        properties.put("logging.level.root", "warn");
        // Poziom loggera działa przed appenderem: bez tej linii INFO z kontrolera nie dotarłby
        // do Sentry mimo progu minimum-level=info.
        properties.put("logging.level.pl.training.sentry.module05.spring", "info");
        // Tomcat loguje każdy nieobsłużony wyjątek z kontrolera ze stack trace. Wyłączone tylko
        // po to, żeby wydruk demo pokazywał to, co trafia do Sentry. W aplikacji produkcyjnej
        // tego logu się nie wyłącza.
        properties.put("logging.level.org.apache.catalina.core.ContainerBase", "off");
        application.setDefaultProperties(properties);
        application.addInitializers(context ->
                context.getBeanFactory().registerSingleton("module05SentryCustomizer", customizer));
        ConfigurableApplicationContext context = application.run();
        int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port", "0"));
        return new Running(context, URI.create("http://localhost:" + port));
    }

    @Bean
    Sentry.OptionsConfiguration<SentryOptions> trainingSentryOptions(Consumer<SentryOptions> module05SentryCustomizer) {
        return options -> {
            TrainingSentry.applyTrainingDefaults(options, "module05");
            module05SentryCustomizer.accept(options);
        };
    }
}
