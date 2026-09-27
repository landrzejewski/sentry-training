package pl.training.sentry.module08;

import io.sentry.SentryOptions;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Jedno miejsce, w którym aplikacja zamienia swoją konfigurację na {@link SentryOptions}.
 *
 * <p>Klucze właściwości mają te same nazwy co w starterze Spring Boot ({@code sentry.dsn},
 * {@code sentry.environment} itd.), więc przykład odpowiada konfiguracji, którą uczestnicy znają
 * z {@code application.properties}.</p>
 *
 * <p>Klasa robi dokładnie to, co mówi plik konfiguracji. Nie poprawia go i nie waliduje polityki:
 * brak {@code sentry.environment} zostaje brakiem, a SDK uzupełni go wartością domyślną. Zgodność
 * z polityką sprawdza osobno {@link ReadinessAuditor} na opcjach działającego SDK.</p>
 */
public record SentryConfiguration(
        String dsn,
        String environment,
        String release,
        Double sampleRate,
        Double tracesSampleRate,
        boolean sendDefaultPii,
        List<String> inAppIncludes,
        List<String> tracePropagationTargets,
        boolean strictTraceContinuation,
        String orgId,
        boolean logsEnabled
) {

    /**
     * Parsuje właściwości przed {@code Sentry.init}, a nie w jego callbacku.
     *
     * <p>PUŁAPKA: {@code Sentry.init(options -> ...)} łapie każdy wyjątek rzucony w callbacku,
     * zapisuje go w logu SDK (domyślny logger niczego nie wypisuje) i inicjalizuje SDK z opcjami
     * ustawionymi przed wyjątkiem. Literówka w {@code sentry.traces-sample-rate} nie zatrzymuje
     * startu aplikacji, tylko po cichu gubi wszystkie ustawienia, które callback nadałby po niej.
     * Parsowanie tutaj zamienia taki błąd w wyjątek przy starcie.</p>
     */
    public static SentryConfiguration from(Map<String, String> properties) {
        return new SentryConfiguration(
                // PUŁAPKA: DSN null (brak klucza) nie wyłącza SDK. Sentry.init rzuca wtedy
                // IllegalArgumentException „DSN is required”, więc aplikacja bez DSN nie wstaje.
                // Wyłączenie to pusty tekst albo enabled=false. Typowa „naprawa” wyjątku, czyli
                // wklejenie DSN z produkcji do wspólnej konfiguracji, jest źródłem scenariusza 1.
                properties.getOrDefault("sentry.dsn", ""),
                properties.get("sentry.environment"),
                properties.get("sentry.release"),
                rate(properties.get("sentry.sample-rate")),
                rate(properties.get("sentry.traces-sample-rate")),
                Boolean.parseBoolean(properties.get("sentry.send-default-pii")),
                list(properties.get("sentry.in-app-includes")),
                // null, a nie pusta lista: brak klucza oznacza wartość domyślną SDK. Pusta lista
                // znaczyłaby „nie propaguj nigdzie”, co zrywa trace także między własnymi usługami.
                properties.containsKey("sentry.trace-propagation-targets")
                        ? list(properties.get("sentry.trace-propagation-targets"))
                        : null,
                Boolean.parseBoolean(properties.get("sentry.strict-trace-continuation")),
                properties.get("sentry.org-id"),
                Boolean.parseBoolean(properties.get("sentry.logs.enabled"))
        );
    }

    /** Przepisuje konfigurację do opcji SDK. Wywoływane w callbacku {@code Sentry.init}. */
    public void applyTo(SentryOptions options) {
        options.setDsn(dsn);
        // Brak wartości zostaje null. SDK zwraca wtedy z getEnvironment() „production”, release
        // zostaje pusty, a sampleRate null działa jak 1.0.
        options.setEnvironment(environment);
        options.setRelease(release);
        options.setSampleRate(sampleRate);
        options.setTracesSampleRate(tracesSampleRate);
        options.setSendDefaultPii(sendDefaultPii);
        inAppIncludes.forEach(options::addInAppInclude);
        options.setTracePropagationTargets(tracePropagationTargets);
        options.setStrictTraceContinuation(strictTraceContinuation);
        options.setOrgId(orgId);
        options.getLogs().setEnabled(logsEnabled);
    }

    private static Double rate(String value) {
        if (value == null) {
            return null;
        }
        // Zakres sprawdza też setter SDK, ale jego wyjątek poleciałby w callbacku i zostałby połknięty.
        double rate = Double.parseDouble(value);
        if (rate < 0.0 || rate > 1.0) {
            throw new IllegalArgumentException("Sample rate poza zakresem 0.0..1.0: " + value);
        }
        return rate;
    }

    private static List<String> list(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::strip).toList();
    }
}
