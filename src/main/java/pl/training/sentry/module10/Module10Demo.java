package pl.training.sentry.module10;

import io.sentry.ISentryLifecycleToken;
import io.sentry.Sentry;
import io.sentry.protocol.User;
import pl.training.sentry.module10.CheckoutEndpoint.Response;
import pl.training.sentry.module10.CheckoutEndpoint.SessionTracking;
import pl.training.sentry.module10.Order.PaymentMethod;
import pl.training.sentry.module10.Ratios.Bucket;
import pl.training.sentry.support.DemoConsole;
import pl.training.sentry.support.DemoScenarios;
import pl.training.sentry.support.TrainingSentry;

import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.function.Function;

// Moduł 10: Dashboardy, metryki i operacyjny monitoring aplikacji.
//
// Dashboard jako kontrakt operacyjny
//
// Dashboard ma skracać drogę od pytania operacyjnego do decyzji albo do widoku, w którym szuka się dowodu.
// Sam zbiór wykresów tego nie robi. Każdy widget (pojedynczy element dashboardu: wykres, liczba albo
// tabela) powinien mieć odbiorcę, pytanie, czyli niepewność, którą usuwa, oraz decyzję dla wyniku
// prawidłowego, pogorszonego i nieznanego. Do tego dochodzi część pomiarowa: dataset, zakres, agregacja,
// mianownik i jednostka. Dashboard jako całość ma właściciela, który odpowiada za semantykę i filtry.
// Widget bez pytania albo decyzji tylko zwiększa koszt poznawczy, więc trzeba go przeprojektować albo
// usunąć. Sentry nie ma jednak pól na pytanie, decyzję ani właściciela, dlatego w tym module zapisujemy je
// w definicji dashboardu w repozytorium.
//
// Dashboard pokazuje stan i trend, ale nie pilnuje niczego sam. Warunek ocenia automatycznie Monitor, a
// powiadomienie do odbiorcy kieruje Alert, więc krytyczny sygnał nie może zależeć od tego, czy ktoś akurat
// patrzy na wykres.
//
// Jeden widget, jeden dataset
//
// Widget czyta dane z jednego datasetu, czyli źródła o określonej semantyce. W tym module używamy trzech:
// Application Metrics (liczby wysyłane świadomie przez kod aplikacji), Releases (sesje Release Health
// przypisane do wersji) i Issues (pogrupowane problemy ze statusem). Dashboard może stawiać obok siebie
// widgety z różnych datasetów, ale nie tworzy to wspólnej populacji. Nie dzieli się więc liczby zdarzeń
// błędów przez liczbę sesji: to inne populacje z innym cyklem życia.
//
// Application Metrics: typ metryki wyznacza agregacje
//
// Metryka ma nazwę, typ, opcjonalną jednostkę i atrybuty. Widget dzieli zakres czasu na przedziały
// (buckety) i w każdym liczy wybraną agregację, a typ metryki decyduje o tym, które agregacje są dostępne,
// czyli jakie pytania da się potem zadać:
// - counter liczy zdarzenia, np. checkout.attempted albo payments.queue.enqueued, i daje się sumować;
// - gauge to wartość stanu w chwili pomiaru, np. payments.queue.depth, czyli liczba czekających
//   potwierdzeń płatności. Ma agregacje min, max i avg, ale nie ma agregacji latest (wartość ostatnia),
//   więc widget stanu jawnie wybiera max albo avg w buckecie;
// - distribution zachowuje rozkład obserwacji, np. checkout.duration, więc Sentry liczy z niego percentyle
//   p50 i p95.
// Na jednej osi łączy się tylko serie o tej samej jednostce, dlatego p50 i p95 czasu mogą być w jednym
// widgecie. Zaległość w kolejce jest stanem, więc widać ją tylko w gauge: licznik napływu ma co minutę tę
// samą wartość także wtedy, gdy konsument stoi. Gauge emituje się w stałym rytmie, np. raz na minutę z
// harmonogramu, bo jego średnia zależy od częstotliwości emisji: gdy kod mierzy przy każdej zmianie,
// okresy dużego ruchu dostają więcej pomiarów i przeważają w średniej.
//
// Jednostkę bierze się z MetricsUnit, np. MetricsUnit.Duration.MILLISECOND, a przekazana wartość musi być
// wyrażona w tej jednostce. Sentry rozróżnia metryki po nazwie, typie i jednostce, więc ta sama nazwa z
// inną jednostką to inna seria. W Java SDK metryki są domyślnie włączone. SDK nie wysyła ich od razu:
// zbiera je w buforze i wysyła partiami mniej więcej 5 sekund po pierwszej metryce, dlatego demo po każdym
// kroku woła Sentry.flush. Obiekt z Sentry.metrics() pobiera się przy każdym wywołaniu: zapamiętany w polu
// przed Sentry.init jest implementacją NoOp, która po cichu gubi wszystkie metryki.
//
// Kontrakt emisji: próba przed pracą, wynik osobno
//
// Nazwy, typy, jednostki i atrybuty metryk tworzą kontrakt, na którym opierają się dashboard, Monitory i
// zapisane zapytania. W tym module zbiera go jedna klasa, CheckoutMetrics. Failure rate checkoutu, czyli
// odsetek prób zakończonych porażką, to suma checkout.failed podzielona przez sumę checkout.attempted,
// więc o jego wartości decyduje miejsce, w którym kod liczy próbę. Próba liczona dopiero po odpowiedzi
// bramki płatności gubi wyjątki i timeouty: znikają one i z licznika, i z mianownika, a wskaźnik wygląda
// lepiej, niż jest. Dlatego checkout.attempted emituje się przed pracą, która może się nie udać, a wynik
// terminalny (końcowy) osobno: checkout.completed albo checkout.failed z przyczyną w atrybucie
// checkout.outcome.
//
// Wynik domenowy to nie kod HTTP. Odrzucona płatność wraca do klienta z HTTP 200, bo to poprawna odpowiedź
// biznesowa, ale zamówienie nie jest opłacone. Wskaźnik liczony z kodów HTTP uzna ją za sukces
// transportowy, a checkout.completed słusznie jej nie policzy.
//
// Atrybuty i kardynalność
//
// Atrybut metryki to wymiar, po którym widget grupuje dane w serie, czyli osobne linie albo słupki.
// Kardynalność to liczba różnych wartości atrybutu. Wymiar o zamkniętym słowniku, jak payment.method albo
// checkout.outcome, daje kilka serii i pozwala odpowiedzieć na pytanie, dlaczego płatności nie przechodzą.
// Identyfikator zamówienia daje tyle serii, ile zamówień, a wykres i tak pokaże tylko tyle, ile pozwala
// limit widgetu (najwyżej 10 serii). Komunikat bramki ma podobną kardynalność i może zawierać dane
// osobowe. Dlatego metody CheckoutMetrics przyjmują wyłącznie enumy: przez takie API nie da się przekazać
// identyfikatora ani komunikatu. Identyfikator zamówienia należy do zdarzenia błędu albo logu, gdzie służy
// do znalezienia konkretnego przypadku.
//
// Dane osobowe w metrykach: osobny callback
//
// Metryka dostaje atrybuty nie tylko z wywołania count, gauge czy distribution. SDK dokleja do niej
// atrybuty user.id, user.name i user.email ze scope, czyli kontekstu, który SDK trzyma dla bieżącego
// żądania (w Java SDK 8.x w isolation scope żądania), jeśli ktoś wcześniej ustawił tam użytkownika, np.
// filtr uwierzytelniania. Enumowe API nic tu nie pomoże. Callback options.setBeforeSend widzi tylko
// zdarzenia błędów i wiadomości, więc zespół, który czyści w nich e-mail, wciąż wysyła go w każdej metryce
// żądania. Metryki mają własny callback, options.getMetrics().setBeforeSend, ustawiany w Sentry.init obok
// callbacku zdarzeń. Usuwa on user.email i user.name, zostawia techniczny user.id i zawsze zwraca metrykę,
// bo null oznacza jej odrzucenie. Wyjątek w tym callbacku też kończy się odrzuceniem metryki, więc błąd w
// polityce danych po cichu opróżnia wykresy.
//
// Wskaźnik z całego zakresu: iloraz sum
//
// Failure rate doby liczy się jako suma porażek przez sumę prób z tego samego zakresu, okna i filtrów.
// Wtedy każda próba ma tę samą wagę. Średnia z ilorazów policzonych osobno dla każdego bucketu daje
// każdemu bucketowi tę samą wagę niezależnie od ruchu: nocna godzina z 3 porażkami na 6 prób waży tyle co
// godzina szczytu z tysiącem prób. Na dashboardzie failure rate jest więc jednym równaniem (equation)
// sum(checkout.failed) / sum(checkout.attempted), a nie średnią procentów liczonych w aplikacji.
//
// Przy zerowym mianowniku wyniku nie ma. Okno bez prób nie ma failure rate 0%, a wstawienie zera za pustą
// godzinę zaniżyłoby wynik. Obserwowane zero i brak pomiaru to różne stany i dashboard powinien je
// odróżniać.
//
// Release Health: sesje trzeba utworzyć
//
// Release Health opisuje zdrowie wersji na podstawie sesji, czyli jednostek pracy aplikacji o znanym
// początku i końcu. Crash-free sessions to udział sesji bez crashu we wszystkich sesjach wersji, a
// crash-free users to udział zidentyfikowanych użytkowników bez crashu. W trybie request, typowym dla
// backendu, sesją jest jedno żądanie. Serwerowy Java SDK nie tworzy sesji sam, a integracja Spring też nie
// wywołuje Sentry.startSession. Wersja bez sesji w ogóle nie ma wskaźnika crash-free, co nie znaczy, że
// jest zdrowa. SDK nie wystartuje też sesji bez ustawionego pola release.
//
// Sesja na żądanie to Sentry.startSession w isolation scope żądania i Sentry.endSession na jego końcu. SDK
// wysyła sesję na początku (z oznaczeniem init) i przy zakończeniu. Stan sesji zależy od tego, jak
// zgłoszono błąd. Błąd obsłużony zwiększa licznik errors, a sesja kończy się jako exited i Sentry liczy ją
// jako errored. Tylko wyjątek zgłoszony jako nieobsłużony (handled=false) zmienia sesję na crashed, a SDK
// wysyła wtedy jej stan końcowy razem ze zdarzeniem. Dlatego po crashu nie woła się już endSession: drugi
// stan crashed Sentry policzyłoby jako drugi crash. Wskaźnik crash-free users wymaga identyfikatora
// distinctId, który Java SDK bierze z konfiguracji, a nie z użytkownika w scope, więc jedna wartość
// obowiązuje dla całego procesu. Przy sesji na żądanie nie da się zatem liczyć crash-free users dla
// poszczególnych klientów i w tym module wskaźnik ten zostaje bez wartości. Sesje pojawiają się w Sentry
// UI (Releases) po około minucie.
//
// Dashboard jako kod
//
// Definicja dashboardu leży w repozytorium obok kodu, który wysyła metryki, i przechodzi przegląd jak kod.
// Ma własny format bliski REST API Sentry, z dodatkowymi polami na pytanie, decyzję i właściciela. Eksport
// JSON z Sentry się do tego nie nadaje, bo zawiera identyfikatory, daty i pola ustawiane przez serwer,
// więc każdy zapis w UI zmieniałby plik. Agregacja metryki ma w zapytaniu postać
// funkcja(value,nazwa,typ,jednostka), np. max(value,payments.queue.depth,gauge,none), gdzie none oznacza
// brak jednostki.
//
// Walidacja serwera sprawdza format, a nie sens. Lokalny self-hosted Sentry 26.9.0 zapisuje bez błędu
// średnią procentów, wykres z 12 seriami, grupowanie po order.id, nazwę metryki, której kod nie wysyła, i
// puste filtry środowiska czy release. Przyjmuje je także żądanie z parametrem validateOnly, które
// sprawdza definicję bez zapisu. Zapis się udaje, a błąd widać dopiero w liczbach albo w pustym wykresie.
// Dlatego reguły (iloraz sum, jedno środowisko, przypięty release, limit serii jako liczba grup razy
// liczba agregacji, zamknięty słownik grupowania, właściciel, zgodność z kontraktem CheckoutMetrics)
// działają w repozytorium, bez sieci, także w teście uruchamianym przy każdym ./mvnw test. Reguły biorą
// nazwy metryk ze stałych CheckoutMetrics, więc zmiana nazwy w kodzie bez zmiany dashboardu daje czerwony
// test, a nie pusty wykres po wdrożeniu.
//
// Filtry globalne i zapis przez REST API
//
// Dashboard ma filtry globalne projektu, środowiska, zakresu czasu i release, które obejmują wszystkie
// widgety. Pusta lista w filtrze oznacza w Sentry wszystko: pusty filtr środowiska miesza production ze
// środowiskiem staging, a pusty filtr release miesza nowe wydanie ze starym i z ruchem testowym. Liczby z
// takiej mieszanki nie da się porównać z punktem odniesienia (baseline), dlatego definicja przypina jedno
// środowisko i konkretny release. Nowe wydanie to zmiana tej wartości w repozytorium, która przechodzi
// przegląd.
//
// Pola kontraktu trzeba przenieść do pól, które Sentry ma. Pytanie i decyzja trafiają do opisu widgetu
// (API przyjmuje najwyżej 350 znaków, więc pełny kontrakt zostaje w repozytorium). Właściciel trafia do
// ustawienia Edit Access (uprawnienia do edycji): zapisać zmianę mogą wtedy członkowie zespołu, twórca
// dashboardu i właściciele organizacji, a czytać wszyscy. Dashboard bez właściciela może zmienić każdy.
// Granice z decyzji, dla failure rate 0,02 i 0,05, trafiają do pola thresholds, które koloruje wartość
// widgetu, a kierunek (polarity) „-” oznacza, że mniej porażek to lepiej.
//
// Synchronizacja idempotentna i drift
//
// Synchronizacja jest idempotentna, gdy kolejne uruchomienie z tą samą definicją niczego nie zmienia. Samo
// żądanie POST tego nie daje: przy zajętym tytule Sentry tworzy drugi dashboard z dopiskiem „copy”.
// Dlatego synchronizacja najpierw szuka dashboardu po tytule, porównuje tylko pola, za które odpowiada
// definicja, i wysyła PUT tylko wtedy, gdy są różnice. Porównanie całych dokumentów zgłaszałoby różnice
// przy każdym uruchomieniu, a PUT bez różnic tworzyłby za każdym razem nową wersję. Drift to rozbieżność
// między definicją a dashboardem zmienionym ręcznie w UI. W pipeline CI tryb PLAN tylko raportuje różnice
// przy pull requeście, a tryb APPLY po scaleniu przywraca definicję. Drift wykryty w PLAN trzeba przenieść
// do repozytorium albo świadomie odrzucić, a nie nadpisać bez słowa.
//
// Test odbiorowy: czy widget ma dane
//
// Dashboard zapisany bez błędu nie dowodzi, że widgety mają dane. Literówka w nazwie metryki, zła
// jednostka albo filtr środowiska dają pusty wykres, a nie błąd zapisu. Test odbiorowy wykonuje zapytanie
// każdego widgetu z filtrami dashboardu i odróżnia dane od ich braku. Pełny test obejmuje wartość
// prawidłową, pogorszoną i brak danych. Do sprawdzenia widgetów służy ruch referencyjny o znanych
// proporcjach, wysłany do przypiętego release. Uwaga na równania: failure rate dla release bez ruchu
// wychodzi 0, a nie brak wyniku, więc widget może pokazać zero w zielonym przedziale. Dlatego test
// sprawdza oba operandy równania, a decyzja widgetu każe patrzeć także na liczbę prób.
public final class Module10Demo {

