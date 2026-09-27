package pl.training.sentry.module10;

import pl.training.sentry.module10.DashboardDefinition.Widget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pokazuje reguły, które definicja dashboardu musi spełnić, zanim trafi do Sentry.
 *
 * <p>Część reguł powtarza walidację serwera (obsługiwane wizualizacje, wymagany {@code limit},
 * siatka), żeby błąd było widać w teście bez sieci. Ważniejsze są reguły, których serwer nie
 * sprawdza: na lokalnym self-hosted 26.9.0 API przyjęło {@code avg} z countera, wykres z 12
 * seriami i grupowanie po {@code order.id}. Takie widgety zapisują się poprawnie i pokazują
 * liczby, tylko że błędne.</p>
 *
 * <p>Reguły działają na definicji, bez sieci, więc {@code DashboardAsCodeTest} uruchamia je przy
 * każdym {@code ./mvnw test}. Zmiana nazwy metryki w {@link CheckoutMetrics} bez zmiany
 * dashboardu kończy się czerwonym testem, a nie pustym wykresem po wdrożeniu.</p>
 */
public final class DashboardRules {

    /** Najwięcej serii na wykresie z grupowaniem (kreator widgetu w UI); serwer sprawdza tylko limit ≤ 10. */
    public static final int MAX_SERIES = 10;
    /** Najwięcej widgetów na dashboardzie ({@code Dashboard.MAX_WIDGETS} w Sentry 26.9.0). */
    public static final int MAX_WIDGETS = 30;
    /** Najdłuższy opis widgetu przyjmowany przez API. */
    public static final int MAX_DESCRIPTION = 350;
    /** Szerokość siatki dashboardu w kolumnach. */
    public static final int GRID_COLUMNS = 6;

    /** Naruszenie reguły: gdzie, której i dlaczego to błąd. */
    public record Violation(String where, String rule, String message) {

        @Override
        public String toString() {
            return "[" + rule + "] " + where + ": " + message;
        }
    }

    /** Typ i jednostka metryki z kontraktu {@link CheckoutMetrics}, tak jak zapisuje je Sentry. */
    record MetricContract(String type, String unit) {
    }

    // Kontrakt emisji z CheckoutMetrics. Jednostka "none" to brak jednostki: tak Sentry zapisuje
    // metrykę wysłaną z jednostką null. Stałe z CheckoutMetrics, a nie tekst: zmiana nazwy
    // metryki w kodzie zmienia też to, czego wymagają reguły.
    static final Map<String, MetricContract> CHECKOUT_METRICS = Map.of(
            CheckoutMetrics.ATTEMPTED, new MetricContract("counter", "none"),
            CheckoutMetrics.COMPLETED, new MetricContract("counter", "none"),
            CheckoutMetrics.FAILED, new MetricContract("counter", "none"),
            CheckoutMetrics.DURATION, new MetricContract("distribution", "millisecond"),
            CheckoutMetrics.QUEUE_DEPTH, new MetricContract("gauge", "none"),
            CheckoutMetrics.QUEUE_ENQUEUED, new MetricContract("counter", "none"));

    // Agregacje dostępne dla typu metryki, jak w Metrics Explorerze Sentry. Gauge nie ma latest.
    static final Map<String, Set<String>> FUNCTIONS_BY_TYPE = Map.of(
            "counter", Set.of("sum", "per_second", "per_minute"),
            "gauge", Set.of("min", "max", "avg", "per_second", "per_minute"),
            "distribution", Set.of("p50", "p75", "p90", "p95", "p99", "avg", "sum", "min", "max", "count",
                    "per_second", "per_minute"));

    // Wymiary Application Metrics o zamkniętym słowniku: enumy z CheckoutMetrics i release.
    static final Set<String> BOUNDED_METRIC_DIMENSIONS = Set.of(
            CheckoutMetrics.PAYMENT_METHOD, CheckoutMetrics.OUTCOME, "release");

