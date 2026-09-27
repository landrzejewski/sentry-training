package pl.training.sentry.module02;

import io.sentry.Hint;
import io.sentry.ITransportFactory;
import io.sentry.Sentry;
import io.sentry.SentryEnvelope;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryId;
import io.sentry.protocol.SentryStackFrame;
import io.sentry.protocol.User;
import io.sentry.transport.ITransport;
import io.sentry.transport.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import pl.training.sentry.module02.CheckoutEndpoint.CheckoutResponse;
import pl.training.sentry.module02.spring.CheckoutApplication;
import pl.training.sentry.module02.spring.CheckoutWebClient;
import pl.training.sentry.module02.spring.SentryPrivacyConfiguration;
import pl.training.sentry.support.CapturedItem;
import pl.training.sentry.support.EnvelopeDecoder;
import pl.training.sentry.support.SentryTestSupport;
import pl.training.sentry.support.TrainingSentry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenariusze {@link Module02Demo} sprawdzone na opcjach SDK i na treści eventów, które SDK
 * przekazało do transportu.
 *
 * <p>Każda klasa zagnieżdżona odpowiada jednemu scenariuszowi. Testy konfiguratora działają na
 * zwykłym {@code SentryOptions}, bez {@code Sentry.init}, więc nie zależą od globalnego stanu SDK.
 * Pozostałe uruchamiają prawdziwe SDK z transportem w pamięci, a testy Spring Boot prawdziwą aplikację
 * ze starterem, więc asercje dotyczą danych po przejściu przez integracje, filtry i {@code beforeSend}.</p>
 */
@ResourceLock("sentry-global-state")
class Module02ScenariosTest {

    private static final String DSN = "https://public@example.invalid/1";
    private static final String RELEASE = "checkout-api@4.12.0+8f24c7a";
    private static final String EMPLOYEE_TOKEN = "emp-1";
    private static final String PROBE_SECRET = "probe-secret";

    private final SentryOptionsConfigurer configurer = new SentryOptionsConfigurer();
    private final TrafficClassifier traffic = new TrafficClassifier(Set.of(EMPLOYEE_TOKEN), PROBE_SECRET);

    @Nested
    class Scenario1ValidatedContract {

        @Test
        void configuresStagingBaselineWithoutSentryInit() {
            SentryOptions options = new SentryOptions();

            configurer.configure(options, SentrySettings.fromEnvironment(deployment("staging")));

            assertTrue(options.isEnabled());
            assertEquals(DSN, options.getDsn());
            assertEquals("staging", options.getEnvironment());
            assertEquals(RELEASE, options.getRelease());
            assertNull(options.getSampleRate());
            assertFalse(options.isSendDefaultPii());
            assertEquals("checkout-api", options.getTags().get("service.name"));
            assertTrue(options.getInAppIncludes().contains(SentryOptionsConfigurer.IN_APP_PACKAGE));
            assertTrue(options.getIgnoredExceptionsForType().contains(ClientAbortedException.class));
            assertInstanceOf(FilteringBeforeSend.class, options.getBeforeSend());
            assertFalse(Sentry.isEnabled(), "konfigurator nie może inicjalizować SDK");
        }

        @Test
        void stageComesFromClosedVocabulary() {
            assertEquals(DeploymentStage.PRODUCTION, DeploymentStage.fromConfig("Production"));
            assertEquals(DeploymentStage.STAGING, DeploymentStage.fromConfig(" staging "));
            assertEquals("development", DeploymentStage.LOCAL.sentryEnvironment());

            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> SentrySettings.fromEnvironment(with(deployment("staging"), "APP_STAGE", "prod")));
            assertTrue(rejected.getMessage().contains("nieznany APP_STAGE 'prod'"));
        }

        @Test
        void emptyDsnOutsideLocalFailsFast() {
            assertThrows(IllegalArgumentException.class,
                    () -> SentrySettings.fromEnvironment(with(deployment("staging"), "SENTRY_DSN", " ")));
        }

        @Test
        void sdkWithEmptyDsnSilentlyDisablesItself() {
            // Zachowanie SDK, przed którym chroni reguła kontraktu: brak wyjątku, brak wysyłki.
            Sentry.init(options -> options.setDsn(""));

            assertFalse(Sentry.isEnabled());
            Sentry.close();
        }