    private static final CheckoutEndpoint CHECKOUT = new CheckoutEndpoint(SessionTracking.NONE);

    private Module10Demo() {
    }

    public static void main(String[] args) {
        DemoScenarios scenarios = DemoScenarios.from(args, 1, 6);
        try (TrainingSentry.TrainingSession session = TrainingSentry.init("module10", options -> {
            // Wartość domyślna SDK, ustawiona jawnie. Eksperyment: false wyłącza wszystkie metryki.
            // Wydruk traci linie metryk, a eventy, sesje i odpowiedzi aplikacji zostają bez zmian.
            options.getMetrics().setEnabled(true);
            // Polityka danych dla eventów, jaką zespół zwykle ma od dawna (moduły 1 i 2).
            // Callback dla metryk scenariusz 4 włącza dopiero w swoim drugim kroku.
            options.setBeforeSend(TelemetryPrivacy::scrubEvent);
        })) {
            if (scenarios.includes(1)) attemptBeforeWork();
            if (scenarios.includes(2)) gaugeForState();
            if (scenarios.includes(3)) boundedAttributes();
            if (scenarios.includes(4)) personalDataFromScope();
            if (scenarios.includes(5)) ratioOfSums();
            if (scenarios.includes(6)) releaseHealthNeedsSessions();
        }
    }

