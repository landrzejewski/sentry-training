package pl.training.sentry.module10;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryLogEventAttributeValue;
import io.sentry.SentryMetricsEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Pokazuje politykę danych osobowych dla telemetrii: osobny callback dla eventów i osobny dla
 * metryk.
 *
 * <p>Metryka dostaje atrybuty nie tylko z wywołania {@code count}/{@code gauge}/{@code distribution}. SDK
 * dokleja do niej ze scope {@code user.id}, {@code user.name} i {@code user.email}, a także
 * atrybuty ustawione przez {@code Sentry.setAttribute()}. Enumowe API {@link CheckoutMetrics}
 * nie ma na to wpływu.</p>
 *
 * <p>PUŁAPKA: {@code options.setBeforeSend} widzi tylko eventy (błędy i wiadomości). Metryki
 * przechodzą przez własny callback {@code options.getMetrics().setBeforeSend}. Zespół, który
 * filtruje e-mail w eventach, wciąż wysyła go w każdej metryce requestu (scenariusz 4).</p>
 */
public final class TelemetryPrivacy {

    private TelemetryPrivacy() {
    }

    /** Dla {@code options.setBeforeSend}: event zachowuje techniczny identyfikator użytkownika. */
    public static SentryEvent scrubEvent(SentryEvent event, Hint hint) {
        if (event.getUser() != null) {
            event.getUser().setEmail(null);
            event.getUser().setUsername(null);
        }
        return event;
    }

    /**
     * Dla {@code options.getMetrics().setBeforeSend}: usuwa e-mail i nazwę użytkownika.
     *
     * <p>{@code user.id} zostaje, bo to zatwierdzony identyfikator techniczny (moduł 1). Callback
     * zwraca metrykę zawsze: {@code null} oznaczałby odrzucenie jej w całości.</p>
     *
     * <p>PUŁAPKA: wyjątek rzucony w tym callbacku nie przerywa aplikacji. SDK odrzuca wtedy metrykę
     * (ze względu na ryzyko wycieku danych), więc błąd w polityce po cichu zeruje wykresy.
     * Politykę trzeba testować jak kod produkcyjny.</p>
     */
    public static SentryMetricsEvent scrubMetric(SentryMetricsEvent metric, Hint hint) {
        Map<String, SentryLogEventAttributeValue> attributes = metric.getAttributes();
        if (attributes != null) {
            Map<String, SentryLogEventAttributeValue> kept = new HashMap<>(attributes);
            kept.remove("user.email");
            kept.remove("user.name");
            metric.setAttributes(kept);
        }
        return metric;
    }
}
