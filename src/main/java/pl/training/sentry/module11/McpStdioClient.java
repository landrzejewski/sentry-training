package pl.training.sentry.module11;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Minimalny klient MCP po stdio: JSON-RPC 2.0, jedna wiadomość na linię.
 *
 * <p>Pokazuje, co klient agentowy (np. Codex) robi przed pierwszym wywołaniem narzędzia:
 * uruchamia proces serwera, negocjuje protokół ({@code initialize}), pobiera katalog
 * ({@code tools/list}) i dopiero potem wywołuje narzędzia ({@code tools/call}).</p>
 *
 * <p>Klient nie podejmuje decyzji o dostępie: przed {@link #callTool} wywołujący sprawdza
 * wywołanie w {@link ToolPolicy#checkCall}.</p>
 */
public final class McpStdioClient implements AutoCloseable {

    /** Wersja protokołu, którą proponuje klient. Serwer 0.42.0 ją akceptuje. */
    static final String PROTOCOL_VERSION = "2025-06-18";

    private static final String END_OF_STREAM = "\u0000EOF";

    private final Process process;
    private final OutputStream toServer;
    private final BlockingQueue<String> fromServer = new LinkedBlockingQueue<>();
    private final Deque<String> stderrTail = new ArrayDeque<>();
    private final Duration timeout;
    private int nextId = 1;

    McpStdioClient(Process process, InputStream serverStdout, OutputStream serverStdin, Duration timeout) {
        this.process = process;
        this.toServer = serverStdin;
        this.timeout = timeout;
        // Osobny wątek czyta stdout serwera, żeby oczekiwanie na odpowiedź mogło mieć timeout.
        // Samo readLine() na strumieniu procesu blokuje bez limitu, gdy serwer się zawiesi.
        Thread.ofVirtual().name("mcp-stdout").start(() -> pump(serverStdout, fromServer::add, () -> fromServer.add(END_OF_STREAM)));
    }

    /**
     * Uruchamia serwer i zwraca klienta. Proces dostaje wyłącznie środowisko z {@code launch},
     * a nie środowisko rodzica.
     */
    public static McpStdioClient start(McpServerLaunch launch, Duration timeout) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(launch.command());
        builder.environment().clear();
        builder.environment().putAll(launch.environment());
        Process process = builder.start();
        McpStdioClient client = new McpStdioClient(process, process.getInputStream(), process.getOutputStream(), timeout);
        // PUŁAPKA: stdout serwera stdio to kanał protokołu, a logi i ostrzeżenia idą na stderr.
        // Nieczytany stderr zapełnia bufor potoku i zatrzymuje serwer w połowie odpowiedzi.
        // Zachowujemy końcówkę, bo przy błędzie startu (zły token, brak npx) to jedyna diagnoza.
        Thread.ofVirtual().name("mcp-stderr").start(() -> pump(process.getErrorStream(), client::rememberStderr, () -> { }));
        return client;
    }

    /**
     * Negocjacja protokołu. Zwraca {@code serverInfo} (nazwa i wersja serwera), które warto
     * zapisać razem ze snapshotem katalogu.
     */
    public JsonNode initialize() {
        ObjectNode params = McpTool.JSON.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "sentry-training-module11").put("version", "1.0");
        JsonNode result = request("initialize", params);
        send(message("notifications/initialized", null, null));
        return result.path("serverInfo");
    }

    /**
     * Pełny katalog narzędzi bezpośrednich, ze wszystkich stron.
     *
     * <p>PUŁAPKA: {@code tools/list} może być stronicowane ({@code nextCursor}). Klient, który
     * czyta tylko pierwszą stronę, buduje allowlistę na niepełnym katalogu, a narzędzia z kolejnych
     * stron omijają przegląd.</p>
     */
    public List<McpTool> listTools() {
        List<McpTool> tools = new ArrayList<>();
        String cursor = null;
        do {
            ObjectNode params = McpTool.JSON.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = request("tools/list", params);
            tools.addAll(McpTool.fromJsonArray(result.path("tools")));
            cursor = result.path("nextCursor").isString() ? result.path("nextCursor").asString() : null;
        } while (cursor != null);
        return tools;
    }

    /**
     * Wywołanie narzędzia. Zwraca {@code result} z odpowiedzi.
     *
     * <p>PUŁAPKA: błąd narzędzia nie jest błędem JSON-RPC. Serwer odpowiada poprawnym
     * {@code result} z {@code isError: true} i opisem w treści, np. „Tool find_projects not
     * found”, gdy ograniczenie sesji usunęło narzędzie. Klient, który sprawdza tylko pole
     * {@code error}, potraktuje porażkę jak dane.</p>
     */
    public JsonNode callTool(String name, Map<String, ?> arguments) {
        ObjectNode params = McpTool.JSON.createObjectNode();
        params.put("name", name);
        params.set("arguments", McpTool.JSON.valueToTree(arguments));
        return request("tools/call", params);
    }

    /** Tekst wyniku narzędzia: połączone elementy {@code content} typu {@code text}. */
    public static String text(JsonNode toolResult) {
        StringBuilder text = new StringBuilder();
        for (JsonNode content : toolResult.path("content").values()) {
            if ("text".equals(content.path("type").asString(""))) {
                text.append(content.path("text").asString(""));
            }
        }
        return text.toString();
    }

    @Override
    public void close() {
        try {
            // Zamknięcie stdin to dla serwera stdio sygnał końca sesji.
            toServer.close();
        } catch (IOException ignored) {
            // Proces mógł już się zakończyć.
        }
        if (process != null) {
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    // npx uruchamia serwer jako proces potomny, więc kończymy całe drzewo.
                    process.descendants().forEach(ProcessHandle::destroy);
                    process.destroy();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private JsonNode request(String method, JsonNode params) {
        int id = nextId++;
        send(message(method, id, params));
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            String line = poll(deadline, method);
            JsonNode message = McpTool.JSON.readTree(line);
            if (message.has("method")) {
                // Serwer może wysłać notyfikację (np. notifications/tools/list_changed) albo własne
                // żądanie. Na żądanie trzeba odpowiedzieć, inaczej serwer będzie czekał.
                if (message.has("id")) {
                    ObjectNode error = McpTool.JSON.createObjectNode();
                    error.put("jsonrpc", "2.0");
                    error.set("id", message.get("id"));
                    error.putObject("error").put("code", -32601).put("message", "Method not supported by client");
                    send(error);
                }
                continue;
            }
            if (message.path("id").asInt(-1) != id) {
                continue;
            }
            if (message.has("error")) {
                throw new McpException(method + " zwróciło błąd JSON-RPC " + message.path("error").path("code").asInt()
                        + ": " + message.path("error").path("message").asString(""));
            }
            return message.path("result");
        }
    }

    private String poll(long deadline, String method) {
        try {
            long remaining = deadline - System.nanoTime();
            String line = remaining > 0 ? fromServer.poll(remaining, TimeUnit.NANOSECONDS) : null;
            if (line == null) {
                throw new McpException("brak odpowiedzi na " + method + " w ciągu " + timeout.toSeconds() + " s" + stderrHint());
            }
            if (END_OF_STREAM.equals(line)) {
                // Znacznik wraca do kolejki, żeby kolejne wywołania też od razu dostały ten sam błąd.
                fromServer.add(END_OF_STREAM);
                throw new McpException("serwer zakończył działanie przed odpowiedzią na " + method + stderrHint());
            }
            return line;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new McpException("przerwano oczekiwanie na " + method);
        }
    }

    private void send(JsonNode message) {
        try {
            // Jedna wiadomość na linię: JSON nie może zawierać znaku nowej linii poza stringami,
            // a writeValueAsString nie formatuje wydruku.
            toServer.write((McpTool.JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8));
            toServer.flush();
        } catch (IOException exception) {
            throw new UncheckedIOException("nie udało się wysłać wiadomości do serwera MCP" + stderrHint(), exception);
        }
    }

    private static ObjectNode message(String method, Integer id, JsonNode params) {
        ObjectNode message = McpTool.JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        if (id != null) {
            message.put("id", id);
        }
        message.put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        return message;
    }

    private static void pump(InputStream stream, Consumer<String> sink, Runnable onEnd) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    sink.accept(line);
                }
            }
        } catch (IOException ignored) {
            // Strumień zamknięty razem z procesem.
        } finally {
            onEnd.run();
        }
    }

    private synchronized void rememberStderr(String line) {
        stderrTail.addLast(line);
        if (stderrTail.size() > 5) {
            stderrTail.removeFirst();
        }
    }

    private synchronized String stderrHint() {
        return stderrTail.isEmpty() ? "" : "; stderr serwera: " + TelemetrySanitizer.redact(String.join(" | ", stderrTail));
    }

    /** Błąd protokołu albo procesu serwera MCP. */
    public static final class McpException extends RuntimeException {
        public McpException(String message) {
            super(message);
        }
    }
}