    // Scenariusz 1: próba przed pracą, wynik osobno.
    //
    // O co chodzi: failure rate to suma checkout.failed przez sumę checkout.attempted, więc o jego wartości
    // decyduje miejsce, w którym kod emituje próbę. Do tego odrzucona płatność wraca z HTTP 200, choć
    // zamówienie nie jest opłacone: sukces transportowy to nie sukces domenowy.
    //
    // Co pokazujemy: te same cztery zamówienia (dwa opłacone, jedno odrzucone, jeden timeout bramki) przez
    // dwie wersje CheckoutEndpoint: A to handleCountingAfterWork, B to handle. Każdy wariant kończy się
    // Sentry.flush, bo SDK buforuje metryki i bez tego wypisałoby je kilka sekund później, pod innym
    // scenariuszem.
    //
    // Problem: w wariancie A checkout.attempted jest liczony dopiero po odpowiedzi bramki, a ścieżki
    // wyjątków nie emitują niczego. Timeout ORD-1004 znika z licznika i z mianownika: 3 próby, 1 porażka,
    // failure rate 1/3 zamiast 2/4, czyli lepiej, niż jest.
    //
    // Dobra praktyka: checkout.attempted przed wywołaniem bramki, a każdy wynik terminalny osobno:
    // checkout.completed albo checkout.failed z checkout.outcome, plus checkout.duration z jednostką
    // MetricsUnit.Duration.MILLISECOND. Opłacone zamówienie liczy checkout.completed, a nie kod HTTP.
    //
    // Na co patrzeć: w konsoli pod każdym wariantem odpowiedzi, event timeoutu z handled=tak i po flush
    // linie „↳ metryka”. Wariant A: 3 razy checkout.attempted i 1 checkout.failed (declined). Wariant B: 4
    // próby, 2 checkout.completed i 2 checkout.failed (declined, gateway_timeout). 3 z 4 odpowiedzi to HTTP
    // 200, opłacone są 2. W Sentry UI (Explore, Metrics) suma checkout.attempted po payment.method, suma
    // checkout.failed po checkout.outcome, a p50 i p95 checkout.duration są podpisane jednostką
    // millisecond.
    //
    // Uruchomienie: -Dexec.args=1
    static void attemptBeforeWork() {
        DemoConsole.scenario(1, "Próba przed pracą, wynik osobno",
                "pokazać, że kolejność emisji decyduje o mianowniku failure rate, a HTTP 200 nie oznacza opłaconego zamówienia.");

        List<Order> orders = List.of(
                Order.pln("ORD-1001", PaymentMethod.CARD, "ok"),
                Order.pln("ORD-1002", PaymentMethod.BLIK, "ok"),
                Order.pln("ORD-1003", PaymentMethod.BLIK, "declined"),
                Order.pln("ORD-1004", PaymentMethod.CARD, "timeout"));

        DemoConsole.step("A. Metryki dopisane na końcu happy path (handleCountingAfterWork)");
        respond(orders, CHECKOUT::handleCountingAfterWork);
        Sentry.flush(2_000);

        DemoConsole.step("B. Wersja docelowa: checkout.attempted przed wywołaniem bramki (handle)");
        respond(orders, CHECKOUT::handle);
        Sentry.flush(2_000);

        DemoConsole.lookAt("w konsoli wariant A ma 3 razy checkout.attempted i 1 checkout.failed: timeout ORD-1004 "
                + "zniknął z metryk, a failure rate wynosi 1/3. Wariant B ma 4 próby, 2 sukcesy i 2 porażki "
                + "(declined, gateway_timeout), czyli 2/4. Odpowiedzi: 3 z 4 to HTTP 200, opłacone są 2.");
        DemoConsole.lookAt("w Sentry UI (Explore, Metrics) suma checkout.attempted z grupowaniem po payment.method "
                + "i suma checkout.failed z grupowaniem po checkout.outcome; checkout.duration ma jednostkę millisecond, "
                + "więc p50 i p95 są podpisane czasem.");
    }

