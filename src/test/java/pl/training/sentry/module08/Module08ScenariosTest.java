package pl.training.sentry.module08;

import io.sentry.BaggageHeader;
import io.sentry.Sentry;
import io.sentry.Session;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.TransactionContext;
import io.sentry.protocol.SentryId;
import io.sentry.protocol.SentryStackFrame;
import io.sentry.util.PropagationTargetsUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import pl.training.sentry.module08.DeploymentProfile.Capability;
import pl.training.sentry.module08.LabelPrinter.PrintJob;
import pl.training.sentry.module08.PrintStation.SessionHandling;
import pl.training.sentry.module08.ReadinessReport.ControlResult;
import pl.training.sentry.module08.ReadinessReport.Decision;
import pl.training.sentry.module08.ReadinessReport.Status;
import pl.training.sentry.module08.SentryEventApi.ReceivedEvent;
import pl.training.sentry.support.CapturedItem;
import pl.training.sentry.support.SentryTestSupport;
import pl.training.sentry.support.SentryTestSupport.CapturedTelemetry;

import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenariusze {@link Module08Demo} sprawdzone na prawdziwym SDK z transportem w pamięci.
 *
 * <p>Audyty czytają opcje SDK po inicjalizacji, więc testy inicjalizują SDK tą samą konfiguracją
 * co demo. Testy zachowań SDK, na których opierają się komentarze w kodzie modułu (wartości
 * domyślne, połykanie wyjątków w callbacku, kontynuacja trace, sesje), są przy scenariuszu,
 * którego dotyczą.</p>
 */
@ResourceLock("sentry-global-state")
class Module08ScenariosTest {

    private static final String SELF_HOSTED_DSN = "http://4a287873c890@localhost:9000/2";
    private static final String INCOMING_TRACE = "771a43a4192642f0b136d5159a501700-bb8f278130535c3c-1";

    @Nested
    class Scenario1LocalProfile {

        @Test
        void inheritedProjectDsnEnablesSdkInLocalProfileAndFailsDsnControl() {
            ReadinessReport report = startAndAudit(CheckoutDeployment.localAsFound(), CheckoutDeployment.LOCAL);

            assertEquals(Status.FAIL, report.status("DSN"));
            assertTrue(report.results().getFirst().detail().contains("o450812.ingest.de.sentry.io/4508127"));
            assertTrue(report.results().getFirst().detail().contains("environment=production"));
            assertEquals(Decision.NOT_READY, report.decision());
            report.results().stream().skip(1).forEach(result -> assertEquals(Status.NOT_APPLICABLE, result.status()));
        }

        @Test
        void eventFromLaptopWouldLookLikeProductionAndCarryHostName() {
            try (CapturedTelemetry telemetry = start(CheckoutDeployment.localAsFound())) {
                Sentry.captureException(new IllegalStateException("lokalny test"));

                SentryEvent event = telemetry.singleEvent();
                assertEquals("production", event.getEnvironment());
                assertNotNull(event.getServerName());
            }
        }

        @Test
        void emptyDsnDisablesSdkAndPassesDsnControl() {
            try (CapturedTelemetry telemetry = start(CheckoutDeployment.localFixed())) {
                assertFalse(Sentry.isEnabled());
                assertEquals(SentryId.EMPTY_ID, Sentry.captureException(new IllegalStateException("lokalny test")));

                ReadinessReport report = ReadinessAuditor.auditRunningSdk(CheckoutDeployment.LOCAL);
                assertEquals(Status.PASS, report.status("DSN"));
                assertEquals(Decision.READY, report.decision());
                assertTrue(telemetry.items().isEmpty());
            }
        }

        @Test
        void everyCapabilityMustBeExplicitlyActiveOrExcluded() {
            assertThrows(IllegalArgumentException.class, () -> new DeploymentProfile("checkout-api", "local", "",
                    "r", "pl.acme", 0.0, Set.of(Capability.TRACING), Set.of()));

            DeploymentProfile withoutLogs = new DeploymentProfile("checkout-api", "staging", CheckoutDeployment.PROJECT_DSN,
                    CheckoutDeployment.BUILD_RELEASE, "pl.acme.checkout", 0.2,
                    EnumSet.complementOf(EnumSet.of(Capability.SENTRY_LOGS)), Set.of(Capability.SENTRY_LOGS));
            assertEquals(Status.NOT_APPLICABLE, startAndAudit(CheckoutDeployment.stagingFixed(), withoutLogs).status("LOGS"));
        }

