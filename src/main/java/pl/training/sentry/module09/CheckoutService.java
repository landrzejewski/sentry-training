package pl.training.sentry.module09;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.IntSupplier;

/**
 * checkout-api: przyjmuje zamówienie, liczy cenę na puli wątków i rezerwuje towar
 * w inventory-service przez HTTP.
 *
 * <p>Obie granice, na których kontekst trace może się zgubić, mają tu wersję docelową i warianty
 * z typowymi błędami:
 * {@link Outbound} dla wywołania HTTP (scenariusze 2 i 6), {@link Handoff} dla zadania na puli
 * (scenariusz 3).</p>
 */
public final class CheckoutService implements AutoCloseable {

    public static final String INSTRUMENTATION_SCOPE = "pl.training.checkout";

    /** Zamówienie. {@code channel} (web, mobile) jedzie do inventory-service w Baggage. */
    public record Order(String id, String sku, String channel) {
    }

    /** Jak checkout-api woła inventory-service. */
    public enum Outbound {
        /** Instrumentowany klient: span CLIENT, inject W3C, Baggage i sentry-trace. */
        PROPAGATED,
        /** PUŁAPKA: własny span CLIENT, ale bez inject. Serwer zaczyna nowy trace. */
        NOT_PROPAGATED,
        /** PUŁAPKA: ręcznie sklejony traceparent z bieżącego spana i sztywną flagą sampled. */
        MANUAL_TRACEPARENT,
        /** PUŁAPKA: ręczny span CLIENT wokół instrumentowanego klienta, czyli dwa spany jednej granicy. */
        DOUBLE_INSTRUMENTED
    }

    /** Jak zadanie liczenia ceny trafia na pulę wątków. */
    public enum Handoff {
        /** Kontekst przechwycony przy zleceniu zadania ({@code Context.current().wrap}). */
        CONTEXT_WRAPPED,
        /** PUŁAPKA: zadanie bez kontekstu. Span na puli zaczyna nowy trace. */
        PLAIN,
        /** PUŁAPKA: zadanie ustawia swój span jako bieżący i nie zamyka {@code Scope}. */
        LEAKING_SCOPE
    }

    private final Tracer tracer;
    private final TracedHttpClient http;
    private final HttpClient plainHttp = HttpClient.newHttpClient();
    private final InventoryService inventory;
    private final ExecutorService pricingPool;

    public CheckoutService(ServiceTelemetry telemetry, InventoryService inventory, ExecutorService pricingPool) {
        this.tracer = telemetry.tracer(INSTRUMENTATION_SCOPE);
        this.http = new TracedHttpClient(telemetry);
        this.inventory = inventory;
        this.pricingPool = pricingPool;
    }

    public int submit(Order order) {
        return submit(order, Outbound.PROPAGATED, Handoff.CONTEXT_WRAPPED);
    }

    public int submit(Order order, Outbound outbound) {
        return submit(order, outbound, Handoff.CONTEXT_WRAPPED);
    }

    public int submit(Order order, Handoff handoff) {
        return submit(order, Outbound.PROPAGATED, handoff);
    }

    /** Obsługa jednego requestu {@code POST /checkouts}. Zwraca kod odpowiedzi dla klienta. */
    public int submit(Order order, Outbound outbound, Handoff handoff) {
        // Span wejściowy requestu. W usłudze webowej tworzy go instrumentacja frameworka po
        // extract z nagłówków; tu wejście symuluje wywołanie metody, a klient nie przysyła
        // kontekstu, więc span jest rootem.
        Span span = tracer.spanBuilder("POST /checkouts")
                .setNoParent()
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("http.request.method", "POST")
                .setAttribute("http.route", "/checkouts")
                .setAttribute("checkout.order_id", order.id())
                .startSpan();
        // Baggage żyje w tym samym Context co span i przechodzi przez te same propagatory.
        // Tylko wartości o niskiej wrażliwości: Baggage widzi każda usługa po drodze.
        Context requestContext = Context.current()
                .with(span)
                .with(Baggage.builder().put("request.channel", order.channel()).build());
        try (Scope ignored = requestContext.makeCurrent()) {
            calculatePrice(order, handoff).join();
            int inventoryStatus = reserve(order, outbound);
            int status = switch (inventoryStatus) {
                case 201 -> 201;
                case 409 -> 409;
                default -> 502;
            };
            span.setAttribute("http.response.status_code", status);
            if (status >= 500) {
                span.setStatus(StatusCode.ERROR);
            }
            return status;
        } finally {
            span.end();
        }
    }

