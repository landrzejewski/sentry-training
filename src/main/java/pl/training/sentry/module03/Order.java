package pl.training.sentry.module03;

/**
 * Zamówienie w usłudze checkout-api: cała domena przykładów modułu 3.
 *
 * <p>Zamówienie opłaca się przez zewnętrzną bramkę płatności ({@link PaymentGatewayClient}).
 * Karta lojalnościowa jest opcjonalna i obsługuje ją biblioteka innego zespołu
 * ({@link pl.training.sentry.module03legacy.LegacyLoyaltyClient}).</p>
 *
 * @param customerId  techniczny, pseudonimowy identyfikator klienta, zatwierdzony do telemetrii
 * @param loyaltyCard numer karty lojalnościowej albo {@code null}, gdy klient jej nie podał
 */
public record Order(String id, String customerId, PaymentMethod paymentMethod, String loyaltyCard) {

    public enum PaymentMethod {
        /** Bramka od razu zwraca decyzję. */
        CARD,
        /** Bramka zwraca PENDING, a decyzja zapada, gdy klient potwierdzi płatność w aplikacji banku. */
        BLIK
    }

    public static Order card(String id, String customerId) {
        return new Order(id, customerId, PaymentMethod.CARD, null);
    }

    public static Order blik(String id, String customerId) {
        return new Order(id, customerId, PaymentMethod.BLIK, null);
    }

    public Order withLoyaltyCard(String cardNumber) {
        return new Order(id, customerId, paymentMethod, cardNumber);
    }
}
