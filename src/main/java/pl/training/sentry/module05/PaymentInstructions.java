package pl.training.sentry.module05;

import pl.training.sentry.module05.OrderChecks.Cart;

/**
 * Treść polecenia autoryzacji, które orders-api wysyła do payments-api.
 *
 * <p>Kod domenowy bez Sentry. Nowa ścieżka płatności (za flagą
 * {@code checkout.new-payment-flow}) ma celowy błąd kontraktu: przelicza kwotę na grosze drugi
 * raz. payments-api odrzuca taką kwotę jako przekraczającą limit (scenariusz 5).</p>
 */
public final class PaymentInstructions {

    private PaymentInstructions() {
    }

    public static String currentFlow(Cart cart) {
        return "provider=provider-a&amount=" + cart.amountMinor();
    }

    public static String newFlow(Cart cart) {
        // Celowy błąd: amountMinor jest już w groszach, więc kwota rośnie stukrotnie.
        return "provider=provider-b&amount=" + cart.amountMinor() * 100;
    }
}
