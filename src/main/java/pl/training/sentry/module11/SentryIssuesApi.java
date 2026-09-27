package pl.training.sentry.module11;

import pl.training.sentry.module11.EvidenceLog.DataState;
import pl.training.sentry.module11.EvidenceLog.Read;
import pl.training.sentry.module11.EvidenceLog.SamplingStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Odczyt listy issues z REST API Sentry jako dowód ze stanem pobrania.
 *
 * <p>To ten sam odczyt, który agent wykonuje przez
 * {@code search_issues} w MCP, ale bez warstwy agenta, więc widać surowe sygnały kompletności:
 * status HTTP, nagłówek {@code Link} z kursorem kolejnej strony i nagłówek {@code X-Hits}.
 * Token potrzebuje zakresów {@code project:read} (odczyt projektu) i {@code event:read} (issues).</p>
 *
 * <p>Interpretacja odpowiedzi ({@link #interpret}) jest czystą funkcją, więc testy sprawdzają ją
 * bez sieci, a demo online stosuje ją do prawdziwych odpowiedzi lokalnego Sentry.</p>
 */
public final class SentryIssuesApi {

    static final String SOURCE = "GET /api/0/organizations/{org}/issues/";

    private static final Pattern NEXT_PAGE_WITH_RESULTS = Pattern.compile("rel=\"next\";\\s*results=\"true\"");

    private final URI baseUrl;
    private final String token;
    private final HttpClient http;

    public SentryIssuesApi(URI baseUrl, String token) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /**
     * Pierwsza strona issues w zamrożonym zakresie.
     *
     * @param query zapytanie w składni wyszukiwania Sentry, np. {@code is:unresolved}
     * @param limit rozmiar strony; mały limit celowo pokazuje stronicowanie
     */
    public Read listIssues(DiagnosticScope scope, String query, int limit) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("query", query);
        parameters.put("environment", scope.environment());
        parameters.put("start", scope.start().toString());
        parameters.put("end", scope.end().toString());
        parameters.put("limit", String.valueOf(limit));
        try {
            // PUŁAPKA: endpoint projektowy /api/0/projects/{org}/{project}/issues/ w self-hosted
            // 26.9.0 zwrócił issues spoza podanego start/end, bez żadnego błędu. Endpoint
            // organizacji z identyfikatorem projektu respektuje okno, więc najpierw ustalamy id.
            HttpResponse<String> projectResponse = get("/api/0/projects/" + scope.organization() + "/" + scope.project() + "/");
            if (projectResponse.statusCode() != 200) {
                return interpret(scope, parameters, projectResponse.statusCode(), "", "", projectResponse.body());
            }
            String projectId = McpTool.JSON.readTree(projectResponse.body()).path("id").asString();
            parameters.put("project", projectId);

            HttpResponse<String> response = get("/api/0/organizations/" + scope.organization() + "/issues/?"
                    + parameters.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&")));
            return interpret(scope, parameters, response.statusCode(),
                    response.headers().firstValue("Link").orElse(""),
                    response.headers().firstValue("X-Hits").orElse(""),
                    response.body());
        } catch (IOException | JacksonException exception) {
            return failed(scope, parameters, "błąd połączenia albo odpowiedzi: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failed(scope, parameters, "przerwano odczyt");
        }
    }

    /**
     * Zamienia odpowiedź HTTP na odczyt ze stanem.
     *
     * @param linkHeader nagłówek {@code Link}; Sentry stronicuje kursorem i oznacza istnienie
     *                   kolejnej strony przez {@code rel="next"; results="true"}
     * @param hitsHeader nagłówek {@code X-Hits}: liczba wszystkich pasujących issues
     */
    static Read interpret(DiagnosticScope scope, Map<String, String> parameters, int status,
                          String linkHeader, String hitsHeader, String body) {
        if (status != 200) {
            String reason = switch (status) {
                case 401 -> "401: brak tokenu albo token nieważny";
                case 403 -> "403: token bez wymaganego zakresu albo brak dostępu do projektu";
                // Sprawdzone na self-hosted 26.9.0: environment, którego projekt nie zna, daje 404
                // na liście issues organizacji, a nie pustą listę.
                case 404 -> "404: zasób nie istnieje w tym zakresie, np. organizacja, projekt albo environment";
                case 429 -> "429: limit zapytań, ponów ten sam odczyt z tym samym zakresem";
                default -> status + ": nieoczekiwana odpowiedź";
            };
            return failed(scope, parameters, reason);
        }
        JsonNode issues = McpTool.JSON.readTree(body);
        if (issues.isEmpty()) {
            return new Read(SOURCE, parameters, scope.organization(), scope.project(), scope.environment(),
                    DataState.NO_DATA, SamplingStatus.UNKNOWN, "0 issues dla tego zapytania");
        }
        boolean hasNextPage = NEXT_PAGE_WITH_RESULTS.matcher(linkHeader).find();
        List<String> listed = new ArrayList<>();
        for (JsonNode issue : issues.values()) {
            listed.add(issue.path("shortId").asString() + " (" + issue.path("count").asString() + " ev., "
                    + issue.path("userCount").asInt() + " users, " + issue.path("level").asString() + ")");
        }
        String summary = issues.size() + " issues na stronie"
                + (hitsHeader.isBlank() ? "" : ", pasujących łącznie (X-Hits) " + hitsHeader)
                + ": " + String.join(", ", listed);
        // Sampling UNKNOWN: API nie mówi, jaką część błędów wysłało SDK (sampleRate, beforeSend,
        // filtry po stronie serwera). Liczba eventów w issue to więc dolna granica.
        return new Read(SOURCE, parameters, scope.organization(), scope.project(), scope.environment(),
                hasNextPage ? DataState.PARTIAL : DataState.COMPLETE, SamplingStatus.UNKNOWN, summary);
    }

    private HttpResponse<String> get(String pathAndQuery) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve(pathAndQuery))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Read failed(DiagnosticScope scope, Map<String, String> parameters, String reason) {
        return new Read(SOURCE, parameters, scope.organization(), scope.project(), scope.environment(),
                DataState.FAILED, SamplingStatus.UNKNOWN, reason);
    }
}
