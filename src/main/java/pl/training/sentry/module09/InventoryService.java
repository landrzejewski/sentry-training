package pl.training.sentry.module09;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.sentry.ISentryLifecycleToken;
import io.sentry.Sentry;
import pl.training.sentry.module09.StockReservation.OutOfStockException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * inventory-service: strona serwera na granicy HTTP, czyli extract kontekstu przed spanem
 * {@code SERVER}, allowlista kluczy Baggage i powiązanie błędu Sentry z bieżącym spanem.
 *
 * <p>Serwer działa w tym samym procesie co checkout-api, ale ma własne SDK OTel
 * ({@link ServiceTelemetry}), więc relację między usługami niosą wyłącznie nagłówki HTTP, jak
 * między dwoma procesami.</p>
 *
 * <p>Endpoint: {@code POST /inventory/reservations?sku=...}. Odpowiedzi: 201 rezerwacja,
 * 409 brak towaru (odmowa biznesowa), 500 awaria magazynu (błąd raportowany do Sentry).</p>
 */
public final class InventoryService implements AutoCloseable {

    public static final String ROUTE = "/inventory/reservations";

    /** Gdzie stoi raportowanie błędów do Sentry względem spana {@code SERVER}. */
    public enum ErrorReporting {
        /** Wewnątrz: span OTel jest bieżący, procesor Sentry wiąże event ze spanem. */
        INSIDE_SPAN,
        /** PUŁAPKA: w globalnej obsłudze błędów, już po zamknięciu {@code Scope} spana. */
        OUTSIDE_SPAN
    }

    /**
     * Klucze Baggage, które ta usługa czyta. Baggage przychodzi z zewnątrz i może nieść
     * cokolwiek, więc usługa kopiuje do atrybutów tylko klucze z allowlisty.
     */
    private static final Set<String> ALLOWED_BAGGAGE = Set.of("request.channel");

