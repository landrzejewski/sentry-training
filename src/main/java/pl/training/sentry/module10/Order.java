package pl.training.sentry.module10;

import java.math.BigDecimal;

/**
 * Zamówienie opłacane w checkout-api: domena przykładów modułu 10.
 *
 * <p>Pola dobrano pod scenariusze. Metoda płatności ma mały, stały słownik, więc nadaje się na
 * wymiar metryk. Identyfikator zamówienia jest unikalny i do metryk nie trafia (scenariusz 3).
 * Waluta i token płatności sterują symulowaną bramką w {@link CheckoutService}.</p>
 *
 * @param paymentToken token z formularza płatności; symulator bramki rozpoznaje tokeny
 *                     {@code declined} i {@code timeout}, każdy inny oznacza płatność przyjętą
 */
public record Order(String id, PaymentMethod paymentMethod, BigDecimal amount, String currency, String paymentToken) {

    /** Metody płatności. Enum, a nie dowolny tekst: słownik wymiaru metryk jest zamknięty. */
    public enum PaymentMethod {
        CARD,
        BLIK
    }

    public static Order pln(String id, PaymentMethod paymentMethod, String paymentToken) {
        return new Order(id, paymentMethod, new BigDecimal("149.00"), "PLN", paymentToken);
    }

    public static Order eur(String id, PaymentMethod paymentMethod) {
        return new Order(id, paymentMethod, new BigDecimal("35.00"), "EUR", "ok");
    }
}
