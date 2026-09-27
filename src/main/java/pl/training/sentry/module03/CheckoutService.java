package pl.training.sentry.module03;

import pl.training.sentry.module03legacy.LegacyLoyaltyClient;

/**
 * Składanie zamówienia: rabat z karty lojalnościowej i autoryzacja płatności.
 *
 * <p>Na granicy warstwy każdy błąd zamienia się w {@link CheckoutException}. Sposób opakowania
 * decyduje, co zobaczy zespół w evencie: cały łańcuch przyczyn albo tylko wyjątek zewnętrzny.
 * Dlatego klasa ma oba warianty spotykane w przeglądach kodu ({@link CauseHandling}).</p>
 *
 * <p>Klasa nie zna Sentry. Płatności dostaje przez {@link Payments}, więc ta sama logika działa
 * z pojedynczą próbą ({@link PaymentGatewayClient}) i z ponowieniami ({@link PaymentRetry}).</p>
 */
public final class CheckoutService {

    /** Autoryzacja płatności zamówienia; zwraca decyzję bramki. */
    @FunctionalInterface
    public interface Payments {
        String authorize(Order order);
    }

    /** Jak warstwa checkout opakowuje błąd z warstwy niższej. */
    public enum CauseHandling {
        /** Nowy wyjątek z {@code cause}: event zawiera cały łańcuch. */
        KEEP_CAUSE,
        /**
         * PUŁAPKA: nowy wyjątek bez {@code cause}, z tekstem przyczyny doklejonym do komunikatu.
         * Znika typ przyczyny, jej stack trace i klasyfikacja zapisana w wyjątku.
         */
        MESSAGE_ONLY
    }

    private final Payments payments;
    private final LegacyLoyaltyClient loyalty;
    private final CauseHandling causeHandling;

    public CheckoutService(Payments payments, LegacyLoyaltyClient loyalty, CauseHandling causeHandling) {
        this.payments = payments;
        this.loyalty = loyalty;
        this.causeHandling = causeHandling;
    }

    /** Zwraca decyzję bramki, np. {@code AUTHORIZED}, z dopiskiem o rabacie lojalnościowym. */
    public String placeOrder(Order order) {
        try {
            int discount = order.loyaltyCard() == null ? 0 : loyalty.discountPercent(order.loyaltyCard());
            String decision = payments.authorize(order);
            return discount > 0 ? decision + " (rabat " + discount + "%)" : decision;
        } catch (RuntimeException failure) {
            // Identyfikator zamówienia w komunikacie jest wygodny dla człowieka, a grupowaniu nie
            // szkodzi: przy stack trace serwer grupuje po ramkach, nie po komunikacie. Szkodzi
            // identyfikator w fingerprincie, np. surowa ścieżka wywołania (scenariusz 3).
            throw switch (causeHandling) {
                case KEEP_CAUSE -> new CheckoutException("Nie udało się złożyć zamówienia " + order.id(), failure);
                case MESSAGE_ONLY -> new CheckoutException(
                        "Nie udało się złożyć zamówienia " + order.id() + ": " + failure.getMessage());
            };
        }
    }
}
