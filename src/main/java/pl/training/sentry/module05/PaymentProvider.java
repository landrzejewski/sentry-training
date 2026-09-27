package pl.training.sentry.module05;

/**
 * Wywołanie dostawcy płatności po stronie payments-api (symulowane).
 *
 * <p>Kod domenowy bez Sentry. Kwota powyżej limitu kończy się odmową, którą payments-api
 * zwraca jako HTTP 422. To poprawne zachowanie tej usługi, a nie jej błąd.</p>
 */
public final class PaymentProvider {

    /** Limit jednej transakcji w groszach (10 000 zł). */
    public static final long LIMIT_MINOR = 1_000_000;

    public static final class AmountOverLimitException extends RuntimeException {
        public AmountOverLimitException(long amountMinor) {
            super("Kwota " + amountMinor + " przekracza limit " + LIMIT_MINOR);
        }
    }

    public String authorize(String provider, long amountMinor) {
        Latency.pause(50);
        if (amountMinor > LIMIT_MINOR) {
            throw new AmountOverLimitException(amountMinor);
        }
        return "AUTH-" + provider + "-" + amountMinor;
    }
}