    /**
     * Getter nagłówków dla propagatora. {@code Headers.getFirst} ignoruje wielkość liter, bo
     * {@code HttpServer} normalizuje nazwy nagłówków ({@code traceparent} przychodzi jako
     * {@code Traceparent}).
     *
     * <p>PUŁAPKA: przepisanie nagłówków do zwykłej {@code Map<String, String>} i odczyt
     * {@code map.get("traceparent")} zwraca {@code null}. Propagator uzna, że nagłówka nie ma,
     * a serwer zacznie nowy trace, choć klient wszystko wysłał poprawnie.</p>
     */
    static final TextMapGetter<Headers> HEADERS = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Headers carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Headers carrier, String key) {
            return carrier == null ? null : carrier.getFirst(key);
        }
    };

    private final HttpServer server;
    private final Tracer tracer;
    private final TextMapPropagator propagator;
    private final StockReservation reservation;
    private final ErrorReporting errorReporting;
    private volatile Map<String, String> lastPropagationHeaders = Map.of();

    private InventoryService(ServiceTelemetry telemetry, ErrorReporting errorReporting) throws IOException {
        this.tracer = telemetry.tracer(StockReservation.INSTRUMENTATION_SCOPE);
        this.propagator = telemetry.propagator();
        this.reservation = new StockReservation(tracer);
        this.errorReporting = errorReporting;
        // Port 0: system wybiera wolny port, więc testy i demo nie kolidują z innymi procesami.
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(ROUTE, this::handle);
        server.start();
    }

    public static InventoryService start(ServiceTelemetry telemetry, ErrorReporting errorReporting) {
        try {
            return new InventoryService(telemetry, errorReporting);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public URI reservationUri(String sku) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + ROUTE + "?sku=" + sku);
    }

    /** Nagłówki propagacji ostatniego requestu, żeby demo mogło pokazać, co przyszło po sieci. */
    public Map<String, String> lastPropagationHeaders() {
        return lastPropagationHeaders;
    }

    private void handle(HttpExchange exchange) throws IOException {
        // Własny isolation scope Sentry na request (moduł 1). OTel Context i scopes Sentry to
        // dwa niezależne mechanizmy: żaden nie przenosi drugiego.
        try (ISentryLifecycleToken requestScope = Sentry.pushIsolationScope()) {
            int status;
            try {
                status = handleTraced(exchange);
            } catch (RuntimeException exception) {
                // Globalna obsługa błędów, jak ExceptionHandler we frameworku. Działa poza
                // spanem SERVER: jego Scope zamknął się, zanim wyjątek tu dotarł.
                Sentry.captureException(exception);
                status = 500;
            }
            exchange.sendResponseHeaders(status, -1);
        } finally {
            exchange.close();
        }
    }

    private int handleTraced(HttpExchange exchange) {
        Headers headers = exchange.getRequestHeaders();
        rememberPropagationHeaders(headers);

        // Extract przed utworzeniem spana SERVER i z Context.root(), nie z bieżącego kontekstu
        // wątku serwera: rodzicem może być tylko to, co przyszło w żądaniu.
        Context remote = propagator.extract(Context.root(), headers, HEADERS);
        String sku = queryParameter(exchange.getRequestURI(), "sku");
        Span span = tracer.spanBuilder("POST " + ROUTE)
                .setParent(remote)
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("http.request.method", "POST")
                .setAttribute("http.route", ROUTE)
                .startSpan();
        // Baggage nie staje się atrybutem samo. Kopiujemy tylko dozwolone klucze.
        Baggage.fromContext(remote).forEach((key, entry) -> {
            if (ALLOWED_BAGGAGE.contains(key)) {
                span.setAttribute(key, entry.getValue());
            }
        });

        int status = 500;
        // Wewnętrzny try leży w zasięgu Scope. Catch przy try-with-resources wykonuje się już po
        // zamknięciu zasobu, więc Sentry.captureException w takim catch nie widzi spana SERVER.
        try (Scope ignored = remote.with(span).makeCurrent()) {
            try {
                reservation.reserve(sku, 1);
                status = 201;
            } catch (OutOfStockException exception) {
                // Z perspektywy serwera 409 to poprawna odpowiedź, więc status spana zostaje UNSET.
                status = 409;
            } catch (RuntimeException exception) {
                span.setAttribute("error.type", exception.getClass().getName());
                span.setStatus(StatusCode.ERROR);
                if (errorReporting == ErrorReporting.OUTSIDE_SPAN) {
                    // PUŁAPKA: wyjątek trafia do globalnej obsługi w handle(), gdzie żaden span
                    // OTel nie jest bieżący. Event dostanie trace ID ze scope Sentry, a w Sentry UI
                    // nie pojawi się w trace tego requestu.
                    throw exception;
                }
                // Span SERVER jest bieżący, więc procesor Sentry wpisze do eventu jego trace ID
                // i span ID. span.recordException() nie utworzyłby Error Issue: to osobny sygnał.
                Sentry.captureException(exception);
            }
        } finally {
            span.setAttribute("http.response.status_code", status);
            span.end();
        }
        return status;
    }

    private void rememberPropagationHeaders(Headers headers) {
        Map<String, String> seen = new LinkedHashMap<>();
        for (String name : new String[]{"traceparent", "baggage", "sentry-trace"}) {
            String value = headers.getFirst(name);
            if (value != null) {
                seen.put(name, value);
            }
        }
        lastPropagationHeaders = Collections.unmodifiableMap(seen);
    }

    private static String queryParameter(URI uri, String name) {
        String query = uri.getQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(name) && parts.length == 2) {
                return parts[1];
            }
        }
        return null;
    }

    @Override
    public void close() {
        // HttpServer ma wątek dispatchera, który nie jest daemonem: bez stop() proces nie
        // zakończy się po main.
        server.stop(0);
    }
}