    // Scenariusz 2: stan kolejki, gauge, a nie counter.
    //
    // O co chodzi: counter liczy zdarzenia (ile potwierdzeń płatności wpłynęło), a gauge podaje stan w
    // chwili pomiaru (ile czeka). Zaległość jest stanem, więc widać ją tylko w gauge.
    //
    // Co pokazujemy: cztery „minuty” PaymentQueue. Co minutę wpływają 2 potwierdzenia, każde z licznikiem
    // payments.queue.enqueued, a od trzeciej minuty konsument stoi. Raz na minutę kod wysyła stan kolejki
    // jako gauge payments.queue.depth.
    //
    // Problem: licznik napływu ma co minutę tę samą wartość, więc wykres z niego wygląda normalnie, gdy
    // konsument stoi. Typowy błąd przy gauge to emisja przy każdym enqueue: średnia gauge zależy wtedy od
    // częstotliwości emisji i nadreprezentuje okresy dużego ruchu.
    //
    // Dobra praktyka: gauge stanu w stałym rytmie (tu raz na „minutę”, w aplikacji z harmonogramu) i widget
    // z jawnie wybraną agregacją max albo avg w buckecie, bo gauge nie ma agregacji latest.
    //
    // Na co patrzeć: w konsoli kroki „Minuta 1-4” z liczbą czekających 0, 0, 2, 4, a pod nimi co minutę
    // dwie linie payments.queue.enqueued = 1 i gauge payments.queue.depth rosnący 0, 0, 2, 4. W Sentry UI
    // cztery „minuty” trafiają do jednego bucketu: jedno uruchomienie daje max(payments.queue.depth) = 4 i
    // sum(payments.queue.enqueued) = 8, a nie wykres rosnący minuta po minucie.
    //
    // Uruchomienie: -Dexec.args=2
    static void gaugeForState() {
        DemoConsole.scenario(2, "Stan kolejki: gauge, a nie counter",
                "pokazać, że licznik napływu wygląda normalnie, gdy konsument stoi, a gauge pokazuje rosnącą zaległość.");

        PaymentQueue queue = new PaymentQueue();
        for (int minute = 1; minute <= 4; minute++) {
            for (int i = 0; i < 2; i++) {
                queue.enqueue("ORD-20" + minute + i);
                CheckoutMetrics.paymentEnqueued();
            }
            // Od trzeciej minuty konsument stoi (np. zablokowane połączenie z systemem księgowym).
            int processed = minute <= 2 ? queue.consume(2) : 0;
            // Pomiar stanu w stałym rytmie: raz na minutę, z harmonogramu, a nie przy każdym enqueue.
            CheckoutMetrics.queueDepth(queue.depth());
            DemoConsole.step("Minuta " + minute + ": przyjęto 2, zaksięgowano " + processed + ", czeka " + queue.depth());
        }
        Sentry.flush(2_000);

        DemoConsole.lookAt("w konsoli payments.queue.enqueued ma co minutę te same 2 zdarzenia, "
                + "a payments.queue.depth rośnie 0, 0, 2, 4. Z napływu nie odczytasz, że konsument stoi.");
        DemoConsole.lookAt("w Sentry UI widget stanu dla payments.queue.depth wybiera jawnie max albo avg w buckecie "
                + "(gauge nie ma agregacji latest). Cztery „minuty” demo mijają w ułamku sekundy, więc w UI trafiają "
                + "do jednego bucketu: jedno uruchomienie daje max(payments.queue.depth) = 4 i "
                + "sum(payments.queue.enqueued) = 8, a nie wykres rosnący minuta po minucie.");
    }

