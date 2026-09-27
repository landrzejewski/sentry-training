package pl.training.sentry.module09;

import com.sun.net.httpserver.Headers;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import pl.training.sentry.module09.CheckoutService.Handoff;
import pl.training.sentry.module09.CheckoutService.Order;
import pl.training.sentry.module09.CheckoutService.Outbound;
import pl.training.sentry.module09.InventoryService.ErrorReporting;
import pl.training.sentry.module09.StockReservation.OutOfStockException;
import pl.training.sentry.support.SentryTestSupport;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenariusze {@link Module09Demo} sprawdzone na spanach, które SDK przekazało do exportera,
 * i na eventach, które Sentry SDK przekazało do transportu.
 *
 * <p>Każda klasa zagnieżdżona odpowiada jednemu scenariuszowi. Usługi rozmawiają przez
 * prawdziwe HTTP na porcie lokalnym, a każda ma własne SDK OTel z exporterem w pamięci.
 * Globalny stan ma tylko Sentry SDK (propagator {@code sentry} czyta jego opcje), stąd blokada
 * na całej klasie.</p>
 */
@ResourceLock("sentry-global-state")
class Module09ScenariosTest {

    private static final Sampler ALWAYS = Sampler.parentBased(Sampler.alwaysOn());

    @Nested
    class Scenario1SpanLifecycle {

        private final InMemorySpanExporter exporter = InMemorySpanExporter.create();

        @Test
        void targetVersionNestsChildAndMarksFailureAsError() {
            try (ServiceTelemetry telemetry = telemetry("inventory-service", ALWAYS, exporter)) {
                StockReservation reservation = reservation(telemetry);
                reservation.reserve("SKU-1", 1);
                assertThrows(IllegalStateException.class, () -> reservation.reserve("SKU-LEGACY", 1));

                List<SpanData> spans = exporter.getFinishedSpanItems();
                SpanData okReserve = named(spans, "inventory.reserve").getFirst();
                SpanData okDecrement = named(spans, "inventory.stock.decrement").getFirst();
                assertEquals(okReserve.getSpanId(), okDecrement.getParentSpanId());
                assertEquals(okReserve.getTraceId(), okDecrement.getTraceId());
                assertEquals(StatusCode.UNSET, okReserve.getStatus().getStatusCode());

                SpanData failed = named(spans, "inventory.reserve").get(1);
                assertEquals(StatusCode.ERROR, failed.getStatus().getStatusCode());
                assertEquals("java.lang.IllegalStateException", attribute(failed, "error.type"));
                assertEquals(List.of("exception"), eventNames(failed));
            }
        }

        @Test
        void carelessVersionLosesParentAndLeavesFailureUnset() {
            try (ServiceTelemetry telemetry = telemetry("inventory-service", ALWAYS, exporter)) {
                StockReservation reservation = reservation(telemetry);
                reservation.reserveCarelessly("SKU-1", 1);
                assertThrows(IllegalStateException.class, () -> reservation.reserveCarelessly("SKU-LEGACY", 1));

                List<SpanData> spans = exporter.getFinishedSpanItems();
                // startSpan() bez makeCurrent(): span podrzędny nie widzi rodzica i zaczyna nowy trace.
                SpanData decrement = named(spans, "inventory.stock.decrement").getFirst();
                assertFalse(SpanId.isValid(decrement.getParentSpanId()));
                assertNotEquals(named(spans, "inventory.reserve").getFirst().getTraceId(), decrement.getTraceId());
                assertEquals(4, spans.stream().map(SpanData::getTraceId).distinct().count());

                // recordException() bez setStatus(): zdarzenie jest, porażki w statusie nie ma.
                SpanData failed = named(spans, "inventory.reserve").get(1);
                assertEquals(List.of("exception"), eventNames(failed));
                assertEquals(StatusCode.UNSET, failed.getStatus().getStatusCode());
            }
        }

        @Test
        void outOfStockIsRecordedButIsNotAnError() {
            try (ServiceTelemetry telemetry = telemetry("inventory-service", ALWAYS, exporter)) {
                assertThrows(OutOfStockException.class, () -> reservation(telemetry).reserve("SKU-EMPTY", 1));

                SpanData reserve = named(exporter.getFinishedSpanItems(), "inventory.reserve").getFirst();
                assertEquals(List.of("exception"), eventNames(reserve));
                assertEquals(StatusCode.UNSET, reserve.getStatus().getStatusCode());
            }
        }

