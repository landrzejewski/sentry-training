package pl.training.sentry.module05.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.joran.spi.JoranException;
import io.sentry.logback.SentryAppender;
import org.slf4j.LoggerFactory;

import java.net.URL;

/**
 * Podłączenie Logback do Sentry w czystej Javie: z pliku XML albo programowo.
 *
 * <p>Jeden wpis loggera może trafić do trzech kanałów Sentry (event, breadcrumb, Structured Log),
 * każdy z własnym progiem. {@link #fromXml} wczytuje {@code module05/logback-sentry.xml}, czyli
 * postać, w jakiej konfigurację trzyma aplikacja.
 * {@link #programmatic} buduje to samo w kodzie, z progami podanymi jako argument: tak scenariusz 8
 * pokazuje skutek zmiany jednego progu. W Spring Boot tę klasę zastępuje starter i właściwości
 * {@code sentry.logging.*} (pakiet {@code spring}).</p>
 *
 * <p>Obie metody zakładają, że SDK działa już po {@code Sentry.init}. {@code SentryAppender.start()}
 * sam woła {@code Sentry.init} z najniższym priorytetem ({@code InitPriority.LOWEST}), więc nie
 * nadpisze konfiguracji z kodu.</p>
 *
 * <p>PUŁAPKA: gdy SDK nie działa (brak {@code Sentry.init} albo {@code enabled=false}), a DSN jest
 * w zmiennej {@code SENTRY_DSN}, właściwości systemowej {@code sentry.dsn} lub w
 * {@code sentry.properties}, start appendera inicjalizuje SDK sam, z domyślnym transportem.
 * Wyłączenie Sentry flagą w kodzie nie wyłącza wtedy wysyłki z logów.</p>
 */
public final class LogbackSentryConfig {

    /** Progi SentryAppender: od jakiego poziomu log staje się eventem, breadcrumbem, Structured Log. */
    public record Thresholds(Level event, Level breadcrumb, Level log) {
        /** Wartości domyślne SentryAppender w 8.54.0, takie same jak w {@code logback-sentry.xml}. */
        public static final Thresholds DEFAULTS = new Thresholds(Level.ERROR, Level.INFO, Level.INFO);
    }

    public static final String XML = "/module05/logback-sentry.xml";
    public static final String SENTRY_APPENDER = "SENTRY";

    private static final String CONSOLE_PATTERN = "log>  %-5level %logger{0} [%X{correlation_id:-}] %msg%n%nopex";

    private LogbackSentryConfig() {
    }

    /** Konfiguracja z pliku XML, tak jak w aplikacji z {@code logback.xml}. */
    public static void fromXml(String resource) {
        LoggerContext context = resetContext();
        URL xml = LogbackSentryConfig.class.getResource(resource);
        if (xml == null) {
            throw new IllegalArgumentException("Brak pliku " + resource + " na classpath");
        }
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        try {
            configurator.doConfigure(xml);
        } catch (JoranException exception) {
            throw new IllegalStateException("Niepoprawna konfiguracja Logback " + resource, exception);
        }
    }

    /** Ta sama konfiguracja co w {@code logback-sentry.xml}, zbudowana w kodzie. */
    public static void programmatic(Thresholds thresholds) {
        LoggerContext context = resetContext();

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(CONSOLE_PATTERN);
        encoder.start();
        ConsoleAppender<ILoggingEvent> console = new ConsoleAppender<>();
        console.setContext(context);
        console.setName("CONSOLE");
        console.setEncoder(encoder);
        console.start();

        SentryAppender sentry = new SentryAppender();
        sentry.setContext(context);
        sentry.setName(SENTRY_APPENDER);
        sentry.setMinimumEventLevel(thresholds.event());
        sentry.setMinimumBreadcrumbLevel(thresholds.breadcrumb());
        sentry.setMinimumLevel(thresholds.log());
        sentry.start();

        context.getLogger("pl.training.sentry.module05.logback").setLevel(Level.DEBUG);
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.INFO);
        root.addAppender(console);
        root.addAppender(sentry);
    }

    /** Appender Sentry podpięty do root loggera albo null. */
    public static SentryAppender sentryAppender() {
        return (SentryAppender) context().getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender(SENTRY_APPENDER);
    }

    /** Odłącza wszystkie appendery i przywraca poziomy (testy, koniec demo). */
    public static void reset() {
        resetContext();
    }

    /**
     * Czysty kontekst przed konfiguracją. W tym procesie mógł go wcześniej skonfigurować Spring Boot
     * (scenariusz 7): jego appender Sentry zostaje na root loggerze także po zamknięciu aplikacji.
     */
    private static LoggerContext resetContext() {
        LoggerContext context = context();
        context.reset();
        return context;
    }

    private static LoggerContext context() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }
}
