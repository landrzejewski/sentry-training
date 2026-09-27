package pl.training.sentry.module05;

import ch.qos.logback.classic.Level;
import io.sentry.Breadcrumb;
import io.sentry.ISentryLifecycleToken;
import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.ITransportFactory;
import io.sentry.InitPriority;
import io.sentry.NoOpSpan;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.SentryLogEvent;
import io.sentry.SentryLogLevel;
import io.sentry.SentryOptions;
import io.sentry.SentryWrapper;
import io.sentry.SpanStatus;
import io.sentry.TransactionOptions;
import io.sentry.logback.SentryAppender;
import io.sentry.logger.LoggerBatchProcessor;
import io.sentry.protocol.FeatureFlag;
import io.sentry.protocol.SentrySpan;
import io.sentry.protocol.SentryTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import pl.training.sentry.module05.CheckoutService.ChecksMode;
import pl.training.sentry.module05.StorefrontClient.BrowserTrace;
import pl.training.sentry.module05.logback.ErrorReporting;
import pl.training.sentry.module05.logback.LogbackSentryConfig;
import pl.training.sentry.module05.logback.LogbackSentryConfig.Thresholds;
import pl.training.sentry.module05.logback.RefundEndpoint;
import pl.training.sentry.module05.logback.RefundEndpoint.MdcFields;
import pl.training.sentry.module05.logback.RefundGateway;
import pl.training.sentry.module05.logback.RefundService;
import pl.training.sentry.module05.spring.OrderStatusApplication;
import pl.training.sentry.support.SentryTestSupport;
import pl.training.sentry.support.SentryTestSupport.CapturedTelemetry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Scenariusze {@link Module05Demo} sprawdzone na tym, co SDK przekazało do transportu:
 * eventach, transakcjach ze spanami i Structured Logs.
 *
 * <p>Każda klasa zagnieżdżona odpowiada jednemu scenariuszowi. Testy uruchamiają prawdziwe SDK
 * z transportem w pamięci, a usługi HTTP na prawdziwych gniazdach, więc propagacja nagłówków
 * i przejścia między wątkami działają tak jak w demo. Dodatkowe testy potwierdzają zachowania
 * SDK opisane w komentarzach kodu.</p>
 */
@ResourceLock("sentry-global-state")
class Module05ScenariosTest {

    private static final String LOCALHOST_ONLY = "^http://localhost:[0-9]+/.*$";
    private static final StorefrontClient STOREFRONT = new StorefrontClient();

    /** Ustawienia jak w {@link Module05Demo}. */
    private static Consumer<SentryOptions> demoTracing() {
        return options -> {
            options.setTracesSampleRate(1.0);
            options.setTracesSampler(new CheckoutTracesSampler(true, CheckoutTracesSampler.CHECKOUT_RATES));
            options.setTracePropagationTargets(List.of(LOCALHOST_ONLY));
            options.getLogs().setEnabled(true);
        };
    }

    @Nested
    class Scenario1StandaloneJob {

        private final PaymentReconciliationJob job = new PaymentReconciliationJob();

        @Test
        void withoutTransactionEveryRunSharesOneTraceAndNoTimingIsSent() {
            try (var telemetry = SentryTestSupport.start(demoTracing())) {
                job.runWithoutTransaction("provider-a");
                job.runWithoutTransaction("provider-b");
                telemetry.flush();

                assertTrue(telemetry.transactions().isEmpty());
                String eventTrace = traceId(telemetry.singleEvent());
                assertEquals(4, telemetry.logs().size());
                for (SentryLogEvent log : telemetry.logs()) {
                    assertEquals(eventTrace, log.getTraceId().toString());
                }
            }
        }

        @Test
        void transactionGivesEachRunOwnTraceAndLinksErrorToFailingSpan() {
            try (var telemetry = SentryTestSupport.start(demoTracing())) {
                job.run("provider-a");
                job.run("provider-b");
                telemetry.flush();

                List<SentryTransaction> runs = telemetry.transactions();
                assertEquals(2, runs.size());
                assertNotEquals(traceId(runs.get(0)), traceId(runs.get(1)));

                SentryTransaction failed = runs.get(1);
                assertEquals(SpanStatus.INTERNAL_ERROR, failed.getContexts().getTrace().getStatus());
                SentrySpan report = span(failed, "GET settlement report");
                assertEquals(SpanStatus.INTERNAL_ERROR, report.getStatus());
                assertEquals("provider-b", report.getData().get("payment.provider"));

                // Capture nastąpił po zakończeniu spanu, a event i tak wskazuje span raportu.
                SentryEvent event = telemetry.singleEvent();
                assertEquals(traceId(failed), traceId(event));
                assertEquals(report.getSpanId(), event.getContexts().getTrace().getSpanId());
                assertEquals("payments.reconcile", event.getTransaction());

                for (SentryTransaction run : runs) {
                    List<SentryLogEvent> logs = logsOfTrace(telemetry, traceId(run));
                    assertEquals(2, logs.size());
                    logs.forEach(log -> assertEquals("payment-reconciliation",
                            log.getAttributes().get("workflow.kind").getValue()));
                }
                // Log ERROR nie tworzy eventu: jedyny event to captureException z granicy joba.
                assertTrue(telemetry.logs().stream().anyMatch(log -> log.getLevel() == SentryLogLevel.ERROR));
            }
        }

        @Test
        void scopeTagsGoToEventsAndTransactionsButNotToLogs() {
            try (var telemetry = SentryTestSupport.start(demoTracing())) {
                job.run("provider-b");
                telemetry.flush();

                assertEquals("payments.reconcile", telemetry.singleEvent().getTag("job.name"));
                assertEquals("payments.reconcile", telemetry.transactions().getFirst().getTag("job.name"));
                telemetry.logs().forEach(log -> assertFalse(log.getAttributes().containsKey("job.name")));
            }
        }

        @Test
        void transactionBoundOnInitThreadWithoutForkLeaksToEveryNewThread() throws Exception {
            try (var telemetry = SentryTestSupport.start(demoTracing());
                 ExecutorService workers = Executors.newSingleThreadExecutor()) {
                ITransaction transaction = Sentry.startTransaction("payments.reconcile", "task", bound());

                assertSame(transaction, CompletableFuture.supplyAsync(Sentry::getSpan, workers).get());
                transaction.finish();
            }
        }

