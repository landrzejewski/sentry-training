package pl.training.sentry.module02;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Kontrakt konfiguracji Sentry dla checkout-api: odczytany ze zmiennych wdrożenia i zwalidowany,
 * zanim cokolwiek trafi do SDK.
 *
 * <p>Scenariusz 1 pokazuje, jak kontrakt przerywa start przy błędnym wdrożeniu, a scenariusz 2, jak
 * wyłącza SDK na laptopie. Każda reguła odpowiada realnej awarii konfiguracji, której SDK samo
 * nie zgłosi:</p>
 * <ul>
 *   <li>pusty {@code SENTRY_DSN} (np. pusta wartość w szablonie wdrożenia) nie jest dla SDK błędem,
 *   tylko sygnałem „wyłącz się”, więc środowisko przestaje raportować bez żadnego komunikatu;</li>
 *   <li>{@code APP_STAGE=prod} utworzyłoby w Sentry nowe, trwałe środowisko obok
 *   {@code production};</li>
 *   <li>release {@code latest} odrzuca serwer Sentry, a samo {@code 4.12.0} jest niejednoznaczne
 *   w organizacji z wieloma usługami;</li>
 *   <li>DSN skopiowany z produkcji do {@code .env} na laptopie wysyłałby lokalne błędy do projektu
 *   produkcyjnego.</li>
 * </ul>
 *
 * <p>Wszystkie problemy są zbierane i zgłaszane razem, żeby jedno wdrożenie poprawiało wszystkie
 * naraz. Klasa nie zna {@code SentryOptions}: przepisaniem kontraktu do SDK zajmuje się
 * {@link SentryOptionsConfigurer}.</p>
 *
 * @param stage           etap wdrożenia, źródło wartości {@code environment}
 * @param dsn             DSN projektu albo {@code null}, gdy SDK jest wyłączone
 * @param release         identyfikator artefaktu z pipeline albo {@code null} dla {@link DeploymentStage#LOCAL}
 * @param errorSampleRate losowy odsetek error events albo {@code null}, czyli brak losowania
 * @param warnings        problemy, które nie blokują startu, ale ktoś powinien je zobaczyć w logu
 */
public record SentrySettings(
        DeploymentStage stage,
        String dsn,
        String release,
        Double errorSampleRate,
        List<String> warnings
) {

    /** Nazwa usługi: tag {@code service.name} i obowiązkowy prefiks release. */
    public static final String SERVICE_NAME = "checkout-api";

    private static final int MAX_RELEASE_LENGTH = 200;

    public SentrySettings {
        warnings = List.copyOf(warnings);
    }

    /** SDK wysyła dane tylko poza laptopem developera. */
    public boolean enabled() {
        return stage != DeploymentStage.LOCAL;
    }

    /**
     * Odczytuje kontrakt ze zmiennych wdrożenia: {@code APP_STAGE}, {@code SENTRY_DSN},
     * {@code SENTRY_RELEASE} (ustawia pipeline CI/CD) i opcjonalnie {@code SENTRY_SAMPLE_RATE}.
     *
     * @throws IllegalArgumentException z listą wszystkich naruszeń kontraktu
     */
    public static SentrySettings fromEnvironment(Map<String, String> env) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        DeploymentStage stage = null;
        try {
            stage = DeploymentStage.fromConfig(env.get("APP_STAGE"));
        } catch (IllegalArgumentException exception) {
            errors.add(exception.getMessage());
        }
        String dsn = blankToNull(env.get("SENTRY_DSN"));
        String release = blankToNull(env.get("SENTRY_RELEASE"));
        Double sampleRate = parseSampleRate(env.get("SENTRY_SAMPLE_RATE"), errors);

        if (stage == DeploymentStage.LOCAL) {
            // Lokalny proces nie wysyła nic, nawet gdy DSN przyszedł z .env skopiowanego z produkcji.
            // Nie blokujemy startu, bo developer nie musi wiedzieć, skąd zmienna trafiła do jego shella,
            // ale ostrzeżenie mówi, że w konfiguracji leży DSN, którego tu być nie powinno.
            if (dsn != null) {
                warnings.add("SENTRY_DSN zignorowany dla APP_STAGE=local: lokalny proces nie wysyła eventów");
            }
            dsn = null;
            release = null;
        } else if (stage != null) {
            if (dsn == null) {
                errors.add("pusty SENTRY_DSN dla APP_STAGE=" + env.get("APP_STAGE")
                        + ": SDK z pustym DSN wyłącza się bez błędu i środowisko po cichu przestaje raportować");
            }
            validateRelease(release, errors);
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "Niepoprawna konfiguracja Sentry: " + String.join("; ", errors));
        }
        return new SentrySettings(stage, dsn, release, sampleRate, warnings);
    }

    private static void validateRelease(String release, List<String> errors) {
        if (release == null) {
            errors.add("brak SENTRY_RELEASE: eventów nie powiążesz z artefaktem ani commitem");
            return;
        }
        String prefix = SERVICE_NAME + "@";
        if (!release.startsWith(prefix) || release.length() == prefix.length()) {
            errors.add("SENTRY_RELEASE '" + release + "' musi mieć postać " + prefix
                    + "<wersja>: sama wersja jest niejednoznaczna w organizacji z wieloma usługami");
            return;
        }
        String version = release.substring(prefix.length());
        if (version.equalsIgnoreCase("latest")) {
            errors.add("SENTRY_RELEASE '" + release + "' nie wskazuje konkretnego artefaktu");
        }
        // Serwer Sentry odrzuca nazwę release z nową linią, tabulatorem, / albo \ i dłuższą niż 200
        // znaków. Kontrakt jest ostrzejszy: odrzuca każdy biały znak, także spację.
        if (release.chars().anyMatch(ch -> Character.isWhitespace(ch) || ch == '/' || ch == '\\')) {
            errors.add("SENTRY_RELEASE '" + release + "' zawiera biały znak, / albo \\: nowej linii, tabulatora,"
                    + " / i \\ serwer Sentry nie przyjmie, spację odrzuca kontrakt wdrożenia");
        }
        if (release.length() > MAX_RELEASE_LENGTH) {
            errors.add("SENTRY_RELEASE ma " + release.length() + " znaków, a serwer Sentry przyjmuje najwyżej "
                    + MAX_RELEASE_LENGTH);
        }
    }

    private static Double parseSampleRate(String raw, List<String> errors) {
        if (blankToNull(raw) == null) {
            // Brak wartości to świadoma decyzja: bez losowania każdy error event przechodzi dalej.
            return null;
        }
        try {
            double rate = Double.parseDouble(raw.trim());
            if (!Double.isNaN(rate) && rate >= 0.0 && rate <= 1.0) {
                return rate;
            }
        } catch (NumberFormatException ignored) {
            // Obsłużone niżej jednym komunikatem, np. dla wartości „25%”.
        }
        errors.add("SENTRY_SAMPLE_RATE '" + raw + "' musi być liczbą od 0.0 do 1.0");
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
