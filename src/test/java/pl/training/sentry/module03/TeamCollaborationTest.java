package pl.training.sentry.module03;

import io.sentry.SentryEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import pl.training.sentry.module03.CheckoutService.CauseHandling;
import pl.training.sentry.module03.FakePaymentGateway.Reply;
import pl.training.sentry.module03.OwnershipRules.Preview;
import pl.training.sentry.module03.OwnershipRules.Rule;
import pl.training.sentry.module03legacy.LegacyLoyaltyClient;
import pl.training.sentry.support.SentryRestApi;
import pl.training.sentry.support.SentryTestSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Współpraca zespołowa bez sieci do Sentry: Ownership Rules na prawdziwych eventach checkout-api
 * oraz treść żądań i parsowanie odpowiedzi API na przykładach zapisanych z lokalnego Sentry 26.9.0
 * ({@code src/test/resources/module03}).
 *
 * <p>Podgląd reguł to narzędzie szkoleniowe. Test pilnuje, żeby odpowiadał zachowaniu Sentry
 * potwierdzonemu online: ostatnia pasująca reguła, pierwszy owner, ścieżka Javy z pakietu i pliku.</p>
 */
@ResourceLock("sentry-global-state")
class TeamCollaborationTest {

    private static final List<Rule> RULES = OwnershipRules.parse(OwnershipRules.RULES);
    private static final LegacyLoyaltyClient LOYALTY = new LegacyLoyaltyClient();

    private static FakePaymentGateway gateway;
    private static PaymentGatewayClient client;

    @BeforeAll
    static void startGateway() throws Exception {
        gateway = FakePaymentGateway.start(Duration.ofSeconds(2));
        client = new PaymentGatewayClient(gateway.baseUri(), Duration.ofMillis(150), 120, Duration.ZERO,
                "tok_test_1234", new HttpCallBreadcrumbs());
    }

    @AfterAll
    static void stopGateway() {
        client.close();
        gateway.close();
    }

    @Nested
    class OwnershipRulesOnCheckoutEvents {

        @Test
        void rulesAreParsedInFileOrderWithAllOwners() {
            assertEquals(4, RULES.size());
            assertEquals(new Rule("path", "pl/training/sentry/module03/*", List.of("#checkout")), RULES.get(0));
            assertEquals(List.of("#payments", "#checkout"), RULES.get(1).owners());
            assertEquals("tags.component", RULES.get(3).matcher());
        }

        @Test
        void gatewayTimeoutGoesToFirstOwnerOfLastMatchingRule() {
            gateway.respond("ORD-6101", Reply.SLOW);
            SentryEvent event = placeOrder(Order.card("ORD-6101", "c-1"), CauseHandling.KEEP_CAUSE);

            Preview preview = OwnershipRules.preview(RULES, event);
            assertEquals(List.of(RULES.get(0), RULES.get(1)), preview.matched());
            // Ostatnia pasująca reguła ma dwóch ownerów, auto-assignment bierze pierwszego.
            assertEquals("#payments", preview.autoAssignee());
            assertEquals(List.of("#payments", "#checkout"), preview.owners());
        }

        @Test
        void wrapperWithoutCauseHasNoReasonTagSoOnlyCheckoutPathMatches() {
            gateway.respond("ORD-6102", Reply.SLOW);
            SentryEvent event = placeOrder(Order.card("ORD-6102", "c-2"), CauseHandling.MESSAGE_ONLY);

            // Dane decydują o ownerze: bez cause nie ma tagu payment.failure_reason (scenariusz 1).
            assertEquals("#checkout", OwnershipRules.preview(RULES, event).autoAssignee());
        }

        @Test
        void legacyLibraryFramesGoToLibraryOwner() {
            SentryEvent event = placeOrder(Order.card("ORD-6103", "c-3").withLoyaltyCard("LOY2-00A7F3"),
                    CauseHandling.KEEP_CAUSE);

            assertTrue(OwnershipRules.javaPaths(event).contains("pl/training/sentry/module03legacy/LegacyLoyaltyClient.java"));
            Preview preview = OwnershipRules.preview(RULES, event);
            assertEquals(List.of(RULES.get(0), RULES.get(2)), preview.matched());
            assertEquals(OwnershipRules.LOYALTY_OWNER, preview.autoAssignee());
        }