    private CompletableFuture<Void> calculatePrice(Order order, Handoff handoff) {
        return switch (handoff) {
            // Kontekst przechwycony w wątku zlecającym, w chwili zlecenia. Wywołanie
            // Context.current() dopiero wewnątrz zadania zwróciłoby kontekst wątku puli.
            // Całą pulę można też opakować raz: Context.taskWrapping(executorService).
            case CONTEXT_WRAPPED -> CompletableFuture.runAsync(
                    Context.current().wrap(() -> priceSpan(order, false)), pricingPool);
            // PUŁAPKA: bieżący kontekst jest związany z wątkiem. Na wątku puli nie ma spana
            // requestu, więc checkout.price.calculate zaczyna nowy trace.
            case PLAIN -> CompletableFuture.runAsync(() -> priceSpan(order, false), pricingPool);
            case LEAKING_SCOPE -> CompletableFuture.runAsync(
                    Context.current().wrap(() -> priceSpan(order, true)), pricingPool);
        };
    }

    private void priceSpan(Order order, boolean leakScope) {
        Span span = tracer.spanBuilder("checkout.price.calculate")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("checkout.order_id", order.id())
                .startSpan();
        if (leakScope) {
            // PUŁAPKA: makeCurrent() bez try-with-resources. Scope nigdy się nie zamyka, więc
            // span zostaje bieżący na wątku puli także po końcu zadania. Opakowanie z
            // Context.wrap tego nie naprawi: jego Scope przy zamknięciu widzi inny kontekst
            // niż ten, który ustawił, i nic nie przywraca. Następne zadanie bez kontekstu
            // podczepi swoje spany pod trace tego requestu, czyli innego klienta.
            span.makeCurrent();
            span.end();
            return;
        }
        try (Scope ignored = span.makeCurrent()) {
            // Tu byłoby liczenie ceny; span istnieje po to, żeby było widać, gdzie trafił.
        } finally {
            span.end();
        }
    }

    private int reserve(Order order, Outbound outbound) {
        URI uri = inventory.reservationUri(order.sku());
        return switch (outbound) {
            case PROPAGATED -> http.post(uri, InventoryService.ROUTE);
            case NOT_PROPAGATED -> withOwnClientSpan(uri, () -> plainPost(uri, Map.of()));
            case MANUAL_TRACEPARENT -> plainPost(uri, Map.of("traceparent", handMadeTraceparent()));
            case DOUBLE_INSTRUMENTED -> withOwnClientSpan(uri, () -> http.post(uri, InventoryService.ROUTE));
        };
    }

    /**
     * PUŁAPKA: traceparent sklejony ręcznie. Trzy błędy naraz: parent span ID pochodzi ze spana
     * requestu (spana CLIENT nie ma), flaga {@code 01} wymusza próbkowanie u odbiorcy
     * z {@code ParentBased} niezależnie od decyzji tej usługi, a Baggage i sentry-trace nie jadą wcale. Propagator robi to
     * poprawnie i waliduje format, więc ręczne składanie nie ma przewagi.
     */
    private static String handMadeTraceparent() {
        SpanContext current = Span.current().getSpanContext();
        return "00-" + current.getTraceId() + "-" + current.getSpanId() + "-01";
    }

    /**
     * Span CLIENT utworzony przez kod usługi, nie przez instrumentację klienta HTTP. Z instrumentowanym
     * klientem w środku daje dwa spany CLIENT dla jednego żądania; ze zwykłym klientem daje span bez
     * inject. W obu przypadkach trace checkout-api wygląda poprawnie, a problem widać dopiero po
     * stronie inventory-service.
     */
    private int withOwnClientSpan(URI uri, IntSupplier call) {
        Span span = tracer.spanBuilder("POST " + InventoryService.ROUTE)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("http.request.method", "POST")
                .setAttribute("url.full", uri.toString())
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            int status = call.getAsInt();
            span.setAttribute("http.response.status_code", status);
            return status;
        } finally {
            span.end();
        }
    }

    private int plainPost(URI uri, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody());
        headers.forEach(request::setHeader);
        try {
            return plainHttp.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @Override
    public void close() {
        http.close();
        plainHttp.close();
    }
}
