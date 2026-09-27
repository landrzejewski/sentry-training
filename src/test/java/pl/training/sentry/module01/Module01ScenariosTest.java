package pl.training.sentry.module01;

import io.sentry.Breadcrumb;
import io.sentry.ISentryLifecycleToken;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.protocol.Mechanism;
import io.sentry.protocol.SentryException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import pl.training.sentry.module01.LabelExportJob.ErrorReporting;
import pl.training.sentry.module01.Order.Address;
import pl.training.sentry.module01.Order.Customer;
import pl.training.sentry.module01.Order.DeliveryMode;
import pl.training.sentry.support.SentryTestSupport;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenariusze {@link Module01Demo} sprawdzone na treści eventów, które SDK przekazało do transportu.
 *
 * <p>Każda klasa zagnieżdżona odpowiada jednemu scenariuszowi. Testy uruchamiają prawdziwe SDK
 * z transportem w pamięci, więc asercje dotyczą danych po przejściu przez scope, integracje
 * i deduplikację, czyli tego, co zobaczyłby zespół w Sentry.</p>
 */
@ResourceLock("sentry-global-state")
class Module01ScenariosTest {

    private static final Customer ANNA = new Customer("c-7f3a9c", "anna.kowalska@example.com");
    private static final Customer PIOTR = new Customer("c-19bd42", "piotr.nowak@example.com");

    private static final Order PICKUP = Order.pickupPoint("ORD-1", ANNA, "WAW-114");
    private static final Order BROKEN_COURIER = new Order("ORD-2", PIOTR, DeliveryMode.COURIER, null, null);

    private final CheckoutEndpoint checkout = new CheckoutEndpoint();
    private final LabelExportJob exportJob = new LabelExportJob();

    @Nested
    class Scenario1DiagnosticDebt {