    // Scenariusz 3: kardynalność atrybutów, order.id w metryce.
    //
    // O co chodzi: atrybut metryki to wymiar, po którym widget grupuje serie. Wymiar o zamkniętym słowniku
    // (payment.method, checkout.outcome) daje kilka serii, a identyfikator tyle serii, ile zamówień.
    //
    // Co pokazujemy: trzy odrzucone płatności wysłane dwa razy jako checkout.failed. Wariant A to
    // CheckoutMetrics.failedCarelessly z order.id i komunikatem bramki w atrybutach, wariant B to
    // CheckoutMetrics.failed, która przyjmuje tylko enumy.
    //
    // Problem: w wariancie A każda metryka ma inną wartość order.id i gateway.message, a komunikat zawiera
    // identyfikator i może zawierać dane osobowe. Grupowanie po order.id daje serię na zamówienie, a wykres
    // pokaże tylko tyle serii, ile pozwala limit widgetu.
    //
    // Dobra praktyka: wymiary jako enumy w API CheckoutMetrics, przez które nie da się przekazać
    // identyfikatora ani komunikatu. order.id należy do eventu błędu albo logu, gdzie służy do znalezienia
    // konkretnego przypadku, a nie do stałego grupowania.
    //
    // Na co patrzeć: w konsoli wariant A ma trzy różne wartości order.id i gateway.message na trzy metryki,
    // wariant B dwie wartości payment.method i jedną checkout.outcome=declined. W Sentry UI grupowanie
    // checkout.failed po checkout.outcome odpowiada na pytanie, dlaczego płatności nie przechodzą.
    //
    // Uruchomienie: -Dexec.args=3
    static void boundedAttributes() {
        DemoConsole.scenario(3, "Kardynalność atrybutów: order.id w metryce",
                "porównać atrybuty z typowego przeglądu kodu z wymiarami o zamkniętym słowniku.");

        List<Order> declined = List.of(
                Order.pln("ORD-3001", PaymentMethod.CARD, "declined"),
                Order.pln("ORD-3002", PaymentMethod.CARD, "declined"),
                Order.pln("ORD-3003", PaymentMethod.BLIK, "declined"));

        DemoConsole.step("A. Wersja z typowymi błędami: order.id i komunikat bramki w atrybutach");
        for (Order order : declined) {
            CheckoutMetrics.failedCarelessly(order, "Płatność " + order.id() + " odrzucona: brak środków");
        }
        Sentry.flush(2_000);

        DemoConsole.step("B. Wersja docelowa: tylko payment.method i checkout.outcome");
        for (Order order : declined) {
            CheckoutMetrics.failed(order.paymentMethod(), CheckoutOutcome.DECLINED);
        }
        Sentry.flush(2_000);

        DemoConsole.lookAt("w konsoli wariant A ma trzy różne wartości order.id i gateway.message na trzy metryki, "
                + "wariant B dwie wartości payment.method i jedną checkout.outcome. Przy realnym ruchu A daje tyle "
                + "wartości, ile zamówień.");
        DemoConsole.lookAt("w Sentry UI grupowanie checkout.failed po order.id daje serię na zamówienie; przy "
                + "realnym ruchu wykres pokaże tylko tyle serii, ile pozwala limit widgetu. Grupowanie po "
                + "checkout.outcome odpowiada na pytanie, dlaczego płatności nie przechodzą.");
    }

