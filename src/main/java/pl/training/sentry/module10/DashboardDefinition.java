package pl.training.sentry.module10;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Pokazuje definicję dashboardu checkoutu trzymaną w repozytorium obok kodu, który wysyła metryki.
 *
 * <p>Plik {@value #RESOURCE} opisuje dashboard w formacie bliskim REST API Sentry (te same nazwy
 * {@code widgetType}, {@code displayType}, {@code aggregates}, {@code columns}), ale dodaje pola
 * kontraktu, których Sentry nie przechowuje osobno: {@code question} i {@code decision} przy
 * każdym widgecie oraz {@code owner} przy dashboardzie. {@link DashboardPayload} zamienia je na
 * opis widgetu i Edit Access, a {@link DashboardRules} sprawdza je przed wysłaniem.</p>
 *
 * <p>Dlaczego własny format, a nie eksport JSON z Sentry: eksport zawiera identyfikatory, daty
 * i pola ustawiane przez serwer, więc każde zapisanie w UI zmieniałoby plik w repozytorium.
 * Definicja zawiera tylko to, za co odpowiada właściciel dashboardu.</p>
 *
 * <p>Jeden widget ma jedno zapytanie. API pozwala na kilka zapytań w widgecie (np. ta sama
 * agregacja dla dwóch filtrów), ale ten dashboard ich nie potrzebuje, a jedno zapytanie
 * upraszcza liczenie serii w {@link DashboardRules}.</p>
 *
 * @param title       tytuł, a zarazem klucz, po którym synchronizacja szuka dashboardu w Sentry
 * @param owner       slug zespołu odpowiedzialnego za semantykę, filtry i utrzymanie
 * @param projects    slugi projektów (globalny filtr projektu); identyfikatory ustala synchronizacja
 * @param environment globalny filtr środowiska
 * @param release     globalny filtr release, czyli wydanie, którego dotyczy dashboard; pusta lista
 *                    w Sentry oznacza wszystkie releases, dlatego {@link DashboardRules} jej nie
 *                    przepuszcza. Nowe wydanie to zmiana tej wartości w repozytorium i ponowne APPLY.
 * @param period      domyślny zakres czasu, np. {@code 24h}
 */
public record DashboardDefinition(
        String title,
        String owner,
        List<String> projects,
        List<String> environment,
        List<String> release,
        String period,
        List<Widget> widgets
) {

    /** Definicja dashboardu z tego modułu na classpath. */
    public static final String RESOURCE = "/module10/checkout-dashboard.json";

    // PUŁAPKA: literówka w nazwie pola (np. "querstion") przy domyślnej konfiguracji części
    // parserów przechodzi bez błędu, a pole po prostu znika. Nieznane pola kończą się tu błędem.
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public DashboardDefinition {
        projects = copy(projects);
        environment = copy(environment);
        release = copy(release);
        widgets = copy(widgets);
    }

    /**
     * Widget z kontraktem: pytanie i decyzja obok zapytania.
     *
     * @param question    niepewność, którą widget usuwa
     * @param decision    reakcja na wynik prawidłowy, pogorszony i nieznany
     * @param widgetType  dataset: {@code tracemetrics} (Application Metrics), {@code metrics}
     *                    (Releases, czyli sesje Release Health), {@code issue}, {@code error-events},
     *                    {@code spans}, {@code logs}
     * @param displayType wizualizacja, np. {@code big_number}, {@code line}, {@code bar}, {@code table}
     * @param limit       liczba grup (serii albo wierszy); wymagana, gdy są {@code columns}
     * @param aggregates  agregacje; dla Application Metrics w postaci
     *                    {@code funkcja(value,nazwa,typ,jednostka)} albo {@code equation|...}
     * @param columns     grupowanie (wykres) albo kolumny wierszy (tabela)
     * @param conditions  filtr widgetu w składni wyszukiwania Sentry
     * @param orderby     sortowanie, np. {@code -sum(...)}; dla issues {@code freq}, {@code date}
     */
    public record Widget(
            String title,
            String question,
            String decision,
            String widgetType,
            String displayType,
            String interval,
            Integer limit,
            List<String> aggregates,
            List<String> columns,
            String conditions,
            String orderby,
            Thresholds thresholds,
            Layout layout
    ) {

        public Widget {
            aggregates = copy(aggregates);
            columns = copy(columns);
            conditions = conditions == null ? "" : conditions;
            orderby = orderby == null ? "" : orderby;
        }

        /** Treść opisu widgetu w Sentry: pytanie i decyzja, bo API nie ma na nie osobnych pól. */
        public String description() {
            return "Pytanie: " + question + " Decyzja: " + decision;
        }

        public Widget withAggregates(List<String> newAggregates) {
            return new Widget(title, question, decision, widgetType, displayType, interval, limit,
                    newAggregates, columns, conditions, orderby, thresholds, layout);
        }

        public Widget withGrouping(List<String> newColumns, Integer newLimit) {
            return new Widget(title, question, decision, widgetType, displayType, interval, newLimit,
                    aggregates, newColumns, conditions, orderby, thresholds, layout);
        }

        public Widget withConditions(String newConditions) {
            return new Widget(title, question, decision, widgetType, displayType, interval, limit,
                    aggregates, columns, newConditions, orderby, thresholds, layout);
        }
    }

    /**
     * Progi kolorów widgetu: {@code polarity} {@code -} oznacza, że mniej znaczy lepiej.
     * Granice z pola {@code decision} (wynik prawidłowy i pogorszony) widać wtedy kolorem w widgecie.
     */
    public record Thresholds(double max1, double max2, String polarity) {
    }

    /** Położenie na siatce dashboardu o szerokości 6 kolumn. */
    public record Layout(int x, int y, int w, int h) {
    }

    /** Wczytuje definicję z classpath. Błąd składni albo nieznane pole przerywa działanie. */
    public static DashboardDefinition load() {
        try (InputStream in = DashboardDefinition.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Brak definicji dashboardu na classpath: " + RESOURCE);
            }
            return JSON.readValue(in, DashboardDefinition.class);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Pierwszy widget o danym tytule. */
    public Widget widget(String widgetTitle) {
        return widgets.stream()
                .filter(widget -> widget.title().equals(widgetTitle))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Brak widgetu: " + widgetTitle));
    }

    public DashboardDefinition withOwner(String newOwner) {
        return new DashboardDefinition(title, newOwner, projects, environment, release, period, widgets);
    }

    public DashboardDefinition withEnvironment(List<String> newEnvironment) {
        return new DashboardDefinition(title, owner, projects, newEnvironment, release, period, widgets);
    }

    public DashboardDefinition withRelease(List<String> newRelease) {
        return new DashboardDefinition(title, owner, projects, environment, newRelease, period, widgets);
    }

    /** Kopia z podmienionym widgetem o danym tytule; służy wariantom z pułapkami w demo i testach. */
    public DashboardDefinition withWidget(String widgetTitle, UnaryOperator<Widget> change) {
        List<Widget> changed = new ArrayList<>();
        for (Widget widget : widgets) {
            changed.add(widget.title().equals(widgetTitle) ? change.apply(widget) : widget);
        }
        return new DashboardDefinition(title, owner, projects, environment, release, period, changed);
    }

    public DashboardDefinition withAddedWidget(Widget widget) {
        List<Widget> changed = new ArrayList<>(widgets);
        changed.add(widget);
        return new DashboardDefinition(title, owner, projects, environment, release, period, changed);
    }

    private static <T> List<T> copy(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }
}
