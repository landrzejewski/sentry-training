package pl.training.sentry.module10;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pl.training.sentry.module10.DashboardDefinition.Widget;
import pl.training.sentry.module10.DashboardRules.Violation;
import pl.training.sentry.module10.DashboardSync.Action;
import pl.training.sentry.module10.DashboardSync.Mode;
import pl.training.sentry.module10.DashboardSync.Result;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenariusze 7 do 12 z {@link DashboardAsCodeDemo} sprawdzone bez sieci.
 *
 * <p>Definicja z repozytorium przechodzi przez te same reguły co w demo, a synchronizacja działa
 * na implementacji API w pamięci, która zwraca dashboard w postaci zbliżonej do odpowiedzi
 * Sentry 26.9.0 (identyfikatory, pola serwera, progi z {@code preferredPolarity}).</p>
 */
class DashboardAsCodeTest {

    private final DashboardDefinition dashboard = DashboardDefinition.load();

    @Nested
    class Scenario7DefinitionInRepository {

        @Test
        void repositoryDefinitionHasNoViolations() {
            assertEquals(List.of(), DashboardRules.check(dashboard));
            assertEquals(8, dashboard.widgets().size());
        }

        @Test
        void failureRateIsRatioOfSumsNotAverageOfPercentages() {
            for (String title : List.of("Failure rate checkoutu", "Failure rate w czasie")) {
                assertEquals(List.of("equation|sum(value," + CheckoutMetrics.FAILED + ",counter,none) / sum(value,"
                        + CheckoutMetrics.ATTEMPTED + ",counter,none)"), dashboard.widget(title).aggregates());
            }
            boolean anyAverage = dashboard.widgets().stream()
                    .flatMap(widget -> widget.aggregates().stream())
                    .anyMatch(aggregate -> aggregate.contains("avg("));
            assertFalse(anyAverage);
        }

        @Test
        void everyQueryInheritsExactlyOneEnvironment() {
            assertEquals(List.of("training"), dashboard.environment());
            assertTrue(dashboard.widgets().stream().noneMatch(widget -> widget.conditions().contains("environment:")));
        }

        @Test
        void dashboardIsPinnedToReferenceRelease() {
            assertEquals(List.of("sentry-training@1.0.0-m10ref"), dashboard.release());
        }

        @Test
        void chartsStayWithinSeriesLimit() {
            for (Widget widget : dashboard.widgets()) {
                if (!widget.columns().isEmpty() && Set.of("line", "area", "bar").contains(widget.displayType())) {
                    assertTrue(widget.limit() * widget.aggregates().size() <= DashboardRules.MAX_SERIES, widget.title());
                }
            }
        }

        @Test
        void everyWidgetHasQuestionAndDecisionWithinDescriptionLimit() {
            for (Widget widget : dashboard.widgets()) {
                assertFalse(widget.question().isBlank(), widget.title());
                assertFalse(widget.decision().isBlank(), widget.title());
                assertTrue(widget.description().length() <= DashboardRules.MAX_DESCRIPTION, widget.title());
            }
        }

        @Test
        void metricsWidgetsUseOnlyNamesTypesAndUnitsFromCheckoutMetrics() {
            List<DashboardRules.MetricOperand> operands = dashboard.widgets().stream()
                    .filter(widget -> widget.widgetType().equals("tracemetrics"))
                    .flatMap(widget -> widget.aggregates().stream())
                    .flatMap(aggregate -> DashboardRules.operands(aggregate).stream())
                    .toList();
            assertFalse(operands.isEmpty());
            for (DashboardRules.MetricOperand operand : operands) {
                DashboardRules.MetricContract contract = DashboardRules.CHECKOUT_METRICS.get(operand.name());
                assertEquals(contract.type(), operand.type(), operand.name());
                assertEquals(contract.unit(), operand.unit(), operand.name());
            }
        }
    }

    @Nested
    class Scenario8Pitfalls {

