package pl.training.sentry.module10;

import io.sentry.ISentryLifecycleToken;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryLogEventAttributeValue;
import io.sentry.SentryMetricsEvent;
import io.sentry.SentryOptions;
import io.sentry.Session;
import io.sentry.exception.ExceptionMechanismException;
import io.sentry.metrics.IMetricsApi;
import io.sentry.metrics.MetricsUnit;
import io.sentry.protocol.Mechanism;
import io.sentry.protocol.User;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import pl.training.sentry.module10.CheckoutEndpoint.Response;
import pl.training.sentry.module10.CheckoutEndpoint.SessionTracking;
import pl.training.sentry.module10.Order.PaymentMethod;
import pl.training.sentry.module10.Ratios.Bucket;
import pl.training.sentry.support.SentryTestSupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenariusze {@link Module10Demo} sprawdzone na metrykach i sesjach, które SDK przekazało do transportu.
 *
 * <p>Każda klasa zagnieżdżona odpowiada jednemu scenariuszowi. Testy uruchamiają prawdziwe SDK
 * z transportem w pamięci. Metryki SDK wysyła partiami, więc przed asercjami test wywołuje
 * {@code flush()}.</p>
 */
@ResourceLock("sentry-global-state")
class Module10ScenariosTest {

    private static final List<Order> MIXED_TRAFFIC = List.of(
            Order.pln("ORD-1", PaymentMethod.CARD, "ok"),
            Order.pln("ORD-2", PaymentMethod.BLIK, "ok"),
            Order.pln("ORD-3", PaymentMethod.BLIK, "declined"),
            Order.pln("ORD-4", PaymentMethod.CARD, "timeout"));

    private final CheckoutEndpoint checkout = new CheckoutEndpoint(SessionTracking.NONE);

    @Nested
    class Scenario1AttemptBeforeWork {

        @Test
        void countingAfterWorkDropsTimeoutFromAttemptsAndFailures() {
            try (var telemetry = start(options -> {})) {
                MIXED_TRAFFIC.forEach(checkout::handleCountingAfterWork);
                telemetry.flush();

                assertEquals(3, sum(telemetry.metrics(), CheckoutMetrics.ATTEMPTED));
                assertEquals(2, sum(telemetry.metrics(), CheckoutMetrics.COMPLETED));
                assertEquals(1, sum(telemetry.metrics(), CheckoutMetrics.FAILED));
                // Event timeoutu jest, więc problem widać w Issues, ale nie w failure rate.
                assertEquals(1, telemetry.events().size());
            }
        }

        @Test
        void countingBeforeWorkKeepsEveryAttemptAndSeparatesOutcomes() {
            try (var telemetry = start(options -> {})) {
                MIXED_TRAFFIC.forEach(checkout::handle);
                telemetry.flush();

                List<SentryMetricsEvent> metrics = telemetry.metrics();
                assertEquals(4, sum(metrics, CheckoutMetrics.ATTEMPTED));
                assertEquals(2, sum(metrics, CheckoutMetrics.COMPLETED));
                assertEquals(List.of("declined", "gateway_timeout"), named(metrics, CheckoutMetrics.FAILED).stream()
                        .map(metric -> attribute(metric, CheckoutMetrics.OUTCOME)).toList());

                List<SentryMetricsEvent> durations = named(metrics, CheckoutMetrics.DURATION);
                assertEquals(4, durations.size());
                for (SentryMetricsEvent duration : durations) {
                    assertEquals("distribution", duration.getType());
                    assertEquals(MetricsUnit.Duration.MILLISECOND, duration.getUnit());
                }
                assertEquals("counter", named(metrics, CheckoutMetrics.ATTEMPTED).getFirst().getType());
            }
        }

        @Test
        void http200DoesNotMeanPaidOrder() {
            try (var telemetry = start(options -> {})) {
                List<Response> responses = MIXED_TRAFFIC.stream().map(checkout::handle).toList();
                telemetry.flush();

                assertEquals(3, responses.stream().filter(response -> response.httpStatus() == 200).count());
                assertEquals(2, sum(telemetry.metrics(), CheckoutMetrics.COMPLETED));
            }
        }

