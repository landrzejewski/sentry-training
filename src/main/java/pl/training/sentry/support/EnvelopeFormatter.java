package pl.training.sentry.support;

import io.sentry.Breadcrumb;
import io.sentry.SentryEvent;
import io.sentry.SentryLogEvent;
import io.sentry.SentryMetricsEvent;
import io.sentry.Session;
import io.sentry.SpanContext;
import io.sentry.clientreport.DiscardedEvent;
import io.sentry.protocol.FeatureFlag;
import io.sentry.protocol.FeatureFlags;
import io.sentry.protocol.Mechanism;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentrySpan;
import io.sentry.protocol.SentryTransaction;
import io.sentry.protocol.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Czytelny, tekstowy opis {@link CapturedItem} do wydruku w konsoli demo.
 *
 * <p>Pokazuje tylko pola, na które patrzy się przy diagnozie: wyjątek, release,
 * environment, tagi, własne contexts, user i breadcrumbs. Standardowe contexts
 * dodawane automatycznie przez SDK (runtime, os) są pomijane, żeby nie zasłaniały
 * tego, co ustawił kod aplikacji. Pełną treść pokazuje tryb JSON.</p>
 */
public final class EnvelopeFormatter {

    private static final Set<String> SDK_CONTEXTS =
            Set.of("os", "runtime", "trace", "device", "app", "culture", "gpu", "browser", "profile", "spring");
    private static final int MAX_BREADCRUMBS = 10;
    private static final String INDENT = "      ";

    public String format(CapturedItem item) {
        return switch (item) {
            case CapturedItem.Event e -> formatEvent(e.event());
            case CapturedItem.Transaction t -> formatTransaction(t.transaction());
            case CapturedItem.Logs l -> formatLogs(l.logs());
            case CapturedItem.Metrics m -> formatMetrics(m.metrics());
            case CapturedItem.SessionUpdate s -> formatSession(s.session());
            case CapturedItem.CheckIn c -> formatCheckIn(c.checkIn());
            case CapturedItem.ClientReport r -> formatClientReport(r.report());
            case CapturedItem.Other o -> "  ↳ item " + o.type() + ": " + abbreviate(o.json(), 160);
        };
    }

    private String formatEvent(SentryEvent event) {
        StringBuilder out = new StringBuilder();
        out.append("  ↳ event ").append(shortId(String.valueOf(event.getEventId())))
                .append(" | level=").append(event.getLevel() == null
                        ? "error (domyślny, SDK nie ustawiło jawnie)"
                        : lower(event.getLevel()))
                .append(" | handled=").append(handled(event))
                .append('\n');

        List<SentryException> exceptions = event.getExceptions();
        if (exceptions != null && !exceptions.isEmpty()) {
            // Protokół Sentry zapisuje łańcuch od najgłębszej przyczyny do wyjątku zewnętrznego,
            // więc odwracamy kolejność, żeby czytać go tak jak stack trace w Javie.
            List<SentryException> chain = new ArrayList<>(exceptions);
            java.util.Collections.reverse(chain);
            line(out, "wyjątek", describe(chain.getFirst()));
            for (SentryException cause : chain.subList(1, chain.size())) {
                line(out, "caused by", describe(cause));
            }
        }
        // Event z integracji logowania ma jednocześnie wyjątek, treść wpisu i nazwę loggera.
        if (event.getMessage() != null) {
            String message = event.getMessage().getFormatted() != null
                    ? event.getMessage().getFormatted()
                    : event.getMessage().getMessage();
            line(out, "wiadomość", message);
        }
        if (event.getLogger() != null) {
            line(out, "logger", event.getLogger());
        }

        line(out, "release", orMissing(event.getRelease(), "nie powiążesz eventu z wersją artefaktu"));
        line(out, "environment", orMissing(event.getEnvironment(), "nie odróżnisz produkcji od stagingu"));
        if (event.getTransaction() != null) {
            line(out, "transaction", event.getTransaction());
        }
        line(out, "tags", formatMap(event.getTags()));
        line(out, "contexts", formatContexts(event));
        line(out, "user", formatUser(event.getUser()));
        // Pole fingerprint istnieje tylko wtedy, gdy kod aplikacji ustawił je jawnie. Bez niego
        // Sentry grupuje event domyślnie (m.in. po stack trace i typie wyjątku).
        if (event.getFingerprints() != null && !event.getFingerprints().isEmpty()) {
            line(out, "fingerprint", String.join(", ", event.getFingerprints()));
        }

        List<Breadcrumb> breadcrumbs = event.getBreadcrumbs();
        if (breadcrumbs == null || breadcrumbs.isEmpty()) {
            line(out, "breadcrumbs", "(brak)");
        } else {
            int from = Math.max(0, breadcrumbs.size() - MAX_BREADCRUMBS);
            for (int i = from; i < breadcrumbs.size(); i++) {
                line(out, i == from ? "breadcrumbs" : "", formatBreadcrumb(breadcrumbs.get(i)));
            }
        }

        SpanContext trace = event.getContexts().getTrace();
        if (trace != null) {
            line(out, "trace", shortId(trace.getTraceId().toString()) + " | span " + shortId(trace.getSpanId().toString()));
        }
        return out.toString().stripTrailing();
    }

