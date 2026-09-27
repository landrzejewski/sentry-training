package pl.training.sentry.module10;

import io.sentry.Sentry;
import io.sentry.metrics.MetricsUnit;
import io.sentry.metrics.SentryMetricsParameters;
import pl.training.sentry.module10.Order.PaymentMethod;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Pokazuje kontrakt Application Metrics checkoutu: stałe nazwy, typy, jednostki i atrybuty.
 *
 * <p>Dashboard, Monitory i zapisane zapytania odwołują się do nazw z tej klasy, więc zmiana nazwy
 * albo wartości atrybutu to zmiana kontraktu, która wymaga aktualizacji zależnych widgetów,
 * Monitorów i linków. Jedno miejsce z nazwami zapobiega też literówkom, po których powstaje druga,
 * prawie taka sama metryka.</p>
 *
 * <p>Metody przyjmują tylko enumy i {@link Duration}. Nie da się przez nie przekazać
 * identyfikatora zamówienia, identyfikatora użytkownika ani komunikatu wyjątku. Wyjątkiem jest
 * {@link #failedCarelessly}, która zbiera typowe błędy z przeglądów kodu na potrzeby scenariusza 3.
 * Ograniczone API nie chroni jednak przed atrybutami, które SDK dokleja ze scope: to temat
 * scenariusza 4 i klasy {@link TelemetryPrivacy}.</p>
 */
public final class CheckoutMetrics {

    /** Counter: przyjęta próba checkoutu, emitowana przed pracą, która może się nie udać. */
    public static final String ATTEMPTED = "checkout.attempted";
    /** Counter: terminalny sukces domenowy, czyli opłacone zamówienie. */
    public static final String COMPLETED = "checkout.completed";
    /** Counter: terminalne niepowodzenie domenowe; przyczyna w atrybucie {@link #OUTCOME}. */
    public static final String FAILED = "checkout.failed";
    /** Distribution w milisekundach: czas od przyjęcia próby do wyniku. */
    public static final String DURATION = "checkout.duration";
    /** Gauge: liczba potwierdzeń płatności czekających w kolejce w chwili pomiaru. */
    public static final String QUEUE_DEPTH = "payments.queue.depth";
    /** Counter: potwierdzenie płatności trafiło do kolejki (napływ, a nie stan kolejki). */
    public static final String QUEUE_ENQUEUED = "payments.queue.enqueued";

    public static final String PAYMENT_METHOD = "payment.method";
    public static final String OUTCOME = "checkout.outcome";

    private CheckoutMetrics() {
    }

    public static void attempted(PaymentMethod method) {
        // Sentry.metrics() przy każdym wywołaniu, a nie zapamiętane w polu. API metryk jest
        // związane z obiektem scopes, z którego je pobrano, i z niego czyta user, atrybuty i trace.
        // PUŁAPKA: pole static final IMetricsApi zainicjalizowane przed Sentry.init dostaje
        // implementację NoOp i gubi wszystkie metryki bez żadnego komunikatu. Zainicjalizowane
        // po init widzi scopes z chwili pobrania (np. z uruchomienia aplikacji), a nie isolation
        // scope bieżącego requestu, więc metryki tracą usera i atrybuty requestu.
        Sentry.metrics().count(ATTEMPTED, 1.0, null, attributes(Map.of(PAYMENT_METHOD, value(method))));
    }

    public static void completed(PaymentMethod method) {
        Sentry.metrics().count(COMPLETED, 1.0, null, attributes(Map.of(PAYMENT_METHOD, value(method))));
    }

    public static void failed(PaymentMethod method, CheckoutOutcome outcome) {
        Sentry.metrics().count(FAILED, 1.0, null, attributes(Map.of(
                PAYMENT_METHOD, value(method),
                OUTCOME, outcome.attributeValue())));
    }

    public static void duration(PaymentMethod method, CheckoutOutcome outcome, Duration duration) {
        // Jednostka z MetricsUnit, a nie wpisany ręcznie tekst. SDK wysyła ją w polu unit metryki;
        // bez niej zostaje sama liczba i nikt nie odróżni 840 ms od 840 s.
        // Wartość musi być w tej samej jednostce co deklaracja: toMillis(), a nie getSeconds()
        // z jednostką MILLISECOND.
        Sentry.metrics().distribution(DURATION, (double) duration.toMillis(), MetricsUnit.Duration.MILLISECOND,
                attributes(Map.of(
                        PAYMENT_METHOD, value(method),
                        OUTCOME, outcome.attributeValue())));
    }

    /**
     * Stan kolejki w chwili pomiaru.
     *
     * <p>Gauge emituje się w stałym rytmie (np. co minutę z harmonogramu), a nie przy każdej zmianie
     * kolejki. Średnia gauge zależy od częstotliwości emisji: raportowanie przy każdym
     * {@code enqueue} nadreprezentuje okresy dużego ruchu.</p>
     */
    public static void queueDepth(int depth) {
        // Liczba sztuk nie ma stałej w MetricsUnit, więc jednostka zostaje pusta.
        Sentry.metrics().gauge(QUEUE_DEPTH, (double) depth, null);
    }

    public static void paymentEnqueued() {
        Sentry.metrics().count(QUEUE_ENQUEUED);
    }

    /**
     * PUŁAPKA: wersja z typowymi błędami z przeglądów kodu, dla scenariusza 3.
     *
     * <p>{@code order.id} ma tyle wartości, ile zamówień: grupowanie po nim daje po jednej serii na
     * zamówienie, a wykres i tak pokaże tylko kilka z nich. Komunikat bramki też ma wysoką
     * kardynalność (zawiera identyfikator) i może zawierać dane osobowe. Identyfikator zamówienia
     * należy do eventu błędu albo logu, gdzie służy do znalezienia konkretnego przypadku.</p>
     */
    public static void failedCarelessly(Order order, String gatewayMessage) {
        Sentry.metrics().count(FAILED, 1.0, null, attributes(Map.of(
                PAYMENT_METHOD, value(order.paymentMethod()),
                "order.id", order.id(),
                "gateway.message", gatewayMessage)));
    }

    private static SentryMetricsParameters attributes(Map<String, Object> attributes) {
        return SentryMetricsParameters.create(attributes);
    }

    private static String value(PaymentMethod method) {
        return method.name().toLowerCase(Locale.ROOT);
    }
}
