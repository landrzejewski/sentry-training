package pl.training.sentry.module06;

import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import pl.training.sentry.module06.AlertingSetup.Firing;
import pl.training.sentry.module06.BankGateway.Mode;
import pl.training.sentry.module06.BankGateway.Payment;
import pl.training.sentry.module06.PaymentsEndpoint.Reporting;
import pl.training.sentry.module06.WebhookReceiver.Notification;
import pl.training.sentry.module06.WebhookReceiver.Signature;
import pl.training.sentry.support.SentryRestApi;
import pl.training.sentry.support.SentryTestSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Alerty end-to-end bez sieci do Sentry: treść żądań REST API budowana z {@link PaymentsAlertPolicy},
 * podgląd filtrów na prawdziwych eventach z {@link PaymentTelemetry}, odbiornik webhooków
 * i parsowanie odpowiedzi zapisanych z lokalnego Sentry 26.9.0.
 *
 * <p>Czy Alert faktycznie wykonał akcję, rozstrzyga serwer (trigger, throttling, stan Monitora).
 * To potwierdza uruchomienie online {@code AlertingDemo}, opisane w {@code README.md} pakietu {@code module06}.</p>
 */
@ResourceLock("sentry-global-state")
class AlertingTest {

    private static final String SLUG = "module06-alert-webhook-abc123";

    @Nested
    class AlertRequests {

        @Test
        void componentAlertFiresOnEveryEventInTrainingWithTagLevelFiltersAndThrottling() {
            ObjectNode body = PaymentsAlertPolicy.alertBody(PaymentsAlertPolicy.COMPONENT_ERRORS, 4, SLUG);

            assertEquals("training", body.path("environment").asString());
            assertEquals(30, body.path("config").path("frequency").asInt());
            assertEquals(4, body.path("detectorIds").get(0).asLong());
            assertEquals(List.of("every_event"), types(body.path("triggers").path("conditions")));
            JsonNode block = body.path("actionFilters").get(0);
            assertEquals("all", block.path("logicType").asString());
            assertEquals(List.of("tagged_event", "level", "tagged_event"), types(block.path("conditions")));
            JsonNode component = block.path("conditions").get(0).path("comparison");
            assertEquals("component/eq/payments", component.path("key").asString() + "/"
                    + component.path("match").asString() + "/" + component.path("value").asString());
            assertEquals(40, block.path("conditions").get(1).path("comparison").path("level").asInt());
            assertEquals("ne", block.path("conditions").get(2).path("comparison").path("match").asString());
            JsonNode webhook = block.path("actions").get(0);
            assertEquals("webhook", webhook.path("type").asString());
            assertEquals(SLUG, webhook.path("config").path("targetIdentifier").asString());
        }

        @Test
        void regressionAlertUsesOnlyIssueAttributeFilters() {
            ObjectNode body = PaymentsAlertPolicy.alertBody(PaymentsAlertPolicy.REGRESSIONS, 4, SLUG);

            assertEquals(List.of("regression_event"), types(body.path("triggers").path("conditions")));
            // Według dokumentacji filtry atrybutów eventu nie działają z triggerem regresji.
            assertEquals(List.of("issue_priority_greater_or_equal"),
                    types(body.path("actionFilters").get(0).path("conditions")));
            assertEquals(75, body.path("actionFilters").get(0).path("conditions").get(0).path("comparison").asInt());
        }

        @Test
        void monitorAlertHasNoEventTriggerAndPointsAtMetricMonitor() {
            ObjectNode body = PaymentsAlertPolicy.alertBody(PaymentsAlertPolicy.ERROR_SPIKE, 24, SLUG);

            assertTrue(body.path("triggers").path("conditions").isEmpty());
            assertEquals(24, body.path("detectorIds").get(0).asLong());
            assertEquals(0, body.path("config").path("frequency").asInt());
        }

        @Test
        void metricMonitorCountsComponentErrorsInFiveMinutesWithAlarmAndRecovery() {
            ObjectNode body = PaymentsAlertPolicy.monitorBody(PaymentsAlertPolicy.PAYMENT_ERRORS, "2");

            assertEquals("metric_issue", body.path("type").asString());
            JsonNode source = body.path("dataSources").get(0);
            assertEquals("count()", source.path("aggregate").asString());
            assertEquals("component:payments level:error", source.path("query").asString());
            assertEquals(300, source.path("timeWindow").asInt());
            assertEquals("training", source.path("environment").asString());
            JsonNode conditions = body.path("conditionGroup").path("conditions");
            assertEquals("gt/10/75", condition(conditions.get(0)));
            assertEquals("lte/10/0", condition(conditions.get(1)));
        }

