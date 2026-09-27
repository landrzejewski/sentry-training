package pl.training.sentry.module03;

import io.sentry.Breadcrumb;
import io.sentry.SentryEvent;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryStackFrame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import pl.training.sentry.module03.CheckoutService.CauseHandling;
import pl.training.sentry.module03.FakePaymentGateway.Reply;
import pl.training.sentry.module03.PaymentGrouping.Strategy;
import pl.training.sentry.module03.PaymentRetry.AttemptReporting;
import pl.training.sentry.module03legacy.LegacyLoyaltyClient;
import pl.training.sentry.support.SentryTestSupport;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenariusze {@link Module03Demo} sprawdzone na treści eventów, które SDK przekazało do transportu.
 *
 * <p>Każda klasa zagnieżdżona odpowiada jednemu scenariuszowi. Bramka płatności działa lokalnie na
 * HTTP jak w demo, więc timeouty i kody odpowiedzi są prawdziwe. Asercje dotyczą danych po scope,
 * event processorach (w tym deduplikacji) i {@code beforeSend}, czyli tego, co trafiłoby do Sentry.
 * Grupowanie wykonuje serwer, więc testy sprawdzają jego wejście: fingerprint i ramki stosu.</p>
 */
@ResourceLock("sentry-global-state")
class Module03ScenariosTest {

    private static final String ANNA = "c-7f3a9c";
    private static final String PIOTR = "c-19bd42";
    private static final String EWA = "c-5e21d0";
    private static final String TOKEN = "tok_test_1234";

    private static final LegacyLoyaltyClient LOYALTY = new LegacyLoyaltyClient();

    private static FakePaymentGateway gateway;
    private static PaymentGatewayClient client;

    @BeforeAll
    static void startGateway() throws Exception {
        gateway = FakePaymentGateway.start(Duration.ofSeconds(2));
        client = new PaymentGatewayClient(gateway.baseUri(), Duration.ofMillis(150), 120, Duration.ZERO,
                TOKEN, new HttpCallBreadcrumbs());
    }

    @AfterAll
    static void stopGateway() {
        client.close();
        gateway.close();
    }

    @Nested
    class Scenario1WrapperHidesCause {

        @Test
        void wrapperWithoutCauseLeavesOneExceptionAndIdenticalFramesForDifferentCauses() {
            gateway.respond("ORD-1001", Reply.SLOW);
            gateway.respond("ORD-1002", Reply.UNAVAILABLE);
            try (var telemetry = SentryTestSupport.start(CheckoutSentryConfig.TARGET)) {
                placeOrders(new CheckoutService(client, LOYALTY, CauseHandling.MESSAGE_ONLY),
                        Order.card("ORD-1001", ANNA), Order.card("ORD-1002", PIOTR));

                List<SentryEvent> events = telemetry.events();
                assertEquals(2, events.size());
                for (SentryEvent event : events) {
                    assertEquals(List.of("CheckoutException"), exceptionTypes(event));
                    // Bez cause beforeSend nie znajdzie klasyfikacji: brak tagu i fingerprintu.
                    assertNull(event.getTag("payment.failure_reason"));
                    assertTrue(fingerprints(event).isEmpty());
                }
                assertTrue(outermost(events.get(0)).getValue().contains("nie odpowiedziała w 150 ms"));
                assertTrue(outermost(events.get(1)).getValue().contains("HTTP 503"));
                // Ten sam typ i te same ramki: serwer nie ma czym rozdzielić timeoutu od awarii.
                assertEquals(frames(outermost(events.get(0))), frames(outermost(events.get(1))));
            }
        }

