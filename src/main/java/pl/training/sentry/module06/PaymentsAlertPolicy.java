package pl.training.sentry.module06;

import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import pl.training.sentry.support.SentryRestApi;
import pl.training.sentry.support.TrainingSentry;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;

/**
 * Pokazuje politykę alertową payments-api zapisaną jako dane: Monitor wykrywa problem, trzy
 * Alerty decydują o reakcji, kanałem jest webhook.
 *
 * <p>Z tych samych obiektów powstają treści żądań REST API ({@link AlertingSetup}) i lokalny
 * podgląd filtrów ({@link #preview}), więc opis w demo nie rozjedzie się z tym, co trafia do
 * Sentry. Metric Monitor nie zna kanału: reakcję opisuje podłączony do niego Alert, więc
 * zmiana kanału nie wymaga zmiany warunku detekcji.</p>
 *
 * <p>Nazewnictwo: w API Sentry 26.9.0 Monitor to {@code detector}, a Alert to
 * {@code workflow}. W kodzie i na wydruku używamy nazw z UI: Monitor i Alert.</p>
 *
 * <p>Wszystkie nazwy zaczynają się od {@value #NAME_PREFIX}: po nim {@link AlertingSetup}
 * znajduje własną konfigurację (idempotentny setup i sprzątanie), a w UI widać, skąd pochodzi.</p>
 */
public final class PaymentsAlertPolicy {

    public static final String NAME_PREFIX = "[module06] ";
    /** Środowisko, które obsługują Alerty. W produkcji {@code production}, tu środowisko demo. */
    public static final String ENVIRONMENT = TrainingSentry.DEFAULT_ENVIRONMENT;
    /** Nazwa Internal Integration, która dostarcza akcję webhook. */
    public static final String WEBHOOK_INTEGRATION_NAME = "module06 alert webhook";

    /** Trigger Alertu: nazwa z formularza w UI i typ warunku w API. */
    public enum Trigger {
        NEW_ISSUE("first_seen_event", "A new issue is created"),
        REGRESSION("regression_event", "A resolved issue regresses"),
        EVENT_CAPTURED("every_event", "An event or issue activity is captured"),
        /** Alert podłączony do Metric Monitora: ocenę uruchamia zmiana stanu Monitora, nie event. */
        MONITOR_STATE(null, "zmiana stanu podłączonego Monitora");

        final String apiType;
        final String label;

