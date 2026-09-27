package pl.training.sentry.module03;

import io.sentry.Breadcrumb;
import io.sentry.Sentry;
import io.sentry.SentryLevel;

import java.util.Map;

/**
 * Ponawianie autoryzacji płatności przy błędach przejściowych i raportowanie kolejnych prób.
 *
 * <p>Scenariusz 4: retry, który raportuje każdą nieudaną próbę, zawyża event count i liczbę users,
 * a przy okazji uruchamia deduplikację SDK przeciwko najcenniejszemu eventowi. Wersja docelowa
 * zapisuje próby jako breadcrumbs, historię ponowień jako context, a event wysyła dopiero
 * warstwa, która wie, że operacja się nie udała.</p>
 */
public final class PaymentRetry implements CheckoutService.Payments {

    /** Co robi retry z nieudaną próbą. */
    public enum AttemptReporting {
        /**
         * PUŁAPKA: {@code captureException} w każdej nieudanej próbie. Ten sam efekt daje
         * {@code log.error("...", e)}, gdy integracja logowania zamienia logi ERROR na eventy.
         */
        CAPTURE_EACH_FAILURE,
        /** Każda próba to breadcrumb; event wysyła endpoint po wyczerpaniu prób. */
        BREADCRUMB_PER_ATTEMPT
    }

    private final CheckoutService.Payments delegate;
    private final int maxAttempts;
    private final AttemptReporting reporting;

    public PaymentRetry(CheckoutService.Payments delegate, int maxAttempts, AttemptReporting reporting) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts musi być dodatnie: " + maxAttempts);
        }
        this.delegate = delegate;
        this.maxAttempts = maxAttempts;
        this.reporting = reporting;
    }

    @Override
    public String authorize(Order order) {
        PaymentGatewayException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long start = System.nanoTime();
            try {
                String decision = delegate.authorize(order);
                recordAttempt(attempt, "success", start);
                return decision;
            } catch (PaymentGatewayException failure) {
                if (!failure.reason().retryable()) {
                    throw failure;
                }
                lastFailure = failure;
                switch (reporting) {
                    // Każda próba tworzy nowy obiekt wyjątku, więc deduplikacja SDK ich nie scali:
                    // trzy nieudane próby to trzy eventy. Nieudana próba daje event także wtedy,
                    // gdy następna się uda, czyli opisuje błąd, którego klient nie odczuł.
                    case CAPTURE_EACH_FAILURE -> Sentry.captureException(failure);
                    case BREADCRUMB_PER_ATTEMPT -> recordAttempt(attempt, failure.reason().tagValue(), start);
                }
            }
            // PRODUKCJA: między próbami jest odstęp z jitterem, żeby retry nie dobijał bramki.
        }

        // Historia ponowień należy do contextu: opisuje ten jeden przypadek, a numer próby
        // nie jest wymiarem do filtrowania ani do fingerprintu.
        String lastReason = lastFailure.reason().tagValue();
        Sentry.configureScope(scope -> scope.setContexts("payment_retry", Map.of(
                "attempts", maxAttempts,
                "exhausted", true,
                "last_reason", lastReason
        )));
        // Po wyczerpaniu prób retry rzuca dalej ostatni wyjątek, a checkout opakowuje go
        // z cause. W wariancie CAPTURE_EACH_FAILURE ten obiekt został już przechwycony, więc
        // DuplicateEventDetectionEventProcessor odrzuci event z endpointu: deduplikacja sprawdza
        // także łańcuch przyczyn. Znika jedyny event mówiący, że zamówienie nie zostało złożone.
        throw lastFailure;
    }

    private void recordAttempt(int attempt, String outcome, long startNanos) {
        if (reporting != AttemptReporting.BREADCRUMB_PER_ATTEMPT) {
            return;
        }
        // Stała kategoria i wartości z enumów: przebieg da się odczytać i przefiltrować.
        Breadcrumb breadcrumb = new Breadcrumb("Próba autoryzacji płatności");
        breadcrumb.setCategory("payment.retry");
        breadcrumb.setLevel("success".equals(outcome) ? SentryLevel.INFO : SentryLevel.WARNING);
        breadcrumb.setData("attempt", attempt);
        breadcrumb.setData("max_attempts", maxAttempts);
        breadcrumb.setData("outcome", outcome);
        breadcrumb.setData("duration_ms", (System.nanoTime() - startNanos) / 1_000_000);
        Sentry.addBreadcrumb(breadcrumb);
    }
}
