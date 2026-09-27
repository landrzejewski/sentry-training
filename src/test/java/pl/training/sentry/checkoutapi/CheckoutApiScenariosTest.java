package pl.training.sentry.checkoutapi;

import io.sentry.ITransportFactory;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.protocol.SentryTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import pl.training.sentry.support.SentryTestSupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backend przykładu {@code frontend/checkout-web}: CORS dla nagłówków trace i kontynuacja trace
 * przeglądarki przez starter Spring Boot. Przeglądarkę zastępuje tu klient HTTP, który wysyła
 * te same nagłówki co browser SDK.
 */
@ResourceLock("sentry-global-state")
class CheckoutApiScenariosTest {

    private static final String BROWSER_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String BROWSER_SPAN_ID = "00f067aa0ba902b7";
    private static final String SENTRY_TRACE = BROWSER_TRACE_ID + "-" + BROWSER_SPAN_ID + "-1";
    private static final String BAGGAGE = "sentry-trace_id=" + BROWSER_TRACE_ID
            + ",sentry-environment=training,sentry-release=checkout-web%401.0.0%2Blocal,sentry-sample_rate=1";

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void preflightAllowsTraceHeadersOnlyOnApiPath() throws Exception {
        try (var telemetry = SentryTestSupport.start(options -> {
        })) {
            ITransportFactory recording = Sentry.getCurrentScopes().getOptions().getTransportFactory();
            Sentry.close();
            try (var app = CheckoutApiApplication.start(0, options -> options.setTransportFactory(recording))) {
                HttpResponse<Void> api = preflight(app.baseUri(), "/api/checkout");
                assertEquals(200, api.statusCode());
                String allowed = api.headers().firstValue("Access-Control-Allow-Headers").orElse("");
                assertTrue(allowed.contains("sentry-trace") && allowed.contains("baggage"), allowed);

                // Stara polityka: status 200, ale bez nagłówków trace na liście. Przeglądarka
                // porównuje listę z nagłówkami requestu i nie wysyła właściwego POST.
                HttpResponse<Void> legacy = preflight(app.baseUri(), "/legacy-api/checkout");
                assertEquals(200, legacy.statusCode());
                assertEquals("content-type", legacy.headers().firstValue("Access-Control-Allow-Headers").orElse(""));
                Sentry.flush(2_000);
                // Preflight nie niesie nagłówków trace, więc starter tworzy dla niego osobną
                // transakcję OPTIONS w nowym trace. Dla starej ścieżki to jedyny ślad w Sentry.
                assertTrue(telemetry.events().isEmpty());
                assertEquals(List.of("OPTIONS /api/checkout", "OPTIONS /legacy-api/checkout"),
                        telemetry.transactions().stream().map(SentryTransaction::getTransaction).toList());
            }
        }
    }

    @Test
    void starterContinuesBrowserTraceAndBackendErrorJoinsIt() throws Exception {
        try (var telemetry = SentryTestSupport.start(options -> {
        })) {
            ITransportFactory recording = Sentry.getCurrentScopes().getOptions().getTransportFactory();
            Sentry.close();
            try (var app = CheckoutApiApplication.start(0, options -> options.setTransportFactory(recording))) {
                assertEquals(201, post(app.baseUri(), "{\"deliveryMode\":\"PICKUP_POINT\",\"pickupPointId\":\"WAW-114\"}"));
                assertEquals(500, post(app.baseUri(), "{\"deliveryMode\":\"PICKUP_POINT\",\"pickupPointId\":\"KRK-031\"}"));
                Sentry.flush(2_000);

                assertEquals(2, telemetry.transactions().size());
                for (SentryTransaction transaction : telemetry.transactions()) {
                    assertEquals("POST /api/checkout", transaction.getTransaction());
                    assertEquals(BROWSER_TRACE_ID, transaction.getContexts().getTrace().getTraceId().toString());
                    assertEquals(BROWSER_SPAN_ID, transaction.getContexts().getTrace().getParentSpanId().toString());
                    assertEquals("checkout-api@1.0.0+local", transaction.getRelease());
                }

                SentryEvent error = telemetry.singleEvent();
                assertEquals(BROWSER_TRACE_ID, error.getContexts().getTrace().getTraceId().toString());
                assertFalse(error.getExceptions().getLast().getMechanism().isHandled());
                assertEquals("frontend", error.getTag("training.module"));
            }
        }
    }

    @Test
    void withoutTraceHeadersBackendStartsItsOwnTrace() throws Exception {
        try (var telemetry = SentryTestSupport.start(options -> {
        })) {
            ITransportFactory recording = Sentry.getCurrentScopes().getOptions().getTransportFactory();
            Sentry.close();
            try (var app = CheckoutApiApplication.start(0, options -> options.setTransportFactory(recording))) {
                // Adres spoza tracePropagationTargets: przeglądarka nie dokleja nagłówków.
                HttpRequest request = HttpRequest.newBuilder(app.baseUri().resolve("/api/checkout"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"deliveryMode\":\"COURIER\"}"))
                        .build();
                assertEquals(201, http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
                Sentry.flush(2_000);

                SentryTransaction transaction = telemetry.transactions().getFirst();
                assertFalse(BROWSER_TRACE_ID.equals(transaction.getContexts().getTrace().getTraceId().toString()));
                assertNull(transaction.getContexts().getTrace().getParentSpanId());
            }
        }
    }

    private HttpResponse<Void> preflight(URI base, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,sentry-trace,baggage")
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding());
    }

    private int post(URI base, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/api/checkout"))
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost:4200")
                .header("sentry-trace", SENTRY_TRACE)
                .header("baggage", BAGGAGE)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
