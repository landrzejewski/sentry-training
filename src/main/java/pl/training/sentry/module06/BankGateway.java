package pl.training.sentry.module06;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Połączenie z bankiem rozliczeniowym: zależność zewnętrzna wszystkich przykładów modułu 6.
 *
 * <p>Atrapa z przełączanym stanem, bez Sentry. Stan {@link Mode} odpowiada trzem awariom, które
 * w produkcji wyglądają zupełnie inaczej w monitoringu: bank odpowiada błędem po timeoucie,
 * bank nie odpowiada wcale (połączenie wisi) albo bank działa, a odmawia konkretnej operacji
 * (odrzucenie karty jest wynikiem biznesowym, nie awarią).</p>
 */
public final class BankGateway {

    public static final String BANK_CODE = "bank-alfa";

    /** Token karty, dla której bank zawsze odmawia autoryzacji z braku środków. */
    public static final String CARD_WITHOUT_FUNDS = "tok-insufficient-funds";

    public enum Mode {
        /** Bank działa. */
        AVAILABLE,
        /** Bank odpowiada błędem po upływie timeoutu klienta HTTP. */
        TIMING_OUT,
        /** Bank przyjmuje połączenie i nie odpowiada; klient bez timeoutu czeka w nieskończoność. */
        HANGING
    }

    private volatile Mode mode = Mode.AVAILABLE;
    private final AtomicInteger timeoutsLeft = new AtomicInteger();

    public void switchTo(Mode mode) {
        this.mode = mode;
    }

    /** Chwilowa awaria: najbliższe wywołania kończą się timeoutem, kolejne działają. */
    public void timeOutNextCalls(int calls) {
        timeoutsLeft.set(calls);
    }

    /** Lekkie sprawdzenie połączenia, bez operacji biznesowej. */
    public void ping() {
        failIfUnavailable("ping");
    }

    /** Wyciąg przelewów przychodzących z danego dnia. */
    public List<String> statement(LocalDate day) {
        failIfUnavailable("pobranie wyciągu za " + day);
        return List.of("PAY-1001", "PAY-1002", "PAY-1003");
    }

    /** Autoryzacja obciążenia karty. */
    public void authorize(Payment payment) {
        failIfUnavailable("autoryzacja płatności " + payment.id() + " sprzedawcy " + payment.merchant());
        if (CARD_WITHOUT_FUNDS.equals(payment.cardToken())) {
            throw new CardDeclinedException("insufficient_funds");
        }
    }

    private void failIfUnavailable(String operation) {
        Mode effective = timeoutsLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0 ? Mode.TIMING_OUT : mode;
        switch (effective) {
            case AVAILABLE -> {
            }
            // Komunikat zawiera identyfikator operacji, jak często w bibliotekach klienckich.
            // Scenariusz 5 pokazuje, co się dzieje, gdy taki komunikat trafia do fingerprintu.
            case TIMING_OUT -> throw new BankTimeoutException(
                    "Bank " + BANK_CODE + " nie odpowiedział w 30 s: " + operation);
            case HANGING -> waitForever();
        }
    }

    private static void waitForever() {
        // Model gniazda bez timeoutu odczytu: wątek czeka, dopóki ktoś go nie przerwie.
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BankTimeoutException("Oczekiwanie na bank " + BANK_CODE + " przerwane");
        }
    }

    /**
     * @param merchant sprzedawca w marketplace, dla którego payments-api obciąża kartę klienta
     */
    public record Payment(String id, String merchant, String cardToken) {
    }

    /** Bank nie odpowiedział: awaria infrastruktury, której nikt w firmie nie naprawi kodem. */
    public static final class BankTimeoutException extends RuntimeException {
        public BankTimeoutException(String message) {
            super(message);
        }
    }

    /** Bank odmówił obciążenia karty: oczekiwany wynik biznesowy, a nie defekt aplikacji. */
    public static final class CardDeclinedException extends RuntimeException {

        private final String reason;

        public CardDeclinedException(String reason) {
            super("Karta odrzucona przez bank: " + reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