        @Test
        void metricsWaitInBufferUntilFlush() {
            try (var telemetry = start(options -> {})) {
                CheckoutMetrics.attempted(PaymentMethod.CARD);
                assertTrue(telemetry.metrics().isEmpty());

                telemetry.flush();
                assertEquals(1, telemetry.metrics().size());
            }
        }
    }

    @Nested
    class Scenario2GaugeForState {

        @Test
        void gaugeShowsBacklogWhileEnqueuedCounterStaysFlat() {
            try (var telemetry = start(options -> {})) {
                PaymentQueue queue = new PaymentQueue();
                for (int minute = 1; minute <= 4; minute++) {
                    queue.enqueue("a" + minute);
                    CheckoutMetrics.paymentEnqueued();
                    queue.enqueue("b" + minute);
                    CheckoutMetrics.paymentEnqueued();
                    if (minute <= 2) {
                        queue.consume(2);
                    }
                    CheckoutMetrics.queueDepth(queue.depth());
                }
                telemetry.flush();

                List<SentryMetricsEvent> depth = named(telemetry.metrics(), CheckoutMetrics.QUEUE_DEPTH);
                assertEquals(List.of(0.0, 0.0, 2.0, 4.0), depth.stream().map(SentryMetricsEvent::getValue).toList());
                assertEquals("gauge", depth.getFirst().getType());
                assertEquals(8, sum(telemetry.metrics(), CheckoutMetrics.QUEUE_ENQUEUED));
            }
        }
    }

    @Nested
    class Scenario3BoundedAttributes {

        @Test
        void carelessVersionSendsOrderIdAndMessageAsAttributes() {
            try (var telemetry = start(options -> {})) {
                Order order = Order.pln("ORD-3001", PaymentMethod.CARD, "declined");
                CheckoutMetrics.failedCarelessly(order, "Płatność ORD-3001 odrzucona");
                telemetry.flush();

                SentryMetricsEvent metric = telemetry.metrics().getFirst();
                assertEquals("ORD-3001", attribute(metric, "order.id"));
                assertEquals("Płatność ORD-3001 odrzucona", attribute(metric, "gateway.message"));
            }
        }

        @Test
        void targetVersionSendsOnlyBoundedDimensionsPlusSdkAttributes() {
            try (var telemetry = start(options -> {
                options.setServerName("checkout-api-1");
                options.setTag("training.module", "module10");
            })) {
                CheckoutMetrics.failed(PaymentMethod.BLIK, CheckoutOutcome.DECLINED);
                telemetry.flush();

                SentryMetricsEvent metric = telemetry.metrics().getFirst();
                // Tag z options.setTag trafia do eventów, a do metryk nie.
                assertEquals(Map.of("payment.method", "blik", "checkout.outcome", "declined"), ownAttributes(metric));
                // Atrybuty, które SDK dodaje do każdej metryki bez udziału kodu aplikacji.
                assertEquals(SentryTestSupport.TEST_ENVIRONMENT, attribute(metric, "sentry.environment"));
                assertEquals(SentryTestSupport.TEST_RELEASE, attribute(metric, "sentry.release"));
                assertEquals("checkout-api-1", attribute(metric, "server.address"));
                assertTrue(metric.getAttributes().containsKey("sentry.sdk.name"));
                assertTrue(metric.getAttributes().containsKey("sentry.sdk.version"));
            }
        }
    }

    @Nested
    class Scenario4PersonalDataFromScope {

        private static final Order TIMEOUT = Order.pln("ORD-4001", PaymentMethod.CARD, "timeout");

        @Test
        void eventBeforeSendScrubsEventButNotMetrics() {
            try (var telemetry = start(options -> options.setBeforeSend(TelemetryPrivacy::scrubEvent))) {
                requestOfLoggedInCustomer(TIMEOUT);
                telemetry.flush();

                SentryEvent event = telemetry.singleEvent();
                assertEquals("c-7f3a9c", event.getUser().getId());
                assertNull(event.getUser().getEmail());
                for (SentryMetricsEvent metric : telemetry.metrics()) {
                    assertEquals("anna.kowalska@example.com", attribute(metric, "user.email"));
                    assertEquals("anna.k", attribute(metric, "user.name"));
                }
            }
        }