        @Test
        void withoutSetThrowableLaterCaptureIsLinkedToTransactionInsteadOfFailingSpan() {
            try (var telemetry = SentryTestSupport.start(demoTracing())) {
                ITransaction transaction = Sentry.startTransaction("payments.reconcile", "task", bound());
                ISpan report = transaction.startChild("http.client", "GET settlement report");
                IllegalStateException failure = new IllegalStateException("HTTP 503");
                report.setStatus(SpanStatus.INTERNAL_ERROR);
                report.finish();
                Sentry.captureException(failure);
                transaction.finish();

                assertEquals(transaction.getSpanContext().getSpanId(),
                        telemetry.singleEvent().getContexts().getTrace().getSpanId());
            }
        }
    }

    @Nested
    class Scenario2PropagationBetweenServices {

        @Test
        void headersJoinBothServicesInOneTraceWithClientSpanAsParent() throws Exception {
            try (var telemetry = SentryTestSupport.start(demoTracing());
                 PaymentsApi payments = new PaymentsApi();
                 OrdersApi orders = new OrdersApi(payments.baseUri(), FeatureFlagProvider.allDisabled(), ChecksMode.SEQUENTIAL)) {
                STOREFRONT.placeOrder(orders.baseUri(), "ORD-2001", "c-7f3a9c", BrowserTrace.NONE);
                telemetry.flush();

                SentryTransaction authorize = transaction(telemetry, "POST /authorize");
                SentryTransaction checkout = transaction(telemetry, "POST /api/orders");
                assertEquals(traceId(checkout), traceId(authorize));
                assertEquals(spanByOp(checkout, "http.client", "/authorize").getSpanId(),
                        authorize.getContexts().getTrace().getParentSpanId());
                assertNull(checkout.getContexts().getTrace().getParentSpanId());
                assertEquals("payments-api", authorize.getTag("service"));
                assertTrue(logsOfTrace(telemetry, traceId(checkout)).stream()
                        .anyMatch(log -> log.getBody().startsWith("Autoryzacja")));
            }
        }

        @Test
        void addressOutsideAllowlistGetsNoHeadersAndDownstreamStartsNewTrace() throws Exception {
            try (var telemetry = SentryTestSupport.start(demoTracing());
                 PaymentsApi payments = new PaymentsApi();
                 OrdersApi orders = new OrdersApi(payments.baseUriByIp(), FeatureFlagProvider.allDisabled(), ChecksMode.SEQUENTIAL)) {
                STOREFRONT.placeOrder(orders.baseUri(), "ORD-2002", "c-7f3a9c", BrowserTrace.NONE);

                SentryTransaction authorize = transaction(telemetry, "POST /authorize");
                SentryTransaction checkout = transaction(telemetry, "POST /api/orders");
                assertNotEquals(traceId(checkout), traceId(authorize));
                assertNull(authorize.getContexts().getTrace().getParentSpanId());
            }
        }

        @Test
        void withTracingDisabledEachRequestStillGetsOwnTraceFromContinueTrace() throws Exception {
            FeatureFlagProvider rollout = new FeatureFlagProvider(Map.of(
                    CheckoutFlags.NEW_PAYMENT_FLOW, Set.of("c-19bd42", "c-5e21aa")));
            try (var telemetry = SentryTestSupport.start(options -> options.getLogs().setEnabled(true));
                 PaymentsApi payments = new PaymentsApi();
                 OrdersApi orders = new OrdersApi(payments.baseUri(), rollout, ChecksMode.SEQUENTIAL)) {
                STOREFRONT.placeOrder(orders.baseUri(), "ORD-2101", "c-19bd42", BrowserTrace.NONE);
                STOREFRONT.placeOrder(orders.baseUri(), "ORD-2102", "c-5e21aa", BrowserTrace.NONE);
                telemetry.flush();

                assertTrue(telemetry.transactions().isEmpty());
                List<SentryEvent> events = telemetry.events();
                assertEquals(2, events.size());
                assertNotEquals(traceId(events.get(0)), traceId(events.get(1)));
                // Bez spanów nagłówki powstają z propagation context, więc payments-api loguje
                // w trace orders-api mimo wyłączonego tracingu.
                for (SentryEvent event : events) {
                    assertTrue(logsOfTrace(telemetry, traceId(event)).stream()
                            .anyMatch(log -> log.getBody().startsWith("Autoryzacja")));
                }
            }
        }

        @Test
        void defaultPropagationTargetsSendHeadersToEveryAddress() throws Exception {
            try (var telemetry = SentryTestSupport.start(options -> {
                demoTracing().accept(options);
                options.setTracePropagationTargets(List.of(".*"));
            });
                 PaymentsApi payments = new PaymentsApi();
                 OrdersApi orders = new OrdersApi(payments.baseUriByIp(), FeatureFlagProvider.allDisabled(), ChecksMode.SEQUENTIAL)) {
                STOREFRONT.placeOrder(orders.baseUri(), "ORD-2003", "c-7f3a9c", BrowserTrace.NONE);

                assertEquals(traceId(transaction(telemetry, "POST /api/orders")),
                        traceId(transaction(telemetry, "POST /authorize")));
                assertEquals(List.of(".*"), new SentryOptions().getTracePropagationTargets());
            }
        }
    }

    @Nested
    class Scenario3ContextOnThreadPool {

        @Test
        void unwrappedTasksLoseSpansAndLogCorrelation() throws Exception {
            try (var telemetry = SentryTestSupport.start(demoTracing());
                 PaymentsApi payments = new PaymentsApi();
                 OrdersApi orders = new OrdersApi(payments.baseUri(), FeatureFlagProvider.allDisabled(), ChecksMode.PARALLEL_WITHOUT_CONTEXT)) {
                STOREFRONT.placeOrder(orders.baseUri(), "ORD-3001", "c-7f3a9c", BrowserTrace.NONE);
                telemetry.flush();

                SentryTransaction checkout = transaction(telemetry, "POST /api/orders");
                assertEquals(List.of("load cart", "POST " + payments.baseUri() + "/authorize"), descriptions(checkout));
                SentryLogEvent cacheLog = log(telemetry, "Magazyn");
                assertNotEquals(traceId(checkout), cacheLog.getTraceId().toString());
                // Wątek puli nie zna też usera requestu.
                assertFalse(cacheLog.getAttributes().containsKey("user.id"));
            }
        }

