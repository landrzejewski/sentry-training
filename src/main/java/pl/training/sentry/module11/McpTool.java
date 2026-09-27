package pl.training.sentry.module11;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Jedno narzędzie z katalogu serwera MCP: nazwa, pierwsza linia opisu i adnotacje.
 *
 * <p>Ten sam kształt ma wpis
 * w odpowiedzi {@code tools/list} i wynik {@code search_sentry_tools}, czyli katalog operacji
 * dostępnych przez bramę {@code execute_sentry_tool}.</p>
 *
 * <p>Zapisane snapshoty w {@code src/main/resources/module11/} pochodzą z
 * {@code @sentry/mcp-server} 0.42.0 uruchomionego w trybie stdio przeciwko lokalnemu Sentry
 * (self-hosted 26.9.0, organizacja {@code sentry}, projekt {@code sentry-training}):</p>
 * <ul>
 *   <li>{@code tools-list-default.json}: {@code tools/list} bez {@code --skills};</li>
 *   <li>{@code tools-list-inspect.json}: {@code tools/list} z {@code --skills=inspect},
 *   {@code --organization-slug} i {@code --project-slug};</li>
 *   <li>{@code catalog-search-*.json}: wynik {@code search_sentry_tools} dla tego samego zapytania
 *   w obu profilach, skrócony do nazwy, pierwszej linii opisu i adnotacji.</li>
 * </ul>
 *
 * @param readOnlyHint    {@code null}, gdy serwer nie podał adnotacji
 * @param destructiveHint {@code null}, gdy serwer nie podał adnotacji
 */
public record McpTool(String name, String description, Boolean readOnlyHint, Boolean destructiveHint) {

    static final JsonMapper JSON = JsonMapper.shared();

    /** Skutek wynikający wyłącznie z adnotacji serwera, przed lokalnym przeglądem. */
    public ToolEffect declaredEffect() {
        return ToolEffect.fromAnnotations(readOnlyHint, destructiveHint);
    }

    public static McpTool fromJson(JsonNode tool) {
        JsonNode annotations = tool.path("annotations");
        return new McpTool(
                tool.path("name").asString(),
                tool.path("description").asString("").lines().findFirst().orElse(""),
                optionalBoolean(annotations, "readOnlyHint"),
                optionalBoolean(annotations, "destructiveHint"));
    }

    /** Narzędzia z wyniku {@code tools/list} ({@code tools}) albo {@code search_sentry_tools} ({@code results}). */
    public static List<McpTool> fromJsonArray(JsonNode array) {
        List<McpTool> tools = new ArrayList<>();
        for (JsonNode tool : array.values()) {
            tools.add(fromJson(tool));
        }
        return tools;
    }

    /**
     * Wczytuje zapisany snapshot: pełną odpowiedź JSON-RPC na {@code tools/list} albo skrócony
     * wynik {@code search_sentry_tools}.
     */
    public static List<McpTool> loadSnapshot(String fileName) {
        String path = "/module11/" + fileName;
        try (InputStream in = McpTool.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("Brak snapshotu " + path);
            }
            JsonNode root = JSON.readTree(in);
            JsonNode tools = root.path("result").path("tools");
            return fromJsonArray(tools.isMissingNode() ? root.path("results") : tools);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Boolean optionalBoolean(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isBoolean() ? value.booleanValue() : null;
    }
}