        @Test
        void missingDsnKeyBecomesEmptyDsnBecauseNullDsnFailsInit() {
            assertEquals("", SentryConfiguration.from(Map.of()).dsn());

            assertThrows(IllegalArgumentException.class, () -> Sentry.init(options -> options.setDsn(null)));
            Sentry.close();
        }
    }

    @Nested
    class Scenario2StagingAudit {

        @Test
        void foundConfigurationFailsEveryProblemFromReview() {
            ReadinessReport report = startAndAudit(CheckoutDeployment.stagingAsFound(), CheckoutDeployment.STAGING);

            Map<String, Status> expected = new LinkedHashMap<>();
            expected.put("DSN", Status.PASS);
            expected.put("ENVIRONMENT", Status.FAIL);
            expected.put("RELEASE", Status.FAIL);
            expected.put("ERROR_SAMPLING", Status.FAIL);
            expected.put("TRACES_SAMPLING", Status.FAIL);
            expected.put("DEFAULT_PII", Status.FAIL);
            expected.put("IN_APP", Status.PASS);
            expected.put("TRACE_PROPAGATION", Status.FAIL);
            expected.put("TRACE_CONTINUATION", Status.PASS);
            expected.put("LOGS", Status.PASS);
            expected.put("DELIVERY", Status.NOT_VERIFIED);
            assertEquals(expected, statuses(report));
            assertEquals(Decision.NOT_READY, report.decision());
        }

        @Test
        void fixedConfigurationIsNotReadyWithoutDeliveryEvidence() {
            ReadinessReport report = startAndAudit(CheckoutDeployment.stagingFixed(), CheckoutDeployment.STAGING);

            report.results().stream()
                    .filter(result -> !result.control().equals("DELIVERY"))
                    .forEach(result -> assertEquals(Status.PASS, result.status(), result.control()));
            assertEquals(Decision.INSUFFICIENT_EVIDENCE, report.decision());

            ReadinessReport withEvidence = report.with(new ControlResult("DELIVERY", Status.PASS, "event odczytany"));
            assertEquals(Decision.READY, withEvidence.decision());
        }

        @Test
        void approvedProfileWithDisabledSdkFailsDsnAndCannotVerifyTheRest() {
            ReadinessReport report = startAndAudit(CheckoutDeployment.localFixed(), CheckoutDeployment.STAGING);

            assertEquals(Status.FAIL, report.status("DSN"));
            assertEquals(Status.NOT_VERIFIED, report.status("ENVIRONMENT"));
            assertEquals(Decision.NOT_READY, report.decision());
        }

        @Test
        void missingEnvironmentIsReportedAsProductionByTheSdk() {
            try (CapturedTelemetry telemetry = start(CheckoutDeployment.stagingAsFound())) {
                assertEquals("production", Sentry.getCurrentScopes().getOptions().getEnvironment());
            }
        }

        @Test
        void exceptionInInitCallbackIsSwallowedAndLeavesPartialOptions() {
            try (CapturedTelemetry telemetry = SentryTestSupport.start(options -> {
                options.setEnvironment(null);
                options.setSampleRate(1.5);
                options.setEnvironment("staging");
            })) {
                assertTrue(Sentry.isEnabled());
                assertEquals("production", Sentry.getCurrentScopes().getOptions().getEnvironment());
            }
            assertThrows(IllegalArgumentException.class,
                    () -> SentryConfiguration.from(Map.of("sentry.sample-rate", "1.5")));
            assertThrows(NumberFormatException.class,
                    () -> SentryConfiguration.from(Map.of("sentry.traces-sample-rate", "10%")));
        }

