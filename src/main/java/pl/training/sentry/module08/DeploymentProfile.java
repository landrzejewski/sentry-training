package pl.training.sentry.module08;

import java.util.Set;

/**
 * Profil stosowalności: co wiemy o wdrożeniu, zanim spojrzymy na konfigurację SDK.
 *
 * <p>Profil pochodzi z inwentaryzacji (usługa, środowisko, zatwierdzony projekt, release
 * z pipeline, możliwości techniczne), a nie z konfiguracji, którą audytujemy. Dlatego audyt może
 * stwierdzić, że konfiguracja jest inna niż powinna: porównuje ją z niezależnym źródłem.</p>
 *
 * @param service             nazwa usługi
 * @param environment         wartość ze słownika środowisk, której ma używać SDK
 * @param approvedDsn         DSN zatwierdzonego projektu albo pusty tekst, gdy profil nie ma
 *                            zatwierdzonej telemetrii (typowo lokalny laptop)
 * @param release             release zbudowanego artefaktu, taki sam jak w pipeline i w rekordzie deployu
 * @param inAppPackage        pakiet kodu aplikacji, którego ramki mają być oznaczone jako in-app
 * @param maxTracesSampleRate górna granica {@code tracesSampleRate} z polityki samplingu
 * @param active              możliwości aktywne, które włączają kontrole zależne od profilu
 * @param excluded            możliwości jawnie wykluczone; ich kontrole dostają {@code NOT_APPLICABLE}
 */
public record DeploymentProfile(
        String service,
        String environment,
        String approvedDsn,
        String release,
        String inAppPackage,
        double maxTracesSampleRate,
        Set<Capability> active,
        Set<Capability> excluded
) {

    /**
     * Możliwości, od których zależy stosowalność kontroli. Kontrola wykluczonej możliwości dostaje
     * {@code NOT_APPLICABLE}. Wykluczenie wynika z inwentaryzacji, nigdy z tego, że funkcja
     * nie została wdrożona albo nie przechodzi testu.
     */
    public enum Capability {
        /** Usługa wysyła transakcje i spany. */
        TRACING,
        /** Usługa woła API spoza organizacji (np. operatora płatności). */
        THIRD_PARTY_HTTP_CALLS,
        /** Usługa przyjmuje requesty z zewnątrz, które mogą nieść obce nagłówki {@code sentry-trace}. */
        PUBLIC_ENDPOINTS,
        /** Usługa wysyła Sentry Structured Logs. */
        SENTRY_LOGS
    }

    public DeploymentProfile {
        active = Set.copyOf(active);
        excluded = Set.copyOf(excluded);
        // Pominięcie możliwości nie oznacza NOT_APPLICABLE: każda musi być jawnie aktywna albo
        // wykluczona, inaczej zapomniana pozycja inwentaryzacji po cichu wyłączyłaby kontrolę.
        for (Capability capability : Capability.values()) {
            if (active.contains(capability) == excluded.contains(capability)) {
                throw new IllegalArgumentException("Możliwość " + capability
                        + " musi być albo aktywna, albo wykluczona");
            }
        }
    }

    public boolean telemetryApproved() {
        return !approvedDsn.isBlank();
    }

    public boolean has(Capability capability) {
        return active.contains(capability);
    }
}
