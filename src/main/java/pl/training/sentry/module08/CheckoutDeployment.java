package pl.training.sentry.module08;

import pl.training.sentry.module08.DeploymentProfile.Capability;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Dane wejściowe audytu checkout-api: profile wdrożeń z inwentaryzacji i pliki konfiguracji
 * z repozytorium, w wersji zastanej i poprawionej.
 *
 * <p>Konfiguracja ma strukturę znaną ze Spring Boot: plik bazowy {@code application.properties}
 * i nakładka profilu, która nadpisuje jego klucze. Wersje „as found” to stan zastany przy audycie,
 * wersje „fixed” to stan po poprawkach. Problemy wersji zastanych to typowe błędy z przeglądów
 * konfiguracji: DSN, environment, release, dane osobowe, sampling i cele propagacji.</p>
 *
 * <p>DSN jest fikcyjny. Demo inicjalizuje z nim SDK wyłącznie z transportem konsolowym bez
 * delegata, więc nic nie opuszcza procesu także w trybie online.</p>
 */
public final class CheckoutDeployment {

    /** DSN projektu checkout-api w sentry.io. Staging i produkcja to jeden projekt, różne environment. */
    public static final String PROJECT_DSN = "https://7c3b9f2e41d84a6f@o450812.ingest.de.sentry.io/4508127";

    /** Release zbudowanego artefaktu: wersja z pom.xml i numer buildu z pipeline. */
    public static final String BUILD_RELEASE = "checkout-api@4.2.1+192";

    /** Laptop developera: telemetria nie jest zatwierdzona, więc jedyna stosowalna kontrola to brak DSN. */
    public static final DeploymentProfile LOCAL = new DeploymentProfile(
            "checkout-api", "local", "", BUILD_RELEASE, "pl.acme.checkout", 0.0,
            EnumSet.allOf(Capability.class), Set.of());

    /** Staging: ten sam projekt co produkcja, environment {@code staging}, tracing do 20% requestów. */
    public static final DeploymentProfile STAGING = new DeploymentProfile(
            "checkout-api", "staging", PROJECT_DSN, BUILD_RELEASE, "pl.acme.checkout", 0.2,
            EnumSet.allOf(Capability.class), Set.of());

    /** {@code application.properties}: wspólne dla wszystkich profili. */
    static final Map<String, String> BASE = Map.of(
            // PUŁAPKA: DSN w pliku bazowym dziedziczy każdy profil, także lokalny.
            "sentry.dsn", PROJECT_DSN,
            "sentry.in-app-includes", "pl.acme.checkout",
            "sentry.strict-trace-continuation", "true",
            "sentry.logs.enabled", "true"
    );

    /** {@code application-local.properties} zastany: nic o DSN, więc zostaje DSN z pliku bazowego. */
    static final Map<String, String> LOCAL_OVERLAY_AS_FOUND = Map.of(
            "sentry.traces-sample-rate", "1.0"
    );

    /** {@code application-local.properties} po poprawce. */
    static final Map<String, String> LOCAL_OVERLAY_FIXED = Map.of(
            "sentry.traces-sample-rate", "1.0",
            // Pusty DSN wyłącza SDK. PRODUKCJA: pewniejszy jest DSN poza repozytorium, wstrzykiwany
            // przez platformę wdrożeniową, bo wtedy nowy profil domyślnie niczego nie wysyła.
            "sentry.dsn", ""
    );

    /** {@code application-staging.properties} zastany. */
    static final Map<String, String> STAGING_OVERLAY_AS_FOUND = Map.of(
            // PUŁAPKA: brak sentry.environment. SDK przyjmie „production”, więc eventy ze stagingu
            // trafią do filtrów i Alertów produkcyjnych.
            // PUŁAPKA: release przepisany z wersji w pom.xml. Każdy build 4.2.1 ma ten sam release,
            // więc Sentry nie odróżni poprawki od wersji, którą poprawia.
            "sentry.release", "checkout-api@4.2.1",
            // PUŁAPKA: „na chwilę” przy diagnozie zgłoszenia, bez daty wyłączenia i właściciela.
            "sentry.send-default-pii", "true",
            // PUŁAPKA: miało ograniczyć trace do 10%, a ogranicza błędy. sampleRate dotyczy eventów
            // błędów, tracesSampleRate transakcji, a bez tracesSampleRate tracing jest wyłączony.
            "sentry.sample-rate", "0.1"
    );

    /** {@code application-staging.properties} po poprawkach. */
    static final Map<String, String> STAGING_OVERLAY_FIXED = Map.of(
            "sentry.environment", "staging",
            // PRODUKCJA: wartość wstrzykuje pipeline (np. zmienna SENTRY_RELEASE), ta sama co przy
            // uploadzie artefaktów i w rekordzie deployu. Tu wpisana ręcznie dla czytelności.
            "sentry.release", BUILD_RELEASE,
            "sentry.traces-sample-rate", "0.1",
            // Nagłówki sentry-trace i baggage tylko do własnych usług, nie do operatora płatności.
            // W repozytorium ten klucz należy do pliku bazowego; tu jest w nakładce, żeby wszystkie
            // poprawki stagingu były w jednym miejscu.
            "sentry.trace-propagation-targets", "^https://[a-z-]+\\.internal\\.acme\\.pl(/.*)?$"
    );

    private CheckoutDeployment() {
    }

    public static Map<String, String> localAsFound() {
        return merge(BASE, LOCAL_OVERLAY_AS_FOUND);
    }

    public static Map<String, String> localFixed() {
        return merge(BASE, LOCAL_OVERLAY_FIXED);
    }

    public static Map<String, String> stagingAsFound() {
        return merge(BASE, STAGING_OVERLAY_AS_FOUND);
    }

    public static Map<String, String> stagingFixed() {
        return merge(BASE, STAGING_OVERLAY_FIXED);
    }

    /** Nakładka profilu nadpisuje klucze pliku bazowego, a pozostałe dziedziczy. */
    static Map<String, String> merge(Map<String, String> base, Map<String, String> overlay) {
        Map<String, String> merged = new HashMap<>(base);
        merged.putAll(overlay);
        return Map.copyOf(merged);
    }
}