        @Test
        void startScopeAndEndAreIndependentSteps() {
            try (ServiceTelemetry telemetry = telemetry("inventory-service", ALWAYS, exporter)) {
                Tracer tracer = telemetry.tracer("test");
                Span span = tracer.spanBuilder("step").startSpan();
                // startSpan() nie ustawia spana jako bieżącego.
                assertFalse(Span.current().getSpanContext().isValid());

                try (Scope ignored = span.makeCurrent()) {
                    span.end();
                    // end() kończy span, ale nie przywraca kontekstu.
                    assertEquals(span.getSpanContext(), Span.current().getSpanContext());
                }
                Span second = tracer.spanBuilder("second").startSpan();
                try (Scope ignored = second.makeCurrent()) {
                    assertTrue(second.isRecording());
                }
                // Zamknięcie Scope przywraca kontekst, ale spana nie kończy: nie ma go w exporterze.
                assertFalse(Span.current().getSpanContext().isValid());
                assertEquals(List.of("step"), exporter.getFinishedSpanItems().stream().map(SpanData::getName).toList());
                second.end();
            }
        }
    }

    @Nested
    class Scenario2PropagationBetweenServices {

        @Test
        void propagatedCallSharesTraceAndServerHangsUnderClientSpan() {
            try (Services services = new Services(ALWAYS, ALWAYS, ErrorReporting.INSIDE_SPAN)) {
                assertEquals(201, services.checkout.submit(new Order("ORD-1", "SKU-1", "mobile")));

                List<SpanData> spans = services.spans();
                assertEquals(1, spans.stream().map(SpanData::getTraceId).distinct().count());
                SpanData client = single(spans, "POST " + InventoryService.ROUTE, SpanKind.CLIENT);
                SpanData server = single(spans, "POST " + InventoryService.ROUTE, SpanKind.SERVER);
                assertEquals(client.getSpanId(), server.getParentSpanId());
                assertTrue(server.getParentSpanContext().isRemote());
                assertEquals(TracedHttpClient.INSTRUMENTATION_SCOPE, client.getInstrumentationScopeInfo().getName());
                // Baggage dotarło i trafiło do atrybutu, bo klucz jest na allowliście.
                assertEquals("mobile", attribute(server, "request.channel"));
            }
        }

        @Test
        void propagatorsSendTraceparentBaggageAndSentryTrace() {
            try (Services services = new Services(ALWAYS, ALWAYS, ErrorReporting.INSIDE_SPAN)) {
                services.checkout.submit(new Order("ORD-1", "SKU-1", "mobile"));

                SpanData client = single(services.spans(), "POST " + InventoryService.ROUTE, SpanKind.CLIENT);
                Map<String, String> headers = services.inventory.lastPropagationHeaders();
                // Flagi 03: sampled (0x01) i losowy trace ID (0x02, W3C Trace Context Level 2).
                // Kod, który sprawdza sufiks 01, uzna taki trace za niepróbkowany.
                assertEquals("00-" + client.getTraceId() + "-" + client.getSpanId() + "-03", headers.get("traceparent"));
                assertEquals("request.channel=mobile", headers.get("baggage"));
                assertEquals(client.getTraceId() + "-" + client.getSpanId() + "-1", headers.get("sentry-trace"));
            }
        }

        @Test
        void clientSpanWithoutInjectLeavesServerInNewTrace() {
            try (Services services = new Services(ALWAYS, ALWAYS, ErrorReporting.INSIDE_SPAN)) {
                services.checkout.submit(new Order("ORD-1", "SKU-1", "web"), Outbound.NOT_PROPAGATED);

                List<SpanData> spans = services.spans();
                SpanData client = single(spans, "POST " + InventoryService.ROUTE, SpanKind.CLIENT);
                SpanData server = single(spans, "POST " + InventoryService.ROUTE, SpanKind.SERVER);
                assertNotEquals(client.getTraceId(), server.getTraceId());
                assertFalse(SpanId.isValid(server.getParentSpanId()));
                assertTrue(services.inventory.lastPropagationHeaders().isEmpty());
            }
        }