        @Test
        void wrapperWithCauseSendsWholeChainAndClassification() {
            gateway.respond("ORD-1003", Reply.SLOW);
            gateway.respond("ORD-1004", Reply.UNAVAILABLE);
            try (var telemetry = SentryTestSupport.start(CheckoutSentryConfig.TARGET)) {
                placeOrders(new CheckoutService(client, LOYALTY, CauseHandling.KEEP_CAUSE),
                        Order.card("ORD-1003", ANNA), Order.card("ORD-1004", PIOTR));

                SentryEvent timeout = telemetry.events().get(0);
                SentryEvent unavailable = telemetry.events().get(1);
                // Protokół zapisuje łańcuch od najgłębszej przyczyny do wyjątku zewnętrznego.
                assertEquals(List.of("HttpTimeoutException", "PaymentGatewayException", "CheckoutException"),
                        exceptionTypes(timeout));
                assertEquals(List.of("PaymentGatewayException", "CheckoutException"), exceptionTypes(unavailable));
                assertEquals("timeout", timeout.getTag("payment.failure_reason"));
                assertEquals("unavailable", unavailable.getTag("payment.failure_reason"));
                assertNotEquals(timeout.getFingerprints(), unavailable.getFingerprints());
            }
        }

        @Test
        void endpointDescribesRequestForTriage() {
            gateway.respond("ORD-1005", Reply.UNAVAILABLE);
            try (var telemetry = SentryTestSupport.start(CheckoutSentryConfig.TARGET)) {
                placeOrders(new CheckoutService(client, LOYALTY, CauseHandling.KEEP_CAUSE), Order.card("ORD-1005", ANNA));

                SentryEvent event = telemetry.singleEvent();
                assertEquals("checkout.place_order", event.getTag("business.operation"));
                assertEquals(ANNA, event.getUser().getId());
                assertEquals("ORD-1005", checkoutContext(event).get("order_id"));
                assertEquals("checkout", breadcrumbs(event).getFirst().getCategory());
            }
        }
    }

    @Nested
    class Scenario2InAppFrames {

        private final Order legacyCard = Order.card("ORD-2001", ANNA).withLoyaltyCard("LOY2-00A7F3");

        @Test
        void withoutIncludeNoFrameIsMarkedInApp() {
            try (var telemetry = SentryTestSupport.start(CheckoutSentryConfig.TARGET.withInAppPrefix(null))) {
                placeOrders(new CheckoutService(client, LOYALTY, CauseHandling.KEEP_CAUSE), legacyCard);

                SentryEvent event = telemetry.singleEvent();
                for (SentryException exception : event.getExceptions()) {
                    exception.getStacktrace().getFrames().forEach(frame -> assertNull(frame.isInApp()));
                }
            }
        }

        @Test
        void prefixWithoutDotAlsoMarksLibraryWithSimilarPackageName() {
            try (var telemetry = SentryTestSupport.start(
                    CheckoutSentryConfig.TARGET.withInAppPrefix("pl.training.sentry.module03"))) {
                placeOrders(new CheckoutService(client, LOYALTY, CauseHandling.KEEP_CAUSE), legacyCard);

                SentryException rootCause = telemetry.singleEvent().getExceptions().getFirst();
                assertEquals("NumberFormatException", rootCause.getType());
                assertEquals("pl.training.sentry.module03legacy.LegacyLoyaltyClient.parseCardNumber",
                        firstInAppFrame(rootCause));
            }
        }

        @Test
        void prefixWithDotMarksOnlyApplicationPackage() {
            try (var telemetry = SentryTestSupport.start(CheckoutSentryConfig.TARGET)) {
                placeOrders(new CheckoutService(client, LOYALTY, CauseHandling.KEEP_CAUSE), legacyCard);

                SentryEvent event = telemetry.singleEvent();
                SentryException rootCause = event.getExceptions().getFirst();
                assertEquals("pl.training.sentry.module03.CheckoutService.placeOrder", firstInAppFrame(rootCause));
                for (SentryStackFrame frame : rootCause.getStacktrace().getFrames()) {
                    boolean application = frame.getModule().startsWith(CheckoutSentryConfig.IN_APP_PACKAGE);
                    // SDK ustawia tylko true dla include; pozostałe ramki zostają bez decyzji (null).
                    assertEquals(application ? Boolean.TRUE : null, frame.isInApp(), frame.getModule());
                }
                // Błąd biblioteki lojalnościowej to nie błąd bramki: PaymentGrouping go nie zmienia.
                assertNull(event.getTag("payment.failure_reason"));
                assertTrue(fingerprints(event).isEmpty());
            }
        }

