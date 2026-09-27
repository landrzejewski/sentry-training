package pl.training.sentry.module06;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.sentry.ISentryLifecycleToken;
import io.sentry.Sentry;
import pl.training.sentry.module06.BankGateway.BankTimeoutException;
import pl.training.sentry.support.TrainingSentry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pokazuje endpointy zdrowia payments-api dla Uptime Monitora i kontynuację trace próby
 * w evencie aplikacji (scenariusz 7).
 *
 * <p>Dwa endpointy odpowiadają na różne pytania:</p>
 * <ul>
 *   <li>{@code /live}: czy proces odpowiada. Zwraca 200 zawsze, także gdy bank nie działa;</li>
 *   <li>{@code /health}: czy usługa może obsłużyć płatność. Sprawdza bank i zwraca 503, gdy
 *   bank jest nieosiągalny.</li>
 * </ul>
 * <p>PUŁAPKA: Uptime Monitor ustawiony na {@code /live} pokaże dostępność, gdy klienci nie mogą
 * zapłacić. Monitor sprawdza tylko to, co endpoint faktycznie weryfikuje.</p>
 *
 * <p>Każda próba Uptime Monitora niesie nagłówki {@code User-Agent: SentryUptimeBot}
 * i {@code sentry-trace}. Endpoint kontynuuje ten trace, więc event wysłany w trakcie próby
 * ma identyfikator trace sprawdzenia i da się go powiązać z Uptime Issue.</p>
 *
 * <p>Uruchomienie samodzielne dla prawdziwego Uptime Monitora: {@link #main}.</p>
 */
public final class HealthEndpoint implements AutoCloseable {

    private final BankGateway bank;
    private final HttpServer server;
    private final ExecutorService workers = Executors.newFixedThreadPool(2);

    public HealthEndpoint(BankGateway bank, int port) throws IOException {
        this.bank = bank;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/live", exchange -> respond(exchange, 200, "UP"));
        server.createContext("/health", this::health);
        server.setExecutor(workers);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void health(HttpExchange exchange) throws IOException {
        // Request na wątku puli, więc własny isolation scope, jak w każdym requeście.
        try (ISentryLifecycleToken requestScope = Sentry.pushIsolationScope()) {
            // Kontynuacja trace sprawdzenia. Działa także bez włączonego tracingu: SDK zapisuje
            // trace z nagłówka w scope, a każdy event z tego requestu dostaje ten trace_id.
            // Bez nagłówka (ruch użytkownika, load balancer) SDK zaczyna dla requestu nowy trace.
            Sentry.continueTrace(
                    exchange.getRequestHeaders().getFirst("sentry-trace"),
                    exchange.getRequestHeaders().get("baggage"));
            // Każda próba Uptime Monitora ma User-Agent SentryUptimeBot, więc tag odróżnia ruch
            // sprawdzeń od ruchu użytkowników. Ma dwie wartości: nadaje się do filtra Alertu.
            String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
            Sentry.setTag("traffic", userAgent != null && userAgent.startsWith("SentryUptimeBot")
                    ? "uptime-check" : "user");
            try {
                // Lekki ping, a nie operacja biznesowa: sprawdzenie idzie co minutę z kilku
                // lokalizacji i nie może obciążać banku ani niczego zmieniać.
                bank.ping();
                respond(exchange, 200, "{\"status\":\"UP\",\"bank\":\"UP\"}");
            } catch (BankTimeoutException bankDown) {
                PaymentTelemetry.reportBankTimeout(bankDown, "health-check", Map.of("endpoint", "/health"));
                // PRODUKCJA: odpowiedź bez szczegółów błędu i bez sekretów; endpoint jest publiczny.
                respond(exchange, 503, "{\"status\":\"DOWN\",\"bank\":\"DOWN\"}");
            }
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        workers.shutdownNow();
    }

    /**
     * Endpoint uruchomiony na stałe, do podłączenia prawdziwego Uptime Monitora.
     *
     * <p>Port z {@code MODULE06_HEALTH_PORT} (domyślnie 8086). Stan banku przełącza się na
     * konsoli: Enter przełącza bank między AVAILABLE i TIMING_OUT, Ctrl+C kończy program.
     * Konfiguracja monitora: {@code README.md}, „Uptime Monitor dla /health”.</p>
     */
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("MODULE06_HEALTH_PORT", "8086"));
        BankGateway bank = new BankGateway();
        try (TrainingSentry.TrainingSession session = TrainingSentry.init("module06", PaymentTelemetry::configure);
             HealthEndpoint endpoint = new HealthEndpoint(bank, port)) {
            System.out.println("Endpointy: http://localhost:" + endpoint.port() + "/health i /live");
            System.out.println("Enter przełącza stan banku, Ctrl+C kończy.");
            BufferedReader console = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            boolean available = true;
            while (console.readLine() != null) {
                available = !available;
                bank.switchTo(available ? BankGateway.Mode.AVAILABLE : BankGateway.Mode.TIMING_OUT);
                System.out.println("Bank: " + (available ? "AVAILABLE, /health zwraca 200" : "TIMING_OUT, /health zwraca 503"));
            }
            // Wejście zamknięte (np. uruchomienie w tle): endpoint działa do Ctrl+C.
            new CountDownLatch(1).await();
        }
    }

    /**
     * Nagłówki próby Uptime Monitora; demo i test naśladują nimi prawdziwą próbę. Wartość
     * User-Agent pochodzi z uptime-checker 26.9.0 lokalnego self-hosted.
     */
    public static Map<String, List<String>> uptimeProbeHeaders(String sentryTrace) {
        return Map.of("User-Agent", List.of("SentryUptimeBot/1.0 (+http://docs.sentry.io/product/alerts/uptime-monitoring/)"),
                "sentry-trace", List.of(sentryTrace));
    }
}