        @Test
        void handMadeTraceparentSkipsClientSpanAndDropsBaggage() {
            try (Services services = new Services(ALWAYS, ALWAYS, ErrorReporting.INSIDE_SPAN)) {
                services.checkout.submit(new Order("ORD-1", "SKU-1", "web"), Outbound.MANUAL_TRACEPARENT);

                List<SpanData> spans = services.spans();
                SpanData checkoutEntry = single(spans, "POST /checkouts", SpanKind.SERVER);
                SpanData server = single(spans, "POST " + InventoryService.ROUTE, SpanKind.SERVER);
                assertEquals(checkoutEntry.getSpanId(), server.getParentSpanId());
                assertTrue(spans.stream().noneMatch(span -> span.getKind() == SpanKind.CLIENT));
                assertNull(attribute(server, "request.channel"));
                assertEquals(Set.of("traceparent"), services.inventory.lastPropagationHeaders().keySet());
            }
        }

        @Test
        void handMadeSampledFlagOverridesDecisionOfCallingService() {
            Sampler neverSample = Sampler.parentBased(Sampler.alwaysOff());
            try (Services services = new Services(neverSample, ALWAYS, ErrorReporting.INSIDE_SPAN)) {
                services.checkout.submit(new Order("ORD-1", "SKU-1", "web"));
                // Propagator przekazał decyzję „nie próbkuj”, więc inventory-service nic nie zapisało.
                assertTrue(services.spans().isEmpty());

                services.checkout.submit(new Order("ORD-2", "SKU-1", "web"), Outbound.MANUAL_TRACEPARENT);
                // Sztywna flaga 01 wymusza zapis w usłudze podrzędnej: fragment bez rodzica.
                List<SpanData> spans = services.spans();
                assertEquals(Set.of("inventory-service"), services(spans));
                assertTrue(single(spans, "POST " + InventoryService.ROUTE, SpanKind.SERVER).getParentSpanContext().isRemote());
            }
        }

        @Test
        void httpServerNormalizesHeaderNamesSoCaseSensitiveLookupMisses() {
            Headers headers = new Headers();
            headers.add("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
            assertEquals(Set.of("Traceparent"), headers.keySet());

            Map<String, String> copied = new HashMap<>();
            headers.forEach((name, values) -> copied.put(name, values.getFirst()));
            TextMapGetter<Map<String, String>> caseSensitive = new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(Map<String, String> carrier) {
                    return carrier.keySet();
                }

                @Override
                public String get(Map<String, String> carrier, String key) {
                    return carrier.get(key);
                }
            };
            W3CTraceContextPropagator propagator = W3CTraceContextPropagator.getInstance();

            Context lost = propagator.extract(Context.root(), copied, caseSensitive);
            Context kept = propagator.extract(Context.root(), headers, InventoryService.HEADERS);
            assertFalse(Span.fromContext(lost).getSpanContext().isValid());
            assertEquals("0af7651916cd43dd8448eb211c80319c", Span.fromContext(kept).getSpanContext().getTraceId());
        }

        @Test
        void clientAndServerJudgeConflictDifferently() {
            try (Services services = new Services(ALWAYS, ALWAYS, ErrorReporting.INSIDE_SPAN)) {
                assertEquals(409, services.checkout.submit(new Order("ORD-1", "SKU-EMPTY", "web")));

                List<SpanData> spans = services.spans();
                assertEquals(StatusCode.ERROR,
                        single(spans, "POST " + InventoryService.ROUTE, SpanKind.CLIENT).getStatus().getStatusCode());
                assertEquals(StatusCode.UNSET,
                        single(spans, "POST " + InventoryService.ROUTE, SpanKind.SERVER).getStatus().getStatusCode());
            }
        }
    }

    @Nested
    class Scenario3ContextAcrossExecutor {

        @Test
        void plainTaskStartsNewTrace() {
            try (Services services = new Services(ALWAYS, ALWAYS, ErrorReporting.INSIDE_SPAN)) {
                services.checkout.submit(new Order("ORD-1", "SKU-1", "web"), Handoff.PLAIN);

                List<SpanData> spans = services.spans();
                SpanData price = named(spans, "checkout.price.calculate").getFirst();
                assertFalse(SpanId.isValid(price.getParentSpanId()));
                assertNotEquals(single(spans, "POST /checkouts", SpanKind.SERVER).getTraceId(), price.getTraceId());
            }
        }