        @Test
        void sampleRateDropsErrorEventsAndDoesNotEnableTracing() {
            try (CapturedTelemetry telemetry = SentryTestSupport.start(options -> options.setSampleRate(0.0))) {
                assertEquals(SentryId.EMPTY_ID, Sentry.captureException(new IllegalStateException("błąd")));
                assertTrue(telemetry.events().isEmpty());
                assertFalse(Sentry.getCurrentScopes().getOptions().isTracingEnabled());
            }
        }

        @Test
        void defaultPropagationTargetsMatchPaymentProviderAndBaggageCarriesReleaseAndEnvironment() {
            try (CapturedTelemetry telemetry = start(CheckoutDeployment.stagingAsFound())) {
                List<String> targets = Sentry.getCurrentScopes().getOptions().getTracePropagationTargets();
                assertEquals(List.of(".*"), targets);
                assertTrue(PropagationTargetsUtils.contain(targets, "https://secure.payu.com/api/v2_1/orders"));

                BaggageHeader baggage = Sentry.getBaggage();
                assertNotNull(baggage);
                assertTrue(baggage.getValue().contains("sentry-release=checkout-api%404.2.1"), baggage.getValue());
                assertTrue(baggage.getValue().contains("sentry-environment=production"), baggage.getValue());
                assertTrue(baggage.getValue().contains("sentry-public_key=7c3b9f2e41d84a6f"), baggage.getValue());
            }
        }

        @Test
        void fixedPropagationTargetsCoverOnlyInternalServices() {
            List<String> targets = SentryConfiguration.from(CheckoutDeployment.stagingFixed()).tracePropagationTargets();

            assertTrue(PropagationTargetsUtils.contain(targets, "https://inventory.internal.acme.pl/api/stock"));
            assertFalse(PropagationTargetsUtils.contain(targets, "https://secure.payu.com/api/v2_1/orders"));
        }

        @Test
        void withoutInAppIncludesApplicationFramesHaveNoInAppFlag() {
            try (CapturedTelemetry telemetry = SentryTestSupport.start(options -> {})) {
                Sentry.captureException(new IllegalStateException("bez in-app"));
                assertNull(testFrame(telemetry.singleEvent()).isInApp());
            }
            try (CapturedTelemetry telemetry = SentryTestSupport.start(
                    options -> options.addInAppInclude("pl.training.sentry.module08"))) {
                Sentry.captureException(new IllegalStateException("z in-app"));
                assertEquals(Boolean.TRUE, testFrame(telemetry.singleEvent()).isInApp());
            }
        }

        @Test
        void orgIdComesFromSentryIoDsnButNotFromSelfHostedDsn() {
            Map<String, String> selfHosted = new HashMap<>(CheckoutDeployment.stagingFixed());
            selfHosted.put("sentry.dsn", SELF_HOSTED_DSN);
            DeploymentProfile selfHostedProfile = withDsn(CheckoutDeployment.STAGING, SELF_HOSTED_DSN);

            ReadinessReport report = startAndAudit(selfHosted, selfHostedProfile);
            assertEquals(Status.FAIL, report.status("TRACE_CONTINUATION"));

            selfHosted.put("sentry.org-id", "1");
            assertEquals(Status.PASS, startAndAudit(selfHosted, selfHostedProfile).status("TRACE_CONTINUATION"));

            ReadinessReport sentryIo = startAndAudit(CheckoutDeployment.stagingFixed(), CheckoutDeployment.STAGING);
            assertTrue(sentryIo.results().stream().anyMatch(result -> result.detail().contains("orgId=450812")));
        }

        @Test
        void withoutOrgIdForeignOrganizationTraceIsContinued() {
            TransactionContext context = continueIncomingTrace(options -> {
                options.setDsn(SELF_HOSTED_DSN);
                options.setStrictTraceContinuation(false);
            }, "sentry-org_id=999");

            assertEquals(INCOMING_TRACE.substring(0, 32), context.getTraceId().toString());
        }

        @Test
        void withOrgIdButWithoutStrictModeTraceWithoutOrgIdIsContinuedWithItsSamplingDecision() {
            TransactionContext context = continueIncomingTrace(options -> {
                options.setOrgId("450812");
                options.setStrictTraceContinuation(false);
            }, "sentry-sample_rate=1");

            assertEquals(INCOMING_TRACE.substring(0, 32), context.getTraceId().toString());
            assertEquals(Boolean.TRUE, context.getParentSampled());
        }

