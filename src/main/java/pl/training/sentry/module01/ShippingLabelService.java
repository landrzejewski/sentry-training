package pl.training.sentry.module01;

/**
 * Pokazuje błąd domenowy, w którym dwie różne przyczyny dają ten sam stack trace.
 *
 * <p>Kod kieruje każde zamówienie do generatora etykiety kurierskiej, który zakłada, że adres
 * dostawy istnieje. Dwa różne przypadki kończą się tym samym {@code NullPointerException}
 * w tej samej linii: poprawne zamówienie {@code PICKUP_POINT} (brak adresu jest zgodny
 * z modelem, zawiodła ścieżka w kodzie) oraz niekompletne zamówienie {@code COURIER} (adresu
 * zabrakło w danych wejściowych). Przy tej samej ścieżce wywołań Sentry grupuje oba w jedno
 * issue, więc przyczyny rozróżnia dopiero kontekst dołączony do eventu.</p>
 *
 * <p>Klasa nie zna Sentry. Kod domenowy nie zależy od telemetrii, opisuje ją warstwa, która
 * obsługuje request ({@link CheckoutEndpoint}, {@link CheckoutTelemetry}).</p>
 */
public final class ShippingLabelService {

    public String createLabel(Order order) {
        // Celowy błąd: brak rozgałęzienia na tryb dostawy. PICKUP_POINT powinien trafić
        // do generatora etykiety punktu odbioru, który w tej wersji kodu nie istnieje.
        return courierLabel(order);
    }

    private String courierLabel(Order order) {
        // Tu błąd się ujawnia dla obu przyczyn. Stack trace wskaże tę linię, choć przyczyna
        // powstała wcześniej: przy wyborze ścieżki albo przy budowaniu zamówienia.
        String postalCode = order.deliveryAddress().postalCode().replace(" ", "");
        return "KURIER/" + postalCode + "/" + order.id();
    }
}