        @Test
        void eventPreviewPrintsFirstInAppFrameAndBreadcrumbCount() {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
            try (var telemetry = SentryTestSupport.start(CheckoutSentryConfig.TARGET.andThen(
                    options -> options.setBeforeSend(new EventPreview(options.getBeforeSend(), out))))) {
                placeOrders(new CheckoutService(client, LOYALTY, CauseHandling.KEEP_CAUSE), legacyCard);

                assertEquals(1, telemetry.events().size());
                String printed = buffer.toString(StandardCharsets.UTF_8);
                assertTrue(printed.contains("NumberFormatException: pierwsza in-app "
                        + "pl.training.sentry.module03.CheckoutService.placeOrder"), printed);
                assertTrue(printed.contains("breadcrumbs 1 w evencie"), printed);
            }
        }
    }

    @Nested
    class Scenario3Fingerprints {

        private final List<Order> orders = List.of(
                Order.card("ORD-3001", ANNA), Order.card("ORD-3002", PIOTR),
                Order.card("ORD-3003", EWA), Order.card("ORD-3004", ANNA));

        private List<SentryEvent> send(Strategy strategy) {
            gateway.respond("ORD-3001", Reply.UNAVAILABLE);
            gateway.respond("ORD-3002", Reply.UNAVAILABLE);
            gateway.respond("ORD-3003", Reply.RATE_LIMITED);
            gateway.respond("ORD-3004", Reply.SLOW);
            try (var telemetry = SentryTestSupport.start(CheckoutSentryConfig.TARGET.withGrouping(strategy))) {
                placeOrders(new CheckoutService(client, LOYALTY, CauseHandling.KEEP_CAUSE), orders.toArray(Order[]::new));
                return telemetry.events();
            }
        }

        @Test
        void defaultGroupingSees503And429AsTheSameStackTrace() {
            List<SentryEvent> events = send(Strategy.SDK_DEFAULT);

            // Pusta lista: SDK przepisuje fingerprint ze scope, który domyślnie jest pusty.
            events.forEach(event -> assertTrue(fingerprints(event).isEmpty()));
            // 503 i 429: te same typy i ramki w całym łańcuchu, różnią się tylko komunikatem.
            assertEquals(chainSignature(events.get(0)), chainSignature(events.get(2)));
            assertNotEquals(chainSignature(events.get(0)), chainSignature(events.get(3)));
            assertEquals("rate_limited", events.get(2).getTag("payment.failure_reason"));
        }

        @Test
        void requestPathInFingerprintGivesEveryOrderItsOwnValue() {
            List<SentryEvent> events = send(Strategy.DEFAULT_PLUS_REQUEST_PATH);

            assertEquals(4, distinctFingerprints(events));
            assertEquals(List.of("{{ default }}", "POST /payments/ORD-3001"), events.get(0).getFingerprints());
        }

        @Test
        void oneBucketPutsEveryFailureUnderOneFingerprint() {
            List<SentryEvent> events = send(Strategy.ONE_BUCKET);

            assertEquals(1, distinctFingerprints(events));
            assertEquals(List.of("payment-gateway"), events.get(0).getFingerprints());
        }

        @Test
        void reasonInFingerprintSeparatesCausesAndKeepsOrdersTogether() {
            List<SentryEvent> events = send(Strategy.DEFAULT_PLUS_REASON);

            assertEquals(3, distinctFingerprints(events));
            assertEquals(events.get(0).getFingerprints(), events.get(1).getFingerprints());
            assertEquals(List.of("{{ default }}", "payment-gateway", "rate_limited"), events.get(2).getFingerprints());
        }
    }

