package pl.training.sentry.module02;

import io.sentry.SentryOptions;

import java.util.List;

/**
 * Jedno miejsce, w którym zwalidowany kontrakt {@link SentrySettings} trafia do {@link SentryOptions}.
 *
 * <p>Klasa nie wywołuje {@code Sentry.init}, więc test sprawdza wynik na zwykłym
 * {@code new SentryOptions()}, bez globalnego stanu i transportu.
 * W czystej Javie wołamy ją w callbacku {@code Sentry.init}, w Spring Boot z beanu
 * {@code Sentry.OptionsConfiguration}. Inicjalizacja klienta następuje dokładnie raz, przez
 * mechanizm właściwy dla integracji.</p>
 *
 * <p>PUŁAPKA: druga, niezależna konfiguracja tej samej opcji (np. {@code sentry.environment}
 * w pliku i {@code setEnvironment} w kodzie) daje wynik zależny od kolejności wywołań. Test
 * scenariusza 7 ({@code optionsConfigurationBeanOverridesSentryProperties}) pokazuje, że w Spring Boot
 * bean {@code OptionsConfiguration} wygrywa z właściwościami.</p>
 */
public final class SentryOptionsConfigurer {

    /** Pakiet aplikacji: ramki z tego pakietu Sentry oznacza jako in-app i rozwija w stack trace. */
    public static final String IN_APP_PACKAGE = "pl.training.sentry.module02";

    /** Ścieżki sond syntetycznych, których błędy odrzucamy tylko przy potwierdzonym pochodzeniu. */
    public static final List<String> SYNTHETIC_CHECK_PATHS = List.of("/internal/synthetic-check");

    public void configure(SentryOptions options, SentrySettings settings) {
        // enabled i DSN zawsze razem i jawnie. Wyłączone SDK z DSN null to poprawny stan profilu
        // lokalnego. Włączone SDK bez DSN kończy Sentry.init wyjątkiem, a z pustym DSN wyłącza się
        // po cichu, dlatego SentrySettings nie przepuszcza takiej kombinacji.
        options.setEnabled(settings.enabled());
        options.setDsn(settings.enabled() ? settings.dsn() : null);

        // Bez tej linii SDK przyjmie „production” także na stagingu i na laptopie.
        options.setEnvironment(settings.stage().sentryEnvironment());
        options.setRelease(settings.release());

        // null oznacza brak losowania: każdy error event, który przeszedł filtry, trafia do Sentry.
        options.setSampleRate(settings.errorSampleRate());

        // Wartość domyślna ustawiona jawnie, żeby zmiana była widoczna w review. Chroni tylko przed
        // danymi zbieranymi automatycznie przez integracje (IP, cookies, body, część nagłówków),
        // a nie przed danymi, które kod sam dopisze do eventu.
        options.setSendDefaultPii(false);

        // Tag ustawiony w opcjach trafia do każdego eventu procesu. Nadaje się tylko do wartości
        // stałych dla procesu. Tag zależny od requestu (traffic.origin) należy do scope requestu.
        options.setTag("service.name", SentrySettings.SERVICE_NAME);
        options.addInAppInclude(IN_APP_PACKAGE);

        // Filtr typu: dokładna klasa głównego wyjątku, bez podklas i bez łańcucha cause (scenariusz 4).
        options.addIgnoredExceptionForType(ClientAbortedException.class);

        // Brak ignoredErrors to świadoma decyzja. Dla backendu Java typ wyjątku nadany w miejscu,
        // które zna przyczynę, jest pewniejszy niż dopasowanie tekstu (scenariusz 4).
        options.setBeforeSend(new FilteringBeforeSend(SYNTHETIC_CHECK_PATHS, new EventDataScrubber()));
    }
}