        @Test
        void wrappedTaskStaysUnderRequestSpan() {
            try (Services services = new Services(ALWAYS, ALWAYS, ErrorReporting.INSIDE_SPAN)) {
                services.checkout.submit(new Order("ORD-1", "SKU-1", "web"), Handoff.CONTEXT_WRAPPED);

                List<SpanData> spans = services.spans();
                assertEquals(single(spans, "POST /checkouts", SpanKind.SERVER).getSpanId(),
                        named(spans, "checkout.price.calculate").getFirst().getParentSpanId());
            }
        }

        @Test
        void leakedScopeAttachesNextRequestToForeignTrace() {
            try (Services services = new Services(ALWAYS, ALWAYS, ErrorReporting.INSIDE_SPAN)) {
                services.checkout.submit(new Order("ORD-1", "SKU-1", "web"), Handoff.LEAKING_SCOPE);
                services.checkout.submit(new Order("ORD-2", "SKU-1", "web"), Handoff.PLAIN);

                List<SpanData> spans = services.spans();
                SpanData firstPrice = priceOf(spans, "ORD-1");
                SpanData secondPrice = priceOf(spans, "ORD-2");
                // Opakowanie Context.wrap nie usunęło wycieku: następne zadanie widzi span ORD-1.
                assertEquals(firstPrice.getTraceId(), secondPrice.getTraceId());
                assertEquals(firstPrice.getSpanId(), secondPrice.getParentSpanId());
            }
        }

        @Test
        void wrappedTaskIsNotAffectedByLeakedContext() {
            try (Services services = new Services(ALWAYS, ALWAYS, ErrorReporting.INSIDE_SPAN)) {
                services.checkout.submit(new Order("ORD-1", "SKU-1", "web"), Handoff.LEAKING_SCOPE);
                services.checkout.submit(new Order("ORD-2", "SKU-1", "web"), Handoff.CONTEXT_WRAPPED);

                List<SpanData> spans = services.spans();
                SpanData secondEntry = spans.stream()
                        .filter(span -> span.getName().equals("POST /checkouts"))
                        .filter(span -> "ORD-2".equals(attribute(span, "checkout.order_id")))
                        .findFirst().orElseThrow();
                assertEquals(secondEntry.getSpanId(), priceOf(spans, "ORD-2").getParentSpanId());
            }
        }

        private SpanData priceOf(List<SpanData> spans, String orderId) {
            return named(spans, "checkout.price.calculate").stream()
                    .filter(span -> orderId.equals(attribute(span, "checkout.order_id")))
                    .findFirst().orElseThrow();
        }
    }

    @Nested
    class Scenario4HeadSampling {

        private static final int REQUESTS = 100;

        @Test
        void independentLowerRatioDownstreamCutsTracesButLeavesNoOrphans() {
            try (Services services = new Services(Sampler.parentBased(Sampler.traceIdRatioBased(0.5)),
                    Sampler.traceIdRatioBased(0.1), ErrorReporting.INSIDE_SPAN)) {
                submitMany(services);

                Map<String, Set<String>> traces = servicesByTrace(services.spans());
                assertTrue(traces.values().stream().anyMatch(s -> s.equals(Set.of("checkout-api"))));
                // TraceIdRatioBased decyduje z trace ID: co przepuszcza 0.1, przepuszcza też 0.5.
                assertTrue(traces.values().stream().allMatch(s -> s.contains("checkout-api")));
            }
        }

        @Test
        void independentHigherRatioDownstreamLeavesOrphanFragments() {
            try (Services services = new Services(Sampler.parentBased(Sampler.traceIdRatioBased(0.5)),
                    Sampler.traceIdRatioBased(0.9), ErrorReporting.INSIDE_SPAN)) {
                submitMany(services);

                Map<String, Set<String>> traces = servicesByTrace(services.spans());
                assertTrue(traces.values().stream().anyMatch(s -> s.equals(Set.of("inventory-service"))));
                // Każdy trace zapisany przez checkout-api jest pełny: co przepuszcza 0.5, przepuszcza 0.9.
                assertTrue(traces.values().stream()
                        .filter(s -> s.contains("checkout-api"))
                        .allMatch(s -> s.contains("inventory-service")));
            }
        }

