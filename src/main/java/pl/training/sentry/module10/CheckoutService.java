package pl.training.sentry.module10;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Opłacenie zamówienia: przeliczenie kwoty na PLN i obciążenie w bramce płatności.
 *
 * <p>Kod domenowy bez Sentry. Kończy się na cztery sposoby, które metryki muszą rozróżnić:</p>
 * <ul>
 *   <li>płatność przyjęta;</li>
 *   <li>płatność odrzucona przez bramkę: poprawna odpowiedź biznesowa, a nie wyjątek;</li>
 *   <li>przekroczony czas bramki: {@link GatewayTimeoutException};</li>
 *   <li>celowy błąd w kodzie dla zamówień w EUR: {@code NullPointerException}.</li>
 * </ul>
 */
public final class CheckoutService {

    public enum PaymentResult {
        ACCEPTED,
        DECLINED
    }

    /** Bramka płatności nie odpowiedziała w limicie czasu. Wynik płatności jest nieznany. */
    public static final class GatewayTimeoutException extends RuntimeException {

        public GatewayTimeoutException(String message) {
            super(message);
        }
    }

    // Celowy błąd: brakuje kursu EUR. Zamówienie w EUR dostaje null i kończy się
    // NullPointerException przy przeliczeniu, czyli awarią, której kod nie przewidział.
    private static final Map<String, BigDecimal> RATES_TO_PLN = Map.of(
            "PLN", BigDecimal.ONE,
            "USD", new BigDecimal("3.65"));

    public PaymentResult pay(Order order) {
        BigDecimal amountInPln = order.amount().multiply(RATES_TO_PLN.get(order.currency()));
        return charge(amountInPln, order.paymentToken());
    }

    /** Symulator zewnętrznej bramki płatności. Opóźnienia są krótkie, żeby demo działało szybko. */
    private PaymentResult charge(BigDecimal amountInPln, String paymentToken) {
        return switch (paymentToken) {
            case "declined" -> {
                simulateLatency(25);
                yield PaymentResult.DECLINED;
            }
            case "timeout" -> {
                // PRODUKCJA: limit czasu bramki to zwykle kilka sekund. Wynik jest nieznany:
                // pieniądze mogły zostać pobrane, więc takie zamówienie wymaga uzgodnienia.
                simulateLatency(200);
                throw new GatewayTimeoutException("Bramka płatności nie odpowiedziała w limicie czasu");
            }
            default -> {
                simulateLatency(40);
                yield PaymentResult.ACCEPTED;
            }
        };
    }

    private static void simulateLatency(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
