package pl.training.sentry.checkoutapi;

import io.sentry.ISpan;
import io.sentry.Sentry;
import io.sentry.SpanStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Pokazuje endpoint złożenia zamówienia, który dokłada child spany do transakcji startera i kończy
 * się nieobsłużonym wyjątkiem dla punktu odbioru spoza magazynu.
 *
 * <p>Transakcję {@code POST /api/checkout} tworzy starter ({@code SentryTracingFilter}), który
 * najpierw odczytuje nagłówki trace z requestu. Kontroler dodaje tylko child spany dla dwóch
 * kroków, żeby w trace view było widać, na co backend zużył czas. Nieobsłużony wyjątek raportuje
 * {@code SentryExceptionResolver}, więc kontroler nie woła {@code captureException}.</p>
 */
@RestController
public class CheckoutController {

    /** Punkty odbioru znane magazynowi. KRK-031 jest w katalogu frontendu, ale nie w magazynie. */
    private static final Map<String, String> PICKUP_POINTS = Map.of(
            "WAW-114", "Warszawa, Prosta 1",
            "GDA-007", "Gdańsk, Długa 5");

    private final AtomicInteger orderNumbers = new AtomicInteger(1000);

    public record CheckoutRequest(String cartId, String deliveryMode, String pickupPointId, String couponCode) {
    }

    public record CheckoutResult(String orderId, String status, String pickupAddress) {
    }

    /** Ta sama logika pod dwiema ścieżkami, które różnią się tylko polityką CORS ({@link CheckoutCors}). */
    @PostMapping({"/api/checkout", "/legacy-api/checkout"})
    @ResponseStatus(HttpStatus.CREATED)
    public CheckoutResult checkout(@RequestBody CheckoutRequest request) {
        String pickupAddress = inSpan("db.query", "reserve stock", 30, () ->
                "PICKUP_POINT".equals(request.deliveryMode()) ? pickupAddress(request.pickupPointId()) : null);
        inSpan("http.client", "POST payments /authorize", 80, () -> "AUTHORIZED");
        return new CheckoutResult("ORD-" + orderNumbers.incrementAndGet(), "CONFIRMED", pickupAddress);
    }

    private static String pickupAddress(String pickupPointId) {
        String address = PICKUP_POINTS.get(pickupPointId);
        if (address == null) {
            // Nowy punkt dodany we frontendzie, zanim trafił do magazynu. Odpowiedź 500 i event
            // backendu w tym samym trace co kliknięcie w przeglądarce.
            throw new IllegalStateException("Punkt odbioru " + pickupPointId + " nie istnieje w magazynie");
        }
        return address;
    }

    /** Child span aktywnej transakcji; bez transakcji (tracing wyłączony) praca idzie bez spanu. */
    private static <T> T inSpan(String operation, String description, long latencyMillis, Supplier<T> work) {
        ISpan parent = Sentry.getSpan();
        ISpan span = parent == null ? null : parent.startChild(operation, description);
        try {
            sleep(latencyMillis);
            T result = work.get();
            if (span != null) {
                span.setStatus(SpanStatus.OK);
            }
            return result;
        } catch (RuntimeException exception) {
            if (span != null) {
                span.setThrowable(exception);
                span.setStatus(SpanStatus.INTERNAL_ERROR);
            }
            throw exception;
        } finally {
            if (span != null) {
                span.finish();
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