        @Test
        void everyPitfallVariantBreaksExpectedRule() {
            Map<String, String> expectedRule = Map.of(
                    "A.", "iloraz sum",
                    "B.", "jedno środowisko",
                    "C.", "limit serii",
                    "D.", "kardynalność",
                    "E.", "właściciel",
                    "F.", "kontrakt metryk",
                    "G.", "przypięty release");
            Map<String, DashboardDefinition> variants = DashboardAsCodeDemo.pitfalls(dashboard);
            assertEquals(expectedRule.size(), variants.size());
            variants.forEach((name, variant) -> {
                Set<String> rules = DashboardRules.check(variant).stream().map(Violation::rule).collect(Collectors.toSet());
                assertTrue(rules.contains(expectedRule.get(name.substring(0, 2))), name + " -> " + rules);
            });
        }

        @Test
        void ratioOfAveragesIsNotRatioOfSums() {
            DashboardDefinition variant = dashboard.withWidget("Failure rate w czasie", widget -> widget.withAggregates(List.of(
                    "equation|p95(value,checkout.duration,distribution,millisecond) / avg(value,payments.queue.depth,gauge,none)")));

            assertEquals(List.of("iloraz sum"), rules(variant));
        }

        @Test
        void groupingWithoutLimitIsRejectedLikeOnServer() {
            DashboardDefinition variant = dashboard.withWidget("Porażki według przyczyny",
                    widget -> widget.withGrouping(List.of(CheckoutMetrics.OUTCOME), null));

            assertEquals(List.of("limit serii"), rules(variant));
        }

        @Test
        void widgetConditionMustNotOverrideGlobalEnvironment() {
            DashboardDefinition variant = dashboard.withWidget("Nierozwiązane issues checkoutu",
                    widget -> widget.withConditions("is:unresolved environment:production"));

            assertEquals(List.of("jedno środowisko"), rules(variant));
        }

        @Test
        void releaseWithoutServiceNameIsRejected() {
            DashboardDefinition variant = dashboard.withRelease(List.of("1.0.0"));

            assertEquals(List.of("przypięty release"), rules(variant));
        }

        @Test
        void aggregateMustFitMetricType() {
            // Serwer 26.9.0 przyjmuje avg z countera; wynik to średnia z jedynek, czyli zawsze 1.
            DashboardDefinition variant = dashboard.withWidget("Próby checkoutu według metody płatności",
                    widget -> widget.withAggregates(List.of("avg(value,checkout.attempted,counter,none)")));

            assertEquals(List.of("kontrakt metryk"), rules(variant));
        }

        @Test
        void unitIsPartOfMetricIdentity() {
            DashboardDefinition variant = dashboard.withWidget("Czas checkoutu p50 i p95", widget -> widget.withAggregates(List.of(
                    "p50(value,checkout.duration,distribution,second)",
                    "p95(value,checkout.duration,distribution,second)")));

            assertEquals(List.of("kontrakt metryk", "kontrakt metryk"), rules(variant));
        }

        private List<String> rules(DashboardDefinition variant) {
            return DashboardRules.check(variant).stream().map(Violation::rule).toList();
        }
    }

    @Nested
    class Scenario9PayloadForApi {

        private final ObjectNode payload = DashboardPayload.toApi(dashboard, List.of(2L), 7L);

        @Test
        void contractFieldsGoToDescriptionAndEditAccess() {
            JsonNode failureRate = payload.path("widgets").path(0);
            assertTrue(failureRate.path("description").asString().contains(dashboard.widgets().getFirst().question()));
            assertTrue(failureRate.path("description").asString().contains(dashboard.widgets().getFirst().decision()));
            assertFalse(payload.path("permissions").path("isEditableByEveryone").asBoolean());
            assertEquals(7L, payload.path("permissions").path("teamsWithEditAccess").path(0).asLong());
        }

        @Test
        void globalFiltersComeFromDefinition() {
            assertEquals(2L, payload.path("projects").path(0).asLong());
            assertEquals("training", payload.path("environment").path(0).asString());
            assertEquals("24h", payload.path("period").asString());
            assertEquals(dashboard.release(), strings(payload.path("filters").path("release")));
        }

