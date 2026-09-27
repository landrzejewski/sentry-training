package pl.training.sentry.module05;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.sentry.BaggageHeader;
import io.sentry.ISentryLifecycleToken;
import io.sentry.ITransaction;
import io.sentry.Sentry;
import io.sentry.SentryTraceHeader;
import io.sentry.SpanStatus;
import io.sentry.TransactionContext;
import io.sentry.TransactionOptions;
import io.sentry.protocol.TransactionNameSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Granica requestu HTTP w usłudze bez integracji frameworka: to, co w Spring Boot robią
 * {@code SentrySpringFilter} i {@code SentryTracingFilter}.
 *
 * <p>Kolejność na granicy: własne scopes requestu, {@code continueTrace} z nagłówków
 * przychodzących, transakcja {@code http.server} nazwana szablonem trasy (a nie rzeczywistym
 * adresem) i jedyny {@code captureException} dla wyjątków z kodu requestu.</p>
 */
public final class TracedHttpHandler implements HttpHandler {

    /** Odpowiedź usługi: kod HTTP i treść. */
    public record Response(int status, String body) {
    }

    /** Obsługa requestu przez kod aplikacji. Dostaje pola formularza z treści requestu. */
    @FunctionalInterface
    public interface RequestHandler {
        Response handle(Map<String, String> form) throws Exception;
    }

    private final String service;
    private final String route;
    private final RequestHandler handler;

    /**
     * @param service nazwa usługi, trafia do tagu {@code service} (w jednym procesie działają
     *                dwie usługi, a w produkcji odróżniłby je osobny projekt Sentry)
     * @param route   stabilna nazwa operacji, np. {@code POST /api/orders}
     */
    public TracedHttpHandler(String service, String route, RequestHandler handler) {
        this.service = service;
        this.route = route;
        this.handler = handler;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Każdy request dostaje świeże scopes sklonowane z root scopes. Wątki HttpServer pochodzą
        // z puli, więc bez tego user, tagi i propagation context jednego requestu zostałyby na
        // wątku dla następnego (moduł 1, scenariusz 6). Token przywraca poprzedni stan wątku.
        // forkedRootScopes SDK oznacza jako @ApiStatus.Internal, ale używają jej integracje SDK
        // (np. dla Kafki). SentrySpringFilter robi to samo przez forkedScopes na wątku Tomcata.
        try (ISentryLifecycleToken requestScopes = Sentry.forkedRootScopes("http.server " + service).makeCurrent()) {
            Sentry.setTag("service", service);
            ITransaction transaction = startTransaction(exchange);
            Response response;
            try {
                response = handler.handle(readForm(exchange));
                transaction.setStatus(ChildSpans.statusForHttp(response.status()));
            } catch (Exception exception) {
                // Granica usługi jest jedynym właścicielem capture dla wyjątków z kodu requestu.
                transaction.setThrowable(exception);
                transaction.setStatus(SpanStatus.INTERNAL_ERROR);
                Sentry.captureException(exception);
                response = new Response(500, "Błąd serwera");
            }
            // Transakcja kończy się tuż przed wysłaniem odpowiedzi. Czas zapisu odpowiedzi jest
            // tu pomijalny, a wydruk demo ma stałą kolejność: klient dostaje odpowiedź po tym,
            // jak transakcja trafiła do transportu.
            transaction.finish();
            respond(exchange, response);
        }
    }

    private ITransaction startTransaction(HttpExchange exchange) {
        String sentryTrace = exchange.getRequestHeaders().getFirst(SentryTraceHeader.SENTRY_TRACE_HEADER);
        List<String> baggage = exchange.getRequestHeaders().get(BaggageHeader.BAGGAGE_HEADER);

        // continueTrace ustawia propagation context bieżącego scope na podstawie nagłówków. Bez
        // nagłówka tworzy nowy trace, więc także logi i błędy spoza spanów dostają trace tego
        // requestu, a nie wspólny trace z Sentry.init. Musi zadziałać przed startTransaction.
        // PUŁAPKA: wywołanie poza własnymi scopes requestu nadpisałoby propagation context wątku.
        TransactionContext continued = Sentry.continueTrace(sentryTrace, baggage);

        TransactionOptions options = new TransactionOptions();
        options.setBindToScope(true);
        if (continued == null) {
            // Tracing wyłączony (brak tracesSampleRate i tracesSampler): SDK zwróci NoOpTransaction.
            return Sentry.startTransaction(route, "http.server", options);
        }
        // Kontekst z continueTrace niesie trace ID, parent span ID i decyzję samplingu rodzica.
        // Nazwa to szablon trasy: identyfikatory z adresu rozbiłyby agregację i sampling.
        continued.setName(route);
        continued.setTransactionNameSource(TransactionNameSource.ROUTE);
        continued.setOperation("http.server");
        return Sentry.startTransaction(continued, options);
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0) {
                form.put(pair.substring(0, separator),
                        URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
            }
        }
        return form;
    }

    private static void respond(HttpExchange exchange, Response response) throws IOException {
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.status(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
