package pl.training.sentry.module10;

import io.sentry.ISentryLifecycleToken;
import io.sentry.Sentry;
import io.sentry.exception.ExceptionMechanismException;
import io.sentry.protocol.Mechanism;
import pl.training.sentry.module10.CheckoutService.GatewayTimeoutException;
import pl.training.sentry.module10.CheckoutService.PaymentResult;

import java.time.Duration;

/**
 * Pokazuje granicę requestu „opłać zamówienie”: kolejność emisji metryk, błąd obsłużony
 * i nieobsłużony oraz sesję Release Health na request.
 *
 * <p>{@code checkout.attempted} jest emitowany przed wywołaniem bramki, a wynik terminalny
 * osobno ({@link #handle}). {@link #handleCountingAfterWork} to wersja z typowym błędem,
 * w której timeout znika z metryk.</p>
 *
 * <p>Odpowiedzi HTTP celowo odtwarzają częsty kontrakt API: odrzucona płatność to poprawna
 * odpowiedź biznesowa z kodem 200. Wskaźnik liczony z kodów HTTP (albo ze statusów spanów
 * {@code http.server}) uzna ją za sukces, a {@code checkout.completed} nie.</p>
 */
public final class CheckoutEndpoint {

    /** Czy endpoint tworzy sesje Release Health. */
    public enum SessionTracking {
        /** Zachowanie domyślne serwerowego Java SDK: żadnych sesji. */
        NONE,
        /** Tryb request: jedna sesja na request, start i koniec na granicy requestu. */
        PER_REQUEST
    }

    /** Odpowiedź dla klienta: kod HTTP i status biznesowy w treści. */
    public record Response(int httpStatus, String body) {

        /** W tym endpoincie HTTP 500 oznacza wyjątek zgłoszony jako nieobsłużony. */
        public boolean unhandledError() {
            return httpStatus == 500;
        }
    }

    private final CheckoutService checkout = new CheckoutService();
    private final SessionTracking sessionTracking;

    public CheckoutEndpoint(SessionTracking sessionTracking) {
        this.sessionTracking = sessionTracking;
    }

    /** Wersja docelowa: próba liczona przed pracą, każdy wynik terminalny liczony osobno. */
    public Response handle(Order order) {
        // Granica requestu. Sesja i dane scope należą do isolation scope tego requestu:
        // Sentry.startSession pisze do domyślnego scope zapisu, w SDK 8.x isolation scope, więc
        // błąd zgłoszony w trakcie requestu aktualizuje właśnie tę sesję.
        try (ISentryLifecycleToken requestScope = Sentry.pushIsolationScope()) {
            if (sessionTracking == SessionTracking.PER_REQUEST) {
                // Serwerowy Java SDK nie tworzy sesji sam i integracja Spring także nie wywołuje
                // startSession (sprawdzone w źródłach sentry-spring-7 i sentry-spring-boot-4 8.54.0).
                // Bez tej linii release nie ma w Sentry żadnych sesji, czyli nie ma Release Health.
                // PUŁAPKA: SDK nie wystartuje sesji bez ustawionego release (tylko ostrzeżenie
                // w logu SDK).
                // PUŁAPKA: distinctId sesji pochodzi z SentryOptions (jedna wartość na proces), a nie
                // z usera w scope. W trybie request nie ma więc crash-free users per użytkownik.
                // Ustawienie distinctId na identyfikator instancji zrobiłoby z instancji „użytkowników”,
                // a do tego SDK wpisuje distinctId jako user.id do każdej metryki bez usera w scope.
                // PRODUKCJA: każdy request to co najmniej dwa envelope sesji (start i koniec), czyli
                // dodatkowy ruch do Sentry proporcjonalny do ruchu aplikacji.
                Sentry.startSession();
            }
            Response response = process(order);
            if (sessionTracking == SessionTracking.PER_REQUEST && !response.unhandledError()) {
                // PUŁAPKA: po crashu SDK już wysłało końcowy stan sesji (crashed) razem z eventem.
                // endSession wysłałoby go drugi raz, a Sentry policzyłoby ten sam crash dwa razy
                // (sprawdzone na self-hosted 26.9.0: ruch z tego demo daje wtedy 3 sesje, z czego
                // 2 crashed i 0 errored, czyli crash-free sessions 33,3% zamiast 66,7%).
                // Sesja zwykła i errored kończy się tutaj jako exited.
                Sentry.endSession();
            }
            return response;
        }
    }