        @Test
        void queryFieldsAreColumnsFollowedByAggregates() {
            JsonNode query = payload.path("widgets").path(3).path("queries").path(0);
            assertEquals(List.of(CheckoutMetrics.OUTCOME, "sum(value,checkout.failed,counter,none)"), strings(query.path("fields")));
            assertEquals(5, payload.path("widgets").path(3).path("limit").asInt());
        }

        @Test
        void dashboardWithoutOwnerIsEditableByEveryone() {
            ObjectNode withoutOwner = DashboardPayload.toApi(dashboard.withOwner(""), List.of(2L), null);

            assertTrue(withoutOwner.path("permissions").path("isEditableByEveryone").asBoolean());
        }
    }

    @Nested
    class Scenario11SyncAndDrift {

        private final InMemoryDashboardsApi api = new InMemoryDashboardsApi();
        private final DashboardSync sync = new DashboardSync(api);

        @Test
        void serverRepresentationOfSameDefinitionIsNotDrift() {
            ObjectNode payload = DashboardPayload.toApi(dashboard, List.of(2L), 2L);

            assertEquals(List.of(), DashboardPayload.differences(payload, api.asServerReturnsIt(payload, "1")));
        }

        @Test
        void firstRunCreatesAndSecondRunChangesNothing() throws Exception {
            Result first = sync.sync(dashboard, Mode.APPLY);
            Result second = sync.sync(dashboard, Mode.APPLY);

            assertEquals(Action.CREATED, first.action());
            assertEquals(Action.UNCHANGED, second.action());
            assertEquals(first.dashboardId(), second.dashboardId());
            assertEquals(1, api.dashboards.size());
            assertEquals(List.of("POST"), api.writes);
        }

        @Test
        void manualEditInUiIsReportedInPlanAndRevertedInApply() throws Exception {
            String id = sync.sync(dashboard, Mode.APPLY).dashboardId();
            api.manualEdit(id, stored -> {
                stored.putArray("environment");
                ((ObjectNode) stored.path("widgets").path(3).path("queries").path(0)).put("conditions", "payment.method:card");
            });

            Result plan = sync.sync(dashboard, Mode.PLAN);
            assertEquals(Action.DRIFT, plan.action());
            assertEquals(2, plan.drift().size(), plan.drift().toString());
            assertTrue(plan.drift().get(0).contains("environment"));
            assertTrue(plan.drift().get(1).contains("Porażki według przyczyny") && plan.drift().get(1).contains("conditions"));
            assertEquals(List.of("POST"), api.writes);

            List<String> idsBefore = widgetIds(api.dashboards.get(id));
            Result apply = sync.sync(dashboard, Mode.APPLY);
            assertEquals(Action.UPDATED, apply.action());
            assertEquals(List.of("POST", "PUT"), api.writes);
            assertEquals(idsBefore, widgetIds(api.dashboards.get(id)));
            assertEquals(Action.UNCHANGED, sync.sync(dashboard, Mode.APPLY).action());
        }

        @Test
        void releaseSwitchedInUiAndSavedIsDrift() throws Exception {
            String id = sync.sync(dashboard, Mode.APPLY).dashboardId();
            api.manualEdit(id, stored -> ((ObjectNode) stored.path("filters")).putArray("release").add("sentry-training@1.0.0"));

            Result plan = sync.sync(dashboard, Mode.PLAN);
            assertEquals(List.of("dashboard: release w repozytorium [sentry-training@1.0.0-m10ref], w Sentry [sentry-training@1.0.0]"),
                    plan.drift());
            assertEquals(Action.UPDATED, sync.sync(dashboard, Mode.APPLY).action());
            assertEquals(Action.UNCHANGED, sync.sync(dashboard, Mode.APPLY).action());
        }