        @Test
        void strictModeWithOrgIdStartsNewTraceWhenOrgIdIsMissingOrDifferent() {
            for (String baggage : List.of("sentry-sample_rate=1", "sentry-org_id=999")) {
                TransactionContext context = continueIncomingTrace(options -> {
                    options.setOrgId("450812");
                    options.setStrictTraceContinuation(true);
                }, baggage);

                assertNotEquals(INCOMING_TRACE.substring(0, 32), context.getTraceId().toString(), baggage);
            }
        }

        @Test
        void disabledLogsMakeSentryLoggerSilent() {
            for (boolean enabled : List.of(false, true)) {
                try (CapturedTelemetry telemetry = SentryTestSupport.start(options -> options.getLogs().setEnabled(enabled))) {
                    Sentry.logger().info("Wydruk etykiety %s", "ORD-1");
                    telemetry.flush();

                    assertEquals(enabled ? 1 : 0, telemetry.logs().size());
                }
            }
        }

        @Test
        void sendDefaultPiiMakesSdkAttachUserIpAddress() {
            for (boolean sendDefaultPii : List.of(false, true)) {
                try (CapturedTelemetry telemetry = SentryTestSupport.start(options -> options.setSendDefaultPii(sendDefaultPii))) {
                    Sentry.captureException(new IllegalStateException("pii"));

                    String ip = telemetry.singleEvent().getUser().getIpAddress();
                    assertEquals(sendDefaultPii ? "{{auto}}" : null, ip);
                }
            }
        }
    }

    @Nested
    class Scenario3AcceptanceProbe {

        @Test
        void probeIsTaggedGroupedAndLowLevel() {
            try (CapturedTelemetry telemetry = SentryTestSupport.start(options -> options.addInAppInclude("pl.training.sentry"))) {
                SentryId id = AcceptanceProbe.send("deploy-1");

                SentryEvent event = telemetry.singleEvent();
                assertEquals(id, event.getEventId());
                assertEquals("true", event.getTag(AcceptanceProbe.SYNTHETIC_TAG));
                assertEquals(List.of("acceptance-probe"), event.getFingerprints());
                assertEquals(SentryLevel.INFO, event.getLevel());
                assertEquals(Map.of("deployment_id", "deploy-1"), event.getContexts().get("acceptance"));
                assertTrue(event.getExceptions().getLast().getStacktrace().getFrames().stream()
                        .anyMatch(frame -> Boolean.TRUE.equals(frame.isInApp())));
            }
        }

        @Test
        void sdkReturnsEmptyIdWhenEventIsDroppedLocally() {
            List<Consumer<io.sentry.SentryOptions>> droppingConfigurations = List.of(
                    options -> options.setSampleRate(0.0),
                    options -> options.setBeforeSend((event, hint) -> null),
                    options -> options.setDsn(""));
            for (Consumer<io.sentry.SentryOptions> configuration : droppingConfigurations) {
                try (CapturedTelemetry telemetry = SentryTestSupport.start(configuration)) {
                    SentryId id = AcceptanceProbe.send("deploy-2");

                    assertEquals(SentryId.EMPTY_ID, id);
                    assertEquals(Status.FAIL, AcceptanceProbe.verify(id, "r", "e", Optional.empty()).status());
                }
            }
        }

        @Test
        void deliveryIsNotVerifiedWithoutReadingFromSentry() {
            SentryId id = new SentryId();

            assertEquals(Status.NOT_VERIFIED, AcceptanceProbe.verify(id, "r", "e", Optional.empty()).status());
            AcceptanceProbe.EventLookup failing = eventId -> {
                throw new IllegalStateException("API Sentry odpowiedziało 401");
            };
            assertEquals(Status.NOT_VERIFIED, AcceptanceProbe.verify(id, "r", "e", Optional.of(failing)).status());
        }