    @Nested
    class Scenario4RetryInflatesImpact {

        private List<SentryEvent> send(AttemptReporting reporting, boolean deduplication) {
            gateway.respond("ORD-4001", Reply.SLOW);
            gateway.respond("ORD-4002", Reply.SLOW);
            gateway.respond("ORD-4003", Reply.SLOW, Reply.AUTHORIZED);
            try (var telemetry = SentryTestSupport.start(CheckoutSentryConfig.TARGET.andThen(
                    options -> options.setEnableDeduplication(deduplication)))) {
                CheckoutService checkout = new CheckoutService(
                        new PaymentRetry(client, 3, reporting), LOYALTY, CauseHandling.KEEP_CAUSE);
                List<String> responses = placeOrders(checkout, Order.card("ORD-4001", ANNA),
                        Order.card("ORD-4002", PIOTR), Order.card("ORD-4003", EWA));
                assertEquals(List.of(CheckoutEndpoint.ORDER_NOT_PLACED, CheckoutEndpoint.ORDER_NOT_PLACED, "AUTHORIZED"),
                        responses);
                return telemetry.events();
            }
        }

        @Test
        void capturingEachFailedAttemptInflatesEventsAndUsersAndLosesFinalEvent() {
            List<SentryEvent> events = send(AttemptReporting.CAPTURE_EACH_FAILURE, true);

            assertEquals(7, events.size());
            assertEquals(Set.of(ANNA, PIOTR, EWA), userIds(events));
            // Ewa dostała potwierdzenie zamówienia, a mimo to ma event i liczy się do users.
            assertEquals(1, events.stream().filter(event -> EWA.equals(event.getUser().getId())).count());
            // Event końcowy z endpointu (CheckoutException) odrzuciła deduplikacja.
            events.forEach(event -> assertEquals("PaymentGatewayException", outermost(event).getType()));
        }

        @Test
        void withoutDeduplicationFinalEventsComeBackWhichProvesWhoDroppedThem() {
            List<SentryEvent> events = send(AttemptReporting.CAPTURE_EACH_FAILURE, false);

            assertEquals(9, events.size());
            assertEquals(2, events.stream().filter(event -> "CheckoutException".equals(outermost(event).getType())).count());
        }

        @Test
        void breadcrumbPerAttemptSendsOneEventPerFailedOrder() {
            List<SentryEvent> events = send(AttemptReporting.BREADCRUMB_PER_ATTEMPT, true);

            assertEquals(2, events.size());
            assertEquals(Set.of(ANNA, PIOTR), userIds(events));
            for (SentryEvent event : events) {
                assertEquals("CheckoutException", outermost(event).getType());
                List<Breadcrumb> attempts = breadcrumbs(event).stream()
                        .filter(breadcrumb -> "payment.retry".equals(breadcrumb.getCategory()))
                        .toList();
                assertEquals(List.of(1, 2, 3), attempts.stream().map(breadcrumb -> breadcrumb.getData("attempt")).toList());
                assertEquals(Map.of("attempts", 3, "exhausted", true, "last_reason", "timeout"),
                        event.getContexts().get("payment_retry"));
            }
        }
    }

    @Nested
    class Scenario5BreadcrumbBuffer {

