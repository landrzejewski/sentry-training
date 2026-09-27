package pl.training.sentry.module11;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lokalna polityka narzędzi MCP dla sesji diagnostycznej tylko do odczytu, działająca fail closed.
 *
 * <p>Polityka odpowiada na dwa pytania:</p>
 * <ul>
 *   <li>{@link #review}: które narzędzia z {@code tools/list} wolno włączyć w kliencie, czyli
 *   gotowa lista do {@code enabled_tools} w konfiguracji Codex ({@link #enabledTools});</li>
 *   <li>{@link #checkCall}: czy konkretne wywołanie wolno wykonać. Dla bramy
 *   {@code execute_sentry_tool} decyduje operacja docelowa z argumentu {@code name}, a nie nazwa
 *   wrappera.</li>
 * </ul>
 *
 * <p>Dopuszczone jest tylko to, co ktoś przejrzał i co serwer nadal deklaruje jako odczyt.
 * Wszystko inne, w tym nowe narzędzie w katalogu, jest odrzucane z podaniem powodu.</p>
 */
public final class ToolPolicy {

    /** Brama wykonująca operacje odkryte przez {@code search_sentry_tools}. */
    public static final String GATEWAY = "execute_sentry_tool";

    /** Przejrzane narzędzia bezpośrednie potrzebne do diagnozy issue. */
    private static final Set<String> REVIEWED_DIRECT_TOOLS = Set.of(
            "search_issues",
            // search_events i search_issues w trybie języka naturalnego tłumaczą zapytanie przez
            // wbudowanego agenta u zewnętrznego dostawcy LLM. Tu są dopuszczone, bo
            // McpServerLaunch nie przekazuje serwerowi kluczy dostawców: działa wtedy tylko
            // składnia wyszukiwania Sentry. Klucz w środowisku wymaga osobnej zgody.
            "search_events",
            "get_sentry_resource",
            "search_sentry_tools");

    /** Przejrzane operacje katalogowe, które wolno wykonać przez bramę. */
    private static final Set<String> REVIEWED_GATEWAY_TARGETS = Set.of(
            "get_issue_details",
            "get_issue_tag_values",
            "search_issue_events",
            "get_event_stacktrace");

    /**
     * Odczyty technicznie bez zmiany stanu, ale z danymi wrażliwymi: attachment, replay i user
     * feedback wymagają osobnej decyzji o minimalizacji danych.
     */
    private static final Map<String, String> SENSITIVE_READS = Map.of(
            "get_event_attachment", "attachment może zawierać dowolne dane z urządzenia użytkownika",
            "get_issue_user_reports", "user feedback zawiera treść pisaną przez użytkowników",
            "get_replay_details", "replay zawiera zapis sesji użytkownika");

    /** Lokalny przegląd tam, gdzie adnotacje nie wystarczają (patrz {@link ToolEffect#fromAnnotations}). */
    private static final Map<String, ToolEffect> LOCAL_REVIEW = Map.of(
            "analyze_issue_with_seer", ToolEffect.COMPUTE);

    private final Set<String> reviewedDirectTools;
    private final Set<String> reviewedGatewayTargets;

    ToolPolicy(Set<String> reviewedDirectTools, Set<String> reviewedGatewayTargets) {
        this.reviewedDirectTools = Set.copyOf(reviewedDirectTools);
        this.reviewedGatewayTargets = Set.copyOf(reviewedGatewayTargets);
    }

    /** Polityka sesji diagnostycznej: tylko przejrzane odczyty potrzebne do analizy issue. */
    public static ToolPolicy diagnosticReadOnly() {
        return new ToolPolicy(REVIEWED_DIRECT_TOOLS, REVIEWED_GATEWAY_TARGETS);
    }

    /**
     * Decyzja dla jednego narzędzia.
     *
     * @param effect skutek po lokalnym przeglądzie (adnotacje serwera albo {@code LOCAL_REVIEW})
     */
    public record Verdict(String tool, ToolEffect effect, boolean allowed, String reason) {
    }

    /** Ocena każdego narzędzia z katalogu. */
    public List<Verdict> review(List<McpTool> catalog) {
        return catalog.stream().map(tool -> review(tool, reviewedDirectTools)).toList();
    }

    /**
     * Nazwy do {@code enabled_tools} w konfiguracji Codex: tylko narzędzia dopuszczone przez
     * {@link #review}, w kolejności katalogu.
     */
    public List<String> enabledTools(List<McpTool> catalog) {
        return review(catalog).stream().filter(Verdict::allowed).map(Verdict::tool).toList();
    }

    /**
     * Decyzja dla konkretnego wywołania, podejmowana przed wysłaniem {@code tools/call}.
     *
     * @param directTools aktualny wynik {@code tools/list}
     * @param catalog     aktualny katalog bramy ({@code search_sentry_tools}); potrzebny, żeby
     *                    sprawdzić, co serwer deklaruje dla operacji docelowej
     */
    public Verdict checkCall(String tool, Map<String, ?> arguments, List<McpTool> directTools, List<McpTool> catalog) {
        if (!GATEWAY.equals(tool)) {
            return find(directTools, tool)
                    .map(found -> review(found, reviewedDirectTools))
                    .orElseGet(() -> denied(tool, ToolEffect.UNKNOWN,
                            "narzędzia nie ma w aktualnym tools/list"));
        }
        // Brama sama w sobie nie mówi nic o skutku. Oceniamy dokładny target.
        Object target = arguments.get("name");
        if (!(target instanceof String targetName) || targetName.isBlank()) {
            return denied(GATEWAY, ToolEffect.UNKNOWN, "wywołanie bramy bez wskazanej operacji docelowej");
        }
        if (!reviewedGatewayTargets.contains(targetName)) {
            Verdict targetVerdict = find(catalog, targetName)
                    .map(found -> review(found, Set.of()))
                    .orElseGet(() -> denied(targetName, ToolEffect.UNKNOWN, "operacji nie ma w aktualnym katalogu"));
            return denied(GATEWAY + " -> " + targetName, targetVerdict.effect(),
                    (targetVerdict.allowed() ? "odczyt" : targetVerdict.reason())
                            + "; poza listą przejrzanych targetów bramy");
        }
        return find(catalog, targetName)
                .map(found -> review(found, reviewedGatewayTargets))
                .map(verdict -> new Verdict(GATEWAY + " -> " + targetName, verdict.effect(), verdict.allowed(),
                        verdict.allowed() ? "przejrzany target tylko do odczytu" : verdict.reason()))
                .orElseGet(() -> denied(GATEWAY + " -> " + targetName, ToolEffect.UNKNOWN,
                        "operacji nie ma w aktualnym katalogu, np. brak zdolności albo zmiana serwera"));
    }

    private Verdict review(McpTool tool, Set<String> reviewed) {
        ToolEffect effect = LOCAL_REVIEW.getOrDefault(tool.name(), tool.declaredEffect());
        if (GATEWAY.equals(tool.name())) {
            // PUŁAPKA: enabled_tools w Codex dopuszcza bramę w całości. Po jej włączeniu agent
            // może wykonać każdą operację katalogu w granicach przyznanych zdolności: przy
            // domyślnych zdolnościach stdio także create_project, update_dsn czy delete_alert_rule.
            // Kontrolę targetu zapewnia dopiero checkCall albo zatwierdzanie każdego wywołania.
            return denied(tool.name(), effect,
                    "brama otwiera cały katalog w granicach zdolności; tylko pojedyncze wywołania przez checkCall");
        }
        return switch (effect) {
            case WRITE -> denied(tool.name(), effect, "zapis (readOnlyHint=false)");
            case UNKNOWN -> denied(tool.name(), effect, "brak adnotacji albo sprzeczne adnotacje");
            case COMPUTE -> denied(tool.name(), effect, "kosztowne przetwarzanie: wymaga imiennej zgody");
            case READ_ONLY -> {
                if (SENSITIVE_READS.containsKey(tool.name())) {
                    yield denied(tool.name(), effect, "wrażliwy odczyt: " + SENSITIVE_READS.get(tool.name()));
                }
                if (!reviewed.contains(tool.name())) {
                    // Fail closed nie ocenia, czy narzędzie „wygląda bezpiecznie”, tylko czy ktoś
                    // je przejrzał. Nowa operacja w katalogu czeka na przegląd.
                    yield denied(tool.name(), effect, "odczyt spoza przejrzanej listy: nieprzejrzane narzędzia nie są dopuszczane automatycznie");
                }
                yield new Verdict(tool.name(), effect, true, "przejrzany odczyt");
            }
        };
    }

    private static Optional<McpTool> find(List<McpTool> tools, String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst();
    }

    private static Verdict denied(String tool, ToolEffect effect, String reason) {
        return new Verdict(tool, effect, false, reason);
    }
}
