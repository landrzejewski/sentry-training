package pl.training.sentry.module03;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Zewnętrzna bramka płatności uruchomiona lokalnie na {@code com.sun.net.httpserver.HttpServer}.
 *
 * <p>Zastępuje dostawcę płatności, żeby błędy w scenariuszach były prawdziwe: timeout zgłasza
 * {@code java.net.http.HttpClient}, a stack trace zawiera ramki JDK tak jak w produkcji. Dzięki
 * temu widać, które ramki należą do aplikacji, a które do JDK.</p>
 *
 * <p>Zachowanie ustala się osobno dla każdego zamówienia metodą {@link #respond}. Klasa nie zna
 * Sentry: to część otoczenia aplikacji, a nie telemetria.</p>
 */
public final class FakePaymentGateway implements AutoCloseable {

    /** Odpowiedź bramki na jedno wywołanie. */
    public enum Reply {
        /** HTTP 200, decyzja AUTHORIZED. */
        AUTHORIZED,
        /** HTTP 200, decyzja PENDING: klient ma potwierdzić płatność w aplikacji banku. */
        PENDING,
        /** Odpowiedź wolniejsza niż limit czasu klienta, więc klient zgłasza timeout. */
        SLOW,
        /** HTTP 503. */
        UNAVAILABLE,
        /** HTTP 429. */
        RATE_LIMITED
    }

    private final HttpServer server;
    private final ExecutorService workers;
    private final Duration slowDelay;
    private final Map<String, ConcurrentLinkedDeque<Reply>> scripts = new ConcurrentHashMap<>();

    private FakePaymentGateway(HttpServer server, ExecutorService workers, Duration slowDelay) {
        this.server = server;
        this.workers = workers;
        this.slowDelay = slowDelay;
    }

    /**
     * Startuje bramkę na losowym wolnym porcie interfejsu loopback.
     *
     * @param slowDelay czas odpowiedzi {@link Reply#SLOW}; musi przekraczać limit czasu klienta
     */
    public static FakePaymentGateway start(Duration slowDelay) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // Wirtualne wątki: wolna odpowiedź nie blokuje obsługi kolejnych zapytań.
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        FakePaymentGateway gateway = new FakePaymentGateway(server, workers, slowDelay);
        server.createContext("/payments/", gateway::handle);
        server.setExecutor(workers);
        server.start();
        return gateway;
    }

    public URI baseUri() {
        InetSocketAddress address = server.getAddress();
        return URI.create("http://" + address.getAddress().getHostAddress() + ":" + address.getPort() + "/");
    }

    /**
     * Ustala kolejne odpowiedzi dla zamówienia: każde wywołanie (autoryzacja albo odpytanie
     * o status) zużywa jedną. Ostatnia odpowiedź powtarza się bez końca. Zamówienie bez
     * scenariusza dostaje {@link Reply#AUTHORIZED}.
     */
    public void respond(String orderId, Reply... replies) {
        scripts.put(orderId, new ConcurrentLinkedDeque<>(List.of(replies)));
    }

    private void handle(HttpExchange exchange) {
        try (exchange) {
            // Ścieżka: /payments/{orderId} (autoryzacja) albo /payments/{orderId}/status
            String orderId = exchange.getRequestURI().getPath().split("/")[2];
            switch (nextReply(orderId)) {
                case AUTHORIZED -> send(exchange, 200, "{\"decision\":\"AUTHORIZED\"}");
                case PENDING -> send(exchange, 200, "{\"decision\":\"PENDING\"}");
                case SLOW -> {
                    Thread.sleep(slowDelay);
                    send(exchange, 200, "{\"decision\":\"AUTHORIZED\"}");
                }
                case UNAVAILABLE -> send(exchange, 503, "{\"error\":\"maintenance\"}");
                case RATE_LIMITED -> send(exchange, 429, "{\"error\":\"too_many_requests\"}");
            }
        } catch (IOException | InterruptedException ignored) {
            // Klient już zrezygnował (timeout) albo bramka jest zamykana: odpowiedź nie ma odbiorcy.
        }
    }

    private Reply nextReply(String orderId) {
        ConcurrentLinkedDeque<Reply> script = scripts.get(orderId);
        if (script == null || script.isEmpty()) {
            return Reply.AUTHORIZED;
        }
        return script.size() > 1 ? script.pollFirst() : script.peekFirst();
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
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
        // Przerywa odpowiedzi SLOW, które jeszcze śpią.
        workers.shutdownNow();
    }
}
