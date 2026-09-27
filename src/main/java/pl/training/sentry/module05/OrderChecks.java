package pl.training.sentry.module05;

/**
 * Kroki checkoutu w orders-api: koszyk, przeliczenie cen, ocena ryzyka i rezerwacja towaru.
 *
 * <p>Kod domenowy bez Sentry. Czasy są dobrane tak, żeby waterfall był czytelny. Ocena ryzyka
 * i rezerwacja są od siebie niezależne, więc mogą iść równolegle (scenariusz 4).</p>
 */
public final class OrderChecks {

    /** Koszyk zamówienia. Kwota w groszach, czyli w jednostkach, których oczekuje payments-api. */
    public record Cart(String orderId, int itemCount, long amountMinor) {
    }

    /** Wynik rezerwacji. {@code fromCache} oznacza fallback: magazyn nie odpowiedział na czas. */
    public record Stock(boolean reserved, boolean fromCache) {
    }

    public Cart loadCart(String orderId) {
        Latency.pause(40);
        return new Cart(orderId, 3, 25_900);
    }

    /** Lokalne obliczenia (promocje, rabaty) wykonywane w procesie orders-api. */
    public Cart recalculatePrices(Cart cart) {
        Latency.pause(80);
        return cart;
    }

    public boolean isRisky(String customerId) {
        Latency.pause(150);
        return false;
    }

    public Stock reserveStock(String orderId) {
        Latency.pause(200);
        // Magazyn odpowiada zbyt wolno, więc rezerwacja opiera się na stanie z cache.
        return new Stock(true, true);
    }
}