        Trigger(String apiType, String label) {
            this.apiType = apiType;
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Skąd Alert bierze Issues (krok Source formularza). */
    public enum Source {
        /** Wszystkie Issues projektu: w API to Issue Stream Monitor projektu. */
        PROJECT_ISSUES,
        /** Tylko Issues tworzone przez {@link #PAYMENT_ERRORS}. */
        PAYMENT_ERRORS_MONITOR
    }

    /** Filtr bloku If. {@code rejects} to podgląd lokalny: powód odrzucenia eventu albo pusto. */
    public sealed interface Filter permits TagEquals, TagNotEquals, LevelAtLeast, PriorityAtLeast {

        ObjectNode toCondition();

        String describe();

        /** Pusty wynik, gdy event przechodzi filtr. Filtry atrybutów Issue ocenia tylko serwer. */
        Optional<String> rejects(SentryEvent event);
    }

    /** Filtr eventu „Tagged event”: tag równy wartości. Brak tagu nie pasuje. */
    public record TagEquals(String key, String value) implements Filter {

        @Override
        public ObjectNode toCondition() {
            return condition("tagged_event", tagComparison(key, "eq", value));
        }

        @Override
        public String describe() {
            return key + " = " + value;
        }

        @Override
        public Optional<String> rejects(SentryEvent event) {
            String actual = event.getTag(key);
            return value.equals(actual) ? Optional.empty()
                    : Optional.of(actual == null ? "brak tagu " + key : key + "=" + actual);
        }
    }

    /** Filtr eventu „Tagged event”: tag różny od wartości. Brak tagu pasuje. */
    public record TagNotEquals(String key, String value) implements Filter {

        @Override
        public ObjectNode toCondition() {
            return condition("tagged_event", tagComparison(key, "ne", value));
        }

        @Override
        public String describe() {
            return key + " != " + value;
        }

        @Override
        public Optional<String> rejects(SentryEvent event) {
            return value.equals(event.getTag(key)) ? Optional.of(key + "=" + value) : Optional.empty();
        }
    }

    /** Filtr eventu „Event level”: level co najmniej podany. */
    public record LevelAtLeast(SentryLevel level) implements Filter {

        @Override
        public ObjectNode toCondition() {
            ObjectNode comparison = SentryRestApi.JSON.createObjectNode()
                    .put("level", apiLevel(level))
                    .put("match", "gte");
            return condition("level", comparison);
        }

        @Override
        public String describe() {
            return "level >= " + level.name().toLowerCase();
        }

        @Override
        public Optional<String> rejects(SentryEvent event) {
            // captureException nie ustawia level; serwer przyjmuje wtedy error.
            SentryLevel actual = event.getLevel() == null ? SentryLevel.ERROR : event.getLevel();
            return apiLevel(actual) >= apiLevel(level) ? Optional.empty()
                    : Optional.of("level " + actual.name().toLowerCase());
        }
    }

    /** Filtr atrybutu Issue „Issue priority”: działa także z triggerem regresji. */
    public record PriorityAtLeast(int priority, String name) implements Filter {

        public static final PriorityAtLeast HIGH = new PriorityAtLeast(75, "high");

        @Override
        public ObjectNode toCondition() {
            ObjectNode condition = SentryRestApi.JSON.createObjectNode();
            condition.put("type", "issue_priority_greater_or_equal");
            condition.put("comparison", priority);
            condition.put("conditionResult", true);
            return condition;
        }

        @Override
        public String describe() {
            return "priority Issue >= " + name;
        }

        @Override
        public Optional<String> rejects(SentryEvent event) {
            // Priorytet ma Issue, nie event. Nowe Issue z level error dostaje zwykle high
            // (medium tylko przy niskiej ocenie severity), więc lokalnie wynik jest nieznany.
            return Optional.empty();
        }
    }

    /**
     * Alert: źródło, środowisko, trigger, filtry (kwalifikator {@code all}), akcja webhook
     * i throttling w minutach (0 oznacza powiadomienie przy każdym uruchomieniu triggera).
     */
    public record Alert(String name, Source source, Trigger trigger, List<Filter> filters, int throttleMinutes,
                        String purpose) {
    }

    /**
     * Metric Monitor: liczba eventów błędów pasujących do zapytania w oknie, próg alarmowy
     * (priorytet high) i próg recovery (wartość nie większa niż próg).
     */
    public record Monitor(String name, String query, int windowSeconds, int threshold) {
    }

    /**
     * Metric Monitor „liczba błędów komponentu payments w 5 minut”.
     *
     * <p>Dlaczego 10 w 5 minut: pojedyncze błędy komponentu obsługuje Alert
     * {@link #COMPONENT_ERRORS}. Monitor ma odpowiedzieć na inne pytanie: czy awaria trwa
     * i dotyka wielu płatności. Okno 5 minut wygładza pojedyncze skoki, a recovery przy tym samym
     * progu zamyka problem, gdy bank wróci.</p>
     *
     * <p>PRODUKCJA: próg wynika z ruchu i minimalnej liczby płatności w oknie. Przy dużym
     * ruchu lepszy bywa odsetek nieudanych płatności niż liczba błędów, bo count rośnie razem
     * z ruchem.</p>
     */
    public static final Monitor PAYMENT_ERRORS = new Monitor(
            NAME_PREFIX + "payments: liczba błędów w 5 min",
            // PUŁAPKA: bez level:error Monitor liczyłby też odmowy kart (warning), bo dla Sentry
            // każdy wyjątek to event typu error, niezależnie od level.
            "component:payments level:error",
            300,
            10);

    /**
     * Alert „błąd komponentu payments”: pierwszy event awarii powiadamia od razu, kolejne
     * eventy tego samego Issue najwyżej raz na 30 minut.
     *
     * <p>Dlaczego trigger „An event or issue activity is captured”, a nie „A new issue is
     * created”: nowe Issue powstaje raz. Awaria banku, która wraca po tygodniu do istniejącego
     * Issue, nie dałaby żadnego powiadomienia. Trigger na każdy event z throttlingiem daje
     * pierwsze powiadomienie także dla istniejącego Issue, a seria eventów w oknie throttlingu
     * nie wysyła kolejnych. Throttling działa per Issue: inne Issue komponentu powiadamia
     * osobno.</p>
     *
     * <p>Filtry eventu działają tylko dzięki danym z {@link PaymentTelemetry}: tag
     * {@code component}, level i tag {@code expected}. Event bez tagu {@code component}
     * (wspólny pomocnik {@code reportCarelessly}) nie pasuje i nikogo nie powiadomi, także
     * gdy jest prawdziwą awarią.</p>
     */
    public static final Alert COMPONENT_ERRORS = new Alert(
            NAME_PREFIX + "payments: błąd komponentu",
            Source.PROJECT_ISSUES,
            Trigger.EVENT_CAPTURED,
            List.of(new TagEquals("component", "payments"),
                    new LevelAtLeast(SentryLevel.ERROR),
                    // Obrona w głąb: odmowa karty ma level warning, ale gdyby ktoś podniósł ją
                    // do error, tag expected=true nadal ją wyklucza.
                    new TagNotEquals("expected", "true")),
            30,
            "pierwszy błąd komponentu i trwająca awaria, najwyżej raz na 30 min na Issue");

    /**
     * Alert „regresja”: rozwiązane Issue o wysokim priorytecie wróciło.
     *
     * <p>Filtr na priorytet, nie na tag: według dokumentacji filtry atrybutów eventu działają
     * tylko z triggerami „A new issue is created” i „An event or issue activity is captured”,
     * a formularz oznacza je przy regresji ostrzeżeniem. Filtry atrybutów Issue działają
     * z każdym triggerem.</p>
     *
     * <p>PRODUKCJA: payments-api ma własny projekt, więc źródło (projekt) samo zawęża Alert do
     * komponentu. Tu projekt jest wspólny dla modułów szkolenia, więc Alert obejmuje regresje
     * wszystkich modułów w środowisku {@value #ENVIRONMENT}.</p>
     */
    public static final Alert REGRESSIONS = new Alert(
            NAME_PREFIX + "regresja Issue o wysokim priorytecie",
            Source.PROJECT_ISSUES,
            Trigger.REGRESSION,
            List.of(PriorityAtLeast.HIGH),
            30,
            "rozwiązane Issue wróciło: poprawka nie zadziałała albo nie trafiła do release");

    /**
     * Alert Metric Monitora: powiadomienie, gdy {@link #PAYMENT_ERRORS} utworzy albo podniesie
     * Issue do priorytetu high. Throttling 0, bo stan Monitora zmienia się rzadko: alarm
     * i recovery, a nie każdy event.
     */
    public static final Alert ERROR_SPIKE = new Alert(
            NAME_PREFIX + "payments: skok błędów (Metric Monitor)",
            Source.PAYMENT_ERRORS_MONITOR,
            Trigger.MONITOR_STATE,
            List.of(PriorityAtLeast.HIGH),
            0,
            "awaria trwa: ponad " + PAYMENT_ERRORS.threshold() + " błędów payments w 5 min");

    public static final List<Alert> ALERTS = List.of(COMPONENT_ERRORS, REGRESSIONS, ERROR_SPIKE);

    private PaymentsAlertPolicy() {
    }

    /**
     * Treść żądania Alertu ({@code POST /organizations/{org}/workflows/}).
     *
     * @param sourceMonitorId identyfikator Monitora źródła: Issue Stream projektu albo Metric Monitor
     * @param webhookSlug     slug Internal Integration, która wysyła webhook
     */
    public static ObjectNode alertBody(Alert alert, long sourceMonitorId, String webhookSlug) {
        ObjectNode body = SentryRestApi.JSON.createObjectNode();
        body.put("name", alert.name());
        body.put("enabled", true);
        // Środowisko to osobny krok formularza, nie filtr. Brak środowiska nie oznacza
        // production, tylko wszystkie środowiska.
        body.put("environment", ENVIRONMENT);
        body.putObject("config").put("frequency", alert.throttleMinutes());
        body.putArray("detectorIds").add(sourceMonitorId);

        ObjectNode triggers = body.putObject("triggers");
        // Wiele triggerów to alternatywy (logika any), dlatego any-short.
        triggers.put("logicType", "any-short");
        ArrayNode triggerConditions = triggers.putArray("conditions");
        if (alert.trigger().apiType != null) {
            ObjectNode trigger = triggerConditions.addObject();
            trigger.put("type", alert.trigger().apiType);
            trigger.put("comparison", true);
            trigger.put("conditionResult", true);
        }

        ObjectNode block = body.putArray("actionFilters").addObject();
        // Kwalifikator all: event musi spełnić wszystkie filtry bloku.
        block.put("logicType", "all");
        ArrayNode conditions = block.putArray("conditions");
        alert.filters().forEach(filter -> conditions.add(filter.toCondition()));
        ObjectNode webhook = block.putArray("actions").addObject();
        webhook.put("type", "webhook");
        webhook.putObject("config")
                .putNull("targetType")
                .put("targetIdentifier", webhookSlug)
                .putNull("targetDisplay");
        webhook.putObject("data");
        webhook.putNull("integrationId");
        return body;
    }

    /** Treść żądania Metric Monitora ({@code POST /organizations/{org}/projects/{project}/detectors/}). */
    public static ObjectNode monitorBody(Monitor monitor, String projectId) {
        ObjectNode body = SentryRestApi.JSON.createObjectNode();
        body.put("name", monitor.name());
        body.put("type", "metric_issue");
        body.put("projectId", projectId);
        ObjectNode source = body.putArray("dataSources").addObject();
        source.put("dataset", "events");
        source.putArray("eventTypes").add("error");
        source.put("aggregate", "count()");
        source.put("query", monitor.query());
        source.put("queryType", 0);
        source.put("timeWindow", monitor.windowSeconds());
        source.put("environment", ENVIRONMENT);
        body.putObject("config").put("detectionType", "static");
        ObjectNode group = body.putObject("conditionGroup");
        group.put("logicType", "any");
        ArrayNode conditions = group.putArray("conditions");
        // Próg alarmowy: więcej niż threshold daje Issue o priorytecie high (75).
        conditions.addObject().put("type", "gt").put("comparison", monitor.threshold()).put("conditionResult", 75);
        // Próg recovery: tu równy alarmowemu. Przy wartości oscylującej wokół progu lepszy
        // jest niższy próg recovery: Issue nie otwiera się i nie zamyka przy każdej ocenie.
        conditions.addObject().put("type", "lte").put("comparison", monitor.threshold()).put("conditionResult", 0);
        return body;
    }

    /**
     * Treść żądania Internal Integration ({@code POST /sentry-apps/}) z adresem odbiornika.
     * {@code isAlertable} udostępnia ją jako akcję Alertu („Send a notification via an integration”).
     */
    public static ObjectNode webhookIntegrationBody(String org, String webhookUrl) {
        ObjectNode body = SentryRestApi.JSON.createObjectNode();
        body.put("name", WEBHOOK_INTEGRATION_NAME);
        body.put("organization", org);
        body.put("isInternal", true);
        body.put("isAlertable", true);
        body.put("webhookUrl", webhookUrl);
        // Bez zakresów i subskrypcji zdarzeń: integracja tylko odbiera akcje Alertów, nie czyta API.
        body.putArray("scopes");
        body.putArray("events");
        body.put("verifyInstall", false);
        return body;
    }

    /** Wynik podglądu lokalnego: czy event przeszedłby środowisko i filtry Alertu. */
    public record Verdict(boolean notifies, String reason) {
    }

    /**
     * Podgląd lokalny: środowisko i filtry eventu z bloku If. Narzędzie szkoleniowe.
     *
     * <p>Nie zastępuje Sentry: triggera regresji, throttlingu i stanu Monitora nie da się
     * ocenić na pojedynczym evencie w procesie aplikacji. Dla takich Alertów wynik mówi, kto
     * decyduje. Ostateczny dowód to historia Alertu w API ({@link AlertingSetup#status}).</p>
     */
    public static Verdict preview(Alert alert, SentryEvent event) {
        if (alert.trigger() != Trigger.EVENT_CAPTURED && alert.trigger() != Trigger.NEW_ISSUE) {
            return new Verdict(false, "decyduje serwer: " + alert.trigger().label());
        }
        if (!ENVIRONMENT.equals(event.getEnvironment())) {
            return new Verdict(false, "odfiltrowany: environment " + event.getEnvironment());
        }
        for (Filter filter : alert.filters()) {
            Optional<String> rejected = filter.rejects(event);
            if (rejected.isPresent()) {
                return new Verdict(false, "odfiltrowany: " + filter.describe() + " nie pasuje (" + rejected.get() + ")");
            }
        }
        return new Verdict(true, "przechodzi środowisko i filtry; akcję dla tego Issue ogranicza throttling "
                + alert.throttleMinutes() + " min");
    }

    /** Opis jednej linii: trigger, filtry, throttling. */
    public static String describe(Alert alert) {
        String filters = alert.filters().stream().map(Filter::describe).reduce((a, b) -> a + ", " + b).orElse("brak");
        return "When: " + alert.trigger().label() + " | If (all): " + filters
                + " | Throttling: " + (alert.throttleMinutes() == 0 ? "każde uruchomienie" : alert.throttleMinutes() + " min");
    }

    private static ObjectNode condition(String type, ObjectNode comparison) {
        ObjectNode condition = SentryRestApi.JSON.createObjectNode();
        condition.put("type", type);
        condition.set("comparison", comparison);
        condition.put("conditionResult", true);
        return condition;
    }

    private static ObjectNode tagComparison(String key, String match, String value) {
        return SentryRestApi.JSON.createObjectNode().put("key", key).put("match", match).put("value", value);
    }

    /** Level w API: 50 fatal, 40 error, 30 warning, 20 info, 10 debug. */
    static int apiLevel(SentryLevel level) {
        return switch (level) {
            case FATAL -> 50;
            case ERROR -> 40;
            case WARNING -> 30;
            case INFO -> 20;
            case DEBUG -> 10;
        };
    }
}