        @Test
        void metricCallbackRemovesEmailAndNameButKeepsTechnicalId() {
            try (var telemetry = start(options -> {
                options.setBeforeSend(TelemetryPrivacy::scrubEvent);
                options.getMetrics().setBeforeSend(TelemetryPrivacy::scrubMetric);
            })) {
                requestOfLoggedInCustomer(TIMEOUT);
                telemetry.flush();

                assertEquals(3, telemetry.metrics().size());
                for (SentryMetricsEvent metric : telemetry.metrics()) {
                    assertEquals("c-7f3a9c", attribute(metric, "user.id"));
                    assertFalse(metric.getAttributes().containsKey("user.email"));
                    assertFalse(metric.getAttributes().containsKey("user.name"));
                }
            }
        }

        @Test
        void attributesSetOnScopeReachEveryMetric() {
            try (var telemetry = start(options -> {})) {
                try (ISentryLifecycleToken requestScope = Sentry.pushIsolationScope()) {
                    Sentry.setAttribute("customer.email", "anna.kowalska@example.com");
                    CheckoutMetrics.attempted(PaymentMethod.CARD);
                }
                telemetry.flush();

                assertEquals("anna.kowalska@example.com", attribute(telemetry.metrics().getFirst(), "customer.email"));
            }
        }

        @Test
        void exceptionInMetricCallbackDropsTheMetric() {
            try (var telemetry = start(options -> options.getMetrics().setBeforeSend((metric, hint) -> {
                throw new IllegalStateException("błąd w polityce");
            }))) {
                CheckoutMetrics.attempted(PaymentMethod.CARD);
                telemetry.flush();

                assertTrue(telemetry.metrics().isEmpty());
            }
        }

        @Test
        void metricsApiCachedBeforeInitIsNoOpForever() {
            IMetricsApi cachedBeforeInit = Sentry.metrics();
            try (var telemetry = start(options -> {})) {
                cachedBeforeInit.count(CheckoutMetrics.ATTEMPTED);
                telemetry.flush();

                assertTrue(telemetry.metrics().isEmpty());
            }
        }

        @Test
        void metricsApiCachedAfterInitSeesScopeOfItsOriginNotOfRequest() {
            try (var telemetry = start(options -> {})) {
                IMetricsApi cachedAtStartup = Sentry.metrics();
                try (ISentryLifecycleToken requestScope = Sentry.pushIsolationScope()) {
                    User user = new User();
                    user.setId("c-7f3a9c");
                    Sentry.setUser(user);
                    cachedAtStartup.count("cached");
                    Sentry.metrics().count("fresh");
                }
                telemetry.flush();

                assertNull(attribute(named(telemetry.metrics(), "cached").getFirst(), "user.id"));
                assertEquals("c-7f3a9c", attribute(named(telemetry.metrics(), "fresh").getFirst(), "user.id"));
            }
        }

        @Test
        void metricsAreEnabledByDefaultAndOneSwitchTurnsThemOff() {
            assertTrue(new SentryOptions().getMetrics().isEnabled());
            try (var telemetry = start(options -> options.getMetrics().setEnabled(false))) {
                MIXED_TRAFFIC.forEach(checkout::handle);
                telemetry.flush();

                assertTrue(telemetry.metrics().isEmpty());
                // Eventy błędów działają dalej: wyłączenie metryk nie zmienia zachowania aplikacji.
                assertEquals(1, telemetry.events().size());
            }
        }

        private void requestOfLoggedInCustomer(Order order) {
            try (ISentryLifecycleToken requestScope = Sentry.pushIsolationScope()) {
                User user = new User();
                user.setId("c-7f3a9c");
                user.setUsername("anna.k");
                user.setEmail("anna.kowalska@example.com");
                Sentry.setUser(user);
                checkout.handle(order);
            }
        }
    }

    @Nested
    class Scenario5RatioOfSums {

        private final List<Bucket> hours = List.of(
                new Bucket("09:00", 24, 1_180),
                new Bucket("10:00", 25, 1_320),
                new Bucket("03:00", 3, 6),
                new Bucket("04:00", 0, 0));

        @Test
        void ratioOfSumsWeighsEveryAttemptEqually() {
            assertEquals(52.0 / 2_506, Ratios.ratioOfSums(hours).getAsDouble(), 1e-9);
        }

