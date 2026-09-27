package pl.training.sentry.module10;

import io.sentry.Sentry;
import pl.training.sentry.module10.CheckoutEndpoint.Response;
import pl.training.sentry.module10.CheckoutEndpoint.SessionTracking;
import pl.training.sentry.module10.DashboardDefinition.Widget;
import pl.training.sentry.module10.DashboardSync.Mode;
import pl.training.sentry.module10.DashboardSync.Result;
import pl.training.sentry.module10.Order.PaymentMethod;
import pl.training.sentry.support.DemoConsole;
import pl.training.sentry.support.DemoScenarios;
import pl.training.sentry.support.TrainingSentry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Dashboard checkoutu tworzony z kodu: scenariusze 7 do 12 modułu 10.
 *
 * <p>Teoria modułu, w tym dashboard jako kod, reguły, synchronizacja i test odbiorowy, jest
 * w komentarzu na początku {@link Module10Demo}.</p>
 *
 * <p>Scenariusze 7 do 9 działają zawsze i bez sieci: definicja z repozytorium, reguły i treść
 * żądania. Scenariusz 10 wysyła ruch referencyjny tylko przy ustawionym {@code SENTRY_DSN},
 * zawsze do release przypiętego w definicji, a scenariusze 11 i 12 rozmawiają z REST API tylko
 * przy ustawionym {@code SENTRY_AUTH_TOKEN}.</p>
 *
 * <p>Argumenty programu: {@code --plan} (synchronizacja tylko raportuje, niczego nie zmienia)
 * i {@code --simulate-drift} (przed ponownym uruchomieniem synchronizacji zapisuje w Sentry
 * zmianę, jaką zrobiłby człowiek w UI). Uruchomienie: {@code README.md} pakietu.</p>
 */
public final class DashboardAsCodeDemo {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private DashboardAsCodeDemo() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        DemoScenarios scenarios = DemoScenarios.from(args, 7, 12);
        Set<String> options = Set.of(args);
        Mode mode = options.contains("--plan") ? Mode.PLAN : Mode.APPLY;
        Optional<SentryDashboardsApi> api = SentryDashboardsApi.fromEnv(System.getenv());
        DashboardDefinition dashboard = DashboardDefinition.load();