        @Test
        void differentCausesSentWithoutContextProduceIndistinguishableEvents() {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                for (Order order : List.of(PICKUP, BROKEN_COURIER)) {
                    checkout.generateLabel(order);
                }

                List<SentryEvent> events = telemetry.events();
                assertEquals(2, events.size());
                assertEquals(describeException(events.get(0)), describeException(events.get(1)));
                assertEquals(frames(events.get(0)), frames(events.get(1)));
                for (SentryEvent event : events) {
                    // Release i environment pochodzą z konfiguracji SDK, nie z kodu raportującego.
                    assertEquals(SentryTestSupport.TEST_RELEASE, event.getRelease());
                    assertEquals(SentryTestSupport.TEST_ENVIRONMENT, event.getEnvironment());
                    assertNull(event.getTag("delivery.mode"));
                    assertNull(shippingContext(event));
                    assertNull(userId(event));
                    assertTrue(breadcrumbs(event).isEmpty());
                }
            }
        }
    }

    @Nested
    class Scenario2SameStackTraceTwoCauses {

        @Test
        void tagAndContextSeparateCausesThatShareOneStackTrace() {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                for (Order order : List.of(PICKUP, BROKEN_COURIER)) {
                    checkout.handle(order, "B");
                }

                SentryEvent pickup = telemetry.events().get(0);
                SentryEvent brokenCourier = telemetry.events().get(1);
                assertEquals(frames(pickup), frames(brokenCourier));

                assertEquals("PICKUP_POINT", pickup.getTag("delivery.mode"));
                assertEquals(Map.of("order_id", "ORD-1", "has_delivery_address", false, "has_pickup_point", true),
                        shippingContext(pickup));

                assertEquals("COURIER", brokenCourier.getTag("delivery.mode"));
                assertEquals(Map.of("order_id", "ORD-2", "has_delivery_address", false, "has_pickup_point", false),
                        shippingContext(brokenCourier));
            }
        }

        @Test
        void eachRequestCarriesOnlyItsOwnUserAndBreadcrumbs() {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                checkout.handle(PICKUP, "B");
                checkout.handle(BROKEN_COURIER, "B");

                SentryEvent second = telemetry.events().get(1);
                assertEquals("c-19bd42", userId(second));
                assertEquals(List.of("ORD-2"), breadcrumbOrderIds(second));
            }
        }

        @Test
        void completeCourierOrderGetsLabelAndSendsNothing() {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                Order courier = Order.courier("ORD-3", ANNA, new Address("Prosta 1", "00 838"));

                assertEquals("KURIER/00838/ORD-3", checkout.handle(courier, "A"));
                assertTrue(telemetry.events().isEmpty());
            }
        }
    }

    @Nested
    class Scenario3EachDatumInItsMechanism {

        @Test
        void carelessVersionPutsOrderIdInTagsAndEmailInUserAndBreadcrumb() {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                try (ISentryLifecycleToken requestScope = Sentry.pushIsolationScope()) {
                    CheckoutTelemetry.describeRequestCarelessly(PICKUP, "B");
                    checkout.generateLabel(PICKUP);
                }

                SentryEvent event = telemetry.singleEvent();
                assertEquals("ORD-1", event.getTag("order.id"));
                assertEquals(ANNA.email(), event.getUser().getEmail());
                assertTrue(breadcrumbs(event).getFirst().getMessage().contains(ANNA.email()));
                assertNull(shippingContext(event));
            }
        }

        @Test
        void targetVersionKeepsTagsLowCardinalityAndSendsNoEmail() {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                checkout.handle(PICKUP, "B");

                SentryEvent event = telemetry.singleEvent();
                assertEquals(Map.of("delivery.mode", "PICKUP_POINT", "checkout.variant", "B"), event.getTags());
                assertEquals("ORD-1", shippingContext(event).get("order_id"));
                assertEquals(ANNA.id(), userId(event));
                assertNull(event.getUser().getEmail());
                Breadcrumb breadcrumb = breadcrumbs(event).getFirst();
                assertEquals("checkout.label", breadcrumb.getCategory());
                assertFalse(breadcrumb.getMessage().contains("@"));
                assertFalse(breadcrumb.getData().toString().contains("@"));
            }
        }
    }

    @Nested
    class Scenario4HandledVersusUnhandled {

        @Test
        void handledErrorIsReportedManuallyAndClientGetsFallback() {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                assertEquals(CheckoutEndpoint.MANUAL_HANDLING, checkout.handle(PICKUP, "B"));

                Mechanism mechanism = outermostException(telemetry.singleEvent()).getMechanism();
                // Ręczny capture nie ustawia handled; nieobsłużone są tylko eventy z handled=false.
                assertNotEquals(Boolean.FALSE, mechanism.isHandled());
                assertEquals("chained", mechanism.getType());
            }
        }

        @Test
        void exceptionLeavingThreadIsReportedByUncaughtExceptionHandlerIntegration() throws InterruptedException {
            try (var telemetry = SentryTestSupport.start(options -> options.setEnableUncaughtExceptionHandler(true))) {
                exportJob.runInBackground(PICKUP, ErrorReporting.NONE);

                SentryEvent event = telemetry.singleEvent();
                Mechanism mechanism = outermostException(event).getMechanism();
                assertEquals("UncaughtExceptionHandler", mechanism.getType());
                assertEquals(Boolean.FALSE, mechanism.isHandled());
                assertEquals(SentryLevel.FATAL, event.getLevel());
                // Job opisuje się tagiem i contextem, a nie udaje użytkownika.
                assertEquals("label-export", event.getTag("job.name"));
                assertNull(userId(event));
            }
        }
    }

    @Nested
    class Scenario5DuplicateReporting {

        @Test
        void captureAndRethrowOfSameObjectIsDeduplicatedAndLooksHandled() throws InterruptedException {
            try (var telemetry = SentryTestSupport.start(options -> options.setEnableUncaughtExceptionHandler(true))) {
                exportJob.runInBackground(PICKUP, ErrorReporting.CAPTURE_AND_RETHROW);

                SentryEvent event = telemetry.singleEvent();
                assertNotEquals(Boolean.FALSE, outermostException(event).getMechanism().isHandled());
            }
        }

        @Test
        void newExceptionWithoutCauseCreatesSecondEventWithoutRootCause() throws InterruptedException {
            try (var telemetry = SentryTestSupport.start(options -> options.setEnableUncaughtExceptionHandler(true))) {
                exportJob.runInBackground(PICKUP, ErrorReporting.CAPTURE_AND_THROW_NEW);

                List<SentryEvent> events = telemetry.events();
                assertEquals(2, events.size());
                assertEquals("NullPointerException", outermostException(events.get(0)).getType());
                assertEquals(List.of("IllegalStateException"), exceptionTypes(events.get(1)));
            }
        }

        @Test
        void singleOwnerWithCauseSendsOneEventWithFullChain() throws InterruptedException {
            try (var telemetry = SentryTestSupport.start(options -> options.setEnableUncaughtExceptionHandler(true))) {
                exportJob.runInBackground(PICKUP, ErrorReporting.WRAP_WITH_CAUSE);

                assertEquals(List.of("NullPointerException", "IllegalStateException"),
                        exceptionTypes(telemetry.singleEvent()));
            }
        }

        @Test
        void captureAndRethrowWithoutDeduplicationSendsHandledAndUnhandledEvent() throws InterruptedException {
            try (var telemetry = SentryTestSupport.start(options -> {
                options.setEnableUncaughtExceptionHandler(true);
                options.setEnableDeduplication(false);
            })) {
                exportJob.runInBackground(PICKUP, ErrorReporting.CAPTURE_AND_RETHROW);

                // Dowód, że handler SDK dostaje ten sam obiekt: bez deduplikacji powstaje duplikat.
                List<SentryEvent> events = telemetry.events();
                assertEquals(2, events.size());
                assertNotEquals(Boolean.FALSE, outermostException(events.get(0)).getMechanism().isHandled());
                assertEquals(Boolean.FALSE, outermostException(events.get(1)).getMechanism().isHandled());
            }
        }

        @Test
        void wrapperIsDroppedWhenItsCauseWasAlreadyCaptured() {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                NullPointerException cause = new NullPointerException("brak adresu");
                Sentry.captureException(cause);
                Sentry.captureException(new IllegalStateException("Eksport etykiety nie powiódł się", cause));

                assertEquals("NullPointerException", outermostException(telemetry.singleEvent()).getType());
            }
        }

        @Test
        void causeCapturedAfterItsWrapperIsNotDeduplicated() {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                NullPointerException cause = new NullPointerException("brak adresu");
                Sentry.captureException(new IllegalStateException("Eksport etykiety nie powiódł się", cause));
                Sentry.captureException(cause);

                // Deduplikacja pamięta tylko wyjątki przekazane do capture, a nie ich przyczyny.
                assertEquals(2, telemetry.events().size());
            }
        }
    }

    @Nested
    class Scenario6ContextLeakBetweenRequests {

        private static final Order ANNA_COURIER = Order.courier("ORD-6001", ANNA, new Address("Prosta 1", "00 838"));
        private static final Order GUEST_PICKUP = Order.pickupPoint("ORD-6002", null, "KRK-031");

        @Test
        void withoutIsolationGuestRequestInheritsPreviousUserAndBreadcrumbs() throws Exception {
            try (var telemetry = SentryTestSupport.start(options -> {});
                 ExecutorService workers = Executors.newSingleThreadExecutor()) {
                workers.submit(() -> {
                    CheckoutTelemetry.describeRequest(ANNA_COURIER, "A");
                    return checkout.generateLabel(ANNA_COURIER);
                }).get();
                workers.submit(() -> {
                    CheckoutTelemetry.describeRequest(GUEST_PICKUP, "B");
                    return checkout.generateLabel(GUEST_PICKUP);
                }).get();

                SentryEvent guestEvent = telemetry.singleEvent();
                assertEquals(ANNA.id(), userId(guestEvent));
                assertEquals(List.of("ORD-6001", "ORD-6002"), breadcrumbOrderIds(guestEvent));
                // Klucze ustawione ponownie przez gościa są poprawne, dlatego wyciek łatwo przeoczyć.
                assertEquals("PICKUP_POINT", guestEvent.getTag("delivery.mode"));
            }
        }

        @Test
        void ownIsolationScopePerRequestStopsTheLeak() throws Exception {
            try (var telemetry = SentryTestSupport.start(options -> {});
                 ExecutorService workers = Executors.newSingleThreadExecutor()) {
                workers.submit(() -> checkout.handle(ANNA_COURIER, "A")).get();
                workers.submit(() -> checkout.handle(GUEST_PICKUP, "B")).get();

                SentryEvent guestEvent = telemetry.singleEvent();
                assertNull(userId(guestEvent));
                assertEquals(List.of("ORD-6002"), breadcrumbOrderIds(guestEvent));
            }
        }

        @Test
        void isolationScopeOpenedOnPollutedThreadStillInheritsPreviousData() throws Exception {
            try (var telemetry = SentryTestSupport.start(options -> {});
                 ExecutorService workers = Executors.newSingleThreadExecutor()) {
                workers.submit(() -> CheckoutTelemetry.describeRequest(ANNA_COURIER, "A")).get();
                workers.submit(() -> checkout.handle(GUEST_PICKUP, "B")).get();

                // Nowy isolation scope jest kopią bieżącego, więc naprawa działa tylko od początku.
                assertEquals(ANNA.id(), userId(telemetry.singleEvent()));
            }
        }

        @Test
        void staticSettersInsideWithScopeStillWriteToSharedIsolationScope() throws Exception {
            try (var telemetry = SentryTestSupport.start(options -> {});
                 ExecutorService workers = Executors.newSingleThreadExecutor()) {
                workers.submit(() -> Sentry.withScope(scope -> {
                    Sentry.setTag("set.statically", "yes");
                    scope.setTag("set.on.scope", "yes");
                })).get();
                workers.submit(() -> Sentry.captureMessage("następny request")).get();

                SentryEvent next = telemetry.singleEvent();
                assertEquals("yes", next.getTag("set.statically"));
                assertNull(next.getTag("set.on.scope"));
            }
        }
    }

    private static String describeException(SentryEvent event) {
        SentryException exception = outermostException(event);
        return exception.getType() + ": " + exception.getValue();
    }

    /** Protokół zapisuje łańcuch od najgłębszej przyczyny, więc wyjątek zewnętrzny jest ostatni. */
    private static SentryException outermostException(SentryEvent event) {
        return event.getExceptions().getLast();
    }

    private static List<String> exceptionTypes(SentryEvent event) {
        return event.getExceptions().stream().map(SentryException::getType).toList();
    }

    private static List<String> frames(SentryEvent event) {
        return outermostException(event).getStacktrace().getFrames().stream()
                .map(frame -> frame.getModule() + "." + frame.getFunction())
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> shippingContext(SentryEvent event) {
        return (Map<String, Object>) event.getContexts().get("shipping");
    }

    private static String userId(SentryEvent event) {
        return event.getUser() == null ? null : event.getUser().getId();
    }

    private static List<Breadcrumb> breadcrumbs(SentryEvent event) {
        return event.getBreadcrumbs() == null ? List.of() : event.getBreadcrumbs();
    }

    private static List<Object> breadcrumbOrderIds(SentryEvent event) {
        return breadcrumbs(event).stream().map(breadcrumb -> breadcrumb.getData("order_id")).toList();
    }
}