        @Test
        void webhookIntegrationIsInternalAlertableAndWithoutApiScopes() {
            ObjectNode body = PaymentsAlertPolicy.webhookIntegrationBody("sentry", AlertingSetup.DEFAULT_WEBHOOK_URL);

            assertTrue(body.path("isInternal").asBoolean());
            assertTrue(body.path("isAlertable").asBoolean());
            assertTrue(body.path("scopes").isEmpty());
            assertEquals("http://host.docker.internal:8097/sentry/webhook", body.path("webhookUrl").asString());
        }

        @Test
        void allPolicyObjectsShareThePrefixUsedBySetupAndCleanup() {
            PaymentsAlertPolicy.ALERTS.forEach(alert -> assertTrue(alert.name().startsWith(PaymentsAlertPolicy.NAME_PREFIX)));
            assertTrue(PaymentsAlertPolicy.PAYMENT_ERRORS.name().startsWith(PaymentsAlertPolicy.NAME_PREFIX));
        }

        private static List<String> types(JsonNode conditions) {
            return conditions.valueStream().map(condition -> condition.path("type").asString()).toList();
        }

        private static String condition(JsonNode condition) {
            return condition.path("type").asString() + "/" + condition.path("comparison").asInt() + "/"
                    + condition.path("conditionResult").asInt();
        }
    }

    @Nested
    class FilterPreviewOnPaymentTelemetryEvents {

        @Test
        void classifiedBankTimeoutInTrainingPassesEnvironmentAndFilters() {
            SentryEvent event = charge(training(), Mode.TIMING_OUT, "tok-visa", Reporting.CLASSIFIED);

            assertTrue(PaymentsAlertPolicy.preview(PaymentsAlertPolicy.COMPONENT_ERRORS, event).notifies());
        }

        @Test
        void sameFailureInStagingIsFilteredByEnvironment() {
            SentryEvent event = charge(options -> options.setEnvironment("staging"), Mode.TIMING_OUT, "tok-visa",
                    Reporting.CLASSIFIED);

            PaymentsAlertPolicy.Verdict verdict = PaymentsAlertPolicy.preview(PaymentsAlertPolicy.COMPONENT_ERRORS, event);
            assertFalse(verdict.notifies());
            assertTrue(verdict.reason().contains("environment staging"));
        }

        @Test
        void classifiedDeclineIsFilteredByLevel() {
            SentryEvent event = charge(training(), Mode.AVAILABLE, BankGateway.CARD_WITHOUT_FUNDS, Reporting.CLASSIFIED);

            assertEquals(SentryLevel.WARNING, event.getLevel());
            assertTrue(PaymentsAlertPolicy.preview(PaymentsAlertPolicy.COMPONENT_ERRORS, event).reason().contains("level warning"));
        }

        @Test
        void carelessReportWithoutComponentTagIsSilentEvenAsError() {
            SentryEvent event = charge(training(), Mode.TIMING_OUT, "tok-visa", Reporting.CARELESS);

            assertNull(event.getTag("component"));
            assertTrue(PaymentsAlertPolicy.preview(PaymentsAlertPolicy.COMPONENT_ERRORS, event).reason()
                    .contains("brak tagu component"));
        }

        @Test
        void expectedTagExcludesEventEvenWhenSomeoneRaisesItsLevel() {
            SentryEvent event = charge(training(), Mode.AVAILABLE, BankGateway.CARD_WITHOUT_FUNDS, Reporting.CLASSIFIED);
            event.setLevel(SentryLevel.ERROR);

            assertTrue(PaymentsAlertPolicy.preview(PaymentsAlertPolicy.COMPONENT_ERRORS, event).reason().contains("expected=true"));
        }

        @Test
        void regressionAndMonitorAlertsAreLeftToTheServer() {
            SentryEvent event = charge(training(), Mode.TIMING_OUT, "tok-visa", Reporting.CLASSIFIED);

            assertTrue(PaymentsAlertPolicy.preview(PaymentsAlertPolicy.REGRESSIONS, event).reason().startsWith("decyduje serwer"));
            assertTrue(PaymentsAlertPolicy.preview(PaymentsAlertPolicy.ERROR_SPIKE, event).reason().startsWith("decyduje serwer"));
        }

        private static Consumer<io.sentry.SentryOptions> training() {
            return options -> options.setEnvironment(PaymentsAlertPolicy.ENVIRONMENT);
        }

        private static SentryEvent charge(Consumer<io.sentry.SentryOptions> environment, Mode mode, String card,
                                          Reporting reporting) {
            try (var telemetry = SentryTestSupport.start(options -> {
                PaymentTelemetry.configure(options);
                environment.accept(options);
            })) {
                BankGateway bank = new BankGateway();
                bank.switchTo(mode);
                new PaymentsEndpoint(new PaymentService(bank)).charge(new Payment("pay_T1", "sklep", card), reporting);
                return telemetry.singleEvent();
            }
        }
    }

    @Nested
    class WebhookReceiverWithSavedPayload {