        if (scenarios.includes(7)) definitionAndRules(dashboard);
        if (scenarios.includes(8)) pitfallsCaughtBeforeSending(dashboard, api);
        if (scenarios.includes(9)) payloadForApi(dashboard);
        if (scenarios.includes(10)) referenceTraffic(dashboard);
        if (!scenarios.includesAny(11, 12)) {
            return;
        }
        if (api.isEmpty()) {
            DemoConsole.lookAt("scenariusze 11 i 12 wymagają SENTRY_AUTH_TOKEN (zakresy org:read i event:read); "
                    + "bez niego demo nie zapisuje dashboardu w Sentry.");
            return;
        }
        // Test odbiorowy potrzebuje identyfikatora dashboardu, więc scenariusz 12 zawsze poprzedza synchronizacja.
        String dashboardId = syncIdempotently(api.get(), dashboard, mode, options.contains("--simulate-drift"));
        if (scenarios.includes(12)) acceptanceTest(api.get(), dashboard, dashboardId);
    }

    // Scenariusz 7: dashboard jako kod, definicja i reguły.
    //
    // O co chodzi: dashboard jest kontraktem operacyjnym: widget odpowiada na pytanie i prowadzi do
    // decyzji, a dashboard ma właściciela. Definicja checkout-dashboard.json leży w repozytorium obok kodu,
    // który wysyła metryki, i przechodzi przegląd jak kod.
    //
    // Co pokazujemy: DashboardDefinition.load wczytuje definicję (właściciel, projekt, jedno środowisko,
    // przypięty release, zakres 24h), demo wypisuje osiem widgetów z datasetem, wizualizacją, zapytaniem i
    // pytaniem, a DashboardRules.check sprawdza całość bez sieci.
    //
    // Problem: Sentry nie ma pól na pytanie, decyzję ani właściciela, a widget bez pytania albo decyzji
    // tylko zwiększa koszt poznawczy. Eksport JSON z Sentry nie nadaje się na definicję: zawiera
    // identyfikatory, daty i pola serwera, więc każdy zapis w UI zmieniałby plik w repozytorium.
    //
    // Dobra praktyka: failure rate jako jedno równanie sum(checkout.failed) / sum(checkout.attempted), p50
    // i p95 czasu w jednym widgecie (ta sama jednostka), max z gauge kolejki, crash-free sessions z
    // datasetu Releases, issues z datasetu Issues. Te same reguły uruchamia DashboardAsCodeTest przy każdym
    // ./mvnw test.
    //
    // Na co patrzeć: w konsoli nagłówek definicji (właściciel training, environment [training], release
    // [sentry-training@1.0.0-m10ref], zakres 24h), osiem widgetów z zapytaniem i pytaniem (tabela issues ma
    // zapytanie „lista”, bez agregacji) i na końcu „Reguły: brak naruszeń”.
    //
    // Uruchomienie: -Dexec.args=7 (bez sieci)
    static void definitionAndRules(DashboardDefinition dashboard) {
        DemoConsole.scenario(7, "Dashboard jako kod: definicja i reguły",
                "pokazać, że każdy widget ma w repozytorium pytanie, decyzję i dataset, a wykres także agregację.");

        DemoConsole.step("Plik " + DashboardDefinition.RESOURCE + ": „" + dashboard.title() + "”, właściciel "
                + dashboard.owner() + ", projekty " + dashboard.projects() + ", environment " + dashboard.environment()
                + ", release " + (dashboard.release().isEmpty() ? "(wszystkie)" : dashboard.release())
                + ", zakres " + dashboard.period());
        for (Widget widget : dashboard.widgets()) {
            DemoConsole.step("   " + widget.title() + " [" + widget.widgetType() + ", " + widget.displayType() + "]");
            DemoConsole.step("      zapytanie: " + (widget.aggregates().isEmpty() ? "lista" : String.join(" | ", widget.aggregates()))
                    + (widget.columns().isEmpty() ? "" : " według " + widget.columns())
                    + (widget.conditions().isEmpty() ? "" : " gdzie " + widget.conditions()));
            DemoConsole.step("      pytanie: " + widget.question());
        }
        List<DashboardRules.Violation> violations = DashboardRules.check(dashboard);
        DemoConsole.step("Reguły: " + (violations.isEmpty() ? "brak naruszeń" : violations));

        DemoConsole.lookAt("failure rate to jedno równanie sum(checkout.failed) / sum(checkout.attempted), a nie "
                + "średnia; czas checkoutu ma p50 i p95 w jednym widgecie, bo mają tę samą jednostkę; stan kolejki "
                + "to max z gauge; crash-free sessions pochodzi z datasetu Releases, a issues z datasetu Issues.");
    }

    // Scenariusz 8: pułapki wychwycone przed wysłaniem.
    //
    // O co chodzi: walidacja serwera sprawdza format, a nie sens. Część błędnych widgetów Sentry zapisuje
    // bez słowa i pokazuje w nich liczby, tylko błędne. Dlatego reguły są w repozytorium i działają bez
    // sieci.
    //
    // Co pokazujemy: siedem wariantów poprawnej definicji (pitfalls), każdy zmienia jedną rzecz: A średnia
    // procentów z gauge, B pusty filtr środowiska, C p50 i p95 według metody płatności z limitem 6, D
    // grupowanie po order.id, E brak właściciela, F checkout.attempts zamiast checkout.attempted, G pusty
    // filtr release. Z SENTRY_AUTH_TOKEN każdy wariant idzie też do serwera jako POST z validateOnly.
    //
    // Problem: lokalny self-hosted Sentry 26.9.0 przyjmuje wszystkie siedem wariantów. Zapis się udaje, a
    // błąd widać dopiero w liczbach (średnia procentów, mieszanka środowisk albo releases) albo w pustym
    // wykresie (metryka, której kod nie wysyła).
    //
    // Dobra praktyka: DashboardRules przed wysłaniem. DashboardSync nie wysyła definicji z naruszeniami, a
    // DashboardAsCodeTest sprawdza, że każdy wariant łamie oczekiwaną regułę. Reguły biorą nazwy metryk ze
    // stałych CheckoutMetrics, więc zmiana nazwy w kodzie bez zmiany dashboardu daje czerwony test.
    //
    // Na co patrzeć: w konsoli pod każdym wariantem co najmniej jedna linia „reguła [...]”; A ma dwie
    // (iloraz sum i kontrakt metryk), bo checkout.failure_rate nie istnieje w CheckoutMetrics. Z
    // SENTRY_AUTH_TOKEN pod każdym wariantem jest też linia „serwer (validateOnly): ...” z odpowiedzią
    // serwera.
    //
    // Uruchomienie: -Dexec.args=8 (odpowiedź serwera tylko z SENTRY_AUTH_TOKEN)
    static void pitfallsCaughtBeforeSending(DashboardDefinition dashboard, Optional<SentryDashboardsApi> api)
            throws IOException, InterruptedException {
        DemoConsole.scenario(8, "Pułapki wychwycone przed wysłaniem",
                "porównać reguły z repozytorium z walidacją serwera: część błędnych widgetów Sentry zapisuje bez słowa.");

        Map<String, DashboardDefinition> variants = pitfalls(dashboard);
        Map<String, Long> projectIds = api.isPresent() ? api.get().projectIds() : Map.of();
        Long teamId = api.isPresent() ? api.get().teamId(dashboard.owner()) : null;
        for (Map.Entry<String, DashboardDefinition> variant : variants.entrySet()) {
            DemoConsole.step(variant.getKey());
            DashboardRules.check(variant.getValue()).forEach(violation -> DemoConsole.step("   reguła " + violation));
            if (api.isPresent()) {
                List<Long> projects = variant.getValue().projects().stream().map(projectIds::get).toList();
                Long owner = variant.getValue().owner().isBlank() ? null : teamId;
                ObjectNode payload = DashboardPayload.toApi(variant.getValue(), projects, owner);
                Optional<String> serverError = api.get().validate(payload, projects);
                DemoConsole.step("   serwer (validateOnly): " + serverError.map(error -> "odrzuca: " + error).orElse("przyjmuje"));
            }
        }

        DemoConsole.lookAt("w konsoli każdy wariant ma co najmniej jedno naruszenie. Z SENTRY_AUTH_TOKEN widać też "
                + "odpowiedź serwera: Sentry 26.9.0 przyjmuje średnią procentów, pusty filtr środowiska, 12 serii, "
                + "grupowanie po order.id, brak właściciela, nieistniejącą metrykę i pusty filtr release. "
                + "Zapis się udaje, a błąd widać dopiero w liczbach albo w pustym wykresie.");
    }

    /** Warianty definicji z pułapkami; każdy zmienia jedną rzecz w poprawnej definicji. */
    static Map<String, DashboardDefinition> pitfalls(DashboardDefinition dashboard) {
        Map<String, DashboardDefinition> variants = new LinkedHashMap<>();
        variants.put("A. Średnia procentów: gauge checkout.failure_rate liczony w aplikacji i avg w widgecie",
                dashboard.withWidget("Failure rate checkoutu",
                        widget -> widget.withAggregates(List.of("avg(value,checkout.failure_rate,gauge,percent)"))));
        variants.put("B. Mieszanie środowisk: pusty filtr environment, czyli production razem ze staging",
                dashboard.withEnvironment(List.of()));
        variants.put("C. Zbyt wiele serii: p50 i p95 według metody płatności z limitem 6",
                dashboard.withWidget("Czas checkoutu p50 i p95",
                        widget -> widget.withGrouping(List.of(CheckoutMetrics.PAYMENT_METHOD), 6)));
        variants.put("D. Wysoka kardynalność: porażki grupowane po order.id",
                dashboard.withWidget("Porażki według przyczyny",
                        widget -> widget.withGrouping(List.of("order.id"), 5)));
        variants.put("E. Dashboard bez właściciela",
                dashboard.withOwner(""));
        variants.put("F. Kontrakt metryk: widget pyta o checkout.attempts, a kod wysyła checkout.attempted",
                dashboard.withWidget("Próby checkoutu według metody płatności",
                        widget -> widget.withAggregates(List.of("sum(value,checkout.attempts,counter,none)"))));
        variants.put("G. Wszystkie releases: pusty filtr release, czyli nowe wydanie razem ze starym i ruchem testowym",
                dashboard.withRelease(List.of()));
        return variants;
    }

    // Scenariusz 9: treść żądania dla REST API.
    //
    // O co chodzi: pola kontraktu z definicji trzeba przenieść do pól, które Sentry ma: pytanie i decyzję
    // do opisu widgetu, właściciela do Edit Access, progi decyzji do thresholds.
    //
    // Co pokazujemy: DashboardPayload.toApi buduje treść POST /api/0/organizations/{org}/dashboards/, a
    // demo wypisuje jej fragment: tytuł, projekty, środowisko, filtr release, permissions i pierwszy
    // widget. Offline identyfikatory projektu i zespołu są przykładowe (2); synchronizacja ustala je przez
    // API.
    //
    // Problem: API przyjmuje najwyżej 350 znaków opisu, więc pełny kontrakt widgetu (odbiorca, drill-down,
    // jakość danych) zostaje w repozytorium. Dashboard bez właściciela dostaje isEditableByEveryone=true:
    // zapisaną zmianę może zrobić każdy.
    //
    // Dobra praktyka: zespół właściciela w permissions.teamsWithEditAccess z isEditableByEveryone=false
    // (zapisać mogą członkowie zespołu, twórca i właściciele organizacji, czytać wszyscy), progi z polarity
    // „-”, bo mniej porażek to lepiej, i filtry projektu, środowiska oraz release zapisane w dashboardzie.
    //
    // Na co patrzeć: w konsoli JSON, w którym description zaczyna się od „Pytanie: ... Decyzja: ...”,
    // teamsWithEditAccess to [ 2 ], thresholds mają max1 0.02, max2 0.05 i preferred_polarity „-”, a
    // filters zawierają release sentry-training@1.0.0-m10ref.
    //
    // Uruchomienie: -Dexec.args=9 (bez sieci)
    static void payloadForApi(DashboardDefinition dashboard) {
        DemoConsole.scenario(9, "Treść żądania dla REST API",
                "pokazać, gdzie w API trafiają pytanie, decyzja i właściciel, dla których Sentry nie ma osobnych pól.");

        // Offline identyfikatory nie są znane, więc wstawiamy przykładowe (projekt 2, zespół 2
        // to wartości z lokalnego self-hosted). Synchronizacja ustala je przez API.
        ObjectNode payload = DashboardPayload.toApi(dashboard, List.of(2L), 2L);
        ObjectNode excerpt = JSON.createObjectNode();
        excerpt.set("title", payload.path("title"));
        excerpt.set("projects", payload.path("projects"));
        excerpt.set("environment", payload.path("environment"));
        excerpt.set("filters", payload.path("filters"));
        excerpt.set("permissions", payload.path("permissions"));
        excerpt.set("widgets[0]", payload.path("widgets").path(0));
        DemoConsole.step("Fragment treści POST /api/0/organizations/{org}/dashboards/:");
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(excerpt));

        DemoConsole.lookAt("pytanie i decyzja trafiają do description widgetu (limit 350 znaków), właściciel do "
                + "permissions.teamsWithEditAccess, a progi decyzji 0,02 i 0,05 do thresholds z polarity „-”, "
                + "bo mniej porażek to lepiej.");
    }

    // Scenariusz 10: ruch referencyjny pod dashboard.
    //
    // O co chodzi: żeby sprawdzić widgety, potrzebny jest ruch o znanych proporcjach. Trafia on do release
    // przypiętego w definicji, bo dashboard pokazuje tylko ten release.
    //
    // Co pokazujemy: osobna inicjalizacja SDK z release z definicji (inny SENTRY_RELEASE jest pomijany z
    // komunikatem) i z oboma callbackami TelemetryPrivacy, a w niej CheckoutEndpoint z sesją na request. 12
    // zamówień na przemian CARD i BLIK: 9 opłaconych, po jednym declined, timeout i EUR (internal_error,
    // crash), potem 4 pomiary kolejki z konsumentem stojącym w trzeciej minucie.
    //
    // Problem: ruch wysłany do innego release dałby puste widgety. Bez przypiętego release te same widgety
    // mieszałyby ten ruch z wariantami z błędami z Module10Demo: na lokalnej instancji failure rate wyniósł
    // wtedy około 0,91 zamiast 0,25.
    //
    // Dobra praktyka: w produkcji kierunek jest odwrotny: pipeline wdraża release, a zmiana release w
    // definicji przechodzi przegląd i APPLY. Kolejne uruchomienie dokłada ten sam ruch, więc liczby rosną,
    // a proporcje zostają.
    //
    // Na co patrzeć: offline tylko „Pominięty: bez SENTRY_DSN ...”. Z SENTRY_DSN podsumowanie: 12 prób, 9
    // opłaconych, 3 porażki, 12 sesji, w tym 1 crashed, i 4 pomiary kolejki. Na dashboardzie failure rate
    // 0,25 (3 z 12), po jednej porażce dla trzech przyczyn, crash-free sessions 11 z 12 (91,7%, sesje po
    // około minucie) i max głębokości kolejki 2.
    //
    // Uruchomienie: -Dexec.args=10 (wymaga SENTRY_DSN)
    static void referenceTraffic(DashboardDefinition dashboard) {
        DemoConsole.scenario(10, "Ruch referencyjny pod dashboard",
                "wysłać ruch o znanych proporcjach, żeby porównać widgety z konsolą.");
        String dsn = System.getenv("SENTRY_DSN");
        if (dsn == null || dsn.isBlank()) {
            DemoConsole.step("Pominięty: bez SENTRY_DSN nie ma dokąd wysłać ruchu (Module10Demo pokazuje metryki offline).");
            return;
        }
        // Ruch idzie do release przypiętego w definicji, a nie do SENTRY_RELEASE: dashboard pokazuje
        // tylko ten release, więc inna wartość dałaby puste widgety. W produkcji kierunek jest odwrotny:
        // pipeline wdraża release, a zmiana definicji na ten release przechodzi przegląd i APPLY.
        String release = dashboard.release().getFirst();
        String fromEnv = System.getenv("SENTRY_RELEASE");
        if (fromEnv != null && !fromEnv.isBlank() && !fromEnv.equals(release)) {
            DemoConsole.step("SENTRY_RELEASE=" + fromEnv + " pominięty: ruch referencyjny trafia do release z definicji.");
        }
        try (TrainingSentry.TrainingSession session = TrainingSentry.init("module10", options -> {
            options.setBeforeSend(TelemetryPrivacy::scrubEvent);
            options.getMetrics().setBeforeSend(TelemetryPrivacy::scrubMetric);
            options.setRelease(release);
        })) {
            CheckoutEndpoint checkout = new CheckoutEndpoint(SessionTracking.PER_REQUEST);
            List<Order> orders = new ArrayList<>();
            for (int i = 1; i <= 12; i++) {
                PaymentMethod method = i % 2 == 0 ? PaymentMethod.BLIK : PaymentMethod.CARD;
                String id = "ORD-10" + String.format("%02d", i);
                orders.add(switch (i) {
                    case 4 -> Order.pln(id, method, "declined");
                    case 8 -> Order.pln(id, method, "timeout");
                    case 12 -> Order.eur(id, method);
                    default -> Order.pln(id, method, "ok");
                });
            }
            long paid = 0;
            for (Order order : orders) {
                Response response = checkout.handle(order);
                paid += "PAID".equals(response.body()) ? 1 : 0;
            }
            PaymentQueue queue = new PaymentQueue();
            for (int minute = 1; minute <= 4; minute++) {
                queue.enqueue("ORD-Q" + minute + "a");
                queue.enqueue("ORD-Q" + minute + "b");
                queue.consume(minute == 3 ? 0 : 2);
                CheckoutMetrics.queueDepth(queue.depth());
            }
            Sentry.flush(5_000);
            DemoConsole.step("Wysłano " + orders.size() + " prób, opłaconych " + paid + ", porażek " + (orders.size() - paid)
                    + " (declined, gateway_timeout, internal_error), 12 sesji, w tym 1 crashed, i 4 pomiary kolejki.");
        }

        DemoConsole.lookAt("na dashboardzie (filtr release " + release + " jest zapisany w definicji): failure rate "
                + "3 / 12 = 0,25, porażki po jednej dla trzech przyczyn, crash-free sessions 11 z 12 (91,7%, sesje po "
                + "około minucie), max głębokości kolejki 2. Kolejne uruchomienie dokłada ten sam ruch, więc liczby "
                + "rosną, a proporcje zostają.");
    }

    // Scenariusz 11: synchronizacja, utwórz albo zaktualizuj po tytule.
    //
    // O co chodzi: Sentry ma pokazywać definicję z repozytorium. Synchronizacja jest idempotentna: kolejne
    // uruchomienie z tą samą definicją niczego nie zmienia, a ręczna zmiana zapisana w UI to drift.
    //
    // Co pokazujemy: DashboardSync szuka dashboardu po tytule. Krok A tworzy go albo porównuje z definicją,
    // krok B powtarza to samo. Z --simulate-drift krok C zapisuje przez API zmianę, jaką zrobiłby człowiek
    // w UI (wszystkie środowiska, release sentry-training@1.0.0, widget po order.id), D raportuje ją w
    // trybie PLAN, a E synchronizuje w trybie z argumentów. --plan sprawia, że A, B i E tylko raportują.
    //
    // Problem: samo POST nie jest idempotentne: przy zajętym tytule Sentry 26.9.0 tworzy dashboard z
    // dopiskiem „copy”. Porównanie całych dokumentów zgłaszałoby drift przy każdym uruchomieniu
    // (identyfikatory, daty, preferred_polarity wraca jako preferredPolarity), a PUT bez różnic dawałby
    // nową wersję przy każdym przebiegu.
    //
    // Dobra praktyka: wyszukanie po tytule przed POST, porównanie tylko pól zarządzanych przez definicję,
    // PUT tylko przy drifcie i stop przy kilku dashboardach o tym samym tytule. W pipeline PLAN przy pull
    // requeście, APPLY po scaleniu; drift z PLAN trzeba przenieść do repozytorium albo świadomie odrzucić.
    //
    // Na co patrzeć: bez SENTRY_AUTH_TOKEN tylko komunikat, że scenariusze 11 i 12 go wymagają. Z tokenem
    // krok A wypisuje wynik i adres dashboardu, krok B UNCHANGED. Z --simulate-drift krok D wypisuje drift
    // środowiska, release i dodanego widgetu, a krok E przywraca definicję. W Sentry UI (Dashboards) jeden
    // dashboard z zapisanymi filtrami projektu, środowiska i release, z Edit Access zespołu training (widać
    // go w trybie edycji) i z pytaniem oraz decyzją w opisie każdego widgetu.
    //
    // Uruchomienie: -Dexec.args=11 (wymaga SENTRY_AUTH_TOKEN; opcjonalnie z --plan albo --simulate-drift,
    // np. -Dexec.args="11 --simulate-drift")
    static String syncIdempotently(SentryDashboardsApi api, DashboardDefinition dashboard, Mode mode, boolean simulateDrift)
            throws IOException, InterruptedException {
        DemoConsole.scenario(11, "Synchronizacja: utwórz albo zaktualizuj po tytule",
                "pokazać, że ponowne uruchomienie niczego nie duplikuje, a ręczna zmiana w UI jest wykrywana jako drift.");
        DashboardSync sync = new DashboardSync(api);

        DemoConsole.step("A. Synchronizacja z definicją w repozytorium (tryb " + mode + ")");
        Result first = report(sync.sync(dashboard, mode));
        if (first.dashboardId() == null) {
            DemoConsole.lookAt("tryb PLAN niczego nie tworzy; uruchom bez --plan, żeby utworzyć dashboard.");
            return null;
        }
        DemoConsole.step("   " + api.dashboardUrl(first.dashboardId()));

        DemoConsole.step("B. Ponowne uruchomienie z tą samą definicją, tuż po A");
        report(sync.sync(dashboard, mode));

        if (simulateDrift) {
            DemoConsole.step("C. Ręczna zmiana w UI: ktoś wybiera wszystkie środowiska, przełącza release na "
                    + TrainingSentry.DEFAULT_RELEASE + ", dodaje widget po order.id i zapisuje");
            simulateManualEdit(api, dashboard, first.dashboardId());
            DemoConsole.step("D. Ponowne uruchomienie w trybie PLAN: tylko raport");
            report(sync.sync(dashboard, Mode.PLAN));
            DemoConsole.step("E. Ponowne uruchomienie w trybie " + mode);
            report(sync.sync(dashboard, mode));
        }

        DemoConsole.lookAt("w konsoli krok B daje UNCHANGED: bez różnic synchronizacja nie wysyła PUT, więc dashboard "
                + "nie dostaje nowej wersji przy każdym uruchomieniu. Z --simulate-drift krok D wypisuje różnice, "
                + "a krok E przywraca definicję z repozytorium.");
        DemoConsole.lookAt("w Sentry UI (Dashboards) jest jeden dashboard „" + dashboard.title() + "” z zapisanymi "
                + "filtrami projektu, środowiska i release " + dashboard.release() + ". W trybie edycji Edit Access wskazuje zespół " + dashboard.owner()
                + ", a opis każdego widgetu zawiera pytanie i decyzję z definicji.");
        return first.dashboardId();
    }

    private static Result report(Result result) {
        DemoConsole.step("   wynik: " + result.action() + (result.dashboardId() == null ? "" : ", dashboard " + result.dashboardId()));
        result.drift().forEach(difference -> DemoConsole.step("   drift: " + difference));
        return result;
    }

    /**
     * Zapis, jaki zrobiłby człowiek w UI: z pominięciem reguł i definicji w repozytorium.
     *
     * <p>Skrót szkoleniowy. W prawdziwym zespole tę zmianę robi ktoś w przeglądarce, a synchronizacja
     * dowiaduje się o niej dopiero przy kolejnym uruchomieniu.</p>
     */
    private static void simulateManualEdit(SentryDashboardsApi api, DashboardDefinition dashboard, String id)
            throws IOException, InterruptedException {
        DashboardDefinition edited = dashboard
                .withEnvironment(List.of())
                .withRelease(List.of(TrainingSentry.DEFAULT_RELEASE))
                .withAddedWidget(new Widget("Porażki według zamówienia", "Które zamówienie?", "Brak.",
                        "tracemetrics", "line", "1h", 5,
                        List.of("sum(value,checkout.failed,counter,none)"), List.of("order.id"), "", "",
                        null, new DashboardDefinition.Layout(0, 11, 6, 2)));
        Map<String, Long> known = api.projectIds();
        List<Long> projects = dashboard.projects().stream().map(known::get).toList();
        JsonNode actual = api.get(id);
        ObjectNode payload = DashboardPayload.withExistingWidgetIds(
                DashboardPayload.toApi(edited, projects, api.teamId(dashboard.owner())), actual);
        api.update(id, payload, projects);
    }

    // Scenariusz 12: test odbiorowy, czy widgety mają dane.
    //
    // O co chodzi: dashboard zapisany bez błędu nie dowodzi, że widget ma dane. Literówka w nazwie metryki,
    // zła jednostka albo filtr środowiska dają pusty wykres, a nie błąd zapisu. Brak danych to brak
    // pomiaru, a nie zero.
    //
    // Co pokazujemy: dla każdego widgetu SentryDashboardsApi.widgetData wykonuje jego zapytanie z filtrami
    // dashboardu (projekt, środowisko, zakres, release): Application Metrics przez events, Releases przez
    // sessions, Issues przez issues. Wynik to „dane” ze skrótem wartości albo „BRAK DANYCH”.
    //
    // Problem: równanie failure rate dla release bez ruchu zwraca 0, a nie brak wyniku, więc widget z
    // progami może pokazać 0 w zielonym przedziale. Sesje pojawiają się po około minucie, więc zaraz po
    // scenariuszu 10 crash-free może jeszcze nie mieć danych.
    //
    // Dobra praktyka: test sprawdza operandy równania (obie sumy), a nie sam wynik, a decyzja widgetu każe
    // patrzeć na liczbę prób. Test odbiorowy po każdej zmianie dashboardu, dla wartości prawidłowej,
    // pogorszonej i braku danych.
    //
    // Na co patrzeć: bez SENTRY_AUTH_TOKEN tylko komunikat o wymaganym tokenie. Z tokenem najpierw przebieg
    // scenariusza 11, potem linia na widget: „dane” albo „BRAK DANYCH”. Zapytania mają filtr release z
    // definicji, więc failure rate to 0,25 z ruchu referencyjnego, a nie mieszanka z wariantami z błędami z
    // Module10Demo.
    //
    // Uruchomienie: -Dexec.args=12 (wymaga SENTRY_AUTH_TOKEN; zawsze poprzedza go synchronizacja ze
    // scenariusza 11, a dane daje wcześniejsze uruchomienie scenariusza 10 z SENTRY_DSN)
    static void acceptanceTest(SentryDashboardsApi api, DashboardDefinition dashboard, String dashboardId)
            throws IOException, InterruptedException {
        DemoConsole.scenario(12, "Test odbiorowy: czy widgety mają dane",
                "wykonać zapytania widgetów z filtrami dashboardu i odróżnić dane od ich braku.");
        if (dashboardId == null) {
            DemoConsole.step("Pominięty: dashboard nie istnieje.");
            return;
        }
        Map<String, Long> known = api.projectIds();
        List<Long> projects = dashboard.projects().stream().map(known::get).toList();
        for (Widget widget : dashboard.widgets()) {
            SentryDashboardsApi.WidgetData data = api.widgetData(dashboard, widget, projects);
            DemoConsole.step((data.hasData() ? "dane       " : "BRAK DANYCH ") + widget.title() + ": " + data.summary());
        }

        DemoConsole.lookAt("w konsoli widget bez danych to brak pomiaru, a nie zero: metryki pojawiają się po kilku "
                + "sekundach, sesje po około minucie, więc zaraz po scenariuszu 10 crash-free może jeszcze nie mieć "
                + "danych. Zapytania mają filtr release " + dashboard.release() + ", więc failure rate to 0,25 z ruchu "
                + "referencyjnego, a nie mieszanka z wariantami z błędami z Module10Demo.");
    }
}
