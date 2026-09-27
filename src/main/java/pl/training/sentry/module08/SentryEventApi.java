package pl.training.sentry.module08;

import io.sentry.protocol.SentryId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Odczyt eventu z API Sentry po jego identyfikatorze: dowód, że event dotarł i jak został zapisany.
 *
 * <p>Odbiór potwierdza dopiero event odczytany z Sentry: ten sam event ID, właściwy projekt,
 * environment, release i ramki aplikacji. Tego nie widać w procesie aplikacji:
 * transport SDK działa asynchronicznie, a event może jeszcze zginąć po drodze (zły projekt w DSN,
 * limit, filtr po stronie Sentry). Dlatego dowodem jest odczyt z Sentry, nie wynik
 * {@code captureException}.</p>
 *
 * <p>Konfiguracja ze zmiennych środowiskowych, tych samych co w {@code sentry-cli}:
 * {@code SENTRY_AUTH_TOKEN} (token z uprawnieniem {@code event:read}), {@code SENTRY_ORG}
 * (domyślnie {@code sentry}, organizacja lokalnego self-hosted), {@code SENTRY_PROJECT} (domyślnie
 * {@code sentry-training}) i {@code SENTRY_URL} (domyślnie adres z DSN, co pasuje do self-hosted;
 * dla sentry.io trzeba go podać).</p>
 *
 * <p>PRODUKCJA: token API to poświadczenie pipeline, nie konfiguracja runtime. Aplikacja wysyła
 * event odbiorowy i zapisuje jego ID, a sprawdza go krok pipeline po wdrożeniu. Tu oba kroki są
 * w jednym procesie, żeby dało się je uruchomić jednym poleceniem.</p>
 */
public final class SentryEventApi implements AcceptanceProbe.EventLookup {

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private final String eventsUrl;
    private final String token;
    private final Duration timeout;

    SentryEventApi(String baseUrl, String org, String project, String token, Duration timeout) {
        this.eventsUrl = baseUrl + "/api/0/projects/" + org + "/" + project + "/events/";
        this.token = token;
        this.timeout = timeout;
    }

    /** API skonfigurowane ze zmiennych środowiskowych albo pusto, gdy brakuje DSN lub tokenu. */
    public static Optional<SentryEventApi> fromEnvironment(Map<String, String> env) {
        String dsn = env.get("SENTRY_DSN");
        String token = env.get("SENTRY_AUTH_TOKEN");
        if (dsn == null || dsn.isBlank() || token == null || token.isBlank()) {
            return Optional.empty();
        }
        URI dsnUri = URI.create(dsn);
        String baseUrl = env.getOrDefault("SENTRY_URL",
                dsnUri.getScheme() + "://" + dsnUri.getHost() + (dsnUri.getPort() == -1 ? "" : ":" + dsnUri.getPort()));
        return Optional.of(new SentryEventApi(
                baseUrl,
                env.getOrDefault("SENTRY_ORG", "sentry"),
                env.getOrDefault("SENTRY_PROJECT", "sentry-training"),
                token,
                Duration.ofSeconds(60)));
    }

    /**
     * Czeka, aż event będzie dostępny w API, najdłużej {@code timeout}.
     *
     * <p>Odpowiedź 404 bywa przejściowa: Sentry przetwarza eventy asynchronicznie, więc event
     * wysłany sekundę temu może jeszcze nie istnieć. Dopiero 404 po całym oknie oznacza, że event
     * nie dotarł. Inne kody (401, 403) to błąd sprawdzenia, nie wynik.</p>
     */
    @Override
    public Optional<ReceivedEvent> find(SentryId eventId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(eventsUrl + eventId + "/"))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        Instant deadline = Instant.now().plus(timeout);
        try {
            while (true) {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return Optional.of(parse(response.body()));
                }
                if (response.statusCode() != 404) {
                    throw new IllegalStateException("API Sentry odpowiedziało " + response.statusCode());
                }
                if (Instant.now().isAfter(deadline)) {
                    return Optional.empty();
                }
                Thread.sleep(POLL_INTERVAL);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Brak połączenia z API Sentry: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Przerwano oczekiwanie na event", exception);
        }
    }

    /**
     * Pola odpowiedzi {@code GET /api/0/projects/{org}/{project}/events/{event_id}/}, które
     * potwierdzają odbiór. Release i environment czytamy z tagów, bo tak Sentry je indeksuje
     * i po nich filtruje; ramki z wpisu typu {@code exception}.
     */
    ReceivedEvent parse(String body) {
        JsonNode event = json.readTree(body);
        String release = null;
        String environment = null;
        String synthetic = null;
        for (JsonNode tag : event.path("tags")) {
            switch (tag.path("key").asString()) {
                case "release" -> release = tag.path("value").asString();
                case "environment" -> environment = tag.path("value").asString();
                case AcceptanceProbe.SYNTHETIC_TAG -> synthetic = tag.path("value").asString();
                default -> {
                }
            }
        }
        int inAppFrames = 0;
        for (JsonNode entry : event.path("entries")) {
            if ("exception".equals(entry.path("type").asString())) {
                for (JsonNode exception : entry.path("data").path("values")) {
                    for (JsonNode frame : exception.path("stacktrace").path("frames")) {
                        if (frame.path("inApp").asBoolean()) {
                            inAppFrames++;
                        }
                    }
                }
            }
        }
        return new ReceivedEvent(release, environment, synthetic, inAppFrames);
    }

    /**
     * Event w postaci zapisanej przez Sentry.
     *
     * @param inAppFrames liczba ramek, które Sentry uznało za kod aplikacji
     */
    public record ReceivedEvent(String release, String environment, String syntheticTag, int inAppFrames) {
    }
}
