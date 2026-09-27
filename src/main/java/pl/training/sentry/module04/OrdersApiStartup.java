package pl.training.sentry.module04;

import io.sentry.SentryOptions;
import io.sentry.util.DebugMetaPropertiesApplier;

import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Start procesu orders-api: konfiguracja Sentry wyprowadzona z artefaktu, z którego proces wystartował.
 *
 * <p>{@link #sentryConfiguration(BuildArtifact)} to wersja docelowa: release, dist i bundle ID
 * pochodzą z plików zapisanych przez build, a walidacja kończy się, zanim ruszy
 * {@code Sentry.init}. Pozostałe metody zbierają typowe błędy, żeby scenariusze 2 i 5 mogły
 * pokazać je obok wersji docelowej.</p>
 *
 * <p>Metody zwracają konfigurację SDK zamiast same wołać {@code Sentry.init}. Demo przekazuje ją do
 * {@code TrainingSentry.init}, a testy do transportu w pamięci.</p>
 */
public final class OrdersApiStartup {

    /** Prefiks in-app kodu aplikacji. Kropka na końcu, żeby nie objął np. {@code module04legacy}. */
    public static final String IN_APP_PACKAGE = "pl.training.sentry.module04.";

    private OrdersApiStartup() {
    }

    /**
     * Wersja docelowa. Waliduje release z artefaktu i sprawdza, czy konfiguracja środowiska
     * uruchomieniowego nie próbuje go nadpisać.
     *
     * @throws IllegalStateException gdy artefakt nie ma poprawnego release albo gdy
     *                               {@code sentry.release} lub {@code SENTRY_RELEASE} podaje inną
     *                               wartość; start zatrzymuje się, zanim powstanie pierwszy event
     */
    public static Consumer<SentryOptions> sentryConfiguration(BuildArtifact artifact) {
        return sentryConfiguration(artifact, runtimeReleaseOverride());
    }

    static Consumer<SentryOptions> sentryConfiguration(BuildArtifact artifact, Optional<String> runtimeRelease) {
        Settings settings = Settings.fromArtifact(artifact);
        runtimeRelease.filter(value -> !value.equals(settings.release().value())).ifPresent(value -> {
            // Artefakt i manifest wdrożenia opisują różne wersje. Każda wybrana wartość byłaby
            // zgadywaniem, a zły release psuje regresje, commity i deploye w Sentry.
            throw new IllegalStateException("Konfiguracja uruchomieniowa podaje release " + value
                    + ", a artefakt został zbudowany jako " + settings.release()
                    + ". Usuń nadpisanie z manifestu wdrożenia albo wdróż właściwy artefakt.");
        });
        return settings::applyTo;
    }

    /**
     * PUŁAPKA: ta sama walidacja, ale wewnątrz callbacku {@code Sentry.init}.
     *
     * <p>{@code Sentry.init} łapie każdy {@code Throwable} z callbacku konfiguracji, zapisuje go
     * w loggerze SDK (domyślnie wyłączonym) i startuje dalej z tym, co zdążyło się ustawić.
     * Wyjątek nie zatrzyma więc aplikacji, a eventy wyjdą z release ustawionym wcześniej, w demo
     * z release szkoleniowym z {@code TrainingSentry}.</p>
     */
    public static Consumer<SentryOptions> validatingInsideInit(BuildArtifact artifact) {
        return options -> Settings.fromArtifact(artifact).applyTo(options);
    }

    /**
     * PUŁAPKA: poprawny kod, ale z włączoną konfiguracją zewnętrzną.
     *
     * <p>{@code Sentry.init()} bez argumentów oraz {@code setEnableExternalConfiguration(true)}
     * scalają po callbacku właściwości systemowe {@code sentry.*}, zmienne {@code SENTRY_*} i plik
     * {@code sentry.properties}. Wartość z zewnątrz wygrywa z ustawioną w kodzie, więc
     * {@code -Dsentry.release} pozostawione w manifeście po poprzednim wdrożeniu przypisze eventy
     * nowego artefaktu do starej wersji.</p>
     */
    public static Consumer<SentryOptions> withExternalConfiguration(BuildArtifact artifact) {
        Settings settings = Settings.fromArtifact(artifact);
        return options -> {
            settings.applyTo(options);
            options.setEnableExternalConfiguration(true);
        };
    }

    /**
     * PUŁAPKA: bundle ID wpisany na sztywno w kod, np. skopiowany z poprzedniego buildu.
     *
     * <p>SDK wczytuje {@value BuildArtifact#DEBUG_META} tylko wtedy, gdy opcje nie mają jeszcze
     * żadnego bundle ID. Wartość z kodu wyłącza więc plik z bieżącego buildu, a event wskazuje
     * bundle innej rewizji albo bundle, którego nikt nie wysłał.</p>
     */
    public static Consumer<SentryOptions> withHardcodedBundleId(BuildArtifact artifact, String bundleId) {
        Settings settings = Settings.fromArtifact(artifact);
        return options -> {
            options.addBundleId(bundleId);
            settings.applyTo(options);
        };
    }

    /** Wartość, którą konfiguracja zewnętrzna SDK nadpisałaby release: właściwość systemowa albo zmienna. */
    static Optional<String> runtimeReleaseOverride() {
        return Optional.ofNullable(System.getProperty("sentry.release"))
                .or(() -> Optional.ofNullable(System.getenv("SENTRY_RELEASE")));
    }

    /** Ustawienia odczytane z artefaktu i zwalidowane przed startem SDK. */
    private record Settings(ReleaseName release, String dist, Properties debugMeta) {

        static Settings fromArtifact(BuildArtifact artifact) {
            Properties buildInfo = artifact.buildInfo();
            String release = buildInfo.getProperty("release", "");
            try {
                return new Settings(new ReleaseName(release), buildInfo.getProperty("dist"),
                        artifact.debugMeta().orElse(null));
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Artefakt ma nieprawidłowy release: " + exception.getMessage(), exception);
            }
        }

        void applyTo(SentryOptions options) {
            options.setRelease(release.value());
            // null, gdy build nie zapisał dist: event wychodzi wtedy bez dist.
            options.setDist(dist);
            // Ramki z tego pakietu są kodem aplikacji (in_app w evencie). Wpływa to na grouping,
            // suspect commits i prezentację stack trace. SDK porównuje nazwę klasy ze zwykłym
            // prefiksem tekstu (startsWith), stąd kropka na końcu stałej.
            options.addInAppInclude(IN_APP_PACKAGE);
            // PRODUKCJA: gdy sentry-debug-meta.properties leży w korzeniu classpath (JAR z pluginu),
            // Sentry.init wczytuje go sam i ten krok jest zbędny. Demo uruchamia w jednym procesie
            // kilka artefaktów, więc podaje plik jawnie tej samej metodzie, której używa Sentry.init.
            // Dzięki temu obowiązuje ta sama reguła: plik jest pomijany, gdy opcje mają już bundle ID.
            DebugMetaPropertiesApplier.apply(options, debugMeta == null ? null : List.of(debugMeta));
        }
    }
}