        @Test
        void deliveryPassesOnlyForEventWithDeployedReleaseEnvironmentAndInAppFrames() {
            SentryId id = new SentryId();

            assertEquals(Status.FAIL, verifyAgainst(id, Optional.empty()));
            assertEquals(Status.FAIL, verifyAgainst(id, Optional.of(new ReceivedEvent("app@1.0.0", "production", "true", 3))));
            assertEquals(Status.FAIL, verifyAgainst(id, Optional.of(new ReceivedEvent("app@1.0.0+7", "staging", "true", 0))));
            assertEquals(Status.PASS, verifyAgainst(id, Optional.of(new ReceivedEvent("app@1.0.0+7", "staging", "true", 3))));
        }

        @Test
        void apiResponseIsReadFromTagsAndExceptionFrames() {
            SentryEventApi api = new SentryEventApi("http://localhost:9000", "sentry", "sentry-training", "t", Duration.ZERO);
            String body = """
                    {"eventID": "9c029cb526064074846a78250be160bc",
                     "tags": [{"key": "environment", "value": "training"},
                              {"key": "release", "value": "sentry-training@1.0.0"},
                              {"key": "synthetic", "value": "true"}],
                     "entries": [{"type": "exception", "data": {"values": [{"stacktrace": {"frames": [
                         {"module": "org.codehaus.mojo.exec.ExecJavaMojo", "inApp": false},
                         {"module": "pl.training.sentry.module08.Module08Demo", "inApp": true},
                         {"module": "pl.training.sentry.module08.AcceptanceProbe", "inApp": true}]}}]}}]}
                    """;

            assertEquals(new ReceivedEvent("sentry-training@1.0.0", "training", "true", 2), api.parse(body));
        }

        @Test
        void apiIsConfiguredOnlyWithDsnAndToken() {
            assertTrue(SentryEventApi.fromEnvironment(Map.of("SENTRY_DSN", SELF_HOSTED_DSN)).isEmpty());
            assertTrue(SentryEventApi.fromEnvironment(Map.of("SENTRY_AUTH_TOKEN", "t")).isEmpty());
            assertTrue(SentryEventApi.fromEnvironment(Map.of("SENTRY_DSN", SELF_HOSTED_DSN, "SENTRY_AUTH_TOKEN", "t")).isPresent());
        }

        private Status verifyAgainst(SentryId id, Optional<ReceivedEvent> received) {
            AcceptanceProbe.EventLookup lookup = eventId -> received;
            return AcceptanceProbe.verify(id, "app@1.0.0+7", "staging", Optional.of(lookup)).status();
        }
    }

    @Nested
    class Scenario4ReleaseHealth {

        private static final List<PrintJob> THREE_JOBS = List.of(
                new PrintJob("ORD-1", "ETYKIETA/{order}"),
                new PrintJob("ORD-2", "ETYKIETA/{order}"),
                new PrintJob("ORD-3", "ETYKIETA/{order}"));
        private static final List<PrintJob> WITH_MISSING_TEMPLATE = List.of(
                new PrintJob("ORD-4", "ETYKIETA/{order}"),
                new PrintJob("ORD-5", null));

        @Test
        void javaSdkDoesNotStartSessionsByItself() {
            try (CapturedTelemetry telemetry = SentryTestSupport.start(options -> {})) {
                assertTrue(Sentry.getCurrentScopes().getOptions().isEnableAutoSessionTracking());
                Sentry.captureException(new IllegalStateException("bez sesji"));

                assertTrue(telemetry.sessionUpdates().isEmpty());
            }
        }

        @Test
        void sessionIsNotStartedWithoutRelease() {
            try (CapturedTelemetry telemetry = SentryTestSupport.start(options -> options.setRelease(null))) {
                Sentry.startSession();
                Sentry.endSession();

                assertTrue(telemetry.sessionUpdates().isEmpty());
            }
        }