    private String formatTransaction(SentryTransaction transaction) {
        StringBuilder out = new StringBuilder();
        SpanContext trace = transaction.getContexts().getTrace();
        out.append("  ↳ transakcja \"").append(transaction.getTransaction()).append('"');
        if (trace != null) {
            out.append(" | op=").append(trace.getOperation())
                    .append(" | status=").append(lower(trace.getStatus()))
                    .append(" | trace ").append(shortId(trace.getTraceId().toString()))
                    .append(" | span ").append(shortId(trace.getSpanId().toString()));
            // Rodzic spoza transakcji oznacza kontynuację trace z innej usługi (albo z przeglądarki).
            if (trace.getParentSpanId() != null) {
                out.append(" | parent ").append(shortId(trace.getParentSpanId().toString()));
            }
            String flags = formatFlagData(trace.getData());
            if (!flags.isEmpty()) {
                out.append(" | ").append(flags);
            }
        }
        out.append(" | ").append(durationMs(transaction.getStartTimestamp(), transaction.getTimestamp()))
                .append('\n');
        // Spany w kolejności startu z przesunięciem względem początku transakcji: tekstowy waterfall.
        List<SentrySpan> spans = new ArrayList<>(transaction.getSpans());
        spans.sort(java.util.Comparator.comparing(SentrySpan::getStartTimestamp));
        for (SentrySpan span : spans) {
            line(out, "span", span.getOp() + " \"" + span.getDescription() + "\""
                    + " | +" + durationMs(transaction.getStartTimestamp(), span.getStartTimestamp())
                    + ", " + durationMs(span.getStartTimestamp(), span.getTimestamp())
                    + " | status=" + lower(span.getStatus())
                    + " | span " + shortId(span.getSpanId().toString())
                    + (span.getParentSpanId() != null && trace != null && !span.getParentSpanId().equals(trace.getSpanId())
                            ? " | parent " + shortId(span.getParentSpanId().toString())
                            : ""));
        }
        return out.toString().stripTrailing();
    }

    private String formatLogs(List<SentryLogEvent> logs) {
        return logs.stream()
                .map(log -> "  ↳ log [" + lower(log.getLevel()) + "] " + log.getBody()
                        + (log.getTraceId() == null ? "" : " | trace " + shortId(log.getTraceId().toString()))
                        + (log.getSpanId() == null ? "" : " span " + shortId(log.getSpanId().toString()))
                        + formatLogAttributes(log))
                .collect(Collectors.joining("\n"));
    }

    private String formatLogAttributes(SentryLogEvent log) {
        if (log.getAttributes() == null) {
            return "";
        }
        // Atrybuty „sentry.*” dodaje SDK (wersja, release, environment). Pomijamy je w skrócie.
        Map<String, Object> own = new TreeMap<>();
        log.getAttributes().forEach((key, value) -> {
            if (!key.startsWith("sentry.")) {
                own.put(key, value.getValue());
            }
        });
        return own.isEmpty() ? "" : " | " + own;
    }