        @Test
        void wrappedTasksKeepTraceAndGetExplicitParent() throws Exception {
            try (var telemetry = SentryTestSupport.start(demoTracing());
                 PaymentsApi payments = new PaymentsApi();
                 OrdersApi orders = new OrdersApi(payments.baseUri(), FeatureFlagProvider.allDisabled(), ChecksMode.PARALLEL_WITH_CONTEXT)) {
                STOREFRONT.placeOrder(orders.baseUri(), "ORD-3002", "c-7f3a9c", BrowserTrace.NONE);
                telemetry.flush();

                SentryTransaction checkout = transaction(telemetry, "POST /api/orders");
                var root = checkout.getContexts().getTrace().getSpanId();
                assertEquals(root, span(checkout, "fraud check").getParentSpanId());
                assertEquals(root, span(checkout, "GET warehouse /stock").getParentSpanId());
                SentryLogEvent cacheLog = log(telemetry, "Magazyn");
                assertEquals(traceId(checkout), cacheLog.getTraceId().toString());
                assertEquals("c-7f3a9c", cacheLog.getAttributes().get("user.id").getValue());
            }
        }

        @Test
        void getSpanInWorkerReturnsLatestUnfinishedSpanOfTransactionNotOwnBranch() throws Exception {
            try (var telemetry = SentryTestSupport.start(demoTracing());
                 ExecutorService workers = Executors.newSingleThreadExecutor();
                 // Jak w TracedHttpHandler: bez własnych scopes transakcja trafiłaby do root scopes
                 // wątku, który wywołał Sentry.init, i każdy nowy wątek dostałby ją w kopii.
                 ISentryLifecycleToken request = Sentry.forkedRootScopes("request").makeCurrent()) {
                ITransaction transaction = Sentry.startTransaction("POST /api/orders", "http.server", bound());
                ISpan sibling = transaction.startChild("http.client", "GET warehouse /stock");

                ISpan seenByWorker = CompletableFuture.supplyAsync(SentryWrapper.wrapSupplier(Sentry::getSpan), workers).get();
                ISpan seenWithoutWrapper = CompletableFuture.supplyAsync(Sentry::getSpan, workers).get();

                assertSame(sibling, seenByWorker);
                assertNull(seenWithoutWrapper);
                sibling.finish();
                transaction.finish();
            }
        }

        @Test
        void makeCurrentOnChildSpanDoesNotChangeActiveSpanInSentryNativeTracing() {
            try (var telemetry = SentryTestSupport.start(demoTracing())) {
                ITransaction transaction = Sentry.startTransaction("POST /api/orders", "http.server", bound());
                ISpan fraud = transaction.startChild("function", "fraud check");
                ISpan stock = transaction.startChild("http.client", "GET warehouse /stock");

                try (ISentryLifecycleToken ignored = fraud.makeCurrent()) {
                    assertSame(stock, Sentry.getSpan());
                }
                stock.finish();
                fraud.finish();
                transaction.finish();
            }
        }
    }

    @Nested
    class Scenario4Waterfall {

        @Test
        void parallelChecksOverlapAndShortenTheRequest() throws Exception {
            try (var telemetry = SentryTestSupport.start(demoTracing());
                 PaymentsApi payments = new PaymentsApi()) {
                try (OrdersApi orders = new OrdersApi(payments.baseUri(), FeatureFlagProvider.allDisabled(), ChecksMode.SEQUENTIAL)) {
                    STOREFRONT.placeOrder(orders.baseUri(), "ORD-4001", "c-7f3a9c", BrowserTrace.NONE);
                }
                try (OrdersApi orders = new OrdersApi(payments.baseUri(), FeatureFlagProvider.allDisabled(), ChecksMode.PARALLEL_WITH_CONTEXT)) {
                    STOREFRONT.placeOrder(orders.baseUri(), "ORD-4002", "c-7f3a9c", BrowserTrace.NONE);
                }

                List<SentryTransaction> checkouts = telemetry.transactions().stream()
                        .filter(t -> "POST /api/orders".equals(t.getTransaction())).toList();
                SentryTransaction sequential = checkouts.get(0);
                SentryTransaction parallel = checkouts.get(1);

                SentrySpan fraud = span(sequential, "fraud check");
                assertTrue(span(sequential, "GET warehouse /stock").getStartTimestamp() >= fraud.getTimestamp() - 0.001);

                assertTrue(Math.abs(span(parallel, "fraud check").getStartTimestamp()
                        - span(parallel, "GET warehouse /stock").getStartTimestamp()) < 0.05);
                assertTrue(duration(sequential) - duration(parallel) > 0.1);
                double sumOfSpans = parallel.getSpans().stream().mapToDouble(Module05ScenariosTest::duration).sum();
                assertTrue(sumOfSpans > duration(parallel));

                // Przeliczenie cen bez spanu: przerwa między db.query a pierwszym sprawdzeniem.
                double gap = span(parallel, "fraud check").getStartTimestamp() - span(parallel, "load cart").getTimestamp();
                assertTrue(gap >= 0.07, "przerwa " + gap);
            }
        }
    }

    @Nested
    class Scenario5FeatureFlags {

        private final FeatureFlagProvider rollout = new FeatureFlagProvider(Map.of(
                CheckoutFlags.NEW_PAYMENT_FLOW, Set.of("c-19bd42")));

        @Test
        void flagResultIsInErrorEventAndInTransactionDataOfBothVariants() throws Exception {
            try (var telemetry = SentryTestSupport.start(demoTracing());
                 PaymentsApi payments = new PaymentsApi();
                 OrdersApi orders = new OrdersApi(payments.baseUri(), rollout, ChecksMode.PARALLEL_WITH_CONTEXT)) {
                assertEquals(200, STOREFRONT.placeOrder(orders.baseUri(), "ORD-5001", "c-7f3a9c", BrowserTrace.NONE).status());
                assertEquals(500, STOREFRONT.placeOrder(orders.baseUri(), "ORD-5002", "c-19bd42", BrowserTrace.NONE).status());

                SentryEvent event = telemetry.singleEvent();
                assertTrue(event.getExceptions().getLast().getType().endsWith("PaymentFailedException"));
                FeatureFlag flag = event.getContexts().getFeatureFlags().getValues().getFirst();
                assertEquals(CheckoutFlags.NEW_PAYMENT_FLOW, flag.getFlag());
                assertEquals(true, flag.getResult());

                List<Object> flagOnCheckouts = telemetry.transactions().stream()
                        .filter(t -> "POST /api/orders".equals(t.getTransaction()))
                        .map(t -> t.getContexts().getTrace().getData().get("flag.evaluation." + CheckoutFlags.NEW_PAYMENT_FLOW))
                        .toList();
                assertEquals(List.of(false, true), flagOnCheckouts);

                // payments-api odpowiada 422 bez eventu; status spanu nie jest ok.
                SentryTransaction authorize = telemetry.transactions().stream()
                        .filter(t -> "POST /authorize".equals(t.getTransaction()))
                        .reduce((first, second) -> second).orElseThrow();
                assertEquals(SpanStatus.INVALID_ARGUMENT, authorize.getContexts().getTrace().getStatus());
            }
        }