        @Test
        void handledErrorEndsSessionAsExitedWithErrorCountAndInstallationId() throws InterruptedException {
            try (CapturedTelemetry telemetry = startStation()) {
                new PrintStation(new LabelPrinter(2)).runShiftInBackground(THREE_JOBS, SessionHandling.END_ON_NORMAL_EXIT);

                List<Session> sessions = telemetry.sessionUpdates();
                assertTrue(sessions.getFirst().getInit());
                assertEquals(Session.State.Ok, sessions.getFirst().getStatus());
                assertEquals(Session.State.Exited, sessions.getLast().getStatus());
                assertEquals(1, sessions.getLast().errorCount());
                assertEquals("station-waw-07", sessions.getLast().getDistinctId());
                assertEquals(SentryTestSupport.TEST_RELEASE, sessions.getLast().getRelease());
            }
        }

        @Test
        void crashMarksSessionCrashedTogetherWithFatalEvent() throws InterruptedException {
            try (CapturedTelemetry telemetry = startStation()) {
                new PrintStation(new LabelPrinter(100)).runShiftInBackground(WITH_MISSING_TEMPLATE, SessionHandling.END_ON_NORMAL_EXIT);

                assertEquals(SentryLevel.FATAL, telemetry.singleEvent().getLevel());
                assertEquals(Session.State.Crashed, telemetry.sessionUpdates().getLast().getStatus());
                assertEquals(List.of("session", "event", "session"), itemTypes(telemetry));
            }
        }

        @Test
        void endSessionInFinallyHidesCrashFromReleaseHealth() throws InterruptedException {
            try (CapturedTelemetry telemetry = startStation()) {
                new PrintStation(new LabelPrinter(100)).runShiftInBackground(WITH_MISSING_TEMPLATE, SessionHandling.END_IN_FINALLY);

                assertEquals(SentryLevel.FATAL, telemetry.singleEvent().getLevel());
                List<Session> sessions = telemetry.sessionUpdates();
                assertEquals(Session.State.Exited, sessions.getLast().getStatus());
                assertEquals(0, sessions.getLast().errorCount());
                assertTrue(sessions.stream().noneMatch(session -> session.getStatus() == Session.State.Crashed));
                // Event crasha przychodzi po zakończonej sesji i nie niesie jej aktualizacji.
                assertEquals(List.of("session", "session", "event"), itemTypes(telemetry));
            }
        }

        private CapturedTelemetry startStation() {
            return SentryTestSupport.start(options -> {
                PrintStation.configure(options, "station-waw-07");
                options.setEnableUncaughtExceptionHandler(true);
            });
        }

        private List<String> itemTypes(CapturedTelemetry telemetry) {
            return telemetry.items().stream()
                    .map(item -> item instanceof CapturedItem.SessionUpdate ? "session" : "event")
                    .toList();
        }
    }

    private static CapturedTelemetry start(Map<String, String> properties) {
        SentryConfiguration configuration = SentryConfiguration.from(properties);
        return SentryTestSupport.start(configuration::applyTo);
    }

    private static ReadinessReport startAndAudit(Map<String, String> properties, DeploymentProfile profile) {
        try (CapturedTelemetry telemetry = start(properties)) {
            return ReadinessAuditor.auditRunningSdk(profile);
        }
    }

    private static Map<String, Status> statuses(ReadinessReport report) {
        Map<String, Status> statuses = new LinkedHashMap<>();
        report.results().forEach(result -> statuses.put(result.control(), result.status()));
        return statuses;
    }

    private static DeploymentProfile withDsn(DeploymentProfile profile, String dsn) {
        return new DeploymentProfile(profile.service(), profile.environment(), dsn, profile.release(),
                profile.inAppPackage(), profile.maxTracesSampleRate(), profile.active(), profile.excluded());
    }

    private static TransactionContext continueIncomingTrace(Consumer<io.sentry.SentryOptions> configuration, String baggage) {
        try (CapturedTelemetry telemetry = SentryTestSupport.start(options -> {
            options.setTracesSampleRate(1.0);
            configuration.accept(options);
        })) {
            return Sentry.continueTrace(INCOMING_TRACE, List.of(baggage));
        }
    }

    /** Ramka metody testowej, która utworzyła wyjątek. */
    private static SentryStackFrame testFrame(SentryEvent event) {
        return event.getExceptions().getLast().getStacktrace().getFrames().stream()
                .filter(frame -> frame.getModule().startsWith("pl.training.sentry.module08"))
                .findFirst()
                .orElseThrow();
    }
}