    private String formatMetrics(List<SentryMetricsEvent> metrics) {
        return metrics.stream()
                .map(metric -> "  ↳ metryka " + String.format("%-13s", metric.getType()) + metric.getName()
                        + " = " + number(metric.getValue())
                        + (metric.getUnit() == null ? "" : " " + metric.getUnit())
                        + formatMetricAttributes(metric))
                .collect(Collectors.joining("\n"));
    }

    private String formatMetricAttributes(SentryMetricsEvent metric) {
        if (metric.getAttributes() == null) {
            return "";
        }
        // Pomijamy atrybuty „sentry.*” (release, environment, wersja SDK) i server.address, które SDK
        // dodaje do każdej metryki. Atrybuty „user.*” zostają: SDK kopiuje je ze scope i ich obecność
        // bywa zaskoczeniem. Pełną listę pokazuje tryb JSON.
        Map<String, Object> own = new TreeMap<>();
        metric.getAttributes().forEach((key, value) -> {
            if (!key.startsWith("sentry.") && !key.equals("server.address")) {
                own.put(key, value.getValue());
            }
        });
        return own.isEmpty() ? "" : " | " + own.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private String formatSession(Session session) {
        return "  ↳ sesja " + shortId(String.valueOf(session.getSessionId()))
                + " | status=" + lower(session.getStatus())
                + " | errors=" + session.errorCount()
                + (Boolean.TRUE.equals(session.getInit()) ? " | init (start sesji)" : "")
                + " | release=" + orMissing(session.getRelease(), "sesja bez release nie trafi do Release Health")
                + " | environment=" + orMissing(session.getEnvironment(), "brak środowiska")
                + " | distinctId=" + (session.getDistinctId() == null ? "(brak)" : session.getDistinctId());
    }

    private String formatCheckIn(io.sentry.CheckIn checkIn) {
        // Identyfikator łączy check-in początkowy z końcowym, a trace łączy uruchomienie z eventami
        // błędów wysłanymi w jego trakcie. Bez nich nie da się sparować wpisów na wydruku.
        SpanContext trace = checkIn.getContexts().getTrace();
        String line = "  ↳ check-in monitor=" + checkIn.getMonitorSlug()
                + " | status=" + checkIn.getStatus()
                + " | id " + shortId(checkIn.getCheckInId().toString())
                + (checkIn.getDuration() == null ? "" : " | czas=" + Math.round(checkIn.getDuration() * 1000) + " ms")
                + " | environment=" + orMissing(checkIn.getEnvironment(), "brak środowiska")
                + (trace == null ? "" : " | trace " + shortId(trace.getTraceId().toString()));
        io.sentry.MonitorConfig config = checkIn.getMonitorConfig();
        if (config == null) {
            return line;
        }
        // Konfigurację monitora zawiera tylko check-in, do którego kod ją dołączył; na jej
        // podstawie Sentry tworzy albo aktualizuje monitor.
        io.sentry.MonitorSchedule schedule = config.getSchedule();
        return line + "\n" + INDENT + String.format("%-12s", "config")
                + schedule.getType() + " " + schedule.getValue()
                + (schedule.getUnit() == null ? "" : " " + schedule.getUnit())
                + (config.getTimezone() == null ? "" : ", timezone=" + config.getTimezone())
                + ", margin=" + config.getCheckinMargin() + " min"
                + ", max_runtime=" + config.getMaxRuntime() + " min"
                + ", failure_issue_threshold=" + config.getFailureIssueThreshold()
                + ", recovery_threshold=" + config.getRecoveryThreshold();
    }

    private String formatClientReport(io.sentry.clientreport.ClientReport report) {
        List<DiscardedEvent> discarded = report.getDiscardedEvents();
        if (discarded == null || discarded.isEmpty()) {
            return "  ↳ client report: SDK niczego nie odrzuciło";
        }
        return "  ↳ client report: SDK odrzuciło lokalnie " + discarded.stream()
                .map(d -> d.getCategory() + " x" + d.getQuantity() + " (powód: " + d.getReason() + ")")
                .collect(Collectors.joining(", "));
    }

    private static String describe(SentryException exception) {
        String type = exception.getModule() == null
                ? exception.getType()
                : exception.getModule() + "." + exception.getType();
        return type + ": " + exception.getValue();
    }

    private static String handled(SentryEvent event) {
        List<SentryException> exceptions = event.getExceptions();
        if (exceptions == null || exceptions.isEmpty()) {
            return "n/d";
        }
        Mechanism mechanism = exceptions.getLast().getMechanism();
        if (mechanism == null) {
            return "tak (brak mechanizmu, czyli ręczny capture)";
        }
        if (mechanism.isHandled() == null) {
            // Ręczny captureException dostaje w Java SDK mechanizm „chained” bez pola handled,
            // a integracje raportujące nieobsłużone wyjątki ustawiają jawnie handled=false.
            // Integracja Logback też nie ustawia handled (mechanism=LogbackSentryAppender).
            return "tak (mechanism=" + mechanism.getType() + " bez pola handled"
                    + ("chained".equals(mechanism.getType()) ? ", czyli ręczny capture)" : ")");
        }
        return (mechanism.isHandled() ? "tak" : "nie") + " (mechanism=" + mechanism.getType() + ")";
    }

    private static String formatContexts(SentryEvent event) {
        Map<String, Object> own = new TreeMap<>();
        for (Map.Entry<String, Object> entry : event.getContexts().entrySet()) {
            if (entry.getValue() instanceof FeatureFlags flags) {
                // Protokołowa klasa FeatureFlags nie ma toString(), więc wypisujemy pary flaga=wynik.
                own.put(entry.getKey(), flags.getValues().stream()
                        .collect(Collectors.toMap(FeatureFlag::getFlag, FeatureFlag::getResult, (a, b) -> b, TreeMap::new)));
            } else if (!SDK_CONTEXTS.contains(entry.getKey())) {
                own.put(entry.getKey(), entry.getValue());
            }
        }
        return own.isEmpty() ? "(brak własnych)" : own.toString();
    }

    /** Wyniki flag zapisane przez SDK w danych spanu jako {@code flag.evaluation.<nazwa>}. */
    private static String formatFlagData(Map<String, Object> data) {
        if (data == null) {
            return "";
        }
        Map<String, Object> flags = new TreeMap<>();
        data.forEach((key, value) -> {
            if (key.startsWith(FeatureFlag.DATA_PREFIX)) {
                flags.put(key, value);
            }
        });
        return flags.isEmpty() ? "" : flags.toString();
    }

    private static String formatUser(User user) {
        if (user == null) {
            return "(brak)";
        }
        List<String> parts = new ArrayList<>();
        if (user.getId() != null) parts.add("id=" + user.getId());
        if (user.getUsername() != null) parts.add("username=" + user.getUsername());
        if (user.getEmail() != null) parts.add("email=" + user.getEmail());
        if (user.getIpAddress() != null) parts.add("ip=" + user.getIpAddress());
        return parts.isEmpty() ? "(pusty)" : String.join(", ", parts);
    }

    private static String formatBreadcrumb(Breadcrumb breadcrumb) {
        String data = breadcrumb.getData().isEmpty() ? "" : " " + new TreeMap<>(breadcrumb.getData());
        // Breadcrumbs integracji (np. http) nie mają wiadomości, tylko dane.
        String message = breadcrumb.getMessage() == null ? "" : " " + breadcrumb.getMessage();
        return "[" + breadcrumb.getCategory() + "]" + message + data;
    }

    private static String formatMap(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "(brak)";
        }
        return new TreeMap<>(map).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private static void line(StringBuilder out, String label, String value) {
        out.append(INDENT).append(String.format("%-12s", label)).append(value).append('\n');
    }

    private static String durationMs(Double start, Double end) {
        if (start == null || end == null) {
            return "(niezakończony)";
        }
        return Math.round((end - start) * 1000) + " ms";
    }

    private static String number(Double value) {
        if (value == null) {
            return "(brak)";
        }
        return value == Math.rint(value) ? String.valueOf(value.longValue()) : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private static String lower(Object value) {
        return value == null ? "(brak)" : value.toString().toLowerCase(Locale.ROOT);
    }

    private static String orMissing(String value, String consequence) {
        return value == null ? "(BRAK: " + consequence + ")" : value;
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
