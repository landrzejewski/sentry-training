package pl.training.sentry.module03;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code beforeSend} dla błędów bramki płatności: tag z przyczyną i fingerprint.
 *
 * <p>Szuka {@link PaymentGatewayException} w całym łańcuchu {@code cause}, bo do endpointu
 * dociera wyjątek zewnętrzny ({@link CheckoutException}).
 * Z przyczyny ustawia tag {@code payment.failure_reason} (wymiar filtrowania i routingu,
 * np. {@code tags.payment.failure_reason:rate_limited}) oraz fingerprint według
 * {@link Strategy}. Pozostałe eventy przepuszcza bez zmian.</p>
 *
 * <p>Strategie obok siebie odpowiadają temu, co spotyka się w przeglądach kodu: jedna nie zmienia
 * fingerprintu, dwie są pułapkami (oversplitting: osobne issue na każde zamówienie; overgrouping:
 * różne przyczyny w jednym issue), jedna jest wersją docelową. Scenariusz 3 porównuje je na tych
 * samych czterech awariach.</p>
 */
public final class PaymentGrouping implements SentryOptions.BeforeSendCallback {

    public static final String DEFAULT = "{{ default }}";

    public enum Strategy {
        /** Brak fingerprintu: grupowanie po stack trace i typach wyjątków w łańcuchu. */
        SDK_DEFAULT,
        /**
         * PUŁAPKA oversplitting: {@code {{ default }}} plus wywołanie bramki, np.
         * {@code POST /payments/ORD-3001}. Miało oddzielić endpointy bramki (autoryzacja i status),
         * ale surowa ścieżka zawiera identyfikator zamówienia, więc każde zamówienie dostaje własne
         * issue. Stabilny odpowiednik to nazwa operacji, np. {@code payment.authorize}.
         *
         * <p>Częstszy wariant tej pułapki, komunikat wyjątku w fingerprincie, nie rozbił issues na
         * self-hosted Sentry szkolenia (26.9.0, konfiguracja grupowania {@code newstyle:2026-01-20}):
         * serwer zamienia liczby na {@code <int>} w wartości fingerprintu równej komunikatowi
         * któregoś wyjątku w evencie, także z {@code cause} ({@code ORD-3001} na {@code ORD-<int>},
         * ale też {@code 503} na {@code <int>}, więc przy okazji łączy 503 z 429). Wartości innej niż
         * komunikat nie zmienia: ta ścieżka, samo {@code ORD-3001}, sama liczba czy UUID dają osobne
         * issue na każdą wartość. To zachowanie serwera widoczne w Event Grouping Information,
         * a nie kontrakt.</p>
         */
        DEFAULT_PLUS_REQUEST_PATH,
        /**
         * PUŁAPKA overgrouping: jeden stały fingerprint dla całej integracji. Timeout, awaria
         * i limit zapytań trafiają do jednego issue, a pełny własny fingerprint wyłącza też
         * AI-Enhanced Grouping.
         */
        ONE_BUCKET,
        /** Docelowo: {@code {{ default }}} plus stabilne wymiary z zamkniętego zbioru wartości. */
        DEFAULT_PLUS_REASON
    }

    private final Strategy strategy;

    public PaymentGrouping(Strategy strategy) {
        this.strategy = strategy;
    }

    @Override
    public SentryEvent execute(SentryEvent event, Hint hint) {
        PaymentGatewayException failure = findInChain(event.getThrowable());
        if (failure == null) {
            return event;
        }
        event.setTag("payment.failure_reason", failure.reason().tagValue());

        switch (strategy) {
            case SDK_DEFAULT -> {
            }
            case DEFAULT_PLUS_REQUEST_PATH -> event.setFingerprints(List.of(DEFAULT, String.valueOf(failure.requestTarget())));
            case ONE_BUCKET -> event.setFingerprints(List.of("payment-gateway"));
            // Różne zamówienia i klienci z tą samą przyczyną mają ten sam fingerprint, a 503
            // i 429 zgłaszane z tej samej linii (ten sam stack trace) trafiają do osobnych issues.
            case DEFAULT_PLUS_REASON ->
                    event.setFingerprints(List.of(DEFAULT, "payment-gateway", failure.reason().tagValue()));
        }
        return event;
    }

    /** Pierwszy {@link PaymentGatewayException} w łańcuchu przyczyn, licząc od wyjątku zewnętrznego. */
    static PaymentGatewayException findInChain(Throwable throwable) {
        Set<Throwable> visited = new HashSet<>();
        for (Throwable current = throwable; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof PaymentGatewayException failure) {
                return failure;
            }
        }
        return null;
    }
}