    // Scenariusz 4: dane osobowe ze scope w metrykach.
    //
    // O co chodzi: metryka dostaje atrybuty nie tylko z wywołania count, gauge czy distribution. SDK
    // dokleja do niej ze scope user.id, user.name i user.email, a options.setBeforeSend widzi tylko eventy.
    //
    // Co pokazujemy: request zalogowanej klientki z timeoutem bramki. Kod spoza modułu płatności (np. filtr
    // uwierzytelniania) ustawia w isolation scope usera z e-mailem i nazwą. Wariant A: działa tylko
    // TelemetryPrivacy.scrubEvent w beforeSend eventów. Wariant B: ten sam request po włączeniu
    // options.getMetrics().setBeforeSend(TelemetryPrivacy::scrubMetric).
    //
    // Problem: enumowe API CheckoutMetrics nie chroni przed atrybutami ze scope. Zespół, który czyści
    // e-mail w eventach, wciąż wysyła go w każdej metryce requestu.
    //
    // Dobra praktyka: osobny callback metryk ustawiony w Sentry.init obok beforeSend eventów (tu, jako
    // skrót szkoleniowy, włączony w trakcie działania). Usuwa user.email i user.name, zostawia techniczny
    // user.id i zawsze zwraca metrykę, bo null oznacza jej odrzucenie.
    //
    // Na co patrzeć: w konsoli event w obu wariantach ma tylko user id=c-7f3a9c. Metryki wariantu A mają
    // też user.email i user.name, metryki wariantu B tylko user.id. W Sentry UI user.email i user.name z
    // wariantu A są zwykłymi atrybutami metryki: widzi je każdy z dostępem do projektu i można po nich
    // grupować.
    //
    // Uruchomienie: -Dexec.args=4
    static void personalDataFromScope() {
        DemoConsole.scenario(4, "Dane osobowe ze scope w metrykach",
                "pokazać, że SDK dokleja usera ze scope do metryk, a beforeSend eventów ich nie filtruje.");

        Order order = Order.pln("ORD-4001", PaymentMethod.CARD, "timeout");

        DemoConsole.step("A. Filtr logowania ustawia usera z e-mailem; działa tylko beforeSend dla eventów");
        requestOfLoggedInCustomer(order);
        Sentry.flush(2_000);

        // Skrót szkoleniowy: callback metryk włączony w trakcie działania, żeby pokazać oba warianty
        // w jednym procesie. SDK czyta go przy każdej metryce, więc działa od następnej. W aplikacji
        // ustawia się go w Sentry.init, obok options.setBeforeSend (tak robi test scenariusza 4).
        Sentry.getCurrentScopes().getOptions().getMetrics().setBeforeSend(TelemetryPrivacy::scrubMetric);

        DemoConsole.step("B. Ten sam request z polityką options.getMetrics().setBeforeSend");
        requestOfLoggedInCustomer(order);
        Sentry.flush(2_000);

        DemoConsole.lookAt("w konsoli event w obu wariantach ma tylko user id=c-7f3a9c, a metryki w wariancie A "
                + "mają też user.email i user.name, choć CheckoutMetrics przyjmuje wyłącznie enumy. W wariancie B "
                + "zostaje user.id.");
        DemoConsole.lookAt("w Sentry UI metryki z wariantu A mają atrybuty user.email i user.name jak każdy inny "
                + "atrybut: widzi je każdy, kto ma dostęp do projektu, i można po nich grupować.");
    }