        @Test
        void enabledSdkWithoutDsnFailsOnInit() {
            assertThrows(IllegalArgumentException.class, () -> Sentry.init(options -> options.setDsn(null)));
            assertFalse(Sentry.isEnabled());
        }

        @Test
        void releaseMustIdentifyArtifactOfThisService() {
            assertEquals(RELEASE, SentrySettings.fromEnvironment(deployment("production")).release());

            for (String invalid : List.of("4.12.0", "checkout-api@", "checkout-api@latest", "checkout-api@LATEST",
                    "checkout-api@4.12.0 rc", "checkout-api@feature/login", "checkout-api@" + "1".repeat(200))) {
                assertThrows(IllegalArgumentException.class,
                        () -> SentrySettings.fromEnvironment(with(deployment("production"), "SENTRY_RELEASE", invalid)),
                        invalid);
            }
            assertThrows(IllegalArgumentException.class,
                    () -> SentrySettings.fromEnvironment(without(deployment("production"), "SENTRY_RELEASE")));
        }

        @Test
        void sampleRateIsNullOrWithinClosedRange() {
            assertNull(SentrySettings.fromEnvironment(deployment("production")).errorSampleRate());
            assertEquals(0.0, SentrySettings.fromEnvironment(
                    with(deployment("production"), "SENTRY_SAMPLE_RATE", "0.0")).errorSampleRate());
            assertEquals(1.0, SentrySettings.fromEnvironment(
                    with(deployment("production"), "SENTRY_SAMPLE_RATE", "1.0")).errorSampleRate());

            for (String invalid : List.of("1.5", "-0.1", "NaN", "25%")) {
                assertThrows(IllegalArgumentException.class, () -> SentrySettings.fromEnvironment(
                        with(deployment("production"), "SENTRY_SAMPLE_RATE", invalid)), invalid);
            }
        }

        @Test
        void allViolationsAreReportedTogether() {
            Map<String, String> env = with(with(deployment("staging"), "SENTRY_RELEASE", "4.12.0"), "SENTRY_SAMPLE_RATE", "25%");

            String message = assertThrows(IllegalArgumentException.class, () -> SentrySettings.fromEnvironment(env))
                    .getMessage();

            assertTrue(message.contains("SENTRY_RELEASE '4.12.0'"), message);
            assertTrue(message.contains("SENTRY_SAMPLE_RATE '25%'"), message);
        }