        @Test
        void averageOfBucketRatiosIsInflatedBySmallNightBucket() {
            double average = Ratios.averageOfBucketRatios(hours).getAsDouble();
            assertEquals((24.0 / 1_180 + 25.0 / 1_320 + 0.5) / 3, average, 1e-9);
            assertTrue(average > 8 * Ratios.ratioOfSums(hours).getAsDouble());
        }

        @Test
        void windowWithoutAttemptsHasNoResultInsteadOfZero() {
            assertEquals(OptionalDouble.empty(), Ratios.ratioOfSums(List.of(new Bucket("04:00", 0, 0))));
        }
    }

    @Nested
    class Scenario6ReleaseHealthNeedsSessions {

        private static final List<Order> RELEASE_TRAFFIC = List.of(
                Order.pln("ORD-6001", PaymentMethod.CARD, "ok"),
                Order.pln("ORD-6002", PaymentMethod.BLIK, "timeout"),
                Order.eur("ORD-6003", PaymentMethod.CARD));

        private final CheckoutEndpoint withSessions = new CheckoutEndpoint(SessionTracking.PER_REQUEST);

        @Test
        void withoutExplicitSessionsSdkSendsNoneAndCrashFreeHasNoValue() {
            try (var telemetry = start(options -> {})) {
                RELEASE_TRAFFIC.forEach(checkout::handle);
                telemetry.flush();

                assertEquals(2, telemetry.events().size());
                assertTrue(telemetry.sessionUpdates().isEmpty());
                assertEquals(OptionalDouble.empty(), Ratios.crashFreeRate(0, 0));
            }
        }

        @Test
        void sessionPerRequestEndsExitedErroredOrCrashed() {
            try (var telemetry = start(options -> {})) {
                RELEASE_TRAFFIC.forEach(withSessions::handle);

                List<Session> finals = finalStates(telemetry.sessionUpdates());
                assertEquals(3, finals.size());
                assertEquals(List.of(Session.State.Exited, Session.State.Exited, Session.State.Crashed),
                        finals.stream().map(Session::getStatus).toList());
                assertEquals(List.of(0, 1, 1), finals.stream().map(Session::errorCount).toList());
                for (Session session : finals) {
                    assertEquals(SentryTestSupport.TEST_RELEASE, session.getRelease());
                    assertEquals(SentryTestSupport.TEST_ENVIRONMENT, session.getEnvironment());
                }

                long crashed = finals.stream().filter(session -> session.getStatus() == Session.State.Crashed).count();
                assertEquals(2.0 / 3, Ratios.crashFreeRate(finals.size(), crashed).getAsDouble(), 1e-9);
            }
        }

        @Test
        void unhandledErrorAtRequestBoundaryCarriesHandledFalse() {
            try (var telemetry = start(options -> {})) {
                Response response = withSessions.handle(Order.eur("ORD-6003", PaymentMethod.CARD));

                assertEquals(500, response.httpStatus());
                Mechanism mechanism = telemetry.singleEvent().getExceptions().getLast().getMechanism();
                assertEquals("CheckoutEndpoint", mechanism.getType());
                assertEquals(Boolean.FALSE, mechanism.isHandled());
            }
        }

        @Test
        void endSessionAfterCrashSendsCrashedStateSecondTime() {
            try (var telemetry = start(options -> {})) {
                try (ISentryLifecycleToken requestScope = Sentry.pushIsolationScope()) {
                    Sentry.startSession();
                    Mechanism mechanism = new Mechanism();
                    mechanism.setHandled(false);
                    Sentry.captureException(new ExceptionMechanismException(
                            mechanism, new IllegalStateException("crash"), Thread.currentThread()));
                    Sentry.endSession();
                }

                // Start, stan crashed razem z eventem i ten sam stan crashed ponownie z endSession.
                assertEquals(List.of(Session.State.Ok, Session.State.Crashed, Session.State.Crashed),
                        telemetry.sessionUpdates().stream().map(Session::getStatus).toList());
            }
        }

        @Test
        void endpointSendsCrashedStateOnlyOnce() {
            try (var telemetry = start(options -> {})) {
                withSessions.handle(Order.eur("ORD-6003", PaymentMethod.CARD));

                assertEquals(List.of(Session.State.Ok, Session.State.Crashed),
                        telemetry.sessionUpdates().stream().map(Session::getStatus).toList());
            }
        }