        @Test
        void flagEvaluatedBeforeTransactionStartIsMissingFromTransactionData() {
            try (var telemetry = SentryTestSupport.start(demoTracing())) {
                try (ISentryLifecycleToken ignored = Sentry.forkedRootScopes("test").makeCurrent()) {
                    Sentry.addFeatureFlag(CheckoutFlags.NEW_PAYMENT_FLOW, true);
                    ITransaction transaction = Sentry.startTransaction("POST /api/orders", "http.server", bound());
                    Sentry.captureMessage("błąd w requeście");
                    transaction.finish();
                }

                assertNotNull(telemetry.singleEvent().getContexts().getFeatureFlags());
                assertFalse(telemetry.transactions().getFirst().getContexts().getTrace().getData()
                        .containsKey("flag.evaluation." + CheckoutFlags.NEW_PAYMENT_FLOW));
            }
        }
    }

    @Nested
    class Scenario6Sampling {

        private final FeatureFlagProvider rollout = new FeatureFlagProvider(Map.of(
                CheckoutFlags.NEW_PAYMENT_FLOW, Set.of("c-19bd42")));

        @Test
        void sampledBrowserTraceIsContinuedByBothServices() throws Exception {
            try (var telemetry = SentryTestSupport.start(demoTracing());
                 PaymentsApi payments = new PaymentsApi();
                 OrdersApi orders = new OrdersApi(payments.baseUri(), rollout, ChecksMode.SEQUENTIAL)) {
                var result = STOREFRONT.placeOrder(orders.baseUri(), "ORD-6001", "c-7f3a9c", BrowserTrace.SAMPLED);

                assertEquals(2, telemetry.transactions().size());
                telemetry.transactions().forEach(t -> assertEquals(result.browserTraceId(), traceId(t)));
                assertNotNull(transaction(telemetry, "POST /api/orders").getContexts().getTrace().getParentSpanId());
            }
        }

        @Test
        void droppedBrowserTraceLeavesErrorAndLogsWithoutTransactions() throws Exception {
            try (var telemetry = SentryTestSupport.start(demoTracing());
                 PaymentsApi payments = new PaymentsApi();
                 OrdersApi orders = new OrdersApi(payments.baseUri(), rollout, ChecksMode.SEQUENTIAL)) {
                var result = STOREFRONT.placeOrder(orders.baseUri(), "ORD-6002", "c-19bd42", BrowserTrace.NOT_SAMPLED);
                telemetry.flush();

                assertTrue(telemetry.transactions().isEmpty());
                assertEquals(result.browserTraceId(), traceId(telemetry.singleEvent()));
                assertFalse(logsOfTrace(telemetry, result.browserTraceId()).isEmpty());
            }
        }

        @Test
        void healthCheckStartedByServiceIsDroppedByRule() throws Exception {
            try (var telemetry = SentryTestSupport.start(demoTracing());
                 PaymentsApi payments = new PaymentsApi();
                 OrdersApi orders = new OrdersApi(payments.baseUri(), rollout, ChecksMode.SEQUENTIAL)) {
                assertEquals(200, STOREFRONT.healthCheck(orders.baseUri()));
                assertTrue(telemetry.transactions().isEmpty());
            }
        }

        @Test
        void samplerIgnoringParentKeepsBackendFragmentOfDroppedBrowserTrace() throws Exception {
            try (var telemetry = SentryTestSupport.start(options -> {
                demoTracing().accept(options);
                options.setTracesSampler(new CheckoutTracesSampler(false, CheckoutTracesSampler.CHECKOUT_RATES));
            });
                 PaymentsApi payments = new PaymentsApi();
                 OrdersApi orders = new OrdersApi(payments.baseUri(), FeatureFlagProvider.allDisabled(), ChecksMode.SEQUENTIAL)) {
                var result = STOREFRONT.placeOrder(orders.baseUri(), "ORD-6003", "c-7f3a9c", BrowserTrace.NOT_SAMPLED);

                // Reguła POST /api/orders = 1.0 wygrała z decyzją przeglądarki, a payments-api
                // bez własnej reguły odziedziczyło już decyzję orders-api.
                assertEquals(2, telemetry.transactions().size());
                SentryTransaction checkout = transaction(telemetry, "POST /api/orders");
                assertEquals(result.browserTraceId(), traceId(checkout));
                assertNotNull(checkout.getContexts().getTrace().getParentSpanId());
            }
        }
    }

    @Nested
    class Scenario7SpringBoot {

        @Test
        void starterCreatesRouteNamedTransactionAndDecoratedExecutorKeepsContext() throws Exception {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                // Transport testu przejmuje aplikacja Spring, która inicjalizuje SDK od nowa.
                ITransportFactory recording = Sentry.getCurrentScopes().getOptions().getTransportFactory();
                Sentry.close();
                try (var app = OrderStatusApplication.start(options -> options.setTransportFactory(recording))) {
                    assertEquals(200, get(app.baseUri(), "/api/orders/ORD-7001/status"));
                    assertEquals(200, get(app.baseUri(), "/api/orders/ORD-7001/invoice"));
                    assertEquals(500, get(app.baseUri(), "/api/orders/ORD-7003/status"));
                    Sentry.flush(2_000);

                    SentryTransaction status = telemetry.transactions().get(0);
                    assertEquals("GET /api/orders/{orderId}/status", status.getTransaction());
                    assertEquals("http.server", status.getContexts().getTrace().getOperation());
                    assertEquals(List.of("load order status", "GET loyalty /points"), descriptions(status));
                    assertEquals(traceId(status), log(telemetry, "Pobrano punkty").getTraceId().toString());

                    SentryTransaction invoice = telemetry.transactions().get(1);
                    assertEquals("GET /api/orders/{orderId}/invoice", invoice.getTransaction());
                    assertTrue(invoice.getSpans().isEmpty());
                    assertNotEquals(traceId(invoice), log(telemetry, "Wygenerowano fakturę").getTraceId().toString());

                    SentryEvent legacyOrder = telemetry.singleEvent();
                    SentryTransaction failed = telemetry.transactions().get(2);
                    assertEquals("GET /api/orders/{orderId}/status", legacyOrder.getTransaction());
                    assertEquals(Boolean.FALSE, legacyOrder.getExceptions().getLast().getMechanism().isHandled());
                    assertEquals(span(failed, "load order status").getSpanId(),
                            legacyOrder.getContexts().getTrace().getSpanId());
                }
            }
        }