        @Test
        void controlEventShowsEnvironmentReleaseServiceTagAndInAppFrames() {
            SentrySettings staging = SentrySettings.fromEnvironment(deployment("staging"));
            try (var telemetry = SentryTestSupport.start(options -> configurer.configure(options, staging))) {
                DeploymentSmokeCheck.sendControlEvent();

                SentryEvent event = telemetry.singleEvent();
                assertEquals("staging", event.getEnvironment());
                assertEquals(RELEASE, event.getRelease());
                assertEquals("checkout-api", event.getTag("service.name"));
                assertEquals("true", event.getTag("deployment.check"));
                List<SentryStackFrame> appFrames = frames(event).stream()
                        .filter(frame -> frame.getModule().startsWith(SentryOptionsConfigurer.IN_APP_PACKAGE))
                        .toList();
                assertFalse(appFrames.isEmpty());
                assertTrue(appFrames.stream().allMatch(frame -> Boolean.TRUE.equals(frame.isInApp())));
            }
        }
    }

    @Nested
    class Scenario2DeveloperLaptop {

        @Test
        void localStageDisablesSdkEvenWithDsnFromDotEnv() {
            SentrySettings local = SentrySettings.fromEnvironment(Map.of("APP_STAGE", "local", "SENTRY_DSN", DSN));
            assertFalse(local.enabled());
            assertNull(local.dsn());
            assertEquals(1, local.warnings().size());

            try (var telemetry = SentryTestSupport.start(options -> configurer.configure(options, local))) {
                assertFalse(Sentry.isEnabled());
                CheckoutResponse response = endpoint(PaymentGateway.resettingConnection())
                        .handle(customer(), "ORD-1", 100, ResponseChannel.open());

                assertEquals(SentryId.EMPTY_ID, response.sentryEventId());
                assertTrue(telemetry.events().isEmpty());
            }
        }

        @Test
        void sdkWithoutExplicitEnvironmentReportsProduction() {
            try (var telemetry = SentryTestSupport.start(options -> {
                options.setEnvironment(null);
                options.setRelease(null);
            })) {
                endpoint(PaymentGateway.resettingConnection()).handle(customer(), "ORD-1", 100, ResponseChannel.open());

                SentryEvent event = telemetry.singleEvent();
                assertEquals("production", event.getEnvironment());
                assertNull(event.getRelease());
                // Bez beforeSend dane requestu wychodzą w takiej postaci, w jakiej trafiły do scope.
                assertNotNull(event.getRequest().getHeaders().get("Authorization"));
            }
        }
    }

    @Nested
    class Scenario3SampleRate {

        @Test
        void beforeSendRunsForEventsThatSamplingThenDrops() {
            AtomicInteger beforeSendCalls = new AtomicInteger();
            SentrySettings settings = SentrySettings.fromEnvironment(
                    with(deployment("production"), "SENTRY_SAMPLE_RATE", "0.0"));
            try (var telemetry = SentryTestSupport.start(options -> {
                configurer.configure(options, settings);
                SentryOptions.BeforeSendCallback configured = options.getBeforeSend();
                options.setBeforeSend((event, hint) -> {
                    beforeSendCalls.incrementAndGet();
                    return configured.execute(event, hint);
                });
            })) {
                CheckoutEndpoint endpoint = endpoint(PaymentGateway.resettingConnection());
                for (int i = 0; i < 5; i++) {
                    assertFalse(endpoint.handle(customer(), "ORD-" + i, 100, ResponseChannel.open()).eventSent());
                }

                assertEquals(5, beforeSendCalls.get());
                assertTrue(telemetry.events().isEmpty());
            }
        }

        @Test
        void withoutSampleRateEveryEventIsSent() {
            SentrySettings settings = SentrySettings.fromEnvironment(deployment("production"));
            try (var telemetry = SentryTestSupport.start(options -> configurer.configure(options, settings))) {
                CheckoutEndpoint endpoint = endpoint(PaymentGateway.resettingConnection());
                for (int i = 0; i < 5; i++) {
                    assertTrue(endpoint.handle(customer(), "ORD-" + i, 100, ResponseChannel.open()).eventSent());
                }

                assertEquals(5, telemetry.events().size());
            }
        }
    }

    @Nested
    class Scenario4FilterLayers {

        @Test
        void exactTypeFilterDropsClientAbort() {
            try (var telemetry = startProduction(options -> {})) {
                CheckoutResponse response = endpoint(PaymentGateway.available())
                        .handle(customer(), "ORD-1", 100, ResponseChannel.abortedByClient());

                assertEquals(500, response.status());
                assertEquals(SentryId.EMPTY_ID, response.sentryEventId());
                assertTrue(telemetry.events().isEmpty());
            }
        }

        @Test
        void typeFilterIgnoresSubclassesAndCauseChain() {
            try (var telemetry = startProduction(options -> {})) {
                ClientAbortedException abort = new ClientAbortedException("klient zamknął połączenie", null);
                Sentry.captureException(new ClientAbortedException("podklasa", null) {
                });
                Sentry.captureException(new IllegalStateException("Nie udało się wysłać potwierdzenia", abort));

                assertEquals(2, telemetry.events().size());
            }
        }

        @Test
        void rawSocketExceptionFromClientIsReportedAsNoise() {
            try (var telemetry = startProduction(options -> {})) {
                endpoint(PaymentGateway.available()).handle(customer(), "ORD-1", 100, ResponseChannel.resetByClient());

                assertEquals("SocketException", outermostException(telemetry.singleEvent()).getType());
            }
        }

        @Test
        void wideTextRuleAlsoDropsGatewayOutage() {
            try (var telemetry = startProduction(options -> options.addIgnoredError(".*Connection reset.*"))) {
                CheckoutResponse client = endpoint(PaymentGateway.available())
                        .handle(customer(), "ORD-1", 100, ResponseChannel.resetByClient());
                CheckoutResponse gateway = endpoint(PaymentGateway.resettingConnection())
                        .handle(customer(), "ORD-2", 100, ResponseChannel.open());

                assertFalse(client.eventSent());
                assertFalse(gateway.eventSent());
                assertTrue(telemetry.events().isEmpty());
            }
        }

        @Test
        void textRuleMustMatchWholeThrowableText() {
            try (var telemetry = startProduction(options -> options.addIgnoredError("Connection reset"))) {
                endpoint(PaymentGateway.available()).handle(customer(), "ORD-1", 100, ResponseChannel.resetByClient());

                assertEquals(1, telemetry.events().size());
            }
            try (var telemetry = startProduction(options ->
                    options.addIgnoredError("java.net.SocketException: Connection reset"))) {
                endpoint(PaymentGateway.available()).handle(customer(), "ORD-1", 100, ResponseChannel.resetByClient());

                assertTrue(telemetry.events().isEmpty());
            }
        }
    }

    @Nested
    class Scenario5BeforeSendClassification {

        @Test
        void customerErrorIsSentWithoutQueryAndSecrets() {
            try (var telemetry = startProduction(options -> {})) {
                endpoint(PaymentGateway.resettingConnection()).handle(customer(), "ORD-1", 100, ResponseChannel.open());

                SentryEvent event = telemetry.singleEvent();
                Request request = event.getRequest();
                assertEquals("https://checkout.example.com/api/checkout", request.getUrl());
                assertNull(request.getQueryString());
                assertEquals(Map.of("Content-Type", "application/json", "User-Agent", "checkout-web/5.2"),
                        request.getHeaders());
                // Scrubber nie rusza tego, co potrzebne do diagnozy.
                assertEquals("external", event.getTag("traffic.origin"));
                assertEquals("ORD-1", checkoutContext(event).get("order_id"));
                assertFalse(frames(event).isEmpty());
            }
        }

        @Test
        void employeeAndConfirmedProbeAreDropped() {
            try (var telemetry = startProduction(options -> {})) {
                CheckoutEndpoint endpoint = endpoint(PaymentGateway.resettingConnection());

                assertFalse(endpoint.handle(employee(), "ORD-1", 100, ResponseChannel.open()).eventSent());
                assertFalse(endpoint.handle(probe("/internal/synthetic-check", PROBE_SECRET), "SYN-1", 100,
                        ResponseChannel.open()).eventSent());
                assertFalse(endpoint.handle(probe("/internal/synthetic-check/payments", PROBE_SECRET), "SYN-2", 100,
                        ResponseChannel.open()).eventSent());
                assertTrue(telemetry.events().isEmpty());
            }
        }

        @Test
        void pathAloneOrTagAloneIsNotEnoughToDrop() {
            try (var telemetry = startProduction(options -> {})) {
                CheckoutEndpoint endpoint = endpoint(PaymentGateway.resettingConnection());
                endpoint.handle(probe("/internal/synthetic-check/payments", null), "SYN-1", 100, ResponseChannel.open());
                endpoint.handle(probe("/internal/synthetic-check/payments", "zgadnięty-sekret"), "SYN-2", 100,
                        ResponseChannel.open());
                endpoint.handle(probe("/internal/synthetic-checkout", PROBE_SECRET), "SYN-3", 100, ResponseChannel.open());

                List<SentryEvent> events = telemetry.events();
                assertEquals(3, events.size());
                assertEquals(List.of("external", "external", "synthetic"),
                        events.stream().map(event -> event.getTag("traffic.origin")).toList());
            }
        }

        @Test
        void headersSetByClientDoNotMakeTrafficInternal() {
            IncomingRequest spoofed = new IncomingRequest("POST", "/api/checkout", null, Map.of(
                    "Authorization", "Bearer nieznany-token",
                    "X-Traffic", "internal",
                    "X-Forwarded-For", "10.20.0.7"));

            assertEquals(TrafficClassifier.Origin.EXTERNAL, traffic.classify(spoofed));
            assertEquals(TrafficClassifier.Origin.INTERNAL, traffic.classify(employee()));
        }

        @Test
        void scrubberRemovesSecretsAndKeepsOnlyUserId() {
            SentryEvent event = new SentryEvent();
            Request request = new Request();
            request.setUrl("https://anna:haslo@checkout.example.com/api/checkout?email=a%40b.pl#platnosc");
            request.setQueryString("email=a%40b.pl");
            request.setData("{\"cardNumber\":\"4111 1111 1111 1111\"}");
            request.setCookies("remember_me=anna");
            request.setEnvs(Map.of("REMOTE_ADDR", "10.20.0.7"));
            request.setHeaders(new HashMap<>(Map.of(
                    "AUTHORIZATION", "Bearer x",
                    "X-Session-Token", "st_live",
                    "content-type", "application/json")));
            event.setRequest(request);
            User user = new User();
            user.setId("c-7f3a9c");
            user.setEmail("anna.kowalska@example.com");
            user.setIpAddress("10.20.0.7");
            event.setUser(user);
            event.setExtra("payload", "cokolwiek");

            new EventDataScrubber().scrub(event);

            assertEquals("https://checkout.example.com/api/checkout", request.getUrl());
            assertNull(request.getQueryString());
            assertNull(request.getData());
            assertNull(request.getCookies());
            assertNull(request.getEnvs());
            assertEquals(Map.of("content-type", "application/json"), request.getHeaders());
            assertEquals("c-7f3a9c", event.getUser().getId());
            assertNull(event.getUser().getEmail());
            assertNull(event.getUser().getIpAddress());
            assertNull(event.getExtras());
        }

        @Test
        void scrubberDropsUserWithoutIdAndUnreadableUrl() {
            SentryEvent event = new SentryEvent();
            Request request = new Request();
            request.setUrl("https://checkout.example.com/api/checkout?token=%%zepsuty");
            event.setRequest(request);
            User user = new User();
            user.setIpAddress("10.20.0.7");
            event.setUser(user);

            new EventDataScrubber().scrub(event);

            assertNull(request.getUrl());
            assertNull(event.getUser());
        }
    }

    @Nested
    class Scenario6SilentLoss {

        @Test
        void filterTagSetOutsideRequestScopeHidesNextCustomerError() throws Exception {
            try (var telemetry = startProduction(options -> {});
                 ExecutorService worker = Executors.newSingleThreadExecutor()) {
                CheckoutEndpoint endpoint = endpoint(PaymentGateway.resettingConnection());
                worker.submit(() -> endpoint.handleWithoutRequestScope(employee(), "ORD-1", 100, ResponseChannel.open())).get();
                CheckoutResponse customer = worker.submit(() -> endpoint.handleWithoutRequestScope(
                        customer(), "ORD-2", 100, ResponseChannel.open())).get();

                assertFalse(customer.eventSent());
                assertTrue(telemetry.events().isEmpty());
            }
        }

        @Test
        void requestScopeKeepsCustomerErrorVisible() throws Exception {
            try (var telemetry = startProduction(options -> {});
                 ExecutorService worker = Executors.newSingleThreadExecutor()) {
                CheckoutEndpoint endpoint = endpoint(PaymentGateway.resettingConnection());
                worker.submit(() -> endpoint.handle(employee(), "ORD-1", 100, ResponseChannel.open())).get();
                worker.submit(() -> endpoint.handle(customer(), "ORD-2", 100, ResponseChannel.open())).get();

                assertEquals("external", telemetry.singleEvent().getTag("traffic.origin"));
            }
        }

        @Test
        void exceptionInBeforeSendDropsJobEvent() {
            assertThrows(NullPointerException.class, () -> new HttpOnlyBeforeSend().execute(new SentryEvent(), new Hint()));

            try (var telemetry = startProduction(options -> options.setBeforeSend(new HttpOnlyBeforeSend()))) {
                assertTrue(endpoint(PaymentGateway.resettingConnection())
                        .handle(customer(), "ORD-1", 100, ResponseChannel.open()).eventSent());
                SentryId jobEvent = retryJob().retry("ORD-2", 100);

                assertEquals(SentryId.EMPTY_ID, jobEvent);
                assertEquals(1, telemetry.events().size());
            }
        }

        @Test
        void nullSafeBeforeSendSendsJobEvent() {
            try (var telemetry = startProduction(options -> {})) {
                retryJob().retry("ORD-2", 100);

                SentryEvent event = telemetry.singleEvent();
                assertEquals("payment-retry", event.getTag("job.name"));
                assertNull(event.getRequest());
            }
        }
    }

    @Nested
    class Scenario7SpringStarter {

        @BeforeEach
        void clearRecordedEvents() {
            RecordingTransport.EVENTS.clear();
        }

        @Test
        void withoutSentryDsnAutoConfigurationDoesNotRun() throws Exception {
            try (var app = CheckoutApplication.start("local", Map.of(), RecordingTransport.class)) {
                assertFalse(app.sentryAutoConfigured());
                assertFalse(Sentry.isEnabled());
                assertEquals(500, new CheckoutWebClient(app.port()).submitPayment());
            }
            assertTrue(RecordingTransport.EVENTS.isEmpty());
        }

        @Test
        void inheritedDsnStartsStarterButLocalProfileDisablesSending() throws Exception {
            try (var app = CheckoutApplication.start("local", Map.of("SENTRY_DSN", DSN), RecordingTransport.class)) {
                assertTrue(app.sentryAutoConfigured());
                assertFalse(Sentry.isEnabled());
                assertEquals(500, new CheckoutWebClient(app.port()).submitPayment());
            }
            assertTrue(RecordingTransport.EVENTS.isEmpty());
        }

        @Test
        void withoutEnabledFalseInheritedDsnSendsEvents() throws Exception {
            try (var app = CheckoutApplication.start(null, Map.of("SENTRY_DSN", DSN), RecordingTransport.class)) {
                assertTrue(Sentry.isEnabled());
                new CheckoutWebClient(app.port()).submitPayment();
            }
            assertEquals(1, RecordingTransport.EVENTS.size());
            assertFalse(Sentry.isEnabled(), "zamknięcie kontekstu zamyka SDK");
        }

        @Test
        void optionsConfigurationBeanOverridesSentryProperties() throws Exception {
            Map<String, String> env = Map.of(
                    "SENTRY_DSN", DSN,
                    "SENTRY_ENVIRONMENT", "staging",
                    "SENTRY_RELEASE", "checkout-api@9.9.9");
            try (var app = CheckoutApplication.start(null, env, RecordingTransport.class)) {
                new CheckoutWebClient(app.port()).submitPayment();
            }

            SentryEvent event = RecordingTransport.EVENTS.getFirst();
            assertEquals(System.getenv().getOrDefault("SENTRY_ENVIRONMENT", TrainingSentry.DEFAULT_ENVIRONMENT),
                    event.getEnvironment());
            assertEquals(System.getenv().getOrDefault("SENTRY_RELEASE", TrainingSentry.DEFAULT_RELEASE),
                    event.getRelease());
        }

        @Test
        void gitCommitIdBecomesReleaseOnlyWhenEnabledAndReleaseMissing() {
            Map<String, String> env = Map.of("SENTRY_DSN", DSN);
            try (var app = CheckoutApplication.start(null, env, RecordingTransport.class, WithoutReleaseAndWithGit.class)) {
                // application.properties modułu wyłącza fallback: brak release zostaje widoczny.
                assertNull(Sentry.getCurrentScopes().getOptions().getRelease());
            }
            try (var app = CheckoutApplication.start(null, with(env, "SENTRY_USE_GIT_COMMIT_ID_AS_RELEASE", "true"),
                    RecordingTransport.class, WithoutReleaseAndWithGit.class)) {
                assertEquals(WithoutReleaseAndWithGit.COMMIT_ID, Sentry.getCurrentScopes().getOptions().getRelease());
            }
        }

        @Test
        void starterMarksOnlyApplicationClassPackageAsInApp() throws Exception {
            // Zmienna nadpisuje sentry.in-app-includes z pliku, więc zostaje tylko to, co dodaje starter.
            Map<String, String> env = Map.of("SENTRY_DSN", DSN, "SENTRY_IN_APP_INCLUDES", "com.example.none");
            try (var app = CheckoutApplication.start(null, env, RecordingTransport.class)) {
                new CheckoutWebClient(app.port()).submitPayment();
            }
            assertEquals(Map.of("CheckoutController", true, "CheckoutService", false), inAppByClass(RecordingTransport.EVENTS.getFirst()));

            RecordingTransport.EVENTS.clear();
            try (var app = CheckoutApplication.start(null, Map.of("SENTRY_DSN", DSN), RecordingTransport.class)) {
                new CheckoutWebClient(app.port()).submitPayment();
            }
            assertEquals(Map.of("CheckoutController", true, "CheckoutService", true), inAppByClass(RecordingTransport.EVENTS.getFirst()));
        }

        private static Map<String, Boolean> inAppByClass(SentryEvent event) {
            Map<String, Boolean> inApp = new HashMap<>();
            for (SentryStackFrame frame : frames(event)) {
                String module = frame.getModule();
                if (module.endsWith(".CheckoutController") || module.endsWith(".CheckoutService")) {
                    inApp.put(module.substring(module.lastIndexOf('.') + 1), Boolean.TRUE.equals(frame.isInApp()));
                }
            }
            return inApp;
        }

        @Test
        void twoBeforeSendCallbackBeansPreventStartup() {
            // LOGGING_LEVEL_ROOT=OFF wycisza raport Spring Boot o nieudanym starcie, który jest tu oczekiwany.
            Exception failure = assertThrows(Exception.class, () -> CheckoutApplication.start(
                    null, Map.of("SENTRY_DSN", DSN, "LOGGING_LEVEL_ROOT", "OFF"),
                    SentryPrivacyConfiguration.class, SecondBeforeSend.class));

            assertTrue(hasCause(failure, NoUniqueBeanDefinitionException.class), failure::toString);
            Sentry.close();
        }
    }

    @Nested
    class Scenario8SpringRequestData {

        private static final Map<String, String> ENV = Map.of("SENTRY_DSN", DSN);

        @BeforeEach
        void clearRecordedEvents() {
            RecordingTransport.EVENTS.clear();
        }

        @Test
        void piiDebugSendsBodyHeadersCookiesAndSpoofableIp() throws Exception {
            SentryEvent event = submitPayment("pii-debug", ENV);

            Request request = event.getRequest();
            assertTrue(String.valueOf(request.getData()).contains(CheckoutWebClient.CARD_NUMBER));
            assertTrue(String.valueOf(request.getData()).contains(CheckoutWebClient.CUSTOMER_EMAIL));
            assertNotNull(header(request, "Authorization"));
            assertNotNull(header(request, "X-Session-Token"));
            assertEquals(CheckoutWebClient.SPOOFED_CLIENT_IP, header(request, "X-Forwarded-For"));
            // SDK maskuje tylko znane ciasteczka sesji, a remember_me z e-mailem przechodzi.
            assertTrue(request.getCookies().contains("JSESSIONID=[Filtered]"), request.getCookies());
            assertTrue(request.getCookies().contains("remember_me=anna.kowalska%40example.com"), request.getCookies());
            assertEquals(CheckoutWebClient.SPOOFED_CLIENT_IP, event.getUser().getIpAddress());
        }

        @Test
        void defaultPiiSettingKeepsQueryAndUnknownHeaders() throws Exception {
            SentryEvent event = submitPayment(null, ENV);

            Request request = event.getRequest();
            assertNull(request.getData());
            assertNull(request.getCookies());
            assertNull(header(request, "Authorization"));
            assertNull(header(request, "Cookie"));
            assertNull(header(request, "X-Forwarded-For"));
            assertTrue(event.getUser() == null || event.getUser().getIpAddress() == null);
            // Tego send-default-pii=false nie obejmuje.
            assertEquals("coupon=WIOSNA26&email=anna.kowalska%40example.com", request.getQueryString());
            assertEquals("st_live_4f9c2a", header(request, "X-Session-Token"));
        }

        @Test
        void bodySizeWithoutSendDefaultPiiCapturesNoBody() throws Exception {
            SentryEvent event = submitPayment(null, with(ENV, "SENTRY_MAX_REQUEST_BODY_SIZE", "always"));

            assertNull(event.getRequest().getData());
        }

        @Test
        void beforeSendBeanLeavesOnlyAllowedRequestData() throws Exception {
            SentryEvent event = submitPayment("pii-debug", ENV, SentryPrivacyConfiguration.class);

            Request request = event.getRequest();
            assertTrue(request.getUrl().endsWith("/api/checkout"), request.getUrl());
            assertNull(request.getQueryString());
            assertNull(request.getData());
            assertNull(request.getCookies());
            assertEquals(Set.of("content-length", "content-type", "host", "user-agent", "x-request-id"),
                    request.getHeaders().keySet().stream().map(String::toLowerCase)
                            .collect(java.util.stream.Collectors.toSet()));
            assertNull(event.getUser());
            assertEquals("SocketException", outermostException(event).getType());
        }

        private SentryEvent submitPayment(String profile, Map<String, String> env, Class<?>... extra) throws Exception {
            Class<?>[] configuration = java.util.stream.Stream.concat(
                    java.util.stream.Stream.of(RecordingTransport.class), java.util.stream.Stream.of(extra))
                    .toArray(Class<?>[]::new);
            try (var app = CheckoutApplication.start(profile, env, configuration)) {
                assertEquals(500, new CheckoutWebClient(app.port()).submitPayment());
            }
            assertEquals(1, RecordingTransport.EVENTS.size());
            return RecordingTransport.EVENTS.getFirst();
        }
    }

    /**
     * Transport w pamięci dla aplikacji Spring Boot. Bean z najniższym priorytetem działa po
     * {@code SentryTrainingConfiguration}, więc zastępuje transport konsolowy.
     */
    @Configuration(proxyBeanMethods = false)
    static class RecordingTransport {

        static final List<SentryEvent> EVENTS = new CopyOnWriteArrayList<>();

        @Bean
        @Order(Ordered.LOWEST_PRECEDENCE)
        Sentry.OptionsConfiguration<SentryOptions> recordingTransport() {
            return options -> {
                ITransportFactory factory = (sentryOptions, requestDetails) -> new InMemoryTransport(
                        new EnvelopeDecoder(sentryOptions.getSerializer()));
                options.setTransportFactory(factory);
            };
        }
    }

    /** Symuluje wdrożenie bez release i z plikiem git.properties na classpath. */
    @Configuration(proxyBeanMethods = false)
    static class WithoutReleaseAndWithGit {

        static final String COMMIT_ID = "8f24c7a1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7";

        @Bean
        GitProperties gitProperties() {
            Properties git = new Properties();
            git.setProperty("commit.id", COMMIT_ID);
            return new GitProperties(git);
        }

        @Bean
        @Order(Ordered.LOWEST_PRECEDENCE)
        Sentry.OptionsConfiguration<SentryOptions> withoutRelease() {
            return options -> options.setRelease(null);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SecondBeforeSend {

        @Bean
        SentryOptions.BeforeSendCallback secondBeforeSend() {
            return (event, hint) -> event;
        }
    }

    private static final class InMemoryTransport implements ITransport {

        private final EnvelopeDecoder decoder;

        InMemoryTransport(EnvelopeDecoder decoder) {
            this.decoder = decoder;
        }

        @Override
        public void send(SentryEnvelope envelope, Hint hint) {
            for (CapturedItem item : decoder.decode(envelope)) {
                if (item instanceof CapturedItem.Event(SentryEvent event)) {
                    RecordingTransport.EVENTS.add(event);
                }
            }
        }

        @Override
        public void flush(long timeoutMillis) {
        }

        @Override
        public RateLimiter getRateLimiter() {
            return null;
        }

        @Override
        public void close(boolean isRestarting) {
        }

        @Override
        public void close() {
        }
    }

    private SentryTestSupport.CapturedTelemetry startProduction(java.util.function.Consumer<SentryOptions> scenario) {
        SentrySettings production = SentrySettings.fromEnvironment(deployment("production"));
        return SentryTestSupport.start(options -> {
            configurer.configure(options, production);
            scenario.accept(options);
        });
    }

    private CheckoutEndpoint endpoint(PaymentGateway gateway) {
        return new CheckoutEndpoint(new CheckoutService(gateway), traffic);
    }

    private static PaymentRetryJob retryJob() {
        return new PaymentRetryJob(new CheckoutService(PaymentGateway.resettingConnection()));
    }

    private static Map<String, String> deployment(String stage) {
        return Map.of("APP_STAGE", stage, "SENTRY_DSN", DSN, "SENTRY_RELEASE", RELEASE);
    }

    private static Map<String, String> with(Map<String, String> base, String key, String value) {
        Map<String, String> copy = new HashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    private static Map<String, String> without(Map<String, String> base, String key) {
        Map<String, String> copy = new HashMap<>(base);
        copy.remove(key);
        return copy;
    }

    private static IncomingRequest customer() {
        return new IncomingRequest("POST", "/api/checkout", "coupon=WIOSNA26&email=anna.kowalska%40example.com", Map.of(
                "Authorization", "Bearer cust-7f3a9c",
                "Content-Type", "application/json",
                "User-Agent", "checkout-web/5.2"));
    }

    private static IncomingRequest employee() {
        return new IncomingRequest("POST", "/api/checkout", null, Map.of("Authorization", "Bearer " + EMPLOYEE_TOKEN));
    }

    private static IncomingRequest probe(String path, String secret) {
        return new IncomingRequest("GET", path, null,
                secret == null ? Map.of() : Map.of(TrafficClassifier.SYNTHETIC_CHECK_HEADER, secret));
    }

    /** Protokół zapisuje łańcuch od najgłębszej przyczyny, więc wyjątek zewnętrzny jest ostatni. */
    private static SentryException outermostException(SentryEvent event) {
        return event.getExceptions().getLast();
    }

    private static List<SentryStackFrame> frames(SentryEvent event) {
        return outermostException(event).getStacktrace().getFrames();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> checkoutContext(SentryEvent event) {
        return (Map<String, Object>) event.getContexts().get("checkout");
    }

    private static String header(Request request, String name) {
        return request.getHeaders().entrySet().stream()
                .filter(header -> header.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }
}