    // Wizualizacje przyjmowane przez API 26.9.0 dla datasetu. Tabela Application Metrics wymaga
    // flagi tracemetrics-dashboard-table, wyłączonej na lokalnej instancji.
    private static final Set<String> CHART_AND_TABLE = Set.of(
            "line", "area", "bar", "table", "big_number", "categorical_bar");
    static final Map<String, Set<String>> DISPLAY_TYPES = Map.of(
            "tracemetrics", Set.of("line", "area", "bar", "big_number", "categorical_bar", "heatmap"),
            "metrics", CHART_AND_TABLE,
            "issue", Set.of("table", "line", "area", "bar"),
            "error-events", CHART_AND_TABLE,
            "spans", CHART_AND_TABLE,
            "logs", CHART_AND_TABLE);

    private static final Set<String> CHARTS = Set.of("line", "area", "bar");
    private static final Pattern METRIC_AGGREGATE =
            Pattern.compile("(\\w+)\\(value,\\s*([^,\\s]+),\\s*([^,\\s]+),\\s*([^)\\s]+)\\)");
    private static final String EQUATION = "equation|";

    private DashboardRules() {
    }

    /** Wszystkie naruszenia definicji; pusta lista oznacza, że definicję można wysłać. */
    public static List<Violation> check(DashboardDefinition dashboard) {
        List<Violation> violations = new ArrayList<>();
        checkDashboard(dashboard, violations);
        for (Widget widget : dashboard.widgets()) {
            checkContract(widget, violations);
            checkDataset(widget, violations);
            checkScope(widget, violations);
            checkMetrics(widget, violations);
            checkSeries(widget, violations);
            checkLayout(widget, violations);
        }
        return violations;
    }

    private static void checkDashboard(DashboardDefinition dashboard, List<Violation> violations) {
        String where = "dashboard „" + dashboard.title() + "”";
        if (dashboard.owner() == null || dashboard.owner().isBlank()) {
            // Sentry nie ma pola „właściciel dashboardu”. Właściciel z definicji dostaje Edit Access,
            // a bez niego zapisaną zmianę może zrobić każdy i nikt nie odpowiada za semantykę.
            violations.add(new Violation(where, "właściciel",
                    "brak zespołu odpowiedzialnego za semantykę, filtry i utrzymanie"));
        }
        if (dashboard.environment().size() != 1) {
            // Pusta lista w Sentry oznacza wszystkie środowiska, czyli production razem ze staging.
            violations.add(new Violation(where, "jedno środowisko",
                    "filtr środowiska " + dashboard.environment() + " miesza populacje; wskaż dokładnie jedno"));
        }
        if (dashboard.release().isEmpty()) {
            // Pusty filtr release to wszystkie releases z zakresu czasu: nowe wydanie razem ze starym
            // i z ruchem testowym. Liczba z takiej mieszanki nie opisuje żadnego artefaktu, więc nie da
            // się jej porównać z baseline. Na lokalnej instancji pusty filtr dał failure rate około
            // 0,91, a release ruchu referencyjnego 0,25.
            violations.add(new Violation(where, "przypięty release",
                    "pusty filtr release łączy wszystkie wydania z zakresu; wskaż release, którego dotyczy dashboard"));
        }
        dashboard.release().stream()
                .filter(release -> !release.contains("@"))
                .forEach(release -> violations.add(new Violation(where, "przypięty release",
                        "release „" + release + "” nie ma postaci usługa@wersja; sama wersja łączy wydania różnych usług")));
        if (dashboard.projects().isEmpty()) {
            violations.add(new Violation(where, "zakres", "brak projektu; pusty filtr to wszystkie projekty użytkownika"));
        }
        if (dashboard.widgets().size() > MAX_WIDGETS) {
            violations.add(new Violation(where, "liczba widgetów",
                    dashboard.widgets().size() + " widgetów, API przyjmuje najwyżej " + MAX_WIDGETS));
        }
    }

