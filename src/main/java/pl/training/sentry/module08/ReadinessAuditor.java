package pl.training.sentry.module08;

import io.sentry.Sentry;
import io.sentry.SentryOptions;
import pl.training.sentry.module08.DeploymentProfile.Capability;
import pl.training.sentry.module08.ReadinessReport.ControlResult;
import pl.training.sentry.module08.ReadinessReport.Status;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Audyt gotowości konfiguracji SDK: porównuje opcje działającego SDK z profilem wdrożenia.
 *
 * <p>Audyt czyta {@link SentryOptions} po {@code Sentry.init}, a nie plik konfiguracji, bo dopiero
 * tam widać wartości domyślne SDK: {@code production} zamiast brakującego environment, {@code .*}
 * zamiast brakujących celów propagacji, org ID odczytane z DSN, wyłączone SDK.</p>
 *
 * <p>Nawet komplet {@code PASS} dla konfiguracji nie daje decyzji {@code READY}: kontrola
 * {@code DELIVERY} ma {@code NOT_VERIFIED}, dopóki event odbiorowy nie zostanie odnaleziony
 * w Sentry ({@link AcceptanceProbe}). Obecność ustawienia to nie dowód działania.</p>
 */
public final class ReadinessAuditor {

    static final String DELIVERY = "DELIVERY";

    private ReadinessAuditor() {
    }

    /** Audyt SDK zainicjalizowanego w tym procesie, bez dowodu dostarczenia. */
    public static ReadinessReport auditRunningSdk(DeploymentProfile profile) {
        return audit(profile, Sentry.isEnabled(), Sentry.getCurrentScopes().getOptions());
    }

    public static ReadinessReport audit(DeploymentProfile profile, boolean sdkEnabled, SentryOptions options) {
        List<ControlResult> results = new ArrayList<>();
        results.add(dsn(profile, sdkEnabled, options));

        // Kontrole konfiguracji mają sens tylko wtedy, gdy SDK ma działać i działa.
        String skippedBecause = !profile.telemetryApproved() ? "profil bez zatwierdzonej telemetrii"
                : !sdkEnabled ? "SDK wyłączone, brak konfiguracji do oceny" : null;
        List<ControlResult> configuration = List.of(
                environment(profile, options),
                release(profile, options),
                errorSampling(options),
                tracesSampling(profile, options),
                defaultPii(options),
                inApp(profile, options),
                tracePropagation(profile, options),
                traceContinuation(profile, options),
                logs(profile, options),
                new ControlResult(DELIVERY, Status.NOT_VERIFIED,
                        "brak eventu odbiorowego odnalezionego w Sentry: konfiguracja nie dowodzi dostarczenia")
        );
        for (ControlResult result : configuration) {
            if (skippedBecause == null || result.status() == Status.NOT_APPLICABLE) {
                results.add(result);
            } else if (!profile.telemetryApproved()) {
                results.add(new ControlResult(result.control(), Status.NOT_APPLICABLE, skippedBecause));
            } else {
                results.add(new ControlResult(result.control(), Status.NOT_VERIFIED, skippedBecause));
            }
        }
        return new ReadinessReport(profile, results);
    }

    private static ControlResult dsn(DeploymentProfile profile, boolean sdkEnabled, SentryOptions options) {
        String actual = describeDsn(options.getDsn());
        if (!profile.telemetryApproved()) {
            // Aktywne SDK w profilu bez telemetrii wysyła dane developera do cudzego projektu,
            // oznaczone environment i release, które SDK ma w tej chwili.
            return sdkEnabled
                    ? fail("DSN", "SDK aktywne, choć profil nie ma zatwierdzonej telemetrii: DSN " + actual
                            + ", environment=" + options.getEnvironment()
                            + ", tracesSampleRate=" + options.getTracesSampleRate())
                    : pass("DSN", "SDK wyłączone (pusty DSN), zgodnie z profilem");
        }
        if (!sdkEnabled) {
            return fail("DSN", "SDK wyłączone, choć profil wymaga telemetrii");
        }
        return profile.approvedDsn().equals(options.getDsn())
                ? pass("DSN", actual)
                : fail("DSN", actual + " zamiast zatwierdzonego " + describeDsn(profile.approvedDsn()));
    }

    private static ControlResult environment(DeploymentProfile profile, SentryOptions options) {
        // PUŁAPKA: getEnvironment() nigdy nie zwraca null. Bez jawnej wartości SDK zwraca
        // „production”, więc warunek „environment jest ustawione” zawsze przechodzi. Sprawdza się
        // zgodność z profilem.
        String actual = options.getEnvironment();
        return profile.environment().equals(actual)
                ? pass("ENVIRONMENT", actual)
                : fail("ENVIRONMENT", actual + " zamiast " + profile.environment()
                        + ("production".equals(actual) ? " (domyślna wartość SDK, gdy environment nie ustawiono)" : ""));
    }

    private static ControlResult release(DeploymentProfile profile, SentryOptions options) {
        String actual = options.getRelease();
        if (actual == null) {
            return fail("RELEASE", "brak release: eventy bez wersji i brak sesji Release Health");
        }
        return profile.release().equals(actual)
                ? pass("RELEASE", actual)
                : fail("RELEASE", actual + " zamiast " + profile.release()
                        + " z pipeline: kolejne buildy tej wersji będą nierozróżnialne");
    }

