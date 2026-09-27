package pl.training.sentry.module10;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pokazuje idempotentną synchronizację dashboardu z definicją: utwórz albo zaktualizuj po tytule.
 *
 * <p>Wersjonowany opis dashboardu (właściciel, filtry, zapytania) to {@link DashboardDefinition}
 * w repozytorium, a ta klasa pilnuje, żeby Sentry pokazywało właśnie go.</p>
 *
 * <p>Kolejne uruchomienie z tą samą definicją nie zmienia niczego w Sentry: brak różnic oznacza
 * brak {@code PUT}. Różnice to drift, czyli zmiana zapisana ręcznie w UI (zakładka dashboardu,
 * „Save”). W trybie {@link Mode#PLAN} klasa tylko je raportuje, w trybie {@link Mode#APPLY}
 * przywraca definicję z repozytorium.</p>
 *
 * <p>PRODUKCJA: {@link Mode#PLAN} w pipeline przy każdym pull requeście, {@link Mode#APPLY} po
 * scaleniu do gałęzi głównej. Drift wykryty w PLAN to sygnał, że ktoś zmienił dashboard ręcznie:
 * zmianę trzeba przenieść do repozytorium albo świadomie odrzucić, a nie nadpisać bez słowa.</p>
 */
public final class DashboardSync {

    /** Operacje REST API potrzebne synchronizacji; w teście zastępuje je implementacja w pamięci. */
    public interface DashboardsApi {

        /** Slug projektu na identyfikator, tylko projekty widoczne dla tokenu. */
        Map<String, Long> projectIds() throws IOException, InterruptedException;

        long teamId(String slug) throws IOException, InterruptedException;

        /** Dashboardy organizacji o dokładnie tym tytule (lista, bo tytuł nie jest unikalny). */
        List<JsonNode> findByTitle(String title) throws IOException, InterruptedException;

        JsonNode get(String id) throws IOException, InterruptedException;

        JsonNode create(ObjectNode payload, List<Long> projectIds) throws IOException, InterruptedException;

        JsonNode update(String id, ObjectNode payload, List<Long> projectIds) throws IOException, InterruptedException;
    }

    public enum Mode {
        /** Tylko raport: co zostałoby utworzone albo zmienione. */
        PLAN,
        /** Utwórz albo przywróć definicję z repozytorium. */
        APPLY
    }

    public enum Action {
        /** Dashboardu nie było, został utworzony. */
        CREATED,
        /** Dashboardu nie ma, a tryb PLAN niczego nie tworzy. */
        WOULD_CREATE,
        /** Dashboard w Sentry odpowiada definicji; żadne żądanie zmieniające nie zostało wysłane. */
        UNCHANGED,
        /** Wykryto drift i przywrócono definicję z repozytorium. */
        UPDATED,
        /** Wykryto drift, tryb PLAN niczego nie zmienia. */
        DRIFT
    }

    /** Wynik synchronizacji: co się stało, identyfikator dashboardu i wykryte różnice. */
    public record Result(Action action, String dashboardId, List<String> drift) {
    }

    private final DashboardsApi api;

    public DashboardSync(DashboardsApi api) {
        this.api = api;
    }

    public Result sync(DashboardDefinition dashboard, Mode mode) throws IOException, InterruptedException {
        List<DashboardRules.Violation> violations = DashboardRules.check(dashboard);
        if (!violations.isEmpty()) {
            // Definicja z naruszeniami nie trafia do Sentry. Serwer przyjąłby część z nich
            // (np. avg z countera), a błędny widget wyglądałby jak poprawny.
            throw new IllegalArgumentException("Definicja narusza reguły: " + violations);
        }
        List<Long> projectIds = resolveProjects(dashboard);
        ObjectNode desired = DashboardPayload.toApi(dashboard, projectIds, api.teamId(dashboard.owner()));

        List<JsonNode> existing = api.findByTitle(dashboard.title());
        if (existing.size() > 1) {
            // Duplikaty powstają, gdy ktoś tworzy dashboard zwykłym POST przy każdym uruchomieniu
            // albo duplikuje go w UI i zostawia tytuł. Nie zgadujemy, który jest właściwy.
            List<String> ids = existing.stream().map(node -> node.path("id").asString()).toList();
            throw new IllegalStateException("W Sentry jest kilka dashboardów o tytule „" + dashboard.title()
                    + "” (id " + ids + "); usuń nadmiarowe i uruchom ponownie");
        }
        if (existing.isEmpty()) {
            if (mode == Mode.PLAN) {
                return new Result(Action.WOULD_CREATE, null, List.of());
            }
            // PUŁAPKA: samo POST nie jest idempotentne. Przy zajętym tytule Sentry 26.9.0 nie
            // zwraca błędu, tylko zapisuje dashboard pod tytułem z dopiskiem „copy”. Dlatego
            // najpierw wyszukanie po tytule, a POST tylko wtedy, gdy nic nie znaleziono.
            JsonNode created = api.create(desired, projectIds);
            return new Result(Action.CREATED, created.path("id").asString(), List.of());
        }

        String id = existing.getFirst().path("id").asString();
        JsonNode actual = api.get(id);
        List<String> drift = DashboardPayload.differences(desired, actual);
        if (drift.isEmpty()) {
            return new Result(Action.UNCHANGED, id, drift);
        }
        if (mode == Mode.PLAN) {
            return new Result(Action.DRIFT, id, drift);
        }
        api.update(id, DashboardPayload.withExistingWidgetIds(desired, actual), projectIds);
        return new Result(Action.UPDATED, id, drift);
    }

    private List<Long> resolveProjects(DashboardDefinition dashboard) throws IOException, InterruptedException {
        Map<String, Long> known = api.projectIds();
        List<Long> ids = new ArrayList<>();
        for (String slug : dashboard.projects()) {
            Long id = known.get(slug);
            if (id == null) {
                throw new IllegalArgumentException("Projekt „" + slug + "” nie istnieje albo token go nie widzi");
            }
            ids.add(id);
        }
        return ids;
    }
}
