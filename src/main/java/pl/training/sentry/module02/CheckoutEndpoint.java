package pl.training.sentry.module02;

import io.sentry.ISentryLifecycleToken;
import io.sentry.Sentry;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryId;

import java.util.HashMap;
import java.util.Map;

/**
 * Endpoint {@code POST /api/checkout} w czystej Javie: granica requestu, opis requestu w scope
 * i raportowanie błędu płatności.
 *
 * <p>{@link #handle} to wersja docelowa: isolation scope na request, tag {@code traffic.origin}
 * i context {@code checkout}. {@link #handleWithoutRequestScope} zawiera błąd z przeglądu kodu,
 * który scenariusz 6 pokazuje obok niej.</p>
 */
public final class CheckoutEndpoint {

    /**
     * Odpowiedź dla klienta.
     *
     * @param sentryEventId identyfikator eventu, który support może wpisać w wyszukiwarkę Sentry;
     *                      {@code SentryId.EMPTY_ID} oznacza, że event nie opuścił procesu (SDK
     *                      wyłączone, filtr, {@code beforeSend} albo sampling), a {@code null}, że błędu nie było
     */
    public record CheckoutResponse(int status, SentryId sentryEventId) {

        public boolean eventSent() {
            return sentryEventId != null && !SentryId.EMPTY_ID.equals(sentryEventId);
        }
    }

    private static final String PUBLIC_BASE_URL = "https://checkout.example.com";

    private final CheckoutService checkout;
    private final TrafficClassifier traffic;

    public CheckoutEndpoint(CheckoutService checkout, TrafficClassifier traffic) {
        this.checkout = checkout;
        this.traffic = traffic;
    }

    public CheckoutResponse handle(IncomingRequest request, String orderId, long amount, ResponseChannel client) {
        // Granica requestu: wszystko, co opisuje ten request, znika razem z tokenem.
        try (ISentryLifecycleToken requestScope = Sentry.pushIsolationScope()) {
            attachRequest(request);

            // Tag: wymiar o trzech wartościach, po którym beforeSend decyduje, a zespół filtruje.
            // Ustawiany zawsze, także dla klienta, żeby wartość nigdy nie była dziedziczona.
            Sentry.setTag("traffic.origin", traffic.classify(request).tagValue());

            // Context: szczegóły tego jednego eventu. Identyfikator zamówienia nie jest tagiem,
            // bo każde zamówienie to nowa wartość i rozkład takiego tagu nic nie pokazuje.
            Sentry.configureScope(scope -> scope.setContexts("checkout", Map.of(
                    "order_id", orderId,
                    "amount", amount
            )));

            return process(orderId, amount, client);
        }
    }

    /**
     * Wersja z przeglądu kodu: bez własnego isolation scope i z tagiem ustawianym tylko dla pracowników.
     */
    public CheckoutResponse handleWithoutRequestScope(
            IncomingRequest request, String orderId, long amount, ResponseChannel client) {
        attachRequest(request);
        // PUŁAPKA: statyczne Sentry.setTag pisze do isolation scope wątku, który obsługuje kolejne
        // requesty. Tag ustawiany tylko w jednej gałęzi nie jest nigdy kasowany, więc po pierwszym
        // requeście pracownika każdy następny request na tym wątku wygląda na ruch wewnętrzny,
        // a FilteringBeforeSend odrzuca błędy klientów bez śladu.
        if (traffic.classify(request) == TrafficClassifier.Origin.INTERNAL) {
            Sentry.setTag("traffic.origin", TrafficClassifier.Origin.INTERNAL.tagValue());
        }
        return process(orderId, amount, client);
    }

    private CheckoutResponse process(String orderId, long amount, ResponseChannel client) {
        try {
            checkout.checkout(orderId, amount, client);
            return new CheckoutResponse(200, null);
        } catch (Exception exception) {
            // Błąd obsłużony: klient dostaje 500 z identyfikatorem eventu, zespół dostaje event.
            // Pusty identyfikator w odpowiedzi to sygnał, że event nie opuścił procesu.
            SentryId eventId = Sentry.captureException(exception);
            return new CheckoutResponse(500, eventId);
        }
    }

    /**
     * Dane requestu w scope, tak jak robi to integracja frameworka: metoda, URL, query string
     * i nagłówki. Co z nich opuści proces, decyduje {@link EventDataScrubber} w {@code beforeSend}.
     *
     * <p>PRODUKCJA: integracja Spring z {@code send-default-pii=false} sama pomija część nagłówków
     * (m.in. {@code Authorization}, {@code Cookie}, {@code X-Forwarded-For}), ale nie query string
     * ani nagłówków spoza swojej listy (scenariusz 8). Tu kopiujemy wszystko, żeby było widać,
     * co usuwa scrubber.</p>
     */
    private static void attachRequest(IncomingRequest request) {
        Request sentryRequest = new Request();
        sentryRequest.setMethod(request.method());
        sentryRequest.setUrl(PUBLIC_BASE_URL + request.path());
        sentryRequest.setQueryString(request.query());
        sentryRequest.setHeaders(new HashMap<>(request.headers()));
        Sentry.configureScope(scope -> scope.setRequest(sentryRequest));
    }
}
