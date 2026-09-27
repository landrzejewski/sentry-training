package pl.training.sentry.module06;

import pl.training.sentry.module06.BankGateway.Payment;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Obciążenie karty w payments-api, bez Sentry.
 *
 * <p>Rzuca trzy rodzaje wyjątków o zupełnie innym znaczeniu dla zespołu: awarię banku
 * ({@link BankGateway.BankTimeoutException}), odmowę banku
 * ({@link BankGateway.CardDeclinedException}) i powtórzone żądanie klienta
 * ({@link DuplicatePaymentException}). Dla Sentry wszystkie trzy są „wyjątkiem”, dopiero kod
 * raportujący nadaje im znaczenie (scenariusze 5 i 6).</p>
 */
public final class PaymentService {

    private final BankGateway bank;
    private final Set<String> processed = ConcurrentHashMap.newKeySet();

    public PaymentService(BankGateway bank) {
        this.bank = bank;
    }

    public void charge(Payment payment) {
        // Klient mobilny ponawia żądanie po zerwanym połączeniu z tym samym identyfikatorem
        // płatności (kluczem idempotencji). Drugie obciążenie karty byłoby błędem, więc
        // odmowa jest tu poprawnym, oczekiwanym zachowaniem.
        if (!processed.add(payment.id())) {
            throw new DuplicatePaymentException(payment.id());
        }
        try {
            bank.authorize(payment);
        } catch (RuntimeException failure) {
            // Nieudaną płatność klient może ponowić, więc nie blokujemy klucza.
            processed.remove(payment.id());
            throw failure;
        }
    }

    /** Powtórzone żądanie z kluczem idempotencji, który już został przetworzony. */
    public static final class DuplicatePaymentException extends RuntimeException {
        public DuplicatePaymentException(String paymentId) {
            super("Płatność " + paymentId + " została już przetworzona");
        }
    }
}