    private Response process(Order order) {
        // Próba jest liczona, zanim cokolwiek może się nie udać. Wyjątek i timeout nie mogą
        // zabrać próby z mianownika failure rate.
        CheckoutMetrics.attempted(order.paymentMethod());
        long started = System.nanoTime();
        try {
            PaymentResult result = checkout.pay(order);
            if (result == PaymentResult.ACCEPTED) {
                finish(order, CheckoutOutcome.COMPLETED, started);
                return new Response(200, "PAID");
            }
            finish(order, CheckoutOutcome.DECLINED, started);
            // HTTP 200 i porażka domenowa jednocześnie: klient ma wybrać inną metodę płatności.
            return new Response(200, "PAYMENT_DECLINED");
        } catch (GatewayTimeoutException exception) {
            finish(order, CheckoutOutcome.GATEWAY_TIMEOUT, started);
            return reportTimeout(exception);
        } catch (RuntimeException exception) {
            finish(order, CheckoutOutcome.INTERNAL_ERROR, started);
            return reportUnhandled(exception);
        }
    }

    /**
     * PUŁAPKA: metryki dopisane na końcu happy path. Próba jest liczona dopiero po odpowiedzi
     * bramki, a ścieżki wyjątków nie emitują niczego. Timeout i błąd w kodzie znikają więc
     * z licznika i z mianownika, a failure rate wygląda lepiej, niż jest.
     */
    public Response handleCountingAfterWork(Order order) {
        try (ISentryLifecycleToken requestScope = Sentry.pushIsolationScope()) {
            long started = System.nanoTime();
            try {
                PaymentResult result = checkout.pay(order);
                CheckoutMetrics.attempted(order.paymentMethod());
                if (result == PaymentResult.ACCEPTED) {
                    finish(order, CheckoutOutcome.COMPLETED, started);
                    return new Response(200, "PAID");
                }
                finish(order, CheckoutOutcome.DECLINED, started);
                return new Response(200, "PAYMENT_DECLINED");
            } catch (GatewayTimeoutException exception) {
                return reportTimeout(exception);
            } catch (RuntimeException exception) {
                return reportUnhandled(exception);
            }
        }
    }

    private static void finish(Order order, CheckoutOutcome outcome, long startedNanos) {
        if (outcome == CheckoutOutcome.COMPLETED) {
            CheckoutMetrics.completed(order.paymentMethod());
        } else {
            CheckoutMetrics.failed(order.paymentMethod(), outcome);
        }
        CheckoutMetrics.duration(order.paymentMethod(), outcome, Duration.ofNanos(System.nanoTime() - startedNanos));
    }

    /**
     * Timeout bramki jest obsłużony: klient dostaje 503 i może ponowić. Sesja dostaje
     * {@code errors=1} i zostaje w stanie ok; Sentry liczy ją potem jako errored.
     */
    private static Response reportTimeout(GatewayTimeoutException exception) {
        Sentry.captureException(exception);
        return new Response(503, "RETRY_LATER");
    }

    /**
     * Wyjątek, którego kod nie przewidział, zgłoszony jako nieobsłużony ({@code handled=false}).
     *
     * <p>Tak robi integracja frameworka: w Spring MVC {@code SentryExceptionResolver} zgłasza
     * wyjątek z kontrolera z mechanizmem {@code handled=false}. W czystej Javie granica requestu
     * musi zrobić to sama. Dla Release Health to różnica zasadnicza: event nieobsłużony zmienia
     * status sesji na {@code crashed}, a obsłużony tylko zwiększa licznik błędów sesji.</p>
     */
    private static Response reportUnhandled(RuntimeException exception) {
        Mechanism mechanism = new Mechanism();
        mechanism.setType("CheckoutEndpoint");
        mechanism.setHandled(false);
        Sentry.captureException(new ExceptionMechanismException(mechanism, exception, Thread.currentThread()));
        return new Response(500, "INTERNAL_ERROR");
    }
}