        @Test
        void parentBasedDownstreamKeepsEveryRecordedTraceComplete() {
            try (Services services = new Services(Sampler.parentBased(Sampler.traceIdRatioBased(0.5)),
                    Sampler.parentBased(Sampler.traceIdRatioBased(0.1)), ErrorReporting.INSIDE_SPAN)) {
                submitMany(services);

                Map<String, Set<String>> traces = servicesByTrace(services.spans());
                assertFalse(traces.isEmpty());
                assertTrue(traces.values().stream().allMatch(s -> s.equals(Set.of("checkout-api", "inventory-service"))));
            }
        }

        @Test
        void droppedTraceStillPropagatesValidContext() {
            try (Services services = new Services(Sampler.parentBased(Sampler.alwaysOff()), ALWAYS,
                    ErrorReporting.INSIDE_SPAN)) {
                services.checkout.submit(new Order("ORD-1", "SKU-1", "web"));

                Map<String, String> headers = services.inventory.lastPropagationHeaders();
                assertTrue(headers.get("traceparent").endsWith("-02"));
                assertTrue(headers.get("sentry-trace").endsWith("-0"));
                assertTrue(services.spans().isEmpty());
            }
        }

        private void submitMany(Services services) {
            for (int i = 0; i < REQUESTS; i++) {
                services.checkout.submit(new Order("ORD-" + i, "SKU-1", "web"));
            }
        }
    }

    @Nested
    class Scenario5ErrorLinkedToSpan {

        @Test
        void captureInsideServerSpanLinksEventToThatSpan() {
            try (var sentry = SentryTestSupport.start(SentryOtlp::configure);
                 Services services = new Services(ALWAYS, ALWAYS, ErrorReporting.INSIDE_SPAN)) {
                assertEquals(502, services.checkout.submit(new Order("ORD-1", "SKU-LEGACY", "web")));

                SentryEvent event = sentry.singleEvent();
                SpanData server = single(services.spans(), "POST " + InventoryService.ROUTE, SpanKind.SERVER);
                assertEquals(server.getTraceId(), event.getContexts().getTrace().getTraceId().toString());
                assertEquals(server.getSpanId(), event.getContexts().getTrace().getSpanId().toString());
            }
        }

        @Test
        void captureAfterScopeClosedKeepsSentryTraceId() {
            try (var sentry = SentryTestSupport.start(SentryOtlp::configure);
                 Services services = new Services(ALWAYS, ALWAYS, ErrorReporting.OUTSIDE_SPAN)) {
                services.checkout.submit(new Order("ORD-1", "SKU-LEGACY", "web"));

                SpanData server = single(services.spans(), "POST " + InventoryService.ROUTE, SpanKind.SERVER);
                assertEquals(StatusCode.ERROR, server.getStatus().getStatusCode());
                assertNotEquals(server.getTraceId(), sentry.singleEvent().getContexts().getTrace().getTraceId().toString());
            }
        }

        @Test
        void withoutProcessorEventIsNotLinkedEvenInsideSpan() {
            try (var sentry = SentryTestSupport.start(options -> {});
                 Services services = new Services(ALWAYS, ALWAYS, ErrorReporting.INSIDE_SPAN)) {
                services.checkout.submit(new Order("ORD-1", "SKU-LEGACY", "web"));

                SpanData server = single(services.spans(), "POST " + InventoryService.ROUTE, SpanKind.SERVER);
                assertNotEquals(server.getTraceId(), sentry.singleEvent().getContexts().getTrace().getTraceId().toString());
            }
        }

        @Test
        void catchOfTryWithResourcesRunsAfterScopeIsClosed() {
            InMemorySpanExporter exporter = InMemorySpanExporter.create();
            try (var sentry = SentryTestSupport.start(SentryOtlp::configure);
                 ServiceTelemetry telemetry = telemetry("inventory-service", ALWAYS, exporter)) {
                Span span = telemetry.tracer("test").spanBuilder("work").startSpan();
                try (Scope ignored = span.makeCurrent()) {
                    throw new IllegalStateException("awaria");
                } catch (IllegalStateException exception) {
                    Sentry.captureException(exception);
                } finally {
                    span.end();
                }

                assertNotEquals(span.getSpanContext().getTraceId(),
                        sentry.singleEvent().getContexts().getTrace().getTraceId().toString());
            }
        }

