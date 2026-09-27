package pl.training.sentry.module10;

import pl.training.sentry.module10.DashboardDefinition.Widget;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pokazuje REST API dashboardów organizacji i zapytania widgetów (test odbiorowy) na klientcie
 * HTTP z JDK.
 *
 * <p>Konfiguracja ze zmiennych środowiskowych:</p>
 * <ul>
 *   <li>{@code SENTRY_AUTH_TOKEN}: token użytkownika; dashboardy wymagają {@code org:read}
 *   (w 26.9.0 ten zakres wystarcza także do utworzenia, zmiany i usunięcia dashboardu), a test
 *   odbiorowy widgetów {@code event:read};</li>
 *   <li>{@code SENTRY_URL}: adres Sentry, domyślnie {@value #DEFAULT_URL};</li>
 *   <li>{@code SENTRY_ORG}: slug organizacji, domyślnie {@value #DEFAULT_ORG}.</li>
 * </ul>
 *
 * <p>PUŁAPKA: token w pliku repozytorium obok definicji dashboardu. Definicja jest jawna i trafia
 * do przeglądu kodu, token nie. Dlatego klasa czyta go tylko ze środowiska, a {@link #toString()}
 * i komunikaty błędów go nie zawierają.</p>
 */
public final class SentryDashboardsApi implements DashboardSync.DashboardsApi {

    public static final String DEFAULT_URL = "http://localhost:9000";
    public static final String DEFAULT_ORG = "sentry";

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final URI baseUrl;
    private final String organization;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final JsonMapper json = JsonMapper.builder().build();

    SentryDashboardsApi(URI baseUrl, String organization, String token) {
        // Token w nagłówku żądania bez TLS może przeczytać każdy pośrednik; wyjątek tylko dla
        // lokalnego Sentry z docker/sentry, które działa bez TLS.
        if (!"https".equals(baseUrl.getScheme()) && !LOOPBACK_HOSTS.contains(baseUrl.getHost())) {
            throw new IllegalArgumentException("Token API wysyłamy tylko przez HTTPS albo do localhost: " + baseUrl);
        }
        this.baseUrl = baseUrl;
        this.organization = organization;
        this.token = token;
    }

    /** Klient z konfiguracji środowiska albo pusty wynik, gdy nie ma tokenu (tryb offline). */
    public static Optional<SentryDashboardsApi> fromEnv(Map<String, String> env) {
        String token = env.get("SENTRY_AUTH_TOKEN");
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new SentryDashboardsApi(
                URI.create(env.getOrDefault("SENTRY_URL", DEFAULT_URL)),
                env.getOrDefault("SENTRY_ORG", DEFAULT_ORG),
                token.strip()));
    }

    /** Adres dashboardu w UI. */
    public String dashboardUrl(String id) {
        return baseUrl.resolve("/organizations/" + organization + "/dashboard/" + id + "/").toString();
    }

    @Override
    public Map<String, Long> projectIds() throws IOException, InterruptedException {
        Map<String, Long> ids = new LinkedHashMap<>();
        for (JsonNode project : send("GET", org("projects/"), null)) {
            ids.put(project.path("slug").asString(), project.path("id").asLong());
        }
        return ids;
    }

    @Override
    public long teamId(String slug) throws IOException, InterruptedException {
        for (JsonNode team : send("GET", org("teams/"), null)) {
            if (slug.equals(team.path("slug").asString())) {
                return team.path("id").asLong();
            }
        }
        throw new IllegalArgumentException("Zespół właściciela „" + slug + "” nie istnieje w organizacji " + organization);
    }

    @Override
    public List<JsonNode> findByTitle(String title) throws IOException, InterruptedException {
        // Parametr query szuka fragmentu tytułu, więc wynik trzeba jeszcze zawęzić do równego
        // tytułu. Dashboardy wbudowane (prebuiltId) nie są nasze, nawet przy tym samym tytule.
        JsonNode list = send("GET", org("dashboards/?per_page=100&query=" + encode(title)), null);
        List<JsonNode> matching = new ArrayList<>();
        for (JsonNode dashboard : list) {
            if (title.equals(dashboard.path("title").asString()) && dashboard.path("prebuiltId").isNull()) {
                matching.add(dashboard);
            }
        }
        return matching;
    }

    @Override
    public JsonNode get(String id) throws IOException, InterruptedException {
        return send("GET", org("dashboards/" + encode(id) + "/"), null);
    }

    @Override
    public JsonNode create(ObjectNode payload, List<Long> projectIds) throws IOException, InterruptedException {
        return send("POST", org("dashboards/?" + projectParams(projectIds)), payload);
    }

    @Override
    public JsonNode update(String id, ObjectNode payload, List<Long> projectIds) throws IOException, InterruptedException {
        return send("PUT", org("dashboards/" + encode(id) + "/?" + projectParams(projectIds)), payload);
    }

    /**
     * Walidacja po stronie serwera bez zapisu ({@code validateOnly}): pusty wynik oznacza, że
     * Sentry przyjęłoby definicję, w przeciwnym razie komunikat błędu z serwera.
     */
    public Optional<String> validate(ObjectNode payload, List<Long> projectIds) throws IOException, InterruptedException {
        try {
            send("POST", org("dashboards/?validateOnly=1&" + projectParams(projectIds)), payload);
            return Optional.empty();
        } catch (IOException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("HTTP 400")) {
                return Optional.of(exception.getMessage().replaceFirst("^.*serwer odrzucił definicję: ", ""));
            }
            throw exception;
        }
    }

    /** Wynik zapytania widgetu: czy są dane i skrót wartości. */
    public record WidgetData(String widget, boolean hasData, String summary) {
    }

    /**
     * Test odbiorowy widgetu: to samo zapytanie co widget, z filtrami globalnymi dashboardu.
     *
     * <p>Dashboard zapisany bez błędu nie dowodzi, że widget ma dane: literówka w nazwie metryki,
     * zła jednostka albo filtr środowiska dają pusty wykres, a nie błąd zapisu. Każda zmiana
     * dashboardu wymaga testu; to jest jego najprostsza, automatyczna część.</p>
     */
    public WidgetData widgetData(DashboardDefinition dashboard, Widget widget, List<Long> projectIds)
            throws IOException, InterruptedException {
        StringBuilder query = new StringBuilder(projectParams(projectIds))
                .append("&statsPeriod=").append(encode(dashboard.period()));
        dashboard.environment().forEach(environment -> query.append("&environment=").append(encode(environment)));
        String conditions = withRelease(widget.conditions(), dashboard.release());
        return switch (widget.widgetType()) {
            case "tracemetrics" -> {
                // PUŁAPKA: równanie bez danych zwraca 0, a nie null (lokalny Sentry 26.9.0, release bez
                // ruchu: operandy null, failure rate 0). Dane rozpoznajemy więc po operandach równania.
                List<String> probes = new ArrayList<>();
                for (String aggregate : widget.aggregates()) {
                    if (aggregate.startsWith("equation|")) {
                        DashboardRules.operands(aggregate).forEach(operand -> probes.add(operand.field()));
                    } else {
                        probes.add(aggregate);
                    }
                }
                query.append("&dataset=tracemetrics&per_page=").append(widget.limit() == null ? 1 : widget.limit());
                widget.columns().forEach(column -> query.append("&field=").append(encode(column)));
                widget.aggregates().forEach(aggregate -> query.append("&field=").append(encode(aggregate)));
                probes.stream().filter(probe -> !widget.aggregates().contains(probe)).distinct()
                        .forEach(probe -> query.append("&field=").append(encode(probe)));
                query.append("&query=").append(encode(conditions));
                JsonNode rows = send("GET", org("events/?" + query), null).path("data");
                boolean hasData = rows.size() > 0 && probes.stream()
                        .anyMatch(probe -> rows.path(0).path(probe).isNumber());
                yield new WidgetData(widget.title(), hasData, hasData ? summary(rows, widget) : "brak danych");
            }
            case "metrics" -> {
                widget.aggregates().forEach(aggregate -> query.append("&field=").append(encode(aggregate)));
                widget.columns().forEach(column -> query.append("&groupBy=").append(encode(column)));
                if (!widget.orderby().isEmpty()) {
                    query.append("&orderBy=").append(encode(widget.orderby()));
                }
                query.append("&interval=1h&query=").append(encode(conditions));
                JsonNode groups = send("GET", org("sessions/?" + query), null).path("groups");
                List<String> totals = new ArrayList<>();
                groups.forEach(group -> totals.add(group.path("by") + " " + group.path("totals")));
                boolean hasData = !groups.isEmpty();
                yield new WidgetData(widget.title(), hasData,
                        hasData ? String.join("; ", totals.subList(0, Math.min(3, totals.size()))) : "brak sesji");
            }
            case "issue" -> {
                query.append("&limit=").append(widget.limit() == null ? 10 : widget.limit())
                        .append("&query=").append(encode(conditions));
                JsonNode issues = send("GET", org("issues/?" + query), null);
                List<String> ids = new ArrayList<>();
                issues.forEach(issue -> ids.add(issue.path("shortId").asString()));
                yield new WidgetData(widget.title(), !ids.isEmpty(),
                        ids.isEmpty() ? "brak issues" : ids.size() + " issues: " + ids);
            }
            default -> new WidgetData(widget.title(), false, "test odbiorowy nie obsługuje " + widget.widgetType());
        };
    }

    private static String summary(JsonNode rows, Widget widget) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(3, rows.size()); i++) {
            JsonNode row = rows.path(i);
            String group = widget.columns().stream()
                    .map(column -> column + "=" + row.path(column).asString(""))
                    .collect(Collectors.joining(","));
            String values = widget.aggregates().stream()
                    .map(aggregate -> shortName(aggregate) + "=" + row.path(aggregate).asString(""))
                    .collect(Collectors.joining(", "));
            parts.add(group.isEmpty() ? values : group + ": " + values);
        }
        return String.join("; ", parts);
    }

    /** {@code p95(value,checkout.duration,...)} skrócone do {@code p95(checkout.duration)}. */
    private static String shortName(String aggregate) {
        return aggregate.replaceAll("\\(value,([^,]+),[^)]*\\)", "($1)").replace("equation|", "");
    }

    private static String withRelease(String conditions, List<String> releases) {
        if (releases.isEmpty()) {
            return conditions;
        }
        String release = "release:[" + String.join(",", releases) + "]";
        return conditions.isBlank() ? release : conditions + " " + release;
    }

    private JsonNode send(String method, String path, ObjectNode body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUrl.resolve(path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
        }
        String hint = switch (status) {
            case 400 -> "serwer odrzucił definicję: " + abbreviate(response.body());
            case 401 -> "token odrzucony: wygasł, został usunięty albo ma literówkę";
            case 403 -> "brak zakresu tokenu (org:read, event:read), brak Edit Access do dashboardu "
                    + "albo brak dostępu do projektu z filtra";
            case 404 -> "nie znaleziono zasobu albo organizacja nie ma włączonych dashboardów";
            default -> "nieoczekiwana odpowiedź: " + abbreviate(response.body());
        };
        throw new IOException(method + " " + path + " -> HTTP " + status + ": " + hint);
    }

    private String org(String path) {
        return "/api/0/organizations/" + encode(organization) + "/" + path;
    }

    private static String projectParams(List<Long> projectIds) {
        // PUŁAPKA: pole projects w treści żądania nie wystarcza. Bez parametru project w adresie
        // Sentry sprawdza projekty z treści względem projektów zespołów użytkownika i zwraca 403,
        // gdy użytkownik tokenu (np. właściciel organizacji) nie należy do zespołu projektu.
        return projectIds.stream().map(id -> "project=" + id).collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String abbreviate(String body) {
        return body.length() > 400 ? body.substring(0, 400) + "..." : body;
    }

    @Override
    public String toString() {
        return "SentryDashboardsApi[" + baseUrl + ", org=" + organization + ", token=***]";
    }
}
