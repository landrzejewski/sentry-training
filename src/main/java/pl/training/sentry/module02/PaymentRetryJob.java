package pl.training.sentry.module02;

import io.sentry.ISentryLifecycleToken;
import io.sentry.Sentry;
import io.sentry.protocol.SentryId;

import java.util.Map;

/**
 * Nocne zadanie, które ponawia obciążenia zamówień odrzuconych w ciągu dnia.
 *
 * <p>Event z tego zadania nie ma requestu ani usera. Scenariusz 6 pokazuje na nim, że
 * {@code beforeSend} musi działać dla każdego rodzaju eventu, a nie tylko dla tych, na których
 * był testowany.</p>
 */
public final class PaymentRetryJob {

    private final CheckoutService checkout;

    public PaymentRetryJob(CheckoutService checkout) {
        this.checkout = checkout;
    }

    /** Ponawia jedno obciążenie i zwraca identyfikator eventu albo {@code null}, gdy się udało. */
    public SentryId retry(String orderId, long amount) {
        // Własny isolation scope także dla zadania: tag joba nie może zostać na wątku puli.
        try (ISentryLifecycleToken jobScope = Sentry.pushIsolationScope()) {
            Sentry.setTag("job.name", "payment-retry");
            Sentry.configureScope(scope -> scope.setContexts("payment_retry", Map.of("order_id", orderId)));
            try {
                checkout.authorize(orderId, amount);
                return null;
            } catch (Exception exception) {
                return Sentry.captureException(exception);
            }
        }
    }
}