        @Test
        void unsampledSpanStillLinksEvent() {
            try (var sentry = SentryTestSupport.start(SentryOtlp::configure);
                 Services services = new Services(Sampler.parentBased(Sampler.alwaysOff()), ALWAYS,
                         ErrorReporting.INSIDE_SPAN)) {
                services.checkout.submit(new Order("ORD-1", "SKU-LEGACY", "web"));

                String traceparent = services.inventory.lastPropagationHeaders().get("traceparent");
                assertTrue(services.spans().isEmpty());
                // Kontekst jest ważny, choć spany nie trafią do backendu: event ma trace ID requestu.
                assertEquals(traceparent.split("-")[1],
                        sentry.singleEvent().getContexts().getTrace().getTraceId().toString());
            }
        }

        @Test
        void recordExceptionAloneSendsNothingToSentry() {
            InMemorySpanExporter exporter = InMemorySpanExporter.create();
            try (var sentry = SentryTestSupport.start(SentryOtlp::configure);
                 ServiceTelemetry telemetry = telemetry("inventory-service", ALWAYS, exporter)) {
                assertThrows(IllegalStateException.class, () -> reservation(telemetry).reserve("SKU-LEGACY", 1));

                assertEquals(List.of("exception"), eventNames(named(exporter.getFinishedSpanItems(), "inventory.reserve").getFirst()));
                assertTrue(sentry.events().isEmpty());
            }
        }

        @Test
        void otlpEndpointAndHeaderComeFromDsn() {
            String local = "http://4a287873c890113c552d422a678b6a32@localhost:9000/2";
            assertEquals("http://localhost:9000/api/2/integration/otlp/v1/traces", SentryOtlp.tracesEndpoint(local));
            assertEquals("sentry sentry_key=4a287873c890113c552d422a678b6a32", SentryOtlp.authHeader(local));
            assertEquals("https://o11.ingest.de.sentry.io/api/22/integration/otlp/v1/traces",
                    SentryOtlp.tracesEndpoint("https://abc@o11.ingest.de.sentry.io/22"));
        }
    }

    @Nested
    class Scenario6DuplicatedBoundary {

        @Test
        void manualSpanAroundInstrumentedClientGivesTwoClientSpans() {
            try (Services services = new Services(ALWAYS, ALWAYS, ErrorReporting.INSIDE_SPAN)) {
                services.checkout.submit(new Order("ORD-1", "SKU-1", "web"), Outbound.DOUBLE_INSTRUMENTED);

                List<SpanData> spans = services.spans();
                List<SpanData> clients = spans.stream().filter(span -> span.getKind() == SpanKind.CLIENT).toList();
                assertEquals(2, clients.size());
                assertEquals(1, clients.stream().map(SpanData::getName).distinct().count());
                SpanData outer = scoped(clients, CheckoutService.INSTRUMENTATION_SCOPE);
                SpanData inner = scoped(clients, TracedHttpClient.INSTRUMENTATION_SCOPE);
                assertEquals(outer.getSpanId(), inner.getParentSpanId());
                assertEquals(inner.getSpanId(),
                        single(spans, "POST " + InventoryService.ROUTE, SpanKind.SERVER).getParentSpanId());
            }
        }

        private SpanData scoped(List<SpanData> spans, String scope) {
            return spans.stream()
                    .filter(span -> span.getInstrumentationScopeInfo().getName().equals(scope))
                    .findFirst().orElseThrow();
        }
    }

    @Nested
    class Scenario7ShortLivedProcess {

        private static final List<String> SKUS = List.of("SKU-1", "SKU-1", "SKU-1");

        @Test
        void batchedSpansWaitInQueueUntilShutdown() {
            // TraceConsole, a nie InMemorySpanExporter: ten drugi czyści listę spanów przy shutdown.
            TraceConsole exporter = new TraceConsole(new PrintStream(OutputStream.nullOutputStream()));
            ServiceTelemetry telemetry = batched(exporter);
            new StockImportJob(telemetry).run(SKUS, false);

            // Tu krótki proces by się zakończył: wątek eksportu jest daemonem, a hooka nie ma.
            assertEquals(0, exporter.exportedCount());

            telemetry.close();
            assertEquals(7, exporter.exportedCount());
        }

