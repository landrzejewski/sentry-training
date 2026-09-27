package pl.training.sentry.module03;

import java.util.Locale;

/**
 * Niepowodzenie wywołania bramki płatności z przyczyną sklasyfikowaną przez klienta HTTP.
 *
 * <p>{@link FailureReason} to zamknięty zbiór wartości. Dzięki temu nadaje się na tag
 * i na wymiar fingerprintu: różne zamówienia z tą samą przyczyną dają tę samą wartość, a różne
 * przyczyny różne wartości. Komunikat wyjątku tego nie gwarantuje, bo zawiera dane konkretnego
 * wywołania.</p>
 */
public class PaymentGatewayException extends RuntimeException {

    public enum FailureReason {
        /** Brak odpowiedzi w limicie czasu klienta. */
        TIMEOUT(true),
        /** HTTP 5xx: bramka jest niedostępna albo ma awarię. */
        UNAVAILABLE(true),
        /**
         * HTTP 429: przekroczony limit zapytań. Poprawny retry musi respektować nagłówek
         * Retry-After, a ten kod tego nie robi, więc takiego błędu nie ponawia.
         */
        RATE_LIMITED(false),
        /** Inny kod 4xx: bramka odrzuciła zapytanie, ponowienie niczego nie zmieni. */
        REQUEST_REJECTED(false),
        /** Błąd połączenia (np. odmowa połączenia, zerwane połączenie). */
        CONNECTION_FAILED(true),
        /** Odpowiedź bez pola {@code decision}: zmiana kontraktu po stronie bramki. */
        INVALID_RESPONSE(false),
        /** Bramka nie podjęła decyzji w limicie odpytań o status (np. klient nie potwierdził BLIK). */
        NO_DECISION(false);

        private final boolean retryable;

        FailureReason(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }

        /** Wartość do tagu i fingerprintu: stała, krótka, bez danych wywołania. */
        public String tagValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        static FailureReason fromHttpStatus(int status) {
            if (status == 429) {
                return RATE_LIMITED;
            }
            return status >= 500 ? UNAVAILABLE : REQUEST_REJECTED;
        }
    }

    private final FailureReason reason;
    private final String requestTarget;

    /**
     * @param requestTarget metoda i ścieżka wywołania bez query string, np. {@code POST /payments/ORD-3001}
     */
    public PaymentGatewayException(FailureReason reason, String requestTarget, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.requestTarget = requestTarget;
    }

    public FailureReason reason() {
        return reason;
    }

    /** Wywołanie, które się nie powiodło. Ścieżka zawiera identyfikator zamówienia. */
    public String requestTarget() {
        return requestTarget;
    }
}
