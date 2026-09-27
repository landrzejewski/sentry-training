package pl.training.sentry.module10;

import pl.training.sentry.module10.DashboardDefinition.Widget;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pokazuje zamianę definicji z repozytorium na treść żądania REST API i porównanie z tym, co jest
 * w Sentry.
 *
 * <p>Dwie odpowiedzialności, które muszą używać tych samych pól:</p>
 * <ul>
 *   <li>{@link #toApi}: treść {@code POST} i {@code PUT} dla
 *   {@code /api/0/organizations/{org}/dashboards/}; pytanie i decyzja trafiają do opisu widgetu,
 *   a właściciel do Edit Access;</li>
 *   <li>{@link #managedView} i {@link #differences}: wykrywanie driftu, czyli różnic między
 *   definicją a dashboardem zmienionym ręcznie w UI.</li>
 * </ul>
 *
 * <p>Porównanie obejmuje tylko pola, za które odpowiada definicja. Odpowiedź API zawiera też
 * identyfikatory, daty, autora i pola ustawiane przez serwer ({@code datasetSource},
 * {@code minH}, {@code onDemand}); porównanie całych dokumentów zgłaszałoby drift przy każdym
 * uruchomieniu.</p>
 */
public final class DashboardPayload {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private DashboardPayload() {
    }

    /**
     * Treść żądania tworzącego albo nadpisującego dashboard.
     *
     * @param projectIds  identyfikatory projektów z definicji (slug na id), ustalone przez API
     * @param ownerTeamId identyfikator zespołu właściciela, który dostaje Edit Access; {@code null}
     *                    oznacza dashboard bez właściciela, edytowalny przez wszystkich
     */
    public static ObjectNode toApi(DashboardDefinition dashboard, Collection<Long> projectIds, Long ownerTeamId) {
        ObjectNode body = JSON.createObjectNode();
        body.put("title", dashboard.title());
        ArrayNode projects = body.putArray("projects");
        projectIds.forEach(projects::add);
        ArrayNode environment = body.putArray("environment");
        dashboard.environment().forEach(environment::add);
        body.put("period", dashboard.period());
        ArrayNode release = body.putObject("filters").putArray("release");
        dashboard.release().forEach(release::add);
        // Sentry nie ma pola „właściciel”. Najbliżej jest Edit Access: zapisać zmianę mogą tylko
        // członkowie zespołu, twórca dashboardu i właściciele organizacji. Czytać mogą wszyscy.
        ObjectNode permissions = body.putObject("permissions");
        permissions.put("isEditableByEveryone", ownerTeamId == null);
        ArrayNode teams = permissions.putArray("teamsWithEditAccess");
        if (ownerTeamId != null) {
            teams.add(ownerTeamId.longValue());
        }
        ArrayNode widgets = body.putArray("widgets");
        dashboard.widgets().forEach(widget -> widgets.add(widget(widget)));
        return body;
    }

    private static ObjectNode widget(Widget widget) {
        ObjectNode node = JSON.createObjectNode();
        node.put("title", widget.title());
        // PUŁAPKA: API przyjmuje najwyżej 350 znaków opisu (DashboardRules to sprawdza). Pełny
        // kontrakt widgetu (odbiorca, drill-down, jakość danych) zostaje w repozytorium.
        node.put("description", widget.description());
        node.put("widgetType", widget.widgetType());
        node.put("displayType", widget.displayType());
        node.put("interval", widget.interval());
        if (widget.limit() != null) {
            node.put("limit", widget.limit());
        }
        if (widget.thresholds() != null) {
            ObjectNode thresholds = node.putObject("thresholds");
            thresholds.putObject("max_values")
                    .put("max1", widget.thresholds().max1())
                    .put("max2", widget.thresholds().max2());
            thresholds.putNull("unit");
            thresholds.put("preferred_polarity", widget.thresholds().polarity());
        }
        ObjectNode query = node.putArray("queries").addObject();
        query.put("name", "");
        ArrayNode fields = query.putArray("fields");
        widget.columns().forEach(fields::add);
        widget.aggregates().forEach(fields::add);
        ArrayNode aggregates = query.putArray("aggregates");
        widget.aggregates().forEach(aggregates::add);
        ArrayNode columns = query.putArray("columns");
        widget.columns().forEach(columns::add);
        query.put("conditions", widget.conditions());
        query.put("orderby", widget.orderby());
        DashboardDefinition.Layout layout = widget.layout();
        node.putObject("layout")
                .put("x", layout.x()).put("y", layout.y())
                .put("w", layout.w()).put("h", layout.h())
                .put("minH", "big_number".equals(widget.displayType()) ? 1 : 2);
        return node;
    }

    /**
     * Pola zarządzane przez definicję, w tej samej postaci dla treści żądania i odpowiedzi API.
     *
     * <p>Normalizacja usuwa różnice zapisu, które nie są zmianą: kolejność projektów, {@code null}
     * zamiast pustego opisu, liczby całkowite zapisane jako int albo long. Serwer zwraca też progi
     * inaczej, niż je przyjmuje ({@code preferred_polarity} wraca jako {@code preferredPolarity}),
     * więc widgety są porównywane po odczytanych wartościach, a nie po surowym JSON.</p>
     */
    public static ObjectNode managedView(JsonNode dashboard) {
        ObjectNode view = JSON.createObjectNode();
        view.put("title", dashboard.path("title").asString(""));
        view.put("projects", sortedLongs(dashboard.path("projects")));
        view.put("environment", sortedStrings(dashboard.path("environment")));
        view.put("release", sortedStrings(dashboard.path("filters").path("release")));
        view.put("period", dashboard.path("period").asString(""));
        JsonNode permissions = dashboard.path("permissions");
        view.put("editAccess", permissions.isObject() && !permissions.path("isEditableByEveryone").asBoolean(true)
                ? "zespoły " + sortedLongs(permissions.path("teamsWithEditAccess"))
                : "wszyscy");
        ObjectNode widgets = view.putObject("widgets");
        Map<String, Integer> seen = new HashMap<>();
        for (JsonNode widget : dashboard.path("widgets")) {
            String title = widget.path("title").asString("");
            int occurrence = seen.merge(title, 1, Integer::sum);
            widgets.set(occurrence == 1 ? title : title + " #" + occurrence, widgetView(widget));
        }
        return view;
    }

    private static ObjectNode widgetView(JsonNode widget) {
        ObjectNode view = JSON.createObjectNode();
        view.put("description", widget.path("description").asString(""));
        view.put("widgetType", widget.path("widgetType").asString(""));
        view.put("displayType", widget.path("displayType").asString(""));
        view.put("interval", widget.path("interval").asString(""));
        view.put("limit", widget.path("limit").isNumber() ? String.valueOf(widget.path("limit").asInt()) : "brak");
        JsonNode thresholds = widget.path("thresholds");
        view.put("thresholds", thresholds.isObject()
                ? "max1=" + thresholds.path("max_values").path("max1").asDouble()
                  + " max2=" + thresholds.path("max_values").path("max2").asDouble()
                  + " polarity=" + firstText(thresholds, "preferred_polarity", "preferredPolarity")
                : "brak");
        JsonNode query = widget.path("queries").path(0);
        view.put("aggregates", strings(query.path("aggregates")));
        view.put("columns", strings(query.path("columns")));
        view.put("conditions", query.path("conditions").asString(""));
        view.put("orderby", query.path("orderby").asString(""));
        view.put("queries", widget.path("queries").size());
        JsonNode layout = widget.path("layout");
        view.put("layout", "x=" + layout.path("x").asInt() + " y=" + layout.path("y").asInt()
                + " w=" + layout.path("w").asInt() + " h=" + layout.path("h").asInt());
        return view;
    }

    /**
     * Różnice między definicją a dashboardem w Sentry, czytelne dla człowieka.
     *
     * @param desired treść żądania z {@link #toApi}
     * @param actual  odpowiedź {@code GET} dashboardu z Sentry
     * @return pusta lista, gdy dashboard w Sentry odpowiada definicji
     */
    public static List<String> differences(JsonNode desired, JsonNode actual) {
        ObjectNode repo = managedView(desired);
        ObjectNode sentry = managedView(actual);
        List<String> differences = new ArrayList<>();
        for (String field : List.of("title", "projects", "environment", "release", "period", "editAccess")) {
            compare("dashboard", field, repo.path(field), sentry.path(field), differences);
        }
        Map<String, JsonNode> repoWidgets = widgets(repo);
        Map<String, JsonNode> sentryWidgets = widgets(sentry);
        repoWidgets.forEach((title, widget) -> {
            JsonNode other = sentryWidgets.get(title);
            if (other == null) {
                differences.add("widget „" + title + "”: jest w repozytorium, brak w Sentry (usunięty albo przemianowany w UI)");
                return;
            }
            for (String field : widget.propertyNames()) {
                compare("widget „" + title + "”", field, widget.path(field), other.path(field), differences);
            }
        });
        sentryWidgets.keySet().stream()
                .filter(title -> !repoWidgets.containsKey(title))
                .forEach(title -> differences.add("widget „" + title + "”: jest tylko w Sentry (dodany w UI)"));
        return differences;
    }

    /**
     * Kopia treści żądania z identyfikatorami widgetów istniejących w Sentry pod tym samym tytułem.
     *
     * <p>{@code PUT} usuwa widgety, których identyfikatorów nie ma w treści, i tworzy nowe dla
     * widgetów bez identyfikatora. Bez tego każda aktualizacja zmieniałaby identyfikatory wszystkich
     * widgetów, a razem z nimi linki do nich.</p>
     */
    public static ObjectNode withExistingWidgetIds(ObjectNode payload, JsonNode actual) {
        Map<String, String> ids = new LinkedHashMap<>();
        for (JsonNode widget : actual.path("widgets")) {
            ids.putIfAbsent(widget.path("title").asString(""), widget.path("id").asString(""));
        }
        ObjectNode copy = payload.deepCopy();
        for (JsonNode widget : copy.path("widgets")) {
            String id = ids.remove(widget.path("title").asString(""));
            if (id != null && !id.isEmpty()) {
                ((ObjectNode) widget).put("id", id);
            }
        }
        return copy;
    }

    private static void compare(String where, String field, JsonNode repo, JsonNode sentry, List<String> differences) {
        if (Objects.equals(repo, sentry)) {
            return;
        }
        String inRepo = text(repo);
        String inSentry = text(sentry);
        // Opis widgetu ma do 350 znaków; cały w jednej linii konsoli byłby nieczytelny.
        differences.add(inRepo.length() + inSentry.length() > 120
                ? where + ": " + field + " różni się między repozytorium a Sentry"
                : where + ": " + field + " w repozytorium " + inRepo + ", w Sentry " + inSentry);
    }

    private static String text(JsonNode value) {
        return value.isValueNode() ? value.asString() : value.toString();
    }

    private static Map<String, JsonNode> widgets(ObjectNode view) {
        Map<String, JsonNode> widgets = new LinkedHashMap<>();
        view.path("widgets").properties().forEach(entry -> widgets.put(entry.getKey(), entry.getValue()));
        return widgets;
    }

    private static String firstText(JsonNode node, String... names) {
        for (String name : names) {
            if (node.path(name).isString()) {
                return node.path(name).asString();
            }
        }
        return "";
    }

    private static String strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asString()));
        return values.toString();
    }

    private static String sortedStrings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asString()));
        values.sort(null);
        return values.toString();
    }

    private static String sortedLongs(JsonNode array) {
        List<Long> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asLong()));
        values.sort(null);
        return values.toString();
    }
}
