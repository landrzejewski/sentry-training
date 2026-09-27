package pl.training.sentry.support;

import io.sentry.AsyncHttpTransportFactory;
import io.sentry.Sentry;
import io.sentry.SentryOptions;

import java.io.PrintStream;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Wspólna inicjalizacja Sentry dla programów demonstracyjnych wszystkich modułów.
 *
 * <p>Dwa tryby pracy:</p>
 * <ul>
 *   <li><b>online</b>: gdy ustawiona jest zmienna {@code SENTRY_DSN}, eventy trafiają do
 *   prawdziwego projektu Sentry, a konsola pokazuje ich podgląd;</li>
 *   <li><b>offline</b>: bez {@code SENTRY_DSN} SDK działa normalnie (scope, integracje,
 *   {@code beforeSend}, sampling), ale transport tylko wypisuje envelope na konsolę.
 *   Nic nie opuszcza procesu, więc demo można uruchomić bez konta Sentry.</li>
 * </ul>
 *
 * <p>Zmienne środowiskowe:</p>
 * <ul>
 *   <li>{@code SENTRY_DSN}: DSN projektu (brak oznacza tryb offline);</li>
 *   <li>{@code SENTRY_ENVIRONMENT}: domyślnie {@value #DEFAULT_ENVIRONMENT};</li>
 *   <li>{@code SENTRY_RELEASE}: domyślnie {@value #DEFAULT_RELEASE}.</li>
 * </ul>
 *
 * <p>Environment i release ustawiamy jawnie zawsze, także w demo. Bez nich SDK przyjmie
 * {@code production} i brak wersji: eventy z laptopa wyglądają jak produkcyjne i nie da się
 * ich powiązać z wersją artefaktu.</p>
 *
 * <p>PRODUKCJA: w aplikacji konfiguracja pochodzi z jednego zwalidowanego miejsca
 * (moduł 2), a release z pipeline CI/CD (moduł 4). Ta klasa upraszcza to na potrzeby
 * szkolenia.</p>
 */
public final class TrainingSentry {

    public static final String DEFAULT_ENVIRONMENT = "training";
    public static final String DEFAULT_RELEASE = "sentry-training@1.0.0";

    /**
     * DSN zastępczy dla trybu offline. Pusty DSN wyłącza SDK całkowicie, a brak DSN kończy
     * {@code Sentry.init} wyjątkiem, więc potrzebny jest poprawny składniowo adres. Domena
     * {@code .invalid} jest zarezerwowana i nigdy nie zostanie rozwiązana, a transport
     * konsolowy i tak nie wykonuje połączeń.
     */
    static final String OFFLINE_DSN = "https://public@training.invalid/1";

    private TrainingSentry() {
    }

    /**
     * Inicjalizuje SDK dla demo modułu.
     *
     * @param module     nazwa modułu, trafia do tagu {@code training.module}, żeby w jednym
     *                   projekcie Sentry odróżnić eventy z różnych modułów szkolenia
     * @param customizer ustawienia specyficzne dla scenariuszy modułu, stosowane na końcu,
     *                   więc mogą nadpisać wartości domyślne
     */
    public static TrainingSession init(String module, Consumer<SentryOptions> customizer) {
        return init(module, customizer, System.getenv(), System.out);
    }

    /**
     * Jak {@link #init(String, Consumer)}, ale wydruk transportu trafia do {@code out}. Służy
     * scenariuszom z serią jednakowych eventów, które wypisują własne podsumowanie zamiast
     * pełnego wydruku każdego eventu. W trybie online eventy trafiają do Sentry bez zmian.
     */
    public static TrainingSession init(String module, Consumer<SentryOptions> customizer, PrintStream out) {
        return init(module, customizer, System.getenv(), out);
    }

    static TrainingSession init(
            String module,
            Consumer<SentryOptions> customizer,
            java.util.Map<String, String> env,
            PrintStream out
    ) {
        boolean online = isOnline(env);
        Sentry.init(options -> {
            applyTrainingDefaults(options, module, env, out);
            customizer.accept(options);
        });

        out.printf("%nSentry [%s] tryb=%s | environment=%s | release=%s%n",
                module,
                online ? "online (eventy trafiają do Sentry)" : "offline (tylko konsola)",
                Sentry.getCurrentScopes().getOptions().getEnvironment(),
                Objects.requireNonNullElse(Sentry.getCurrentScopes().getOptions().getRelease(), "(brak)"));
        return new TrainingSession(online);
    }

    /**
     * Ustawienia szkoleniowe (DSN, environment, release, tag modułu, transport) nakładane na
     * gotowe {@link SentryOptions}.
     *
     * <p>W czystej Javie wywołuje je {@link #init}. W aplikacji Spring Boot SDK inicjalizuje
     * starter, więc moduł rejestruje bean {@code Sentry.OptionsConfiguration<SentryOptions>},
     * który woła tę metodę. Starter uruchamia się tylko przy ustawionej właściwości
     * {@code sentry.dsn}, dlatego aplikacja przekazuje ją z {@link #dsn()}.</p>
     */
    public static void applyTrainingDefaults(SentryOptions options, String module) {
        applyTrainingDefaults(options, module, System.getenv(), System.out);
    }

    /** DSN z {@code SENTRY_DSN} albo DSN zastępczy trybu offline. */
    public static String dsn() {
        return isOnline(System.getenv()) ? System.getenv("SENTRY_DSN") : OFFLINE_DSN;
    }

    static void applyTrainingDefaults(
            SentryOptions options,
            String module,
            java.util.Map<String, String> env,
            PrintStream out
    ) {
        boolean online = isOnline(env);
        options.setDsn(online ? env.get("SENTRY_DSN") : OFFLINE_DSN);
        options.setEnvironment(env.getOrDefault("SENTRY_ENVIRONMENT", DEFAULT_ENVIRONMENT));
        options.setRelease(env.getOrDefault("SENTRY_RELEASE", DEFAULT_RELEASE));
        options.setTag("training.module", module);

        // Transport decyduje tylko o tym, dokąd trafia gotowy envelope. Cała reszta potoku
        // SDK działa identycznie w obu trybach, więc wnioski z trybu offline są wiarygodne.
        options.setTransportFactory((sentryOptions, requestDetails) -> new ConsoleEnvelopeTransport(
                sentryOptions,
                online ? new AsyncHttpTransportFactory().create(sentryOptions, requestDetails) : null,
                out
        ));
    }

    private static boolean isOnline(java.util.Map<String, String> env) {
        String dsn = env.get("SENTRY_DSN");
        return dsn != null && !dsn.isBlank();
    }

    /**
     * Uchwyt sesji demo. Zamknięcie wysyła zaległe dane i wyłącza SDK.
     *
     * <p>PUŁAPKA: SDK wysyła asynchronicznie, więc eventy mogą czekać w kolejce transportu HTTP
     * albo w buforze logów. Domyślny {@code ShutdownHookIntegration} robi {@code flush} przy
     * normalnym zakończeniu JVM, ale nie przy {@code kill -9}, {@code Runtime.halt} ani przy
     * {@code setEnableShutdownHook(false)}. Krótkie procesy (CLI, zadania wsadowe, testy)
     * zamykają SDK jawnie, żeby moment wysłania nie zależał od sposobu zakończenia procesu.</p>
     */
    public record TrainingSession(boolean online) implements AutoCloseable {

        @Override
        public void close() {
            Sentry.flush(5_000);
            Sentry.close();
        }
    }
}