        @Test
        void widgetAddedInUiIsDrift() throws Exception {
            String id = sync.sync(dashboard, Mode.APPLY).dashboardId();
            api.manualEdit(id, stored -> ((ArrayNode) stored.path("widgets")).addObject().put("title", "Ad hoc"));

            Result plan = sync.sync(dashboard, Mode.PLAN);
            assertEquals(List.of("widget „Ad hoc”: jest tylko w Sentry (dodany w UI)"), plan.drift());
        }

        @Test
        void duplicateTitlesStopSync() throws Exception {
            sync.sync(dashboard, Mode.APPLY);
            api.create(DashboardPayload.toApi(dashboard, List.of(2L), 2L), List.of(2L));

            assertThrows(IllegalStateException.class, () -> sync.sync(dashboard, Mode.APPLY));
        }

        @Test
        void definitionWithViolationsNeverReachesApi() {
            assertThrows(IllegalArgumentException.class, () -> sync.sync(dashboard.withOwner(""), Mode.APPLY));
            assertEquals(List.of(), api.writes);
        }

        @Test
        void planDoesNotCreate() throws Exception {
            assertEquals(Action.WOULD_CREATE, sync.sync(dashboard, Mode.PLAN).action());
            assertTrue(api.dashboards.isEmpty());
        }

        private List<String> widgetIds(JsonNode stored) {
            List<String> ids = new ArrayList<>();
            stored.path("widgets").forEach(widget -> ids.add(widget.path("id").asString()));
            return ids;
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }

    /**
     * REST API dashboardów w pamięci. Zapisuje dashboard tak, jak zwraca go Sentry 26.9.0:
     * z identyfikatorami, polami serwera, pustym {@code filters} przy pustym release
     * i progami z kluczem {@code preferredPolarity}.
     */
    static final class InMemoryDashboardsApi implements DashboardSync.DashboardsApi {

        final Map<String, ObjectNode> dashboards = new LinkedHashMap<>();
        final List<String> writes = new ArrayList<>();
        private int nextId = 1;

        @Override
        public Map<String, Long> projectIds() {
            return Map.of("sentry-training", 2L, "internal", 1L);
        }

        @Override
        public long teamId(String slug) {
            return 2L;
        }

        @Override
        public List<JsonNode> findByTitle(String title) {
            return dashboards.values().stream()
                    .filter(stored -> stored.path("title").asString().equals(title))
                    .map(JsonNode.class::cast)
                    .toList();
        }

        @Override
        public JsonNode get(String id) {
            return dashboards.get(id).deepCopy();
        }

        @Override
        public JsonNode create(ObjectNode payload, List<Long> projectIds) {
            writes.add("POST");
            String id = String.valueOf(nextId++);
            dashboards.put(id, asServerReturnsIt(payload, id));
            return dashboards.get(id);
        }

        @Override
        public JsonNode update(String id, ObjectNode payload, List<Long> projectIds) {
            writes.add("PUT");
            dashboards.put(id, asServerReturnsIt(payload, id));
            return dashboards.get(id);
        }

        void manualEdit(String id, java.util.function.Consumer<ObjectNode> edit) {
            edit.accept(dashboards.get(id));
        }

        ObjectNode asServerReturnsIt(ObjectNode payload, String id) {
            ObjectNode stored = payload.deepCopy();
            stored.put("id", id);
            stored.put("dateCreated", "2026-09-27T10:00:00Z");
            stored.putNull("prebuiltId");
            if (stored.path("filters").path("release").isEmpty()) {
                stored.putObject("filters");
            }
            int widgetId = 100;
            for (JsonNode node : stored.path("widgets")) {
                ObjectNode widget = (ObjectNode) node;
                if (!widget.has("id")) {
                    widget.put("id", String.valueOf(widgetId++ + Integer.parseInt(id) * 1_000));
                }
                widget.put("datasetSource", "user");
                if (!widget.has("limit")) {
                    widget.putNull("limit");
                }
                if (widget.path("thresholds").isObject()) {
                    ObjectNode thresholds = (ObjectNode) widget.path("thresholds");
                    thresholds.set("preferredPolarity", thresholds.remove("preferred_polarity"));
                }
            }
            return stored;
        }
    }
}