    private static ControlResult errorSampling(SentryOptions options) {
        // null oznacza domyślne 1.0, czyli wszystkie eventy błędów.
        Double rate = options.getSampleRate();
        return rate == null || rate == 1.0
                ? pass("ERROR_SAMPLING", "sampleRate=" + (rate == null ? "domyślne 1.0" : rate))
                : fail("ERROR_SAMPLING", "sampleRate=" + rate + ": SDK odrzuci losowo "
                        + Math.round((1 - rate) * 100) + "% eventów błędów przed wysłaniem");
    }

    private static ControlResult tracesSampling(DeploymentProfile profile, SentryOptions options) {
        if (!profile.has(Capability.TRACING)) {
            return notApplicable("TRACES_SAMPLING");
        }
        Double rate = options.getTracesSampleRate();
        if (rate == null || rate == 0.0) {
            return fail("TRACES_SAMPLING", "tracesSampleRate=" + rate + ": tracing wyłączony, choć profil go wymaga");
        }
        return rate <= profile.maxTracesSampleRate()
                ? pass("TRACES_SAMPLING", "tracesSampleRate=" + rate)
                : fail("TRACES_SAMPLING", "tracesSampleRate=" + rate + " powyżej limitu polityki "
                        + profile.maxTracesSampleRate());
    }

    private static ControlResult defaultPii(SentryOptions options) {
        // PASS znaczy tylko tyle, że SDK samo nie dołączy danych osobowych (np. przy true każdy
        // event dostaje user.ip_address={{auto}}, czyli Sentry zapisze adres IP nadawcy). Dane dodane
        // ręcznie przez kod aplikacji sprawdza test danych z markerami, nie ta kontrola.
        return options.isSendDefaultPii()
                ? fail("DEFAULT_PII", "sendDefaultPii=true: SDK dołącza domyślne dane osobowe, m.in. adres IP")
                : pass("DEFAULT_PII", "sendDefaultPii=false");
    }

    private static ControlResult inApp(DeploymentProfile profile, SentryOptions options) {
        // Bez prefiksu na liście SDK zostawia ramkom aplikacji pole in_app puste, a Sentry nie ma
        // po czym odróżnić kodu aplikacji od bibliotek w stack trace i przy grupowaniu.
        return options.getInAppIncludes().contains(profile.inAppPackage())
                ? pass("IN_APP", "inAppIncludes=" + options.getInAppIncludes())
                : fail("IN_APP", "brak " + profile.inAppPackage() + " w inAppIncludes=" + options.getInAppIncludes());
    }

    private static ControlResult tracePropagation(DeploymentProfile profile, SentryOptions options) {
        if (!profile.has(Capability.THIRD_PARTY_HTTP_CALLS)) {
            return notApplicable("TRACE_PROPAGATION");
        }
        // Brak ustawienia to domyślne „.*”: integracje HTTP dołączą sentry-trace i baggage
        // (z release, environment i kluczem publicznym DSN) do każdego wywołania, także do API
        // operatora płatności.
        List<String> targets = options.getTracePropagationTargets();
        return targets.contains(".*")
                ? fail("TRACE_PROPAGATION", "tracePropagationTargets=" + targets
                        + ": nagłówki trace trafią także do usług zewnętrznych")
                : pass("TRACE_PROPAGATION", "tracePropagationTargets=" + targets);
    }

    private static ControlResult traceContinuation(DeploymentProfile profile, SentryOptions options) {
        if (!profile.has(Capability.PUBLIC_ENDPOINTS)) {
            return notApplicable("TRACE_CONTINUATION");
        }
        // Org ID z opcji orgId albo z hosta DSN (o450812.ingest...). Self-hosted i Relay nie mają
        // go w DSN, więc bez opcji orgId SDK nie porówna organizacji z sentry-org_id z baggage.
        String orgId = options.getEffectiveOrgId();
        if (orgId == null) {
            return fail("TRACE_CONTINUATION", "org ID nieznane (DSN bez org ID i brak opcji orgId): "
                    + "SDK nie odróżni trace własnej organizacji od obcego");
        }
        return options.isStrictTraceContinuation()
                ? pass("TRACE_CONTINUATION", "strictTraceContinuation=true, orgId=" + orgId)
                : fail("TRACE_CONTINUATION", "strictTraceContinuation=false: trace bez sentry-org_id "
                        + "(np. nagłówek wysłany przez klienta) zostanie kontynuowany razem z jego decyzją samplingu");
    }

    private static ControlResult logs(DeploymentProfile profile, SentryOptions options) {
        if (!profile.has(Capability.SENTRY_LOGS)) {
            return notApplicable("LOGS");
        }
        // Wyłączone logi nie powodują błędu: Sentry.logger() po cichu nic nie robi.
        return options.getLogs().isEnabled()
                ? pass("LOGS", "logs.enabled=true")
                : fail("LOGS", "logs.enabled=false: wywołania Sentry.logger() nic nie wyślą");
    }

    /** DSN jako host i projekt. Klucz publiczny nie jest sekretem, ale nie ma czego szukać w raporcie. */
    static String describeDsn(String dsn) {
        if (dsn == null || dsn.isEmpty()) {
            return "(brak)";
        }
        URI uri = URI.create(dsn);
        return Objects.toString(uri.getHost()) + (uri.getPort() == -1 ? "" : ":" + uri.getPort()) + uri.getPath();
    }

    private static ControlResult pass(String control, String detail) {
        return new ControlResult(control, Status.PASS, detail);
    }

    private static ControlResult fail(String control, String detail) {
        return new ControlResult(control, Status.FAIL, detail);
    }

    private static ControlResult notApplicable(String control) {
        return new ControlResult(control, Status.NOT_APPLICABLE, "profil nie aktywuje kontroli");
    }
}
