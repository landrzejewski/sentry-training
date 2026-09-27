package pl.training.sentry.module09;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapPropagator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Instrumentacja klienta HTTP: span {@code CLIENT} i inject kontekstu do nagłówków żądania.
 *
 * <p>Klasa gra rolę biblioteki instrumentacji, jaką w produkcji dostarcza Java Agent albo
 * Starter, dlatego ma własny instrumentation scope. Jest jedynym właścicielem granicy klienta
 * HTTP: kod usługi, który tworzy obok niej własny span dla tego samego wywołania, dubluje
 * granicę (scenariusz 6).</p>
 */
public final class TracedHttpClient implements AutoCloseable {

    public static final String INSTRUMENTATION_SCOPE = "pl.training.http-client";

    private final HttpClient http = HttpClient.newHttpClient();
    private final Tracer tracer;
    private final TextMapPropagator propagator;

    public TracedHttpClient(ServiceTelemetry telemetry) {
        this.tracer = telemetry.tracer(INSTRUMENTATION_SCOPE);
        this.propagator = telemetry.propagator();
    }

    /**
     * Wysyła POST i zwraca kod odpowiedzi.
     *
     * @param route szablon ścieżki do nazwy spana; pełny URL z parametrami trafia tylko do
     *              atrybutu {@code url.full}, bo w nazwie tworzyłby wysoką kardynalność
     */
    public int post(URI uri, String route) {
        // Atrybuty semantic conventions w builderze: sampler widzi tylko to, co ustawiono przed
        // startSpan(). url.full czyta też propagator Sentry przy inject: sentry-trace i baggage
        // Sentry trafiają tylko do URL zgodnych z tracePropagationTargets.
        Span span = tracer.spanBuilder("POST " + route)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("http.request.method", "POST")
                .setAttribute("url.full", uri.toString())
                .setAttribute("server.address", uri.getHost())
                .setAttribute("server.port", uri.getPort())
                .startSpan();
        // Kolejność: span CLIENT, jego kontekst jako bieżący, dopiero potem inject. Inject przed
        // makeCurrent() zapisałby w traceparent span nadrzędny, a serwer podczepiłby się pod
        // niego z pominięciem spana klienta.
        try (Scope ignored = span.makeCurrent()) {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody());
            propagator.inject(Context.current(), request, HttpRequest.Builder::setHeader);
            int status = http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            span.setAttribute("http.response.status_code", status);
            // Z perspektywy klienta każda odpowiedź 4xx i 5xx to nieudane wywołanie. Serwer
            // ocenia ten sam kod inaczej (InventoryService: 409 nie jest błędem serwera).
            if (status >= 400) {
                span.setAttribute("error.type", String.valueOf(status));
                span.setStatus(StatusCode.ERROR);
            }
            return status;
        } catch (IOException exception) {
            fail(span, exception);
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            fail(span, exception);
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } finally {
            span.end();
        }
    }

    private static void fail(Span span, Exception exception) {
        span.recordException(exception);
        span.setAttribute("error.type", exception.getClass().getName());
        span.setStatus(StatusCode.ERROR);
    }

    @Override
    public void close() {
        http.close();
    }
}