        @Test
        void flushAfterEachItemSplitsExportAndSkipsActiveSpan() {
            TraceConsole exporter = new TraceConsole(new PrintStream(OutputStream.nullOutputStream()));
            try (ServiceTelemetry telemetry = batched(exporter)) {
                new StockImportJob(telemetry).run(SKUS, true);

                assertEquals(6, exporter.exportedCount());
                assertEquals(3, exporter.exportCalls());
            }
            assertEquals(7, exporter.exportedCount());
            assertEquals(4, exporter.exportCalls());
        }

        @Test
        void shutdownSendsWholeJobInOneBatch() {
            TraceConsole exporter = new TraceConsole(new PrintStream(OutputStream.nullOutputStream()));
            try (ServiceTelemetry telemetry = batched(exporter)) {
                new StockImportJob(telemetry).run(SKUS, false);
            }
            assertEquals(7, exporter.exportedCount());
            assertEquals(1, exporter.exportCalls());
        }

        private ServiceTelemetry batched(io.opentelemetry.sdk.trace.export.SpanExporter exporter) {
            return ServiceTelemetry.create("stock-import", "test", "test", ALWAYS,
                    List.of(BatchSpanProcessor.builder(exporter).build()));
        }
    }

    /** Dwie usługi na prawdziwym HTTP, każda z własnym SDK; wspólny exporter w pamięci. */
    private static final class Services implements AutoCloseable {

        final InMemorySpanExporter exporter = InMemorySpanExporter.create();
        final ServiceTelemetry checkoutTelemetry;
        final ServiceTelemetry inventoryTelemetry;
        final InventoryService inventory;
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        final CheckoutService checkout;

        Services(Sampler checkoutSampler, Sampler inventorySampler, ErrorReporting reporting) {
            checkoutTelemetry = telemetry("checkout-api", checkoutSampler, exporter);
            inventoryTelemetry = telemetry("inventory-service", inventorySampler, exporter);
            inventory = InventoryService.start(inventoryTelemetry, reporting);
            checkout = new CheckoutService(checkoutTelemetry, inventory, pool);
        }

        List<SpanData> spans() {
            List<SpanData> spans = exporter.getFinishedSpanItems();
            exporter.reset();
            return spans;
        }

        @Override
        public void close() {
            checkout.close();
            pool.close();
            inventory.close();
            inventoryTelemetry.close();
            checkoutTelemetry.close();
        }
    }

    private static ServiceTelemetry telemetry(String service, Sampler sampler, InMemorySpanExporter exporter) {
        return ServiceTelemetry.create(service, "test", "test", sampler, List.of(SimpleSpanProcessor.create(exporter)));
    }

    private static StockReservation reservation(ServiceTelemetry telemetry) {
        return new StockReservation(telemetry.tracer(StockReservation.INSTRUMENTATION_SCOPE));
    }

    private static List<SpanData> named(List<SpanData> spans, String name) {
        return spans.stream().filter(span -> span.getName().equals(name)).toList();
    }

    private static SpanData single(List<SpanData> spans, String name, SpanKind kind) {
        List<SpanData> matching = spans.stream()
                .filter(span -> span.getName().equals(name) && span.getKind() == kind)
                .toList();
        assertEquals(1, matching.size(), "spany " + kind + " " + name);
        return matching.getFirst();
    }

    private static Object attribute(SpanData span, String key) {
        return span.getAttributes().asMap().entrySet().stream()
                .filter(entry -> entry.getKey().getKey().equals(key))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    private static List<String> eventNames(SpanData span) {
        return span.getEvents().stream().map(event -> event.getName()).toList();
    }

    private static Set<String> services(List<SpanData> spans) {
        return spans.stream()
                .map(span -> span.getResource().getAttribute(AttributeKey.stringKey("service.name")))
                .collect(Collectors.toSet());
    }

    private static Map<String, Set<String>> servicesByTrace(List<SpanData> spans) {
        return spans.stream().collect(Collectors.groupingBy(SpanData::getTraceId,
                Collectors.mapping(span -> span.getResource().getAttribute(AttributeKey.stringKey("service.name")),
                        Collectors.toSet())));
    }
}
