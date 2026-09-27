package pl.training.sentry.module11;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Jak uruchomić lokalny serwer Sentry MCP w trybie stdio: polecenie, środowisko procesu i preflight.
 *
 * <p>Profil docelowy: token przez {@code SENTRY_ACCESS_TOKEN}, zawężenie przez
 * {@code --skills=inspect} i ograniczenia sesji, środowisko procesu tylko ze zmiennymi
 * potrzebnymi {@code npx}.
 * Zachowanie serwera sprawdzone na {@code @sentry/mcp-server} 0.42.0 (wynik {@code --help}
 * i kod pakietu):</p>
 * <ul>
 *   <li>bez {@code --skills} serwer stdio przyznaje wszystkie aktywne zdolności, czyli także
 *   {@code triage} i {@code project-management}; na hoście innym niż sentry.io pomija tylko
 *   {@code seer};</li>
 *   <li>dla self-hosted token jest wymagany, a {@code --insecure-http} pozwala użyć {@code http://};</li>
 *   <li>{@code --organization-slug} i {@code --project-slug} ustawiają ograniczenia sesji:
 *   argumenty organizacji i projektu znikają ze schematów narzędzi, a {@code find_organizations}
 *   i {@code find_projects} z {@code tools/list};</li>
 *   <li>zmienne {@code SENTRY_DSN} i {@code DEFAULT_SENTRY_DSN} włączają własną telemetrię serwera
 *   MCP (tracing z próbkowaniem 1.0) do projektu z tego DSN;</li>
 *   <li>klucz {@code OPENAI_API_KEY}, {@code ANTHROPIC_API_KEY} albo {@code OPENROUTER_API_KEY}
 *   włącza wbudowanego agenta, który tłumaczy zapytania {@code search_events}
 *   i {@code search_issues} u zewnętrznego dostawcy LLM.</li>
 * </ul>
 *
 * <p>Ten sam profil uruchamia skrypt {@code docker/sentry/mcp/sentry-mcp.sh}, z którego korzysta
 * przykładowa konfiguracja Codex w {@code docker/sentry/mcp/config.toml}.</p>
 *
 * @param command     pełne polecenie procesu
 * @param environment kompletne środowisko procesu (nie dopisujemy go do środowiska rodzica)
 */
public record McpServerLaunch(List<String> command, Map<String, String> environment) {

    /**
     * Przypięta wersja pakietu. {@code npx -y} pobiera i uruchamia kod bez pytania, więc wersja
     * musi być świadomą decyzją, a nie „najnowszą w chwili startu”.
     */
    public static final String PACKAGE = "@sentry/mcp-server@0.42.0";

    public static final String TOKEN_VARIABLE = "SENTRY_ACCESS_TOKEN";

    /**
     * Zmienne potrzebne samemu {@code npx} i Node.js. Wszystko inne ze środowiska rodzica zostaje
     * odcięte, także {@code SENTRY_DSN} ustawiany w trybie online przykładów tego szkolenia.
     */
    private static final List<String> PASS_THROUGH =
            List.of("PATH", "HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "SystemRoot", "TEMP", "TMP");

    private static final List<String> TELEMETRY_VARIABLES = List.of("SENTRY_DSN", "DEFAULT_SENTRY_DSN");
    private static final List<String> LLM_VARIABLES =
            List.of("OPENAI_API_KEY", "ANTHROPIC_API_KEY", "OPENROUTER_API_KEY", "EMBEDDED_AGENT_PROVIDER");

    public McpServerLaunch {
        command = List.copyOf(command);
        environment = Map.copyOf(environment);
    }

    /**
     * Profil docelowy: przypięta wersja, tylko {@code inspect}, jedna organizacja i projekt,
     * token w zmiennej środowiskowej, środowisko procesu zbudowane od zera.
     *
     * @param sentryHost host i port bez schematu, np. {@code localhost:9000}
     * @param insecureHttp {@code true} dla instancji bez TLS (lokalne Sentry z {@code docker/sentry})
     */
    public static McpServerLaunch inspectOnly(String sentryHost, boolean insecureHttp, String organization,
                                              String project, String token, Map<String, String> parentEnvironment) {
        List<String> command = baseCommand(sentryHost, insecureHttp);
        command.add("--skills=inspect");
        command.add("--organization-slug=" + organization);
        command.add("--project-slug=" + project);
        return new McpServerLaunch(command, cleanEnvironment(token, parentEnvironment));
    }

    /**
     * Ustawienia domyślne serwera: bez {@code --skills} i bez ograniczeń sesji. Tylko do
     * porównania katalogów w demo; środowisko jest nadal czyste.
     */
    public static McpServerLaunch serverDefaults(String sentryHost, boolean insecureHttp,
                                                 String token, Map<String, String> parentEnvironment) {
        return new McpServerLaunch(baseCommand(sentryHost, insecureHttp), cleanEnvironment(token, parentEnvironment));
    }

    /**
     * Preflight uruchomienia: lista problemów, pusta oznacza zgodność z profilem docelowym.
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (command.stream().anyMatch(arg -> arg.startsWith("--access-token"))) {
            // PUŁAPKA: argumenty procesu są widoczne w liście procesów (ps), a polecenie
            // wpisane ręcznie zostaje w historii powłoki.
            problems.add("token w argumentach procesu: widoczny w ps i historii powłoki, użyj " + TOKEN_VARIABLE);
        } else if (isBlank(environment.get(TOKEN_VARIABLE))) {
            problems.add("brak " + TOKEN_VARIABLE + ": dla self-hosted token jest wymagany");
        }
        if (command.contains("--all-skills")) {
            problems.add("--all-skills przyznaje wszystkie aktywne zdolności, także seer, triage i project-management");
        } else if (argument("--skills") == null && isBlank(environment.get("MCP_SKILLS"))) {
            problems.add("brak --skills: serwer stdio przyzna wszystkie aktywne zdolności, "
                    + "także triage (update_issue) i project-management (create_project, update_dsn)");
        } else {
            String skills = argument("--skills") != null ? argument("--skills") : environment.get("MCP_SKILLS");
            if (!"inspect".equals(skills.strip())) {
                problems.add("zdolności " + skills + " wykraczają poza profil tylko do odczytu (inspect)");
            }
        }
        if (argument("--organization-slug") == null || argument("--project-slug") == null) {
            problems.add("brak --organization-slug i --project-slug: agent może czytać każdą organizację "
                    + "i każdy projekt dostępny dla tokenu");
        }
        if (command.stream().noneMatch(arg -> arg.startsWith("@sentry/mcp-server@") && !arg.endsWith("@latest"))) {
            problems.add("wersja pakietu nieprzypięta: npx -y uruchomi to, co jest najnowsze w chwili startu");
        }
        for (String variable : TELEMETRY_VARIABLES) {
            if (environment.containsKey(variable)) {
                problems.add(variable + " w środowisku: serwer MCP wyśle własną telemetrię do projektu z tego DSN");
            }
        }
        for (String variable : LLM_VARIABLES) {
            if (environment.containsKey(variable)) {
                problems.add(variable + " w środowisku: zapytania wyszukiwania trafią do zewnętrznego "
                        + "dostawcy LLM, co wymaga osobnej zgody");
            }
        }
        return problems;
    }

    /**
     * Polecenie do wydruku, z ukrytą wartością tokenu, jeśli ktoś podał go w argumentach
     * (w obu formach: {@code --access-token=X} i {@code --access-token X}).
     */
    public String describe() {
        List<String> printable = new ArrayList<>();
        for (int i = 0; i < command.size(); i++) {
            String arg = command.get(i);
            if (arg.startsWith("--access-token=")) {
                printable.add("--access-token=[UKRYTY]");
            } else if (arg.equals("--access-token") && i + 1 < command.size()) {
                printable.add(arg);
                printable.add("[UKRYTY]");
                i++;
            } else {
                printable.add(arg);
            }
        }
        return String.join(" ", printable);
    }

    /** Nazwy zmiennych przekazanych do procesu. Wartości nie wypisujemy. */
    public String describeEnvironment() {
        return environment.keySet().stream().sorted().collect(Collectors.joining(", "));
    }

    private String argument(String name) {
        return command.stream()
                .filter(arg -> arg.startsWith(name + "="))
                .map(arg -> arg.substring(name.length() + 1))
                .findFirst()
                .orElse(null);
    }

    private static List<String> baseCommand(String sentryHost, boolean insecureHttp) {
        List<String> command = new ArrayList<>(List.of(npx(), "-y", PACKAGE, "--host=" + sentryHost));
        if (insecureHttp) {
            // PRODUKCJA: token leci wtedy otwartym tekstem. Dopuszczalne tylko dla localhost.
            command.add("--insecure-http");
        }
        return command;
    }

    private static Map<String, String> cleanEnvironment(String token, Map<String, String> parentEnvironment) {
        Map<String, String> environment = new LinkedHashMap<>();
        for (String name : PASS_THROUGH) {
            if (parentEnvironment.containsKey(name)) {
                environment.put(name, parentEnvironment.get(name));
            }
        }
        if (!isBlank(token)) {
            environment.put(TOKEN_VARIABLE, token);
        }
        return environment;
    }

    private static String npx() {
        // Na Windows npx jest skryptem .cmd, którego ProcessBuilder nie znajdzie pod samą nazwą.
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? "npx.cmd" : "npx";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