    private static void requestOfLoggedInCustomer(Order order) {
        try (ISentryLifecycleToken requestScope = Sentry.pushIsolationScope()) {
            // Kod spoza modułu płatności, np. filtr uwierzytelniania, który kiedyś dodał e-mail
            // „dla supportu”. Metryki i eventy tego requestu dziedziczą tego usera.
            User user = new User();
            user.setId("c-7f3a9c");
            user.setUsername("anna.k");
            user.setEmail("anna.kowalska@example.com");
            Sentry.setUser(user);
            Response response = CHECKOUT.handle(order);
            DemoConsole.step("   " + order.id() + " " + order.paymentMethod() + ": HTTP "
                    + response.httpStatus() + " " + response.body());
        }
    }

    // Scenariusz 5: failure rate doby, iloraz sum, nie średnia procentów.
    //
    // O co chodzi: ratio całego zakresu to suma liczników przez sumę mianowników, bo wtedy każda próba ma
    // tę samą wagę. Średnia z ratio bucketów daje każdemu bucketowi tę samą wagę niezależnie od ruchu.
    //
    // Co pokazujemy: sumy z czterech godzinnych bucketów widgetu (dwie godziny szczytu, nocna 03:00 z 6
    // próbami i 04:00 bez prób) liczone przez Ratios: A średnia z ratio godzin, B iloraz sum całej doby, C
    // okno bez prób. Scenariusz nie wysyła niczego do Sentry.
    //
    // Problem: 3 nocne błędy na 6 prób ważą tyle co tysiące prób w szczycie, więc średnia daje 18,0%
    // zamiast 2,1%. Wstawienie 0 za godzinę bez prób zaniżyłoby wynik, a okno bez prób nie ma failure rate
    // 0%.
    //
    // Dobra praktyka: failure rate z dwóch sum w tym samym zakresie, oknie i filtrach (na dashboardzie
    // jedno równanie sum(checkout.failed) / sum(checkout.attempted)), a przy zerowym mianowniku jawny brak
    // wyniku.
    //
    // Na co patrzeć: w konsoli ratio każdej godziny (03:00 to 50,0%, 04:00 brak danych), wariant A 18,0%,
    // wariant B 2,1%, wariant C brak danych. W Sentry UI nie uśredniaj wartości procentowych bucketów, np.
    // wyeksportowanych do arkusza.
    //
    // Uruchomienie: -Dexec.args=5
    static void ratioOfSums() {
        DemoConsole.scenario(5, "Failure rate doby: iloraz sum, nie średnia procentów",
                "pokazać, że średnia z ratio godzin daje nocnej godzinie z 6 próbami wagę godziny szczytu.");

        // Sumy z widgetu: sum(checkout.failed) i sum(checkout.attempted) w bucketach godzinowych.
        List<Bucket> hours = List.of(
                new Bucket("09:00", 24, 1_180),
                new Bucket("10:00", 25, 1_320),
                new Bucket("03:00", 3, 6),
                new Bucket("04:00", 0, 0));
        for (Bucket hour : hours) {
            DemoConsole.step(hour.label() + ": failed " + hour.matching() + " / attempted " + hour.total()
                    + " = " + percent(hour.ratio()));
        }

        DemoConsole.step("A. Średnia z ratio godzin: " + percent(Ratios.averageOfBucketRatios(hours)));
        DemoConsole.step("B. Iloraz sum całej doby: " + percent(Ratios.ratioOfSums(hours)));
        DemoConsole.step("C. Okno bez prób (sama godzina 04:00): "
                + percent(Ratios.ratioOfSums(List.of(hours.getLast()))));

        DemoConsole.lookAt("w konsoli średnia z procentów (ok. 18%) jest prawie dziewięć razy wyższa niż "
                + "iloraz sum (2,1%), bo 3 błędy nocne ważą tyle co tysiące prób w szczycie. Godzina 04:00 "
                + "nie ma wyniku: to brak danych, a nie 0%.");
        DemoConsole.lookAt("w Sentry UI licz failure rate z dwóch sum w tym samym zakresie, oknie i filtrach, "
                + "a nie jako średnią z wartości procentowych bucketów, np. wyeksportowanych do arkusza.");
    }

