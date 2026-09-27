package pl.training.sentry.module06;

import io.sentry.CheckIn;
import io.sentry.CheckInStatus;
import io.sentry.MonitorConfig;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.SpanId;
import io.sentry.protocol.SentryId;
import io.sentry.util.CheckInUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import pl.training.sentry.module06.BankGateway.BankTimeoutException;
import pl.training.sentry.module06.BankGateway.Mode;
import pl.training.sentry.module06.BankGateway.Payment;
import pl.training.sentry.module06.PaymentsEndpoint.Reporting;
import pl.training.sentry.module06.PayoutBatchCheckIns.IdStorage;
import pl.training.sentry.module06.StatementImportJob.FailureHandling;
import pl.training.sentry.support.SentryTestSupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenariusze {@link Module06Demo} sprawdzone na check-inach i eventach, które SDK przekazało
 * do transportu.
 *
 * <p>Każda klasa zagnieżdżona odpowiada jednemu scenariuszowi. Testy uruchamiają prawdziwe SDK
 * z transportem w pamięci. Stanów, które wylicza serwer (missed, timeout, powiązanie błędu
 * z check-inem), test nie sprawdzi: potwierdza je uruchomienie online opisane w
 * {@code README.md} pakietu {@code module06}.</p>
 */
@ResourceLock("sentry-global-state")
class Module06ScenariosTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);

    @Nested
    class Scenario1CronConfiguredFromCode {

        @Test
        void withCheckInSendsInProgressWithConfigAndOkWithSameIdAndDuration() throws Exception {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                new SettlementJob(new SettlementService(new BankGateway(), Set.of("PAY-1001"))).run(DAY);

                List<CheckIn> checkIns = telemetry.checkIns();
                assertEquals(List.of("in_progress", "ok"), statuses(checkIns));
                assertEquals(checkIns.get(0).getCheckInId(), checkIns.get(1).getCheckInId());
                assertTrue(checkIns.get(1).getDuration() != null);

                MonitorConfig config = checkIns.get(0).getMonitorConfig();
                assertEquals("crontab", config.getSchedule().getType());
                assertEquals("0 2 * * *", config.getSchedule().getValue());
                assertEquals("Europe/Warsaw", config.getTimezone());
                assertEquals(10L, config.getCheckinMargin());
                assertEquals(30L, config.getMaxRuntime());
                assertEquals(1L, config.getFailureIssueThreshold());
                assertEquals(1L, config.getRecoveryThreshold());
                // Konfiguracja jedzie tylko w pierwszym check-inie.
                assertNull(checkIns.get(1).getMonitorConfig());

                for (CheckIn checkIn : checkIns) {
                    assertEquals(SentryTestSupport.TEST_ENVIRONMENT, checkIn.getEnvironment());
                    assertEquals(SentryTestSupport.TEST_RELEASE, checkIn.getRelease());
                }
            }
        }

        @Test
        void unmatchedPaymentsAreNormalReturnSoCheckInIsOk() throws Exception {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                var report = new SettlementJob(new SettlementService(new BankGateway(), Set.of("PAY-1001", "PAY-1004")))
                        .run(DAY);

                assertEquals(1, report.unmatched());
                assertEquals(List.of("in_progress", "ok"), statuses(telemetry.checkIns()));
                assertTrue(telemetry.events().isEmpty());
            }
        }

        @Test
        void withoutEnvironmentInOptionsCheckInIsReportedAsProduction() throws Exception {
            try (var telemetry = SentryTestSupport.start(options -> options.setEnvironment(null))) {
                new SettlementJob(new SettlementService(new BankGateway(), Set.of("PAY-1001"))).run(DAY);

                assertEquals("production", telemetry.checkIns().getFirst().getEnvironment());
            }
        }

        @Test
        void explicitEnvironmentOverloadOverridesOptions() throws Exception {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                CheckInUtils.withCheckIn(SettlementJob.MONITOR_SLUG, "staging", SettlementJob.monitorConfig(), () -> 1);

                telemetry.checkIns().forEach(checkIn -> assertEquals("staging", checkIn.getEnvironment()));
            }
        }
    }

    @Nested
    class Scenario2FailingJob {

        @Test
        void swallowedExceptionProducesOkCheckInAndNoEvent() {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                BankGateway bank = new BankGateway();
                bank.timeOutNextCalls(1);

                new StatementImportJob(bank, "import").scheduledTask(FailureHandling.SWALLOW).run();

                assertEquals(List.of("in_progress", "ok"), statuses(telemetry.checkIns()));
                assertTrue(telemetry.events().isEmpty());
            }
        }

        @Test
        void exceptionLeavingScheduledTaskStopsAllFurtherRunsWithoutAnyEvent() throws Exception {
            try (var telemetry = SentryTestSupport.start(options -> {});
                 ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
                BankGateway bank = new BankGateway();
                bank.timeOutNextCalls(1);
                StatementImportJob job = new StatementImportJob(bank, "import");

                ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                        job.scheduledTask(FailureHandling.PROPAGATE), 0, 20, TimeUnit.MILLISECONDS);
                Thread.sleep(300);

                assertEquals(1, job.runs());
                assertTrue(future.isDone());
                // Wyjątek czeka w Future, którego nikt nie odczytuje.
                ExecutionException stored = assertThrows(ExecutionException.class, future::get);
                assertInstanceOf(BankTimeoutException.class, stored.getCause());
                assertEquals(List.of("in_progress", "error"), statuses(telemetry.checkIns()));
                assertTrue(telemetry.events().isEmpty());
            }
        }

        @Test
        void reportedFailureSharesTraceWithErrorCheckInAndJobKeepsRunning() throws Exception {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                BankGateway bank = new BankGateway();
                bank.timeOutNextCalls(1);
                StatementImportJob job = new StatementImportJob(bank, "import");
                try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
                    ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                            job.scheduledTask(FailureHandling.REPORT_AND_CONTINUE), 0, 20, TimeUnit.MILLISECONDS);
                    awaitTrue(() -> job.runs() >= 2);
                    future.cancel(false);
                }

                List<CheckIn> checkIns = telemetry.checkIns();
                assertEquals(List.of("in_progress", "error", "in_progress", "ok"), statuses(checkIns).subList(0, 4));

                SentryEvent event = telemetry.singleEvent();
                assertEquals(traceId(checkIns.get(1)), event.getContexts().getTrace().getTraceId());
                // Każde uruchomienie dostaje nowy trace.
                assertNotEquals(traceId(checkIns.get(1)), traceId(checkIns.get(3)));
                assertEquals("statement-import", event.getTag("operation"));
                assertEquals(List.of("bank-timeout", BankGateway.BANK_CODE), event.getFingerprints());
            }
        }
    }

    @Nested
    class Scenario3HangingJob {

        @Test
        void sdkHasNoTimeoutOrMissedStatus() {
            // Missed i timeout wylicza Sentry z konfiguracji monitora; SDK ich nie wysyła.
            assertEquals(List.of("IN_PROGRESS", "OK", "ERROR"),
                    Arrays.stream(CheckInStatus.values()).map(Enum::name).toList());
        }

        @Test
        void hangingRunLeavesOnlyInProgress() throws Exception {
            Thread hanging;
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                BankGateway bank = new BankGateway();
                bank.switchTo(Mode.HANGING);
                StatementImportJob job = new StatementImportJob(bank, "import");
                hanging = Thread.ofPlatform().daemon().start(() -> {
                    try {
                        job.importOnce();
                    } catch (Exception expectedAfterInterrupt) {
                    }
                });
                awaitTrue(() -> telemetry.checkIns().size() == 1);
                Thread.sleep(200);

                assertTrue(hanging.isAlive());
                assertEquals(List.of("in_progress"), statuses(telemetry.checkIns()));
            }
            // Sprzątanie po zamknięciu SDK, żeby przerwanie nie wysłało już check-inu ERROR.
            hanging.interrupt();
            hanging.join(2_000);
        }

        @Test
        void heartbeatSendsSingleOkWithConfigAndNothingWhileHanging() throws Exception {
            Thread hanging;
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                BankGateway bank = new BankGateway();
                StatementImportJob job = new StatementImportJob(bank, "import-heartbeat");

                job.importWithHeartbeat();
                bank.switchTo(Mode.HANGING);
                hanging = Thread.ofPlatform().daemon().start(() -> {
                    try {
                        job.importWithHeartbeat();
                    } catch (RuntimeException expectedAfterInterrupt) {
                    }
                });
                awaitTrue(() -> job.runs() == 2);
                Thread.sleep(200);

                List<CheckIn> checkIns = telemetry.checkIns();
                assertEquals(List.of("ok"), statuses(checkIns));
                assertTrue(checkIns.getFirst().getMonitorConfig() != null);
            }
            hanging.interrupt();
            hanging.join(2_000);
        }

        @Test
        void heartbeatRunGetsItsOwnTraceInsteadOfThreadTrace() {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                new StatementImportJob(new BankGateway(), "import-heartbeat").importWithHeartbeat();
                Sentry.captureMessage("inna operacja na tym samym wątku");

                assertNotEquals(traceId(telemetry.checkIns().getFirst()),
                        telemetry.singleEvent().getContexts().getTrace().getTraceId());
            }
        }

        @Test
        void manualCheckInWithoutNewTraceInheritsThreadTrace() {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                Sentry.captureCheckIn(new CheckIn("import-heartbeat", CheckInStatus.OK));
                Sentry.captureMessage("inna operacja na tym samym wątku");

                // Ten sam trace: Sentry powiązałby ten błąd z check-inem.
                assertEquals(traceId(telemetry.checkIns().getFirst()),
                        telemetry.singleEvent().getContexts().getTrace().getTraceId());
            }
        }
    }

    @Nested
    class Scenario4OverlappingRuns {

        @Test
        void sharedFieldAssignsBothResultsToSecondRunAndLeavesFirstOpen() throws Exception {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                runOverlapping(new PayoutBatchCheckIns("payout-batch", IdStorage.SHARED_FIELD));

                List<CheckIn> checkIns = telemetry.checkIns();
                SentryId first = checkIns.get(0).getCheckInId();
                SentryId second = checkIns.get(1).getCheckInId();
                assertEquals(List.of("in_progress", "in_progress", "ok", "error"), statuses(checkIns));
                assertEquals(second, checkIns.get(2).getCheckInId());
                assertEquals(second, checkIns.get(3).getCheckInId());
                assertTrue(checkIns.stream().skip(2).noneMatch(checkIn -> checkIn.getCheckInId().equals(first)));
            }
        }

        @Test
        void perExecutionStorageClosesEachRunWithItsOwnResultAndTrace() throws Exception {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                runOverlapping(new PayoutBatchCheckIns("payout-batch", IdStorage.PER_EXECUTION));

                List<CheckIn> checkIns = telemetry.checkIns();
                assertEquals(checkIns.get(0).getCheckInId(), checkIns.get(2).getCheckInId());
                assertEquals("ok", checkIns.get(2).getStatus());
                assertEquals(checkIns.get(1).getCheckInId(), checkIns.get(3).getCheckInId());
                assertEquals("error", checkIns.get(3).getStatus());
                assertEquals(traceId(checkIns.get(0)), traceId(checkIns.get(2)));
                assertNotEquals(traceId(checkIns.get(0)), traceId(checkIns.get(1)));
            }
        }

        private void runOverlapping(PayoutBatchCheckIns listener) throws Exception {
            try (ExecutorService replica1 = Executors.newSingleThreadExecutor();
                 ExecutorService replica2 = Executors.newSingleThreadExecutor()) {
                replica1.submit(() -> listener.beforeExecution(1)).get();
                replica2.submit(() -> listener.beforeExecution(2)).get();
                replica1.submit(() -> listener.afterExecution(1, false)).get();
                replica2.submit(() -> listener.afterExecution(2, true)).get();
            }
        }
    }

    @Nested
    class Scenario5StableSignalData {

        @Test
        void fingerprintFromMessageCreatesOneKeyPerPaymentWithoutTags() {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                PaymentsEndpoint endpoint = endpointWithBank(Mode.TIMING_OUT);
                endpoint.charge(new Payment("PAY-1", "kwiaciarnia-roza", "tok"), Reporting.CARELESS);
                endpoint.charge(new Payment("PAY-2", "rowery-kolo", "tok"), Reporting.CARELESS);

                List<SentryEvent> events = telemetry.events();
                assertNotEquals(events.get(0).getFingerprints(), events.get(1).getFingerprints());
                assertTrue(events.get(0).getFingerprints().get(1).contains("kwiaciarnia-roza"));
                assertNull(events.get(0).getTag("component"));
            }
        }

        @Test
        void classifiedTimeoutsShareFingerprintAndCarryStableTags() {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                PaymentsEndpoint endpoint = endpointWithBank(Mode.TIMING_OUT);
                assertEquals("RETRY_LATER", endpoint.charge(new Payment("PAY-1", "kwiaciarnia-roza", "tok"), Reporting.CLASSIFIED));
                endpoint.charge(new Payment("PAY-2", "kwiaciarnia-roza", "tok"), Reporting.CLASSIFIED);

                List<SentryEvent> events = telemetry.events();
                for (SentryEvent event : events) {
                    assertEquals(List.of("bank-timeout", BankGateway.BANK_CODE), event.getFingerprints());
                    assertEquals(Map.of("component", "payments", "operation", "charge", "bank", BankGateway.BANK_CODE),
                            event.getTags());
                    assertEquals(SentryLevel.ERROR, event.getLevel());
                }
                assertEquals(Map.of("payment_id", "PAY-2", "merchant", "kwiaciarnia-roza"), events.get(1).getContexts().get("bank_call"));
            }
        }
    }

    @Nested
    class Scenario6ExpectedErrors {

        @Test
        void carelessDeclineLooksLikeDefect() {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                endpointWithBank(Mode.AVAILABLE).charge(declinedPayment("PAY-1"), Reporting.CARELESS);

                SentryEvent event = telemetry.singleEvent();
                // Brak jawnego level: Sentry traktuje event z wyjątkiem jako error.
                assertNull(event.getLevel());
                assertNull(event.getTag("expected"));
            }
        }

        @Test
        void classifiedDeclineIsWarningTaggedExpectedAndGroupedByReason() {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                assertEquals("DECLINED", endpointWithBank(Mode.AVAILABLE)
                        .charge(declinedPayment("PAY-1"), Reporting.CLASSIFIED));

                SentryEvent event = telemetry.singleEvent();
                assertEquals(SentryLevel.WARNING, event.getLevel());
                assertEquals("true", event.getTag("expected"));
                assertEquals("insufficient_funds", event.getTag("decline.reason"));
                assertEquals(List.of("card-declined", "insufficient_funds"), event.getFingerprints());
            }
        }

        @Test
        void repeatedRequestIsReportedByBoundaryButDroppedByIgnoredType() {
            try (var telemetry = SentryTestSupport.start(PaymentTelemetry::configure)) {
                PaymentsEndpoint endpoint = endpointWithBank(Mode.AVAILABLE);
                Payment payment = new Payment("PAY-1", "kwiaciarnia-roza", "tok");
                endpoint.charge(payment, Reporting.CLASSIFIED);
                endpoint.charge(payment, Reporting.CLASSIFIED);

                assertTrue(telemetry.events().isEmpty());
            }
        }

        @Test
        void withoutFilterRepeatedRequestReachesSentry() {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                PaymentsEndpoint endpoint = endpointWithBank(Mode.AVAILABLE);
                Payment payment = new Payment("PAY-1", "kwiaciarnia-roza", "tok");
                endpoint.charge(payment, Reporting.CLASSIFIED);
                assertEquals("ERROR", endpoint.charge(payment, Reporting.CLASSIFIED));

                assertEquals("PaymentService$DuplicatePaymentException", telemetry.singleEvent().getExceptions().getLast().getType());
            }
        }

        @Test
        void ignoredExceptionTypeMatchesExactClassOnly() {
            try (var telemetry = SentryTestSupport.start(options ->
                    options.addIgnoredExceptionForType(IllegalStateException.class))) {
                Sentry.captureException(new IllegalStateException("odrzucony"));
                Sentry.captureException(new CancellationException("podklasa przechodzi"));

                assertEquals("CancellationException", telemetry.singleEvent().getExceptions().getLast().getType());
            }
        }

        @Test
        void broadIgnoredErrorsPatternAlsoDropsBankOutage() {
            try (var telemetry = SentryTestSupport.start(options -> options.setIgnoredErrors(List.of(".*Timeout.*")))) {
                endpointWithBank(Mode.TIMING_OUT).charge(new Payment("PAY-1", "kwiaciarnia-roza", "tok"), Reporting.CLASSIFIED);

                assertTrue(telemetry.events().isEmpty());
            }
        }
    }

    @Nested
    class Scenario7HealthEndpoint {

        @Test
        void liveIgnoresBankWhileHealthReportsItAndContinuesProbeTrace() throws Exception {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                BankGateway bank = new BankGateway();
                bank.switchTo(Mode.TIMING_OUT);
                SentryId probeTrace = new SentryId();
                String sentryTrace = probeTrace + "-" + new SpanId() + "-0";

                try (HealthEndpoint endpoint = new HealthEndpoint(bank, 0);
                     HttpClient http = HttpClient.newHttpClient()) {
                    assertEquals(200, get(http, endpoint, "/live", HealthEndpoint.uptimeProbeHeaders(sentryTrace)));
                    assertEquals(503, get(http, endpoint, "/health", HealthEndpoint.uptimeProbeHeaders(sentryTrace)));
                }

                SentryEvent event = telemetry.singleEvent();
                assertEquals(probeTrace, event.getContexts().getTrace().getTraceId());
                assertEquals("uptime-check", event.getTag("traffic"));
                assertEquals("health-check", event.getTag("operation"));
            }
        }

        @Test
        void healthyBankAnswers200AndUserTrafficIsTaggedSeparately() throws Exception {
            try (var telemetry = SentryTestSupport.start(options -> {})) {
                BankGateway bank = new BankGateway();
                try (HealthEndpoint endpoint = new HealthEndpoint(bank, 0);
                     HttpClient http = HttpClient.newHttpClient()) {
                    assertEquals(200, get(http, endpoint, "/health", Map.of()));
                    bank.switchTo(Mode.TIMING_OUT);
                    assertEquals(503, get(http, endpoint, "/health", Map.of()));
                }

                assertEquals("user", telemetry.singleEvent().getTag("traffic"));
            }
        }

        private int get(HttpClient http, HealthEndpoint endpoint, String path, Map<String, List<String>> headers)
                throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + endpoint.port() + path));
            headers.forEach((name, values) -> values.forEach(value -> request.header(name, value)));
            return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    private static PaymentsEndpoint endpointWithBank(Mode mode) {
        BankGateway bank = new BankGateway();
        bank.switchTo(mode);
        return new PaymentsEndpoint(new PaymentService(bank));
    }

    private static Payment declinedPayment(String id) {
        return new Payment(id, "kwiaciarnia-roza", BankGateway.CARD_WITHOUT_FUNDS);
    }

    private static List<String> statuses(List<CheckIn> checkIns) {
        return checkIns.stream().map(CheckIn::getStatus).toList();
    }

    private static SentryId traceId(CheckIn checkIn) {
        return checkIn.getContexts().getTrace().getTraceId();
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Warunek nie został spełniony w ciągu 5 s");
            }
            Thread.sleep(10);
        }
    }
}