        @Test
        void starterAttachesLogbackAppenderWithConfiguredThresholdsAndLogGetsRequestTrace() throws Exception {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                ITransportFactory recording = Sentry.getCurrentScopes().getOptions().getTransportFactory();
                Sentry.close();
                try (var app = OrderStatusApplication.start(options -> options.setTransportFactory(recording))) {
                    SentryAppender appender = springSentryAppender();
                    assertNotNull(appender, "starter podpina SentryAppender do root loggera");
                    assertEquals(Level.ERROR, appender.getMinimumEventLevel());
                    assertEquals(Level.INFO, appender.getMinimumBreadcrumbLevel());
                    assertEquals(Level.INFO, appender.getMinimumLevel());

                    assertEquals(200, get(app.baseUri(), "/api/orders/ORD-7001/status"));
                    // SentryTracingFilter kończy transakcję po wysłaniu odpowiedzi (jak w demo).
                    for (int i = 0; i < 40 && telemetry.transactions().isEmpty(); i++) {
                        Latency.pause(50);
                    }
                    Sentry.flush(2_000);

                    SentryLogEvent statusLog = log(telemetry, "Status zamówienia");
                    assertEquals("auto.log.logback", attribute(statusLog, "sentry.origin"));
                    assertEquals(traceId(telemetry.transactions().getFirst()), statusLog.getTraceId().toString());
                }
                // Starter nie odpina appendera przy zamknięciu kontekstu.
                assertNotNull(springSentryAppender());
            } finally {
                LogbackSentryConfig.reset();
            }
        }

        private static SentryAppender springSentryAppender() {
            ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
                    LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            return (SentryAppender) root.getAppender("SENTRY_APPENDER");
        }
    }

    @Nested
    class Scenario8LogbackChannels {

        private final Map<String, Integer> gateway = Map.of("ORD-8002", 1, "ORD-8003", RefundGateway.ALWAYS);

        @AfterEach
        void resetLogback() {
            LogbackSentryConfig.reset();
        }

        @Test
        void errorLogWithExceptionBecomesOneEventWithMessageLoggerAndCauseChain() {
            try (var telemetry = SentryTestSupport.start(logbackOptions("channel"))) {
                LogbackSentryConfig.fromXml(LogbackSentryConfig.XML);
                assertEquals(502, refund(singleOwner(gateway), "ORD-8003"));

                SentryEvent event = telemetry.singleEvent();
                assertEquals(SentryLevel.ERROR, event.getLevel());
                assertEquals(RefundEndpoint.class.getName(), event.getLogger());
                assertEquals("Zwrot zamówienia ORD-8003 nieudany, klient dostaje 502", event.getMessage().getFormatted());
                assertEquals("Zwrot zamówienia {} nieudany, klient dostaje 502", event.getMessage().getMessage());
                assertEquals(List.of("ORD-8003"), event.getMessage().getParams());
                assertNotNull(event.getExtra("thread_name"));
                // Łańcuch wyjątków: RefundFailedException z cause GatewayTimeoutException.
                assertEquals(2, event.getExceptions().size());
                var outer = event.getExceptions().getLast();
                assertEquals("RefundService$RefundFailedException", outer.getType());
                assertEquals("LogbackSentryAppender", outer.getMechanism().getType());
                assertNull(outer.getMechanism().isHandled());
                // Appender uruchomiony po Sentry.init nie zmienił konfiguracji SDK.
                assertEquals(SentryTestSupport.TEST_RELEASE, event.getRelease());
            }
        }

        @Test
        void breadcrumbsAreInfoAndWarnOfThisRequestWithoutDebugAndWithoutTheErrorItself() {
            try (var telemetry = SentryTestSupport.start(logbackOptions("channel"))) {
                LogbackSentryConfig.fromXml(LogbackSentryConfig.XML);
                refund(singleOwner(gateway), "ORD-8001");
                refund(singleOwner(gateway), "ORD-8003");

                List<Breadcrumb> breadcrumbs = telemetry.singleEvent().getBreadcrumbs();
                assertEquals(List.of(
                                "Zwrot zamówienia ORD-8003: zlecenie u dostawcy",
                                "Bramka zwrotów nie odpowiedziała w 2000 ms (próba 1 z 3), ponawiam",
                                "Bramka zwrotów nie odpowiedziała w 2000 ms (próba 2 z 3), ponawiam"),
                        breadcrumbs.stream().map(Breadcrumb::getMessage).toList());
                assertEquals(RefundGateway.class.getName(), breadcrumbs.get(1).getCategory());
                assertEquals(SentryLevel.WARNING, breadcrumbs.get(1).getLevel());
            }
        }

        @Test
        void structuredLogsHaveTemplateOriginAndEventTraceButNoLoggerNameOrStackTrace() {
            try (var telemetry = SentryTestSupport.start(logbackOptions("channel"))) {
                LogbackSentryConfig.fromXml(LogbackSentryConfig.XML);
                refund(singleOwner(gateway), "ORD-8003");
                telemetry.flush();

                String eventTrace = traceId(telemetry.singleEvent());
                List<SentryLogEvent> logs = telemetry.logs();
                assertEquals(List.of(SentryLogLevel.INFO, SentryLogLevel.WARN, SentryLogLevel.WARN, SentryLogLevel.ERROR),
                        logs.stream().map(SentryLogEvent::getLevel).toList());
                for (SentryLogEvent log : logs) {
                    assertEquals(eventTrace, log.getTraceId().toString());
                    assertEquals("auto.log.logback", attribute(log, "sentry.origin"));
                    assertTrue(log.getAttributes().keySet().stream().noneMatch(key -> key.contains("logger")));
                }
                SentryLogEvent error = logs.getLast();
                assertEquals("Zwrot zamówienia ORD-8003 nieudany, klient dostaje 502", error.getBody());
                assertEquals("Zwrot zamówienia {} nieudany, klient dostaje 502", attribute(error, "sentry.message.template"));
                assertEquals("ORD-8003", attribute(error, "sentry.message.parameter.0"));
                assertTrue(error.getAttributes().keySet().stream().noneMatch(key -> key.contains("exception")));
            }
        }

        @Test
        void warnEventThresholdTurnsRetryOfSuccessfulRefundIntoEvent() {
            try (var telemetry = SentryTestSupport.start(logbackOptions("channel"))) {
                LogbackSentryConfig.programmatic(new Thresholds(Level.WARN, Level.INFO, Level.INFO));
                assertEquals(202, refund(singleOwner(gateway), "ORD-8002"));

                SentryEvent retry = telemetry.singleEvent();
                assertEquals(SentryLevel.WARNING, retry.getLevel());
                assertEquals(RefundGateway.class.getName(), retry.getLogger());
                assertNull(retry.getExceptions());
            }
        }

        @Test
        void logThresholdWarnDropsInfoFromLogsButNotFromBreadcrumbs() {
            try (var telemetry = SentryTestSupport.start(logbackOptions("channel"))) {
                LogbackSentryConfig.programmatic(new Thresholds(Level.ERROR, Level.INFO, Level.WARN));
                refund(singleOwner(gateway), "ORD-8003");
                telemetry.flush();

                assertEquals(3, telemetry.logs().size());
                assertTrue(telemetry.logs().stream().noneMatch(log -> log.getLevel() == SentryLogLevel.INFO));
                assertEquals(SentryLevel.INFO, telemetry.singleEvent().getBreadcrumbs().getFirst().getLevel());
            }
        }

        @Test
        void withLogsDisabledAppenderStillSendsEventAndBreadcrumbs() {
            try (var telemetry = SentryTestSupport.start(options -> options.getLogs().setEnabled(false))) {
                LogbackSentryConfig.fromXml(LogbackSentryConfig.XML);
                refund(singleOwner(gateway), "ORD-8003");
                telemetry.flush();

                assertTrue(telemetry.logs().isEmpty());
                assertEquals(3, telemetry.singleEvent().getBreadcrumbs().size());
            }
        }

        @Test
        void xmlConfigurationHasAppenderDefaultThresholds() {
            LogbackSentryConfig.fromXml(LogbackSentryConfig.XML);
            SentryAppender fromXml = LogbackSentryConfig.sentryAppender();
            SentryAppender defaults = new SentryAppender();

            assertEquals(defaults.getMinimumEventLevel(), fromXml.getMinimumEventLevel());
            assertEquals(defaults.getMinimumBreadcrumbLevel(), fromXml.getMinimumBreadcrumbLevel());
            assertEquals(defaults.getMinimumLevel(), fromXml.getMinimumLevel());
            assertEquals(new Thresholds(Level.ERROR, Level.INFO, Level.INFO), Thresholds.DEFAULTS);
            assertEquals(Thresholds.DEFAULTS.event(), defaults.getMinimumEventLevel());
            assertEquals(Thresholds.DEFAULTS.breadcrumb(), defaults.getMinimumBreadcrumbLevel());
            assertEquals(Thresholds.DEFAULTS.log(), defaults.getMinimumLevel());
        }

        @Test
        void everyRequestGetsOwnTraceFromContinueTraceWithoutTracing() {
            try (var telemetry = SentryTestSupport.start(logbackOptions("channel"))) {
                LogbackSentryConfig.fromXml(LogbackSentryConfig.XML);
                refund(singleOwner(gateway), "ORD-8001");
                refund(singleOwner(gateway), "ORD-8002");
                telemetry.flush();

                assertTrue(telemetry.transactions().isEmpty());
                assertEquals(2, telemetry.logs().stream().map(log -> log.getTraceId().toString()).distinct().count());
            }
        }
    }

    @Nested
    class Scenario9Mdc {

        private final Map<String, Integer> gateway = Map.of("ORD-9001", RefundGateway.ALWAYS, "ORD-9002", RefundGateway.ALWAYS);

        @AfterEach
        void resetLogback() {
            LogbackSentryConfig.reset();
        }

        @Test
        void contextTagGoesToEventTagAndLogAttributeRestOfMdcOnlyToEventContext() {
            try (var telemetry = SentryTestSupport.start(logbackOptions("channel"))) {
                LogbackSentryConfig.fromXml(LogbackSentryConfig.XML);
                refund(singleOwner(gateway), "ORD-9001", "mobile");
                telemetry.flush();

                SentryEvent event = telemetry.singleEvent();
                assertEquals("mobile", event.getTag("channel"));
                assertNull(event.getTag("order_id"));
                assertEquals(Map.of("correlation_id", "corr-ORD-9001", "order_id", "ORD-9001"), event.getContexts().get("MDC"));
                for (SentryLogEvent log : telemetry.logs()) {
                    assertEquals("mobile", attribute(log, "mdc.channel"));
                    assertFalse(log.getAttributes().containsKey("mdc.order_id"));
                    assertFalse(log.getAttributes().containsKey("mdc.correlation_id"));
                    // Identyfikator zamówienia w logach pochodzi z atrybutu scope, nie z MDC.
                    assertEquals("ORD-9001", attribute(log, "order.id"));
                }
            }
        }

        @Test
        void orderIdInContextTagsIsTagPerOrderAndEmailFromMdcIsSentDespiteSendDefaultPiiFalse() {
            try (var telemetry = SentryTestSupport.start(logbackOptions("channel", "order_id"))) {
                LogbackSentryConfig.fromXml(LogbackSentryConfig.XML);
                RefundEndpoint endpoint = new RefundEndpoint(ErrorReporting.SINGLE_OWNER, MdcFields.WITH_CUSTOMER_EMAIL, gateway);
                refund(endpoint, "ORD-9001", "mobile");
                refund(endpoint, "ORD-9002", "mobile");
                telemetry.flush();

                assertEquals(List.of("ORD-9001", "ORD-9002"), telemetry.events().stream().map(e -> e.getTag("order_id")).toList());
                assertFalse(Sentry.getCurrentScopes().getOptions().isSendDefaultPii());
                Map<?, ?> mdc = (Map<?, ?>) telemetry.events().getFirst().getContexts().get("MDC");
                assertEquals(CUSTOMER_EMAIL, mdc.get("customer_email"));
                // Do logów MDC trafia tylko przez contextTags, więc e-maila tam nie ma.
                assertTrue(telemetry.logs().stream().noneMatch(log -> log.getAttributes().containsKey("mdc.customer_email")));
                assertEquals("ORD-9001", attribute(telemetry.logs().getFirst(), "mdc.order_id"));
            }
        }

        @Test
        void boundaryClearsMdcSoPooledThreadStartsNextRequestClean() {
            try (var telemetry = SentryTestSupport.start(logbackOptions("channel"))) {
                LogbackSentryConfig.fromXml(LogbackSentryConfig.XML);
                refund(singleOwner(gateway), "ORD-9001", "mobile");

                Map<String, String> left = MDC.getCopyOfContextMap();
                assertTrue(left == null || left.isEmpty());
            }
        }
    }

    @Nested
    class Scenario10DoubleReporting {

        private final Map<String, Integer> gateway = Map.of("ORD-10001", RefundGateway.ALWAYS);

        @AfterEach
        void resetLogback() {
            LogbackSentryConfig.reset();
        }

        @Test
        void logErrorAndCaptureOfSameExceptionGiveOneEventFromTheLog() {
            try (var telemetry = SentryTestSupport.start(logbackOptions("channel"))) {
                LogbackSentryConfig.fromXml(LogbackSentryConfig.XML);
                refund(new RefundEndpoint(ErrorReporting.LOG_AND_CAPTURE, MdcFields.TECHNICAL, gateway), "ORD-10001");

                SentryEvent event = telemetry.singleEvent();
                assertEquals(RefundEndpoint.class.getName(), event.getLogger());
                assertEquals("LogbackSentryAppender", event.getExceptions().getLast().getMechanism().getType());
            }
        }

        @Test
        void captureBeforeLogKeepsCaptureEventWithoutMessageAndLogger() {
            try (var telemetry = SentryTestSupport.start(logbackOptions())) {
                LogbackSentryConfig.fromXml(LogbackSentryConfig.XML);
                IllegalStateException failure = new IllegalStateException("Bramka zwrotów nie odpowiada");
                Sentry.captureException(failure);
                LoggerFactory.getLogger(RefundEndpoint.class).error("Zwrot nieudany", failure);

                SentryEvent event = telemetry.singleEvent();
                assertNull(event.getLogger());
                assertNull(event.getMessage());
            }
        }

        @Test
        void loggingOnEveryLayerGivesThreeEventsForOneFailure() {
            try (var telemetry = SentryTestSupport.start(logbackOptions("channel"))) {
                LogbackSentryConfig.fromXml(LogbackSentryConfig.XML);
                refund(new RefundEndpoint(ErrorReporting.LOG_ON_EVERY_LAYER, MdcFields.TECHNICAL, gateway), "ORD-10001");

                List<SentryEvent> events = telemetry.events();
                assertEquals(3, events.size());
                assertEquals(List.of(RefundGateway.class.getName(), RefundService.class.getName(), RefundEndpoint.class.getName()),
                        events.stream().map(SentryEvent::getLogger).toList());
                assertEquals("RefundGateway$GatewayTimeoutException", events.get(0).getExceptions().getLast().getType());
                // ERROR bez wyjątku: event bez stack trace.
                assertNull(events.get(1).getExceptions());
                // Nowy wyjątek bez cause: jeden element łańcucha, nic do deduplikacji.
                assertEquals(1, events.get(2).getExceptions().size());
            }
        }

        @Test
        void wrapperWithCauseIsDuplicateButWrapperWithoutCauseIsNewEvent() {
            try (var telemetry = SentryTestSupport.start(logbackOptions())) {
                LogbackSentryConfig.fromXml(LogbackSentryConfig.XML);
                org.slf4j.Logger log = LoggerFactory.getLogger(RefundService.class);
                IllegalStateException timeout = new IllegalStateException("timeout");

                log.error("bramka", timeout);
                log.error("serwis", new RuntimeException("zwrot nieudany", timeout));
                assertEquals(1, telemetry.events().size());

                log.error("granica", new RuntimeException("zwrot nieudany: " + timeout.getMessage()));
                assertEquals(2, telemetry.events().size());
            }
        }

        @Test
        void causeReportedAfterItsWrapperIsNewEvent() {
            try (var telemetry = SentryTestSupport.start(logbackOptions())) {
                LogbackSentryConfig.fromXml(LogbackSentryConfig.XML);
                org.slf4j.Logger log = LoggerFactory.getLogger(RefundService.class);
                IllegalStateException timeout = new IllegalStateException("timeout");

                // SDK zapamiętuje tylko wyjątek najwyższego poziomu, więc kierunek ma znaczenie.
                log.error("granica", new RuntimeException("zwrot nieudany", timeout));
                log.error("bramka", timeout);
                assertEquals(2, telemetry.events().size());
            }
        }

        @Test
        void withDeduplicationDisabledLogAndCaptureGiveTwoEvents() {
            try (var telemetry = SentryTestSupport.start(options -> options.setEnableDeduplication(false))) {
                LogbackSentryConfig.fromXml(LogbackSentryConfig.XML);
                refund(new RefundEndpoint(ErrorReporting.LOG_AND_CAPTURE, MdcFields.TECHNICAL, gateway), "ORD-10001");

                assertEquals(2, telemetry.events().size());
            }
        }

        @Test
        void singleOwnerGivesOneEventWithFullCauseChain() {
            try (var telemetry = SentryTestSupport.start(logbackOptions("channel"))) {
                LogbackSentryConfig.fromXml(LogbackSentryConfig.XML);
                refund(singleOwner(gateway), "ORD-10001");

                assertEquals(2, telemetry.singleEvent().getExceptions().size());
            }
        }
    }

    @Nested
    class LogbackContract {

        @AfterEach
        void resetLogback() {
            LogbackSentryConfig.reset();
        }

        @Test
        void structuredLogsAreBufferedInBoundedQueueAndHaveNoSampleRateOption() {
            assertEquals(1000, LoggerBatchProcessor.MAX_QUEUE_SIZE);
            assertEquals(100, LoggerBatchProcessor.MAX_BATCH_SIZE);
            assertEquals(5000, LoggerBatchProcessor.FLUSH_AFTER_MS);
            assertTrue(Arrays.stream(SentryOptions.Logs.class.getMethods())
                    .noneMatch(method -> method.getName().toLowerCase(Locale.ROOT).contains("sample")));
            assertEquals(100, new SentryOptions().getMaxBreadcrumbs());
        }

        @Test
        void appenderStartedWhileSdkIsOffInitializesSdkFromExternalDsn() {
            Sentry.close();
            System.setProperty("sentry.dsn", "https://public@training.invalid/1");
            try {
                LogbackSentryConfig.fromXml(LogbackSentryConfig.XML);
                assertTrue(Sentry.isEnabled());
                assertEquals(InitPriority.LOWEST, Sentry.getCurrentScopes().getOptions().getInitPriority());
            } finally {
                System.clearProperty("sentry.dsn");
                Sentry.close();
            }
        }

        @Test
        void appenderStartedWithoutAnyDsnLeavesSdkOff() {
            assumeTrue(System.getenv("SENTRY_DSN") == null, "test zakłada brak SENTRY_DSN w środowisku");
            Sentry.close();
            LogbackSentryConfig.fromXml(LogbackSentryConfig.XML);
            assertFalse(Sentry.isEnabled());
        }
    }

    @Nested
    class ChildSpansContract {

        @Test
        void httpStatusWithoutSdkMappingGetsExplicitFailureStatus() {
            assertNull(SpanStatus.fromHttpStatusCode(422));
            assertEquals(SpanStatus.INVALID_ARGUMENT, ChildSpans.statusForHttp(422));
            assertEquals(SpanStatus.NOT_FOUND, ChildSpans.statusForHttp(404));
            assertEquals(SpanStatus.INTERNAL_ERROR, ChildSpans.statusForHttp(502));
            assertEquals(SpanStatus.OK, ChildSpans.statusForHttp(201));
        }

        @Test
        void childStartedAfterParentFinishedIsNoOpAndNeverSent() {
            try (var telemetry = SentryTestSupport.start(demoTracing())) {
                ITransaction transaction = Sentry.startTransaction("payments.reconcile", "task", bound());
                transaction.finish();

                String result = ChildSpans.trace(transaction, "function", "late work", span -> {
                    assertSame(NoOpSpan.getInstance(), span);
                    return "done";
                });

                assertEquals("done", result);
                assertTrue(telemetry.transactions().getFirst().getSpans().isEmpty());
            }
        }

        @Test
        void withoutParentWorkRunsWithoutSpan() {
            try (var telemetry = SentryTestSupport.start(demoTracing())) {
                assertEquals("koszyk", ChildSpans.trace("db.query", "load cart", span -> "koszyk"));
                assertTrue(telemetry.transactions().isEmpty());
            }
        }
    }

    private static final String CUSTOMER_EMAIL = "anna.nowak@example.com";

    /** Ustawienia jak w sesjach scenariuszy 8-10 {@link Module05Demo}. */
    private static Consumer<SentryOptions> logbackOptions(String... contextTags) {
        return options -> {
            options.getLogs().setEnabled(true);
            for (String key : contextTags) {
                options.addContextTag(key);
            }
        };
    }

    private static RefundEndpoint singleOwner(Map<String, Integer> gatewayTimeouts) {
        return new RefundEndpoint(ErrorReporting.SINGLE_OWNER, MdcFields.TECHNICAL, gatewayTimeouts);
    }

    private static int refund(RefundEndpoint endpoint, String orderId) {
        return refund(endpoint, orderId, "web");
    }

    private static int refund(RefundEndpoint endpoint, String orderId, String channel) {
        return endpoint.handle(new RefundEndpoint.Request(orderId, channel, "corr-" + orderId, CUSTOMER_EMAIL));
    }

    private static Object attribute(SentryLogEvent log, String name) {
        var value = log.getAttributes().get(name);
        return value == null ? null : value.getValue();
    }

    private static TransactionOptions bound() {
        TransactionOptions options = new TransactionOptions();
        options.setBindToScope(true);
        return options;
    }

    private static int get(URI base, String path) throws Exception {
        return HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(base.resolve(path)).GET().build(), HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private static String traceId(SentryEvent event) {
        return event.getContexts().getTrace().getTraceId().toString();
    }

    private static String traceId(SentryTransaction transaction) {
        return transaction.getContexts().getTrace().getTraceId().toString();
    }

    private static SentryTransaction transaction(CapturedTelemetry telemetry, String name) {
        return telemetry.transactions().stream()
                .filter(t -> name.equals(t.getTransaction()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Brak transakcji " + name));
    }

    private static SentrySpan span(SentryTransaction transaction, String description) {
        return transaction.getSpans().stream()
                .filter(span -> description.equals(span.getDescription()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Brak spanu " + description + " w " + descriptions(transaction)));
    }

    private static SentrySpan spanByOp(SentryTransaction transaction, String op, String descriptionSuffix) {
        return transaction.getSpans().stream()
                .filter(span -> op.equals(span.getOp()) && span.getDescription().endsWith(descriptionSuffix))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> descriptions(SentryTransaction transaction) {
        return transaction.getSpans().stream()
                .sorted(java.util.Comparator.comparing(SentrySpan::getStartTimestamp))
                .map(SentrySpan::getDescription)
                .toList();
    }

    private static double duration(SentryTransaction transaction) {
        return transaction.getTimestamp() - transaction.getStartTimestamp();
    }

    private static double duration(SentrySpan span) {
        return span.getTimestamp() - span.getStartTimestamp();
    }

    private static List<SentryLogEvent> logsOfTrace(CapturedTelemetry telemetry, String traceId) {
        return telemetry.logs().stream().filter(log -> traceId.equals(log.getTraceId().toString())).toList();
    }

    private static SentryLogEvent log(CapturedTelemetry telemetry, String bodyPrefix) {
        return telemetry.logs().stream()
                .filter(log -> log.getBody().startsWith(bodyPrefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Brak logu " + bodyPrefix));
    }
}