    // Scenariusz 6: Release Health, sesje trzeba utworzyć samemu.
    //
    // O co chodzi: crash-free sessions liczy się z sesji, a serwerowy Java SDK nie tworzy ich sam
    // (integracja Spring też nie wywołuje startSession). Release bez sesji nie ma crash-free, co nie
    // znaczy, że jest zdrowy.
    //
    // Co pokazujemy: trzy zamówienia (opłacone, timeout bramki, EUR z celowym NullPointerException) przez
    // CheckoutEndpoint dwa razy. Wariant A: domyślnie, bez sesji. Wariant B: SessionTracking.PER_REQUEST,
    // czyli startSession w isolation scope requestu i endSession na jego końcu, z wyjątkiem crashu.
    //
    // Problem: w wariancie A są eventy, ale żadnej sesji, więc crash-free nie ma wartości. endSession po
    // crashu wysłałoby stan crashed drugi raz i Sentry policzyłoby ten sam crash podwójnie (na self-hosted
    // 26.9.0 crash-free 33,3% zamiast 66,7%). Bez ustawionego release SDK w ogóle nie startuje sesji.
    //
    // Dobra praktyka: sesja na request na granicy requestu, wyjątek nieprzewidziany zgłoszony z
    // handled=false (tylko taki zmienia sesję na crashed, obsłużony zwiększa errors) i brak endSession po
    // crashu.
    //
    // Na co patrzeć: w konsoli wariant A ma 3 odpowiedzi i 2 eventy, bez linii „↳ sesja”, crash-free brak
    // danych. W wariancie B każda sesja ma start (init): ORD-6001 kończy się exited z errors=0, ORD-6002
    // exited z errors=1, a ORD-6003 stanem crashed wysłanym raz, po evencie handled=nie; distinctId=(brak),
    // crash-free sessions 66,7%. W Sentry UI (Releases, po około minucie) jedno uruchomienie dodaje 3
    // sesje: healthy, errored i crashed. Crash-free users nie ma wartości, bo sesje nie mają distinctId.
    //
    // Uruchomienie: -Dexec.args=6
    static void releaseHealthNeedsSessions() {
        DemoConsole.scenario(6, "Release Health: sesje trzeba utworzyć samemu",
                "pokazać, że bez startSession/endSession release nie ma sesji, a crash-free liczy się z sesji.");

        List<Order> orders = List.of(
                Order.pln("ORD-6001", PaymentMethod.CARD, "ok"),
                Order.pln("ORD-6002", PaymentMethod.BLIK, "timeout"),
                Order.eur("ORD-6003", PaymentMethod.CARD));

        DemoConsole.step("A. Domyślne zachowanie serwerowego Java SDK: bez sesji");
        List<Response> withoutSessions = respond(orders, CHECKOUT::handle);
        Sentry.flush(2_000);
        DemoConsole.step("   Sesji wysłanych: 0, crash-free sessions: " + percent(Ratios.crashFreeRate(0, 0)));

        DemoConsole.step("B. Sesja na request: startSession i endSession na granicy requestu");
        CheckoutEndpoint withSessions = new CheckoutEndpoint(SessionTracking.PER_REQUEST);
        List<Response> responses = respond(orders, withSessions::handle);
        Sentry.flush(2_000);
        long crashed = responses.stream().filter(Response::unhandledError).count();
        DemoConsole.step("   Sesji: " + responses.size() + ", crashed: " + crashed + ", crash-free sessions: "
                + percent(Ratios.crashFreeRate(responses.size(), crashed)));

        DemoConsole.lookAt("w konsoli wariant A ma " + withoutSessions.size() + " requesty i 2 eventy, ale żadnej "
                + "sesji. W wariancie B każda sesja ma start (init) i koniec: ORD-6001 exited z errors=0, ORD-6002 "
                + "exited z errors=1 (błąd obsłużony), a ORD-6003 kończy się stanem crashed wysłanym raz, razem "
                + "z eventem handled=nie. distinctId jest pusty.");
        DemoConsole.lookAt("w Sentry UI (Releases, po około minucie) jedno uruchomienie dodaje do release 3 sesje: "
                + "healthy, errored i crashed, czyli crash-free sessions 66,7%. Crash-free users nie ma wartości, "
                + "bo sesje nie mają distinctId.");
    }

    private static List<Response> respond(List<Order> orders, Function<Order, Response> endpoint) {
        return orders.stream().map(order -> {
            Response response = endpoint.apply(order);
            DemoConsole.step("   " + order.id() + " " + order.paymentMethod() + " " + order.currency() + ": HTTP "
                    + response.httpStatus() + " " + response.body());
            return response;
        }).toList();
    }

    private static String percent(OptionalDouble ratio) {
        return ratio.isPresent()
                ? String.format(Locale.forLanguageTag("pl"), "%.1f%%", ratio.getAsDouble() * 100)
                : "brak danych";
    }
}
