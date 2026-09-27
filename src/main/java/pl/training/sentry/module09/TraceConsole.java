package pl.training.sentry.module09;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Exporter szkoleniowy: zbiera wyeksportowane spany i wypisuje je jako drzewa trace.
 *
 * <p>Pełni dla OTel tę rolę, którą {@code ConsoleEnvelopeTransport} pełni dla Sentry SDK:
 * pokazuje dokładnie to, co opuściłoby proces, czyli spany zakończone, próbkowane i przekazane
 * przez procesor do exportera. Span, którego tu nie ma, nie dotarłby też do Sentry.</p>
 *
 * <p>Format jednej linii: usługa ({@code service.name} z {@code Resource}), {@code SpanKind},
 * nazwa, czas, status i wybrane atrybuty, a na końcu instrumentation scope. Span, którego rodzic
 * nie został wyeksportowany, stoi na początku drzewa z adnotacją o brakującym rodzicu: tak
 * wygląda fragment rozciętego trace.</p>
 */
public final class TraceConsole implements SpanExporter {

    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
    private static final List<String> SHOWN_ATTRIBUTES = List.of(
            "checkout.order_id", "http.response.status_code", "request.channel", "inventory.sku", "error.type");
    private static final String INDENT = "      ";

    private final List<SpanData> spans = new CopyOnWriteArrayList<>();
    private final AtomicInteger exportCalls = new AtomicInteger();
    private final PrintStream out;

    public TraceConsole(PrintStream out) {
        this.out = out;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> batch) {
        spans.addAll(batch);
        exportCalls.incrementAndGet();
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    /** Liczba spanów wyeksportowanych od ostatniego wydruku. */
    public int exportedCount() {
        return spans.size();
    }

    /** Liczba wywołań {@code export}: przy {@code BatchSpanProcessor} jedno wywołanie to jedna paczka. */
    public int exportCalls() {
        return exportCalls.get();
    }

    /** Wypisuje zebrane spany jako drzewa, po jednym na trace, i czyści bufor. */
    public void print() {
        List<SpanData> batch = drain();
        if (batch.isEmpty()) {
            out.println("  ↳ (exporter nie dostał żadnego spana)");
            return;
        }
        for (List<SpanData> trace : byTrace(batch).values()) {
            out.println("  ↳ trace " + shortId(trace.getFirst().getTraceId()) + " (" + spanCount(trace.size()) + ")");
            Set<String> ids = trace.stream().map(SpanData::getSpanId).collect(Collectors.toSet());
            for (SpanData span : sorted(trace)) {
                if (!ids.contains(span.getParentSpanId())) {
                    printTree(span, trace, 0);
                }
            }
        }
    }

    /**
     * Podsumowanie wielu requestów zamiast drzew: ile trace zapisała usługa wejściowa, ile z nich
     * zawiera spany usługi podrzędnej i ile fragmentów nie ma rodzica. Czyści bufor.
     */
    public void printSamplingSummary(int requests, String entryService, String downstreamService) {
        Map<String, List<SpanData>> traces = byTrace(drain());
        long withEntry = traces.values().stream().filter(t -> hasService(t, entryService)).count();
        long complete = traces.values().stream()
                .filter(t -> hasService(t, entryService) && hasService(t, downstreamService)).count();
        long orphans = traces.values().stream().filter(t -> !hasService(t, entryService)).count();
        out.printf("  ↳ %d requestów: %s zapisał %d trace; pełne: %d, bez %s: %d; "
                        + "fragmenty %s bez rodzica: %d%n",
                requests, entryService, withEntry, complete, downstreamService, withEntry - complete,
                downstreamService, orphans);
    }

    private void printTree(SpanData span, List<SpanData> trace, int depth) {
        StringBuilder line = new StringBuilder(INDENT).append("  ".repeat(depth));
        line.append('[').append(span.getResource().getAttribute(SERVICE_NAME)).append("] ")
                .append(span.getKind()).append(' ')
                .append(span.getName()).append(' ')
                .append(Math.round((span.getEndEpochNanos() - span.getStartEpochNanos()) / 1_000_000.0)).append(" ms");
        if (span.getStatus().getStatusCode() != StatusCode.UNSET) {
            line.append(" status=").append(span.getStatus().getStatusCode());
        }
        for (String key : SHOWN_ATTRIBUTES) {
            Object value = span.getAttributes().asMap().entrySet().stream()
                    .filter(entry -> entry.getKey().getKey().equals(key))
                    .map(Map.Entry::getValue)
                    .findFirst().orElse(null);
            if (value != null) {
                line.append(' ').append(key).append('=').append(value);
            }
        }
        for (EventData event : span.getEvents()) {
            if (event.getName().equals("exception")) {
                line.append(" zdarzenie wyjątku ")
                        .append(event.getAttributes().get(AttributeKey.stringKey("exception.type")));
            }
        }
        line.append(" (").append(span.getInstrumentationScopeInfo().getName()).append(')');
        if (depth == 0 && SpanId.isValid(span.getParentSpanId())) {
            line.append(" | rodzic ").append(shortId(span.getParentSpanId()))
                    .append(span.getParentSpanContext().isRemote() ? " z innej usługi" : "")
                    .append(" nie został wyeksportowany");
        }
        out.println(line);
        for (SpanData child : sorted(trace)) {
            if (child.getParentSpanId().equals(span.getSpanId())) {
                printTree(child, trace, depth + 1);
            }
        }
    }

    private List<SpanData> drain() {
        List<SpanData> batch = new ArrayList<>(spans);
        spans.removeAll(batch);
        return batch;
    }

    private static Map<String, List<SpanData>> byTrace(List<SpanData> batch) {
        // Kolejność trace według startu pierwszego spana, czyli kolejność requestów.
        return sorted(batch).stream().collect(Collectors.groupingBy(
                SpanData::getTraceId, LinkedHashMap::new, Collectors.toList()));
    }

    private static List<SpanData> sorted(List<SpanData> spans) {
        return spans.stream().sorted(Comparator.comparingLong(SpanData::getStartEpochNanos)).toList();
    }

    private static boolean hasService(List<SpanData> trace, String service) {
        return trace.stream().anyMatch(span -> service.equals(span.getResource().getAttribute(SERVICE_NAME)));
    }

    private static String spanCount(int count) {
        int lastTwo = count % 100;
        int last = count % 10;
        if (count == 1) {
            return "1 span";
        }
        boolean few = last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14);
        return count + (few ? " spany" : " spanów");
    }

    private static String shortId(String id) {
        return id.substring(0, 8);
    }
}
