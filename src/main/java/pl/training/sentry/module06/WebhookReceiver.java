package pl.training.sentry.module06;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import pl.training.sentry.support.SentryRestApi;
import tools.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Pokazuje, co przychodzi w webhooku Alertu: lokalny odbiornik wypisuje, kto wysłał
 * powiadomienie, czego dotyczy i dlaczego.
 *
 * <p>Testowa wiadomość integracji potwierdza tylko połączenie, a dopiero kontrolowany sygnał
 * potwierdza warunek i filtry. Odbiornik zastępuje tu
 * Slacka albo system on-call: pokazuje, co faktycznie przychodzi w powiadomieniu i czego
 * odbiorca potrzebuje, żeby zacząć działać bez otwierania Sentry.</p>
 *
 * <p>Format: Internal Integration wysyła {@code POST} z nagłówkami
 * {@code Sentry-Hook-Resource} ({@code event_alert} dla Alertów na Issues) i
 * {@code Sentry-Hook-Signature} (HMAC-SHA256 treści, klucz: Client Secret integracji). Treść ma
 * {@code data.triggered_rule} (nazwa Alertu) i {@code data.event} (event, który uruchomił Alert,
 * z tagami, adresem Issue i, dla Issue Metric Monitora, {@code occurrence}). Przykład zapisany
 * z lokalnego Sentry 26.9.0 i skrócony do pól, których używa odbiornik:
 * {@code src/main/resources/module06/webhook-event-alert.json}. Pełna treść ma cały event, także
 * wyjątki ze stack trace, więc odbiornik też jest miejscem przetwarzania danych z eventów.</p>
 *
 * <p>PRODUKCJA: odbiornik odrzuca żądanie bez poprawnego podpisu, bo adres webhooka nie jest
 * tajny. Tu podpis jest sprawdzany tylko wtedy, gdy ustawiono {@code SENTRY_WEBHOOK_SECRET},
 * a wynik trafia na wydruk.</p>
 *
 * <p>PUŁAPKA: lokalne self-hosted nie dostarczy webhooka pod adres prywatny (także
 * {@code host.docker.internal} i sieć Docker). Uruchomienie i ograniczenie: {@code README.md} pakietu,
 * „Alerty end-to-end”.</p>
 */
public final class WebhookReceiver implements AutoCloseable {

    public static final int DEFAULT_PORT = 8097;
    public static final String PATH = "/sentry/webhook";

    private final HttpServer server;
    private final ExecutorService workers = Executors.newSingleThreadExecutor();
    private final String secret;
    private final Consumer<Notification> sink;
    private final List<Notification> received = new CopyOnWriteArrayList<>();

    /**
     * @param port   port nasłuchu, 0 oznacza dowolny wolny
     * @param secret Client Secret integracji albo {@code null}, gdy podpis nie jest sprawdzany
     * @param sink   co zrobić z odebranym powiadomieniem (w demo: wydruk)
     */
    public WebhookReceiver(int port, String secret, Consumer<Notification> sink) throws IOException {
        this.secret = secret == null || secret.isBlank() ? null : secret.strip();
        this.sink = sink;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(PATH, this::handle);
        server.setExecutor(workers);
        server.start();
    }