        @Test
        void pollingNoiseFillsBufferPushesOutStartAndCarriesToken() {
            gateway.respond("ORD-5001", Reply.PENDING);
            try (var telemetry = SentryTestSupport.start(CheckoutSentryConfig.TARGET.withoutBreadcrumbHygiene())) {
                placeOrders(new CheckoutService(client, LOYALTY, CauseHandling.KEEP_CAUSE), Order.blik("ORD-5001", ANNA));

                List<Breadcrumb> breadcrumbs = breadcrumbs(telemetry.singleEvent());
                // 1 start + 1 autoryzacja + 120 odpytań, a bufor mieści 100: najstarsze wypadły.
                assertEquals(100, breadcrumbs.size());
                assertTrue(breadcrumbs.stream().noneMatch(breadcrumb -> "checkout".equals(breadcrumb.getCategory())));
                breadcrumbs.forEach(breadcrumb -> {
                    assertEquals("GET", breadcrumb.getData("method"));
                    assertEquals("access_token=" + TOKEN, breadcrumb.getData("http.query"));
                    assertFalse(String.valueOf(breadcrumb.getData("url")).contains(TOKEN));
                });
            }
        }

        @Test
        void beforeBreadcrumbDropsSuccessfulPollsAndStripsQuery() {
            gateway.respond("ORD-5002", Reply.PENDING);
            try (var telemetry = SentryTestSupport.start(CheckoutSentryConfig.TARGET)) {
                placeOrders(new CheckoutService(client, LOYALTY, CauseHandling.KEEP_CAUSE), Order.blik("ORD-5002", ANNA));

                SentryEvent event = telemetry.singleEvent();
                List<Breadcrumb> breadcrumbs = breadcrumbs(event);
                assertEquals(List.of("checkout", "http"), breadcrumbs.stream().map(Breadcrumb::getCategory).toList());
                assertEquals("POST", breadcrumbs.get(1).getData("method"));
                breadcrumbs.forEach(breadcrumb -> assertNull(breadcrumb.getData("http.query")));
                assertEquals("no_decision", event.getTag("payment.failure_reason"));
            }
        }
    }

    private static List<String> placeOrders(CheckoutService checkout, Order... orders) {
        CheckoutEndpoint endpoint = new CheckoutEndpoint(checkout);
        return java.util.Arrays.stream(orders).map(endpoint::handle).toList();
    }

    /** Protokół zapisuje łańcuch od najgłębszej przyczyny, więc wyjątek zewnętrzny jest ostatni. */
    private static SentryException outermost(SentryEvent event) {
        return event.getExceptions().getLast();
    }

    private static List<String> exceptionTypes(SentryEvent event) {
        return event.getExceptions().stream().map(SentryException::getType).toList();
    }

    private static List<String> frames(SentryException exception) {
        return exception.getStacktrace().getFrames().stream()
                .map(frame -> frame.getModule() + "." + frame.getFunction())
                .toList();
    }

    /** Typy i ramki całego łańcucha: to, z czego serwer liczy domyślne grupowanie wyjątków. */
    private static List<List<String>> chainSignature(SentryEvent event) {
        return event.getExceptions().stream()
                .map(exception -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(exception.getType()), frames(exception).stream()).toList())
                .toList();
    }

    private static String firstInAppFrame(SentryException exception) {
        List<SentryStackFrame> frames = exception.getStacktrace().getFrames();
        for (int i = frames.size() - 1; i >= 0; i--) {
            if (Boolean.TRUE.equals(frames.get(i).isInApp())) {
                return frames.get(i).getModule() + "." + frames.get(i).getFunction();
            }
        }
        return null;
    }

    private static List<String> fingerprints(SentryEvent event) {
        return event.getFingerprints() == null ? List.of() : event.getFingerprints();
    }

    private static long distinctFingerprints(List<SentryEvent> events) {
        return events.stream().map(SentryEvent::getFingerprints).distinct().count();
    }

    private static Set<String> userIds(List<SentryEvent> events) {
        Set<String> ids = new HashSet<>();
        events.stream().map(event -> event.getUser().getId()).filter(Objects::nonNull).forEach(ids::add);
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> checkoutContext(SentryEvent event) {
        return (Map<String, Object>) event.getContexts().get("checkout");
    }

    private static List<Breadcrumb> breadcrumbs(SentryEvent event) {
        return event.getBreadcrumbs() == null ? List.of() : event.getBreadcrumbs();
    }
}