        @Test
        void everyRequestSendsAtLeastStartAndEndOfItsSession() {
            try (var telemetry = start(options -> {})) {
                withSessions.handle(Order.pln("ORD-6001", PaymentMethod.CARD, "ok"));

                List<Session> updates = telemetry.sessionUpdates();
                assertEquals(2, updates.size());
                assertEquals(Boolean.TRUE, updates.getFirst().getInit());
                assertEquals(Session.State.Ok, updates.getFirst().getStatus());
                assertEquals(Session.State.Exited, updates.getLast().getStatus());
            }
        }

        @Test
        void withoutReleaseSdkStartsNoSession() {
            try (var telemetry = start(options -> options.setRelease(null))) {
                withSessions.handle(Order.pln("ORD-6001", PaymentMethod.CARD, "ok"));

                assertTrue(telemetry.sessionUpdates().isEmpty());
            }
        }

        @Test
        void distinctIdComesFromOptionsNotFromUserInScope() {
            try (var telemetry = start(options -> {})) {
                requestAsUser("c-7f3a9c");
                assertNull(telemetry.sessionUpdates().getFirst().getDistinctId());
            }
            try (var telemetry = start(options -> options.setDistinctId("checkout-api-1"))) {
                requestAsUser("c-7f3a9c");
                assertTrue(telemetry.sessionUpdates().stream()
                        .allMatch(session -> "checkout-api-1".equals(session.getDistinctId())));
            }
        }

        @Test
        void distinctIdBecomesUserIdOfMetricsWithoutUser() {
            try (var telemetry = start(options -> options.setDistinctId("checkout-api-1"))) {
                CheckoutMetrics.attempted(PaymentMethod.CARD);
                telemetry.flush();

                assertEquals("checkout-api-1", attribute(telemetry.metrics().getFirst(), "user.id"));
            }
        }

        @Test
        void crashedCountCannotExceedSessions() {
            assertThrows(IllegalArgumentException.class, () -> Ratios.crashFreeRate(2, 3));
        }

        private void requestAsUser(String userId) {
            try (ISentryLifecycleToken requestScope = Sentry.pushIsolationScope()) {
                User user = new User();
                user.setId(userId);
                Sentry.setUser(user);
                withSessions.handle(Order.pln("ORD-6001", PaymentMethod.CARD, "ok"));
            }
        }

        /** Ostatni wysłany stan każdej sesji, w kolejności startu sesji. */
        private static List<Session> finalStates(List<Session> updates) {
            Map<String, Session> last = new LinkedHashMap<>();
            updates.forEach(update -> last.put(update.getSessionId(), update));
            return List.copyOf(last.values());
        }
    }

    /**
     * {@link SentryTestSupport#start} ze skróconym zamykaniem SDK.
     *
     * <p>Po pierwszej metryce SDK planuje wysyłkę paczki za 5 s, a {@code Sentry.close} czeka na
     * wątki SDK do {@code shutdownTimeoutMillis} (domyślnie 2 s). Testy wywołują {@code flush()}
     * przed asercjami, więc krótki limit niczego nie gubi, a cała klasa działa kilka sekund zamiast
     * kilkudziesięciu.</p>
     */
    private static SentryTestSupport.CapturedTelemetry start(Consumer<SentryOptions> customizer) {
        return SentryTestSupport.start(options -> {
            options.setShutdownTimeoutMillis(100);
            customizer.accept(options);
        });
    }

    private static List<SentryMetricsEvent> named(List<SentryMetricsEvent> metrics, String name) {
        return metrics.stream().filter(metric -> metric.getName().equals(name)).toList();
    }

    private static long sum(List<SentryMetricsEvent> metrics, String name) {
        return Math.round(named(metrics, name).stream().mapToDouble(SentryMetricsEvent::getValue).sum());
    }

    private static Object attribute(SentryMetricsEvent metric, String key) {
        SentryLogEventAttributeValue value = metric.getAttributes().get(key);
        return value == null ? null : value.getValue();
    }

    /** Atrybuty ustawione przez kod aplikacji, bez tych, które SDK dodaje do każdej metryki. */
    private static Map<String, Object> ownAttributes(SentryMetricsEvent metric) {
        return metric.getAttributes().entrySet().stream()
                .filter(entry -> !entry.getKey().startsWith("sentry.") && !entry.getKey().equals("server.address"))
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getValue()));
    }
}
