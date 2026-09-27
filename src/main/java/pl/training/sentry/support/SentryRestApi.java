package pl.training.sentry.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Klient REST API Sentry dla narzędzi szkoleniowych, które konfigurują Sentry z kodu
 * (Alerty i Monitory w module 6, zespoły i Ownership Rules w module 3).
 *
 * <p>To narzędzie, nie treść modułu: ścieżki i treść żądań budują klasy modułów, a ten klient
 * tylko je wysyła. Konfiguracja wyłącznie ze zmiennych środowiskowych:</p>
 * <ul>
 *   <li>{@code SENTRY_AUTH_TOKEN}: token osobisty (Settings, Personal Tokens) z zakresami
 *   opisanymi w {@code README.md} modułu;</li>
 *   <li>{@code SENTRY_URL}: adres Sentry, domyślnie {@value #DEFAULT_URL};</li>
 *   <li>{@code SENTRY_ORG}, {@code SENTRY_PROJECT}: slugi organizacji i projektu, domyślnie
 *   {@value #DEFAULT_ORG} i {@value #DEFAULT_PROJECT} (lokalna instancja z docker/sentry).</li>
 * </ul>
 *
 * <p>PUŁAPKA: token w argumencie procesu albo w logu. Dlatego {@link #toString()} go nie
 * pokazuje, a komunikaty błędów zawierają metodę, ścieżkę i fragment odpowiedzi, bez nagłówków.</p>
 */
public final class SentryRestApi {

    public static final String DEFAULT_URL = "http://localhost:9000";
    public static final String DEFAULT_ORG = "sentry";
    public static final String DEFAULT_PROJECT = "sentry-training";

    /** Wspólny mapper JSON: budowanie treści żądań i parsowanie odpowiedzi. */
    public static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final URI baseUrl;
    private final String token;
    private final String org;
    private final String project;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public SentryRestApi(URI baseUrl, String token, String org, String project) {
        // Token w nagłówku żądania bez TLS przeczyta każdy pośrednik w sieci. Wyjątek tylko dla
        // lokalnego Sentry z docker/sentry, które nie ma TLS.
        if (!"https".equals(baseUrl.getScheme()) && !LOOPBACK_HOSTS.contains(baseUrl.getHost())) {
            throw new IllegalArgumentException("Token API wysyłamy tylko przez HTTPS albo do localhost: " + baseUrl);
        }
        this.baseUrl = baseUrl;
        this.token = token;
        this.org = org;
        this.project = project;
    }

    /** Klient z konfiguracji środowiska albo pusty wynik, gdy nie ma tokenu (tryb offline). */
    public static Optional<SentryRestApi> fromEnv(Map<String, String> env) {
        String token = env.get("SENTRY_AUTH_TOKEN");
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new SentryRestApi(
                URI.create(env.getOrDefault("SENTRY_URL", DEFAULT_URL)),
                token.strip(),
                env.getOrDefault("SENTRY_ORG", DEFAULT_ORG),
                env.getOrDefault("SENTRY_PROJECT", DEFAULT_PROJECT)));
    }

    public String org() {
        return org;
    }

    public String project() {
        return project;
    }

    /** Adres strony w UI Sentry, np. {@code /organizations/sentry/issues/42/}. */
    public String webUrl(String path) {
        return baseUrl.resolve(path).toString();
    }

    public JsonNode get(String path) throws IOException, InterruptedException {
        return send("GET", path, null);
    }

    public JsonNode post(String path, JsonNode body) throws IOException, InterruptedException {
        return send("POST", path, body);
    }

    public JsonNode put(String path, JsonNode body) throws IOException, InterruptedException {
        return send("PUT", path, body);
    }

    public JsonNode delete(String path) throws IOException, InterruptedException {
        return send("DELETE", path, null);
    }

    /**
     * Żądanie do {@code /api/0} + {@code path}. Publiczne, żeby testy modułów sprawdziły je bez sieci.
     *
     * @param path ścieżka po {@code /api/0}, np. {@code /organizations/sentry/workflows/}
     */
    public HttpRequest request(String method, String path, JsonNode body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUrl.resolve("/api/0" + path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if (body == null) {
            return builder.method(method, HttpRequest.BodyPublishers.noBody()).build();
        }
        return builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
    }

    private JsonNode send(String method, String path, JsonNode body) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request(method, path, body), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            // 202 i 204 (np. po DELETE) nie mają treści.
            return response.body().isBlank() ? MissingNode.getInstance() : JSON.readTree(response.body());
        }
        String hint = switch (status) {
            case 400 -> "Sentry odrzucił treść żądania";
            case 401 -> "token odrzucony: wygasł, został usunięty albo ma literówkę";
            case 403 -> "token nie ma wymaganego zakresu albo użytkownik nie ma roli w organizacji";
            case 404 -> "nie znaleziono zasobu (slug organizacji, projektu albo identyfikator)";
            default -> "nieoczekiwana odpowiedź";
        };
        String excerpt = response.body().length() > 300 ? response.body().substring(0, 300) + "..." : response.body();
        throw new ApiException(status, method + " " + path + " -> HTTP " + status + ": " + hint + " " + excerpt);
    }

    /** Kodowanie parametru zapytania i segmentu ścieżki. */
    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Override
    public String toString() {
        return "SentryRestApi[" + baseUrl + ", org=" + org + ", project=" + project + ", token=***]";
    }

    /** Błąd HTTP z API Sentry; komunikat bez nagłówków żądania, więc bez tokenu. */
    public static final class ApiException extends IOException {

        private final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
