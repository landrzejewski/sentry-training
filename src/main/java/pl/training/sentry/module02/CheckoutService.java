package pl.training.sentry.module02;

import java.io.IOException;

/**
 * Płatność za zamówienie w checkout-api: obciążenie w bramce i odesłanie potwierdzenia klientowi.
 *
 * <p>Kod domenowy, bez Sentry. Oba kroki mogą zakończyć się tym samym
 * {@code SocketException: Connection reset}, choć znaczą co innego: awaria bramki blokuje płatności,
 * a zerwane połączenie z klientem to zwykły przebieg (płatność już przeszła). Na tej różnicy
 * scenariusz 4 pokazuje, dlaczego filtr tekstowy jest kruchy.</p>
 */
public final class CheckoutService {

    private final PaymentGateway gateway;

    public CheckoutService(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    /** Obciąża kartę i zwraca kod autoryzacji. */
    public String authorize(String orderId, long amount) throws IOException {
        return gateway.charge(orderId, amount);
    }

    /** Pełna płatność: obciążenie, a potem potwierdzenie wysłane klientowi. */
    public void checkout(String orderId, long amount, ResponseChannel client) throws IOException {
        String authorization = authorize(orderId, amount);
        client.send("{\"status\":\"PAID\",\"authorization\":\"" + authorization + "\"}");
    }
}
