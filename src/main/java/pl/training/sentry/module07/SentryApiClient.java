package pl.training.sentry.module07;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Narzędzie odczytu dla agenta: issue, event i lista issues z REST API Sentry, tylko metody GET.
 *
 * <p>Model nie ma dostępu do Sentry, dopóki host nie udostępni mu narzędzia. Ta klasa jest takim
 * narzędziem w najprostszej postaci: klient HTTP z JDK i token API. Sentry MCP udostępnia
 * operacje na danych Sentry w standardowej formie, ale zasady są te same: minimalny zakres
 * tokenu, zakres zasobów z zaufanej sesji ({@link AgentSession}), sekret poza promptem.</p>
 *
 * <p>Konfiguracja wyłącznie ze zmiennych środowiskowych:</p>
 * <ul>
 *   <li>{@code SENTRY_AUTH_TOKEN}: token z zakresami odczytu {@code org:read},
 *   {@code project:read}, {@code event:read};</li>
 *   <li>{@code SENTRY_URL}: adres Sentry, domyślnie {@value #DEFAULT_URL}.</li>
 * </ul>
 *
 * <p>PUŁAPKA: token w argumencie procesu, pliku repozytorium albo w logu. Token API jest
 * sekretem: nie trafia do promptu, historii czatu ani logów. Dlatego {@link #toString()} go nie
 * pokazuje, a komunikaty błędów nie zawierają nagłówków.</p>
 */
public final class SentryApiClient {

    public static final String DEFAULT_URL = "http://localhost:9000";

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final URI baseUrl;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    SentryApiClient(URI baseUrl, String token) {
        // Token w nagłówku żądania HTTP bez TLS może przeczytać każdy pośrednik w sieci.
        // Wyjątek robimy tylko dla lokalnego Sentry z docker/sentry, które nie ma TLS.
        if (!"https".equals(baseUrl.getScheme()) && !LOOPBACK_HOSTS.contains(baseUrl.getHost())) {
            throw new IllegalArgumentException("Token API wysyłamy tylko przez HTTPS albo do localhost: " + baseUrl);
        }
        this.baseUrl = baseUrl;
        this.token = token;
    }

    /** Klient z konfiguracji środowiska albo pusty wynik, gdy nie ma tokenu (tryb offline). */
    public static Optional<SentryApiClient> fromEnv(Map<String, String> env) {
        String token = env.get("SENTRY_AUTH_TOKEN");
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new SentryApiClient(URI.create(env.getOrDefault("SENTRY_URL", DEFAULT_URL)), token.strip()));
    }

    /** Szczegóły issue: tytuł, culprit, status, priorytet, liczniki, first i last seen. */
    public Object issue(AgentSession session, String issueId) throws IOException, InterruptedException {
        return get("/api/0/organizations/" + encode(session.organization()) + "/issues/" + encode(issueId) + "/");
    }

    /**
     * Jedna strona listy issues.
     *
     * @param hasMore {@code true}, gdy nagłówek {@code Link} wskazuje następną stronę z wynikami
     */
    public record Page(List<?> items, boolean hasMore) {
    }

    /**
     * Pierwsza strona nierozwiązanych issues projektu w środowisku sesji, widzianych w ostatnich 24 h.
     *
     * <p>PUŁAPKA: endpoint projektowy {@code /api/0/projects/{org}/{project}/issues/} nie filtruje
     * issues po czasie, {@code statsPeriod=24h} wybiera tam tylko okres wykresu w odpowiedzi.
     * Prompt briefingu mówi modelowi „okno=ostatnie 24 h”, więc lista musi przyjść z endpointu
     * organizacji, który zamienia {@code statsPeriod} na zakres dat wyszukiwania. Ten endpoint
     * przyjmuje projekt jako identyfikator liczbowy, dlatego najpierw odczytujemy projekt.</p>
     */
    public Page unresolvedIssues(AgentSession session, int limit) throws IOException, InterruptedException {
        Object project = get("/api/0/projects/" + encode(session.organization()) + "/" + encode(session.project()) + "/");
        String projectId = project instanceof Map<?, ?> fields ? String.valueOf(fields.get("id")) : "";
        HttpResponse<String> response = send("/api/0/organizations/" + encode(session.organization())
                + "/issues/?project=" + encode(projectId) + "&query=" + encode("is:unresolved")
                + "&statsPeriod=24h&limit=" + limit + "&environment=" + encode(session.environment()));
        // API dzieli wyniki na strony. Kursor następnej strony jest w nagłówku Link
        // z rel="next"; results="true" oznacza, że ta strona istnieje.
        boolean hasMore = response.headers().firstValue("Link")
                .map(link -> link.contains("rel=\"next\"; results=\"true\""))
                .orElse(false);
        return new Page(Json.parse(response.body()) instanceof List<?> list ? list : List.of(), hasMore);
    }

    /**
     * Czeka, aż event wysłany przez SDK będzie dostępny w API.
     *
     * <p>Wysłanie przez SDK nie oznacza, że event od razu da się odczytać: Sentry najpierw
     * przetwarza go asynchronicznie (w lokalnym Sentry zwykle kilka sekund). Do tego czasu
     * API odpowiada 404.</p>
     */
    public Object awaitEvent(AgentSession session, String eventId, Duration timeout)
            throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        String path = "/api/0/projects/" + encode(session.organization()) + "/" + encode(session.project())
                + "/events/" + encode(eventId) + "/";
        while (true) {
            try {
                return get(path);
            } catch (SentryApiException exception) {
                if (exception.status() != 404 || Instant.now().isAfter(deadline)) {
                    throw exception;
                }
                Thread.sleep(1_000);
            }
        }
    }

    /** Żądanie GET z tokenem. Pakietowo widoczne, żeby test sprawdził je bez sieci. */
    HttpRequest request(String path) {
        return HttpRequest.newBuilder(baseUrl.resolve(path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    private Object get(String path) throws IOException, InterruptedException {
        return Json.parse(send(path).body());
    }

    private HttpResponse<String> send(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request(path), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status == 200) {
            return response;
        }
        String hint = switch (status) {
            case 401 -> "token odrzucony: wygasł, został usunięty albo ma literówkę";
            case 403 -> "token nie ma wymaganego zakresu (org:read, project:read, event:read) albo dostępu do projektu";
            case 404 -> "nie znaleziono zasobu (albo event nie został jeszcze przetworzony)";
            default -> "nieoczekiwana odpowiedź";
        };
        throw new SentryApiException(status, "GET " + path + " -> HTTP " + status + ": " + hint);
    }

    /** Kodowanie segmentu ścieżki i parametru zapytania; identyfikatory nie zmienią ścieżki żądania. */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "SentryApiClient[" + baseUrl + ", token=***]";
    }

    /** Błąd HTTP z API Sentry; komunikat bez nagłówków żądania, więc bez tokenu. */
    public static final class SentryApiException extends IOException {

        private final int status;

        SentryApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