        @Test
        void pathPatternWithoutSlashAlsoMatchesLegacyPackage() {
            // PUŁAPKA z opisu RULES: ta sama pomyłka co prefiks in-app bez kropki.
            List<Rule> careless = OwnershipRules.parse("""
                    path:pl/training/sentry/module03legacy/* anna.checkout@sentry-training.local
                    path:pl/training/sentry/module03* #checkout
                    """);
            SentryEvent event = placeOrder(Order.card("ORD-6104", "c-4").withLoyaltyCard("LOY2-00A7F3"),
                    CauseHandling.KEEP_CAUSE);

            assertEquals("#checkout", OwnershipRules.preview(careless, event).autoAssignee());
        }

        @Test
        void globStarCrossesSlashesAndPathIgnoresCase() {
            assertTrue(OwnershipRules.glob("pl/training/sentry/module03/*", "pl/training/sentry/module03/sub/X.java", true));
            assertFalse(OwnershipRules.glob("pl/training/sentry/module03/*", "pl/training/sentry/module03legacy/X.java", true));
            assertTrue(OwnershipRules.glob("PL/training/*", "pl/training/X.java", true));
            assertFalse(OwnershipRules.glob("Payments", "payments", false));
        }

        private SentryEvent placeOrder(Order order, CauseHandling causeHandling) {
            try (var telemetry = SentryTestSupport.start(CheckoutSentryConfig.TARGET)) {
                new CheckoutEndpoint(new CheckoutService(client, LOYALTY, causeHandling)).handle(order);
                return telemetry.singleEvent();
            }
        }
    }

    @Nested
    class ApiRequestsAndResponses {

        @Test
        void ownershipBodyCarriesRulesTextAndAutoAssignmentMode() {
            ObjectNode body = TeamSetup.ownershipBody(OwnershipRules.RULES);

            assertEquals(OwnershipRules.RULES, body.path("raw").asString());
            assertEquals("Auto Assign to Issue Owner", body.path("autoAssignment").asString());
            assertEquals("payments", TeamSetup.teamBody("payments").path("slug").asString());
        }

        @Test
        void teamOwnerGoesToPaymentsAndLibraryOwnerToCheckout() {
            assertEquals(OwnershipRules.PAYMENTS_TEAM, TeamSetup.memberships().get("me"));
            assertEquals(OwnershipRules.CHECKOUT_TEAM, TeamSetup.memberships().get(OwnershipRules.LOYALTY_OWNER));
        }

        @Test
        void eventOwnersResponseListsMatchedRulesInFileOrder() throws IOException {
            JsonNode owners = resource("event-owners.json");

            assertEquals("path:pl/training/sentry/module03/* #checkout", TriageWalkthrough.describeRule(owners.path("rules").get(0)));
            assertEquals("tags.payment.failure_reason:* #payments #checkout",
                    TriageWalkthrough.describeRule(owners.path("rules").get(1)));
            // Suggested owners w kolejności reguł z pliku, a nie w kolejności auto-assignment.
            assertEquals("#checkout, #payments", TriageWalkthrough.actors(owners.path("owners")));
        }

        @Test
        void activityShowsWhichRuleAssignedTheIssueAndLaterManualDecisions() throws IOException {
            JsonNode issue = resource("issue-after-triage.json");
            List<JsonNode> activity = issue.path("activity").valueStream().toList();

            JsonNode autoAssigned = activity.stream().filter(a -> a.path("data").has("rule")).findFirst().orElseThrow();
            assertTrue(TriageWalkthrough.describeActivity(autoAssigned)
                    .endsWith("-> #payments, reguła: tags.payment.failure_reason:* #payments #checkout"));
            assertEquals(List.of("auto_set_ongoing", "mark_reviewed", "note", "assigned", "assigned", "first_seen"),
                    activity.stream().map(a -> a.path("type").asString()).toList());
            assertEquals("anna.checkout@sentry-training.local", TriageWalkthrough.assignee(issue.path("assignedTo")));
            assertEquals("ongoing", issue.path("substatus").asString());
        }

        @Test
        void triageCommentFollowsTemplateWithoutSecrets() throws IOException {
            String comment = TriageWalkthrough.triageComment(resource("issue-after-triage.json"));

            for (String section : List.of("Zakres:", "Fakty:", "Hipoteza:", "Następny krok:", "Kryterium weryfikacji:")) {
                assertTrue(comment.contains(section), section);
            }
            assertTrue(comment.contains("właściciel: " + OwnershipRules.LOYALTY_OWNER));
            assertFalse(comment.contains("tok_"));
            assertFalse(TriageWalkthrough.markReviewedBody().path("inbox").asBoolean(true));
        }

        private JsonNode resource(String name) throws IOException {
            try (InputStream in = TeamCollaborationTest.class.getResourceAsStream("/module03/" + name)) {
                return SentryRestApi.JSON.readTree(in);
            }
        }
    }
}