    private static void checkContract(Widget widget, List<Violation> violations) {
        if (isBlank(widget.question()) || isBlank(widget.decision())) {
            violations.add(new Violation(where(widget), "pytanie i decyzja",
                    "widget bez pytania albo decyzji tylko zwiększa koszt poznawczy"));
        } else if (widget.description().length() > MAX_DESCRIPTION) {
            violations.add(new Violation(where(widget), "pytanie i decyzja",
                    "opis ma " + widget.description().length() + " znaków, API przyjmuje najwyżej " + MAX_DESCRIPTION));
        }
    }

    private static void checkDataset(Widget widget, List<Violation> violations) {
        Set<String> supported = DISPLAY_TYPES.get(widget.widgetType());
        if (supported == null) {
            violations.add(new Violation(where(widget), "dataset",
                    "nieznany albo wycofywany dataset „" + widget.widgetType() + "”"));
        } else if (!supported.contains(widget.displayType())) {
            violations.add(new Violation(where(widget), "dataset",
                    "wizualizacja " + widget.displayType() + " nie jest obsługiwana dla " + widget.widgetType()));
        }
    }

    private static void checkScope(Widget widget, List<Violation> violations) {
        // Każde zapytanie dziedziczy environment z filtra globalnego. Warunek environment w widgecie
        // po cichu zmienia populację jednego widgetu i psuje porównanie z sąsiednimi.
        if (widget.conditions().toLowerCase(Locale.ROOT).contains("environment:")) {
            violations.add(new Violation(where(widget), "jedno środowisko",
                    "warunek „" + widget.conditions() + "” zmienia zakres środowiska ustalony dla całego dashboardu"));
        }
    }

    private static void checkMetrics(Widget widget, List<Violation> violations) {
        if (!"tracemetrics".equals(widget.widgetType())) {
            return;
        }
        for (String aggregate : widget.aggregates()) {
            List<MetricOperand> operands = operands(aggregate);
            if (operands.isEmpty()) {
                violations.add(new Violation(where(widget), "kontrakt metryk",
                        "agregacja „" + aggregate + "” nie ma postaci funkcja(value,nazwa,typ,jednostka)"));
            }
            for (MetricOperand operand : operands) {
                checkOperand(widget, operand, violations);
            }
            if (aggregate.startsWith(EQUATION) && aggregate.contains("/")) {
                // Ratio całego bucketu albo zakresu to iloraz sum liczników. Iloraz średnich,
                // percentyli albo gauge nie jest udziałem populacji.
                boolean sumsOfCounters = operands.stream().allMatch(operand ->
                        operand.function().equals("sum") && operand.type().equals("counter"));
                if (!sumsOfCounters) {
                    violations.add(new Violation(where(widget), "iloraz sum",
                            "ratio liczy się jako sum(licznik) / sum(mianownik) z counterów: " + aggregate));
                }
            }
        }
        for (String column : widget.columns()) {
            if (!BOUNDED_METRIC_DIMENSIONS.contains(column)) {
                violations.add(new Violation(where(widget), "kardynalność",
                        "grupowanie po „" + column + "” spoza zamkniętego słownika " + BOUNDED_METRIC_DIMENSIONS
                                + "; identyfikator służy do znalezienia próbki, a nie do group by"));
            }
        }
    }