    /** Uruchomienie samodzielne: odbiornik działa do naciśnięcia Enter. */
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("ALERT_WEBHOOK_PORT", String.valueOf(DEFAULT_PORT)));
        try (WebhookReceiver receiver = new WebhookReceiver(port, System.getenv("SENTRY_WEBHOOK_SECRET"),
                notification -> System.out.println(describe(notification)))) {
            System.out.println("Odbiornik webhooków: http://localhost:" + receiver.port() + PATH
                    + " (z kontenerów Sentry: " + AlertingSetup.DEFAULT_WEBHOOK_URL + ")");
            System.out.println("Podpis: " + (receiver.secret == null ? "niesprawdzany (brak SENTRY_WEBHOOK_SECRET)" : "sprawdzany"));
            System.out.println("Enter kończy pracę.");
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public List<Notification> received() {
        return List.copyOf(received);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Signature signature = verify(secret, body, exchange.getRequestHeaders().getFirst("Sentry-Hook-Signature"));
            if (signature == Signature.INVALID) {
                // Odrzucenie przed parsowaniem: treść bez podpisu mógł wysłać każdy, kto zna adres.
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            Notification notification = parse(exchange.getRequestHeaders().getFirst("Sentry-Hook-Resource"), body, signature);
            received.add(notification);
            sink.accept(notification);
            // Szybka odpowiedź 2xx: Sentry ma krótki timeout, a dłuższa praca (ticket, paging)
            // powinna iść asynchronicznie po stronie odbiornika.
            byte[] ok = "OK".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(ok);
            }
        }
    }

    /** Wynik sprawdzenia podpisu. */
    public enum Signature { VALID, INVALID, NOT_CHECKED }

    /** HMAC-SHA256 treści z Client Secret, porównany w stałym czasie z nagłówkiem. */
    public static Signature verify(String secret, String body, String header) {
        if (secret == null) {
            return Signature.NOT_CHECKED;
        }
        if (header == null) {
            return Signature.INVALID;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)))
                    .getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, header.strip().getBytes(StandardCharsets.UTF_8))
                    ? Signature.VALID : Signature.INVALID;
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Powiadomienie w postaci potrzebnej odbiorcy.
     *
     * @param resource  typ zasobu z nagłówka, {@code event_alert} dla Alertów na Issues
     * @param sender    kto wysłał: aktor z treści (dla Alertów aplikacja Sentry)
     * @param alert     dlaczego: nazwa Alertu, który wykonał akcję
     * @param issueId   Issue, którego dotyczy powiadomienie
     * @param title     tytuł eventu
     * @param level     level eventu
     * @param environment środowisko eventu
     * @param tags      tagi eventu, z których odbiorca odczyta komponent i operację
     * @param webUrl    adres eventu w UI Sentry
     * @param metricValue wartość, która przekroczyła próg Metric Monitora, albo {@code null}
     */
    public record Notification(String resource, String sender, String alert, String issueId, String title,
                               String level, String environment, Map<String, String> tags, String webUrl,
                               String metricValue, Signature signature) {
    }

    /** Parsowanie treści webhooka; osobno, żeby test sprawdził je na zapisanym przykładzie. */
    public static Notification parse(String resource, String body, Signature signature) {
        JsonNode root = SentryRestApi.JSON.readTree(body);
        JsonNode data = root.path("data");
        JsonNode event = data.path("event");
        Map<String, String> tags = new LinkedHashMap<>();
        // Tagi w treści webhooka to lista par [klucz, wartość], a nie obiekt.
        for (JsonNode pair : event.path("tags")) {
            tags.put(pair.path(0).asString(), pair.path(1).asString());
        }
        JsonNode value = event.path("occurrence").path("evidenceData").path("value");
        return new Notification(
                resource == null ? "?" : resource,
                root.path("actor").path("name").asString("?") + " (" + root.path("actor").path("type").asString("?") + ")",
                data.path("triggered_rule").asString("?"),
                event.path("issue_id").asString("?"),
                event.path("title").asString("?"),
                event.path("level").asString(tags.getOrDefault("level", "?")),
                event.path("environment").asString(tags.getOrDefault("environment", "?")),
                tags,
                event.path("web_url").asString(""),
                value.isMissingNode() ? null : value.asString(),
                signature);
    }

    /** Czytelny wydruk: od kogo, dlaczego, co i gdzie szukać szczegółów. */
    public static String describe(Notification n) {
        StringBuilder text = new StringBuilder();
        text.append("  <- webhook ").append(n.resource()).append(", podpis: ").append(n.signature()).append('\n');
        text.append("     od:       ").append(n.sender()).append('\n');
        text.append("     dlaczego: Alert „").append(n.alert()).append("”\n");
        text.append("     co:       Issue ").append(n.issueId()).append(", ").append(n.level())
                .append(", environment ").append(n.environment()).append('\n');
        text.append("               ").append(n.title().length() > 100 ? n.title().substring(0, 100) + "..." : n.title()).append('\n');
        if (n.metricValue() != null) {
            text.append("     wartość:  ").append(n.metricValue()).append(" (Metric Monitor)\n");
        }
        // Z tagów odbiorca wie, kto powinien reagować, zanim otworzy Sentry. Brak tagu
        // component w powiadomieniu to brak ownera w kodzie, nie w Alercie.
        text.append("     tagi:     component=").append(n.tags().getOrDefault("component", "(brak)"))
                .append(", operation=").append(n.tags().getOrDefault("operation", "(brak)"))
                .append(", release=").append(n.tags().getOrDefault("release", "(brak)")).append('\n');
        text.append("     szczegóły: ").append(n.webUrl());
        return text.toString();
    }

    @Override
    public void close() {
        server.stop(0);
        workers.shutdownNow();
    }
}
