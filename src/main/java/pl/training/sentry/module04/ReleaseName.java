package pl.training.sentry.module04;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Nazwa release zwalidowana według reguł Sentry, zanim trafi do artefaktu, SDK albo CI.
 *
 * <p>Konstruktor odrzuca to, czego serwer Sentry nie przyjmie jako release: więcej niż 200 znaków,
 * znaki {@code \n}, {@code \r}, {@code \f}, tabulator, {@code /}, {@code \}, wartości {@code .},
 * {@code ..}, {@code latest} w dowolnej wielkości liter i same białe znaki. Lista odpowiada walidacji
 * serwera ({@code Release.is_valid_version} i limit {@code MAX_VERSION_LENGTH} w Sentry). Wartości
 * zarezerwowane serwer porównuje po obcięciu białych znaków z brzegów, więc klasa robi to samo.
 * Klasa nie zależy od Sentry: używa jej zarówno krok CI, który wylicza nazwę, jak i aplikacja,
 * która przy starcie odczytuje ją z artefaktu.</p>
 *
 * <p>PUŁAPKA: Java SDK nie waliduje release. {@code options.setRelease("latest")} przechodzi bez
 * ostrzeżenia, a event wychodzi z tą wartością. Serwer przyjmuje event, ale usuwa z niego samą
 * wartość release (błąd przetwarzania {@code invalid_data}), więc w Sentry event nie ma wersji
 * (scenariusz 4, replika C; sprawdzone na self-hosted 26.9.0). Dlatego walidacja należy do kodu
 * aplikacji i pipeline.</p>
 *
 * <p>Konwencja {@code komponent@wersja+build} nie jest regułą Sentry, tylko polityką organizacji
 * ({@link #forBuild}, {@link #component()}). Goły SHA albo sam numer {@code 5.4.1} są poprawne dla
 * Sentry, ale release jest globalny w organizacji: dwie usługi z tym samym numerem dostaną jeden
 * wspólny release.</p>
 */
public record ReleaseName(String value) {

    static final int MAX_LENGTH = 200;

    public ReleaseName {
        Objects.requireNonNull(value, "value");
        Optional<String> violation = violation(value);
        if (violation.isPresent()) {
            throw new IllegalArgumentException("Nieprawidłowa nazwa release \"" + printable(value) + "\": "
                    + violation.get());
        }
    }

    /** Nazwa w konwencji {@code komponent@wersja+build}, np. {@code orders-api@5.4.1+185}. */
    public static ReleaseName forBuild(String component, String version, String buildNumber) {
        return new ReleaseName(component + "@" + version + "+" + buildNumber);
    }

    /**
     * Powód, dla którego Sentry odrzuci wartość jako release, albo pusty wynik dla wartości poprawnej.
     */
    public static Optional<String> violation(String candidate) {
        if (candidate.isBlank()) {
            return Optional.of("pusta albo złożona z samych białych znaków");
        }
        if (candidate.length() > MAX_LENGTH) {
            return Optional.of("ma " + candidate.length() + " znaków, limit to " + MAX_LENGTH);
        }
        for (char forbidden : new char[]{'\n', '\r', '\t', '\f', '/', '\\'}) {
            if (candidate.indexOf(forbidden) >= 0) {
                return Optional.of("zawiera niedozwolony znak " + printable(String.valueOf(forbidden)));
            }
        }
        // Serwer porównuje wartości zarezerwowane po obcięciu białych znaków, więc " latest " też odpada.
        String stripped = candidate.strip();
        if (stripped.equals(".") || stripped.equals("..")) {
            return Optional.of("wartość zarezerwowana " + stripped);
        }
        if (stripped.toLowerCase(Locale.ROOT).equals("latest")) {
            return Optional.of("wartość zarezerwowana latest (bez względu na wielkość liter)");
        }
        return Optional.empty();
    }

    /**
     * Prefiks komponentu, czyli część przed {@code @}. Pusty wynik oznacza nazwę bez prefiksu
     * usługi, np. goły SHA albo sam numer wersji, który może kolidować z innym projektem.
     */
    public Optional<String> component() {
        int at = value.indexOf('@');
        return at > 0 ? Optional.of(value.substring(0, at)) : Optional.empty();
    }

    @Override
    public String toString() {
        return value;
    }

    private static String printable(String text) {
        String escaped = text.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("\f", "\\f");
        return escaped.length() > 40 ? escaped.substring(0, 37) + "..." : escaped;
    }
}