    private static void checkOperand(Widget widget, MetricOperand operand, List<Violation> violations) {
        String function = operand.function();
        String name = operand.name();
        String type = operand.type();
        String unit = operand.unit();
        if (function.equals("avg") && (unit.equals("percent") || unit.equals("ratio") || name.endsWith("rate"))) {
            // PUŁAPKA: gauge z procentem liczonym w aplikacji (np. co minutę na instancję)
            // i avg w widgecie. Minuta z 6 próbami waży tyle, co minuta z tysiącem.
            violations.add(new Violation(where(widget), "iloraz sum",
                    "średnia procentów avg(" + name + ") zamiast ilorazu sum; wyślij liczniki i policz equation"));
        }
        MetricContract contract = CHECKOUT_METRICS.get(name);
        if (contract == null) {
            violations.add(new Violation(where(widget), "kontrakt metryk",
                    "metryki „" + name + "” nie ma w CheckoutMetrics; widget pokaże brak danych"));
            return;
        }
        if (!contract.type().equals(type) || !contract.unit().equals(unit)) {
            // Sentry rozróżnia metryki po nazwie, typie i jednostce. Ta sama nazwa z inną
            // jednostką to inna seria, więc widget pokazałby brak danych.
            violations.add(new Violation(where(widget), "kontrakt metryk",
                    name + " ma typ " + contract.type() + " i jednostkę " + contract.unit()
                            + ", a widget pyta o " + type + " i " + unit));
        } else if (!FUNCTIONS_BY_TYPE.get(type).contains(function)) {
            violations.add(new Violation(where(widget), "kontrakt metryk",
                    "agregacja " + function + " nie ma sensu dla typu " + type
                            + "; dostępne: " + FUNCTIONS_BY_TYPE.get(type)));
        }
    }

    private static void checkSeries(Widget widget, List<Violation> violations) {
        if (widget.columns().isEmpty()) {
            return;
        }
        Integer limit = widget.limit();
        if (CHARTS.contains(widget.displayType())) {
            if (limit == null) {
                violations.add(new Violation(where(widget), "limit serii",
                        "wykres z grupowaniem wymaga limitu serii (serwer odrzuca go bez limitu)"));
                return;
            }
            // Każda grupa to osobna seria dla każdej agregacji. Kreator widgetu w UI dobiera limit
            // tak, żeby grupy razy agregacje dawały najwyżej 10 serii. API sprawdza tylko limit ≤ 10,
            // więc limit 6 przy p50 i p95 przechodzi i daje 12 linii, których nikt nie odczyta.
            int series = limit * Math.max(1, widget.aggregates().size());
            if (series > MAX_SERIES) {
                violations.add(new Violation(where(widget), "limit serii",
                        limit + " grup × " + widget.aggregates().size() + " agregacji = " + series
                                + " serii; kreator widgetu w UI pozwala na najwyżej " + MAX_SERIES
                                + ", a API przyjmuje więcej bez ostrzeżenia"));
            }
        } else if ("table".equals(widget.displayType()) && limit != null && limit > 20) {
            violations.add(new Violation(where(widget), "limit serii", "tabela pokazuje najwyżej 20 wierszy"));
        }
    }

    private static void checkLayout(Widget widget, List<Violation> violations) {
        DashboardDefinition.Layout layout = widget.layout();
        if (layout == null) {
            violations.add(new Violation(where(widget), "układ", "brak położenia na siatce"));
            return;
        }
        int minHeight = "big_number".equals(widget.displayType()) ? 1 : 2;
        if (layout.x() < 0 || layout.w() < 1 || layout.x() + layout.w() > GRID_COLUMNS || layout.h() < minHeight) {
            violations.add(new Violation(where(widget), "układ",
                    "x + w musi mieścić się w " + GRID_COLUMNS + " kolumnach, a wysokość to co najmniej " + minHeight));
        }
    }

    /** Agregacja jednej metryki: {@code funkcja(value,nazwa,typ,jednostka)}. */
    record MetricOperand(String function, String name, String type, String unit) {

        /** Ta sama agregacja jako osobne pole zapytania, np. do sprawdzenia, czy równanie ma dane. */
        String field() {
            return function + "(value," + name + "," + type + "," + unit + ")";
        }
    }

    /** Operandy agregacji Application Metrics: jeden dla zwykłej agregacji, kilka dla równania. */
    static List<MetricOperand> operands(String aggregate) {
        List<MetricOperand> operands = new ArrayList<>();
        Matcher matcher = METRIC_AGGREGATE.matcher(aggregate);
        while (matcher.find()) {
            operands.add(new MetricOperand(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4)));
        }
        return operands;
    }

    private static String where(Widget widget) {
        return "widget „" + widget.title() + "”";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