        @Test
        void savedEventAlertPayloadIsReadableForTheRecipient() throws IOException {
            Notification notification = WebhookReceiver.parse("event_alert", sample(), Signature.NOT_CHECKED);

            assertEquals("[module06] payments: błąd komponentu", notification.alert());
            assertEquals("46", notification.issueId());
            assertEquals("error", notification.level());
            assertEquals("training", notification.environment());
            assertEquals("payments", notification.tags().get("component"));
            assertEquals("charge", notification.tags().get("operation"));
            assertEquals("Sentry (application)", notification.sender());
            assertNull(notification.metricValue());
            String text = WebhookReceiver.describe(notification);
            assertTrue(text.contains("dlaczego: Alert „[module06] payments: błąd komponentu”"));
            assertTrue(text.contains("component=payments"));
        }

        @Test
        void signatureIsHmacSha256OfBodyWithClientSecret() throws Exception {
            String body = sample();

            assertEquals(Signature.VALID, WebhookReceiver.verify("s3cret", body, hmac("s3cret", body)));
            assertEquals(Signature.INVALID, WebhookReceiver.verify("s3cret", body, hmac("other", body)));
            assertEquals(Signature.INVALID, WebhookReceiver.verify("s3cret", body, null));
            assertEquals(Signature.NOT_CHECKED, WebhookReceiver.verify(null, body, null));
        }

        @Test
        void receiverAcceptsSignedPostAndRejectsForgedOne() throws Exception {
            String body = sample();
            try (WebhookReceiver receiver = new WebhookReceiver(0, "s3cret", notification -> {
            });
                 HttpClient http = HttpClient.newHttpClient()) {
                assertEquals(200, post(http, receiver, body, hmac("s3cret", body)));
                assertEquals(401, post(http, receiver, body, hmac("guess", body)));

                assertEquals(1, receiver.received().size());
                assertEquals(Signature.VALID, receiver.received().getFirst().signature());
            }
        }

        private static int post(HttpClient http, WebhookReceiver receiver, String body, String signature) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + receiver.port() + WebhookReceiver.PATH))
                            .header("Sentry-Hook-Resource", "event_alert")
                            .header("Sentry-Hook-Signature", signature)
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        }

        private static String hmac(String secret, String body) throws Exception {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        }

        private static String sample() throws IOException {
            try (InputStream in = AlertingTest.class.getResourceAsStream("/module06/webhook-event-alert.json")) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    @Nested
    class ApiClientAndHistory {

        @Test
        void alertHistoryShowsOneActionForManyEventsOfOneIssue() throws IOException {
            JsonNode history;
            try (InputStream in = AlertingTest.class.getResourceAsStream("/module06/workflow-group-history.json")) {
                history = SentryRestApi.JSON.readTree(in);
            }

            List<Firing> firings = AlertingSetup.parseFirings(history);
            assertEquals(1, firings.size());
            assertEquals("SENTRY-TRAINING-1E", firings.getFirst().shortId());
            // Zapis z uruchomienia online: 14 eventów w training, jedna akcja dzięki throttlingowi.
            assertEquals(1, firings.getFirst().count());
        }

        @Test
        void integrationPendingDeletionIsNotReused() {
            JsonNode apps = SentryRestApi.JSON.readTree("""
                    [{"name": "module06 alert webhook", "slug": "old-1", "status": "deletion_in_progress"},
                     {"name": "inna integracja", "slug": "other", "status": "internal"},
                     {"name": "module06 alert webhook", "slug": "new-2", "status": "internal"}]
                    """);

            assertEquals("new-2", AlertingSetup.activeIntegration(apps).orElseThrow().path("slug").asString());
            assertTrue(AlertingSetup.activeIntegration(SentryRestApi.JSON.readTree("[]")).isEmpty());
        }

        @Test
        void requestCarriesTokenAndJsonBodyWithoutLeakingTokenInToString() {
            SentryRestApi api = SentryRestApi.fromEnv(Map.of("SENTRY_AUTH_TOKEN", "sntryu_secret")).orElseThrow();

            HttpRequest request = api.request("POST", "/organizations/sentry/workflows/",
                    SentryRestApi.JSON.createObjectNode().put("name", "x"));
            assertEquals("http://localhost:9000/api/0/organizations/sentry/workflows/", request.uri().toString());
            assertEquals("POST", request.method());
            assertEquals("Bearer sntryu_secret", request.headers().firstValue("Authorization").orElseThrow());
            assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow());
            assertFalse(api.toString().contains("sntryu_secret"));
        }

        @Test
        void withoutTokenThereIsNoClientAndTokenGoesOnlyOverHttpsOrToLocalhost() {
            assertTrue(SentryRestApi.fromEnv(Map.of()).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> SentryRestApi.fromEnv(Map.of(
                    "SENTRY_AUTH_TOKEN", "t", "SENTRY_URL", "http://sentry.example.com")));
            assertEquals("sentry", SentryRestApi.fromEnv(Map.of("SENTRY_AUTH_TOKEN", "t")).orElseThrow().org());
        }
    }
}
