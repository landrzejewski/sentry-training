package pl.training.sentry.module01;

import io.sentry.ISentryLifecycleToken;
import io.sentry.Sentry;
import pl.training.sentry.module01.LabelExportJob.ErrorReporting;
import pl.training.sentry.module01.Order.Address;
import pl.training.sentry.module01.Order.Customer;
import pl.training.sentry.module01.Order.DeliveryMode;
import pl.training.sentry.support.DemoConsole;
import pl.training.sentry.support.DemoScenarios;
import pl.training.sentry.support.TrainingSentry;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Moduł 1: Sentry w utrzymaniu aplikacji.
//
// Po co jest Sentry i od czego zależy diagnoza
//
// Powiadomienie z Sentry informuje jedynie, że coś wymaga uwagi. To, czy na jego podstawie da
// się ustalić przyczynę i podjąć decyzję (wycofanie wdrożenia, szybka poprawka albo zaplanowana
// naprawa), zależy od danych, które aplikacja wysłała razem z wyjątkiem. Samo dodanie SDK daje
// typ wyjątku, stack trace i liczbę zdarzeń. Bez wersji aplikacji (pole release) nie powiążesz
// błędu z konkretnym artefaktem, bez środowiska (pole environment) nie odróżnisz produkcji od
// stagingu, bez identyfikatora użytkownika nie ocenisz, ilu klientów dotyczy problem, a bez
// zapisu wcześniejszych kroków (breadcrumbs) nie odtworzysz tego, co działo się przed błędem.
// Jakość diagnozy wyznacza więc instrumentacja, czyli to, co kod aplikacji dodaje do zdarzeń.
//
// Model danych: projekt, zdarzenie i issue
//
// Projekt w Sentry odpowiada jednej usłudze, w tym module usłudze checkout-api. Każde
// zgłoszenie błędu to osobne zdarzenie (event). Dla każdego zdarzenia Sentry wylicza odcisk
// (fingerprint), głównie na podstawie stack trace, typu wyjątku i komunikatu, a zdarzenia
// o tym samym odcisku łączy w jedno issue. Issue jest więc grupą podobnych zdarzeń, a nie jedną
// przyczyną: dwa różne problemy z tym samym stack trace trafią do jednego issue, a jeden błąd
// wywoływany z kilku miejsc w kodzie może utworzyć kilka issues, bo metody wywołujące też należą
// do stack trace. Dlatego zanim wyciągniesz wniosek, porównaj kilka zdarzeń z danego issue
// (w Sentry UI co najmniej Recommended, First i Latest), a nie tylko jedno.
//
// Środowisko i wersja to pola zdarzenia
//
// Staging i produkcja tej samej usługi to ten sam projekt; rozróżnia je pole environment.
// Środowisko nie rozdziela grupowania, więc jedno issue może zbierać zdarzenia z kilku środowisk
// i wersji. Pole release wskazuje niezmienny artefakt, a jego nazwa zawiera nazwę usługi
// (np. checkout-api@3.42.0), bo wersje są rozpoznawane w całej organizacji. Oba pola ustawia
// się raz, w konfiguracji SDK przy starcie aplikacji, a nie w miejscu zgłaszania błędu. Java SDK
// bez tej konfiguracji nie ustawi wersji wcale, a jako środowisko przyjmie production, więc
// zdarzenia z laptopa wyglądałyby jak produkcyjne.
//
// Kontekst zdarzenia: tag, context, user i breadcrumbs
//
// Każda informacja o żądaniu ma w Sentry swoje miejsce i dobór miejsca decyduje, czy pomaga
// w analizie:
// - tag to para klucz i wartość, po której Sentry wyszukuje i porównuje zdarzenia. Nadaje się
//   do wymiaru o kilku stałych wartościach, np. tryb dostawy albo wariant koszyka. Identyfikator
//   zamówienia, znacznik czasu czy adres e-mail tworzą tyle wartości, ile zdarzeń, więc rozkład
//   takiego tagu niczego nie pokazuje;
// - context przechowuje stan jednej operacji, np. identyfikator zamówienia i to, czy zamówienie
//   ma adres dostawy. Widać go w szczegółach zdarzenia, ale nie służy do wyszukiwania;
// - user to zatwierdzony identyfikator techniczny klienta. Na jego podstawie Sentry liczy, ilu
//   użytkowników dotyczy issue. Adres e-mail nie jest do tego potrzebny, a jest daną osobową;
// - breadcrumbs to ograniczony bufor kroków poprzedzających błąd, np. rozpoczęcie generowania
//   etykiety. To nie jest pełny log: wpisy mogą wypaść z bufora i nie powinny zawierać całych
//   obiektów, sekretów ani danych osobowych.
// Dane osobowe najlepiej ograniczać już w aplikacji, zanim zadziała czyszczenie po stronie Sentry.
//
// Gdzie SDK przechowuje kontekst: scope
//
// Kontekst żyje w scope. W Java SDK 8.x statyczne Sentry.setTag, Sentry.setUser
// i Sentry.addBreadcrumb zapisują dane w isolation scope bieżącego wątku, a każde zdarzenie
// wysłane z tego wątku dostaje je automatycznie. Serwer obsługuje żądania na puli wątków, które
// żyją dłużej niż pojedyncze żądanie, więc każde żądanie potrzebuje własnego isolation scope.
// W aplikacji webowej zapewnia go integracja z frameworkiem, a w czystej Javie kod aplikacji
// (Sentry.pushIsolationScope). Bez tego dane jednego klienta trafiają do zdarzeń następnego.
// Sentry.withScope tego problemu nie rozwiązuje, bo tworzy tylko nowy current scope.
//
// Błąd obsłużony i nieobsłużony
//
// Jeśli kod łapie wyjątek i dalej działa (np. zwraca klientowi odpowiedź zastępczą), wyjątek
// nie dotrze do żadnego automatycznego mechanizmu i zgłosić go musi sam kod przez
// Sentry.captureException. Sentry traktuje takie zdarzenie jako obsłużone. Wyjątek, który
// opuszcza wątek, zgłasza integracja SDK: w czystej Javie UncaughtExceptionHandlerIntegration,
// w aplikacji Spring Boot integracja frameworka. Takie zdarzenie ma handled=false, poziom fatal
// i mechanism wskazujący, kto je zgłosił, a w Sentry UI issue dostaje oznaczenie Unhandled.
//
// Jeden właściciel zgłoszenia
//
// Ten sam błąd nie powinien być zgłaszany przez kilka warstw. SDK ma deduplikację: odrzuca
// zdarzenie, jeśli ten sam obiekt wyjątku albo któraś z jego przyczyn (cause) została już
// wysłana. Nie pomoże jednak, gdy kod złapie wyjątek, zgłosi go i rzuci nowy wyjątek bez
// przyczyny. Powstaje wtedy drugie zdarzenie, a łańcuch przyczyn ginie. Zasada jest prosta:
// zgłasza tylko kod, który błąd obsługuje, a wyjątek opakowany w nowy zachowuje cause.
//
// Jak czytać liczby i stany w Sentry
//
// Liczba zdarzeń to nie liczba użytkowników: jeden klient z ponowieniami może wygenerować wiele
// zdarzeń. Wartość 0 users oznacza, że zdarzenia nie mają identyfikatora użytkownika, a nie że
// błąd nikogo nie dotknął. First seen to pierwsza zachowana obserwacja, a nie chwila powstania
// błędu, a status Resolved nie dowodzi, że poprawka działa; potwierdza to dopiero nowa wersja
// przy rzeczywistym ruchu. W analizie oddzielaj fakty widoczne w zdarzeniu od hipotez, które
// trzeba jeszcze sprawdzić, a stack trace traktuj jako miejsce, w którym błąd się ujawnił:
// przyczyna mogła powstać wcześniej, np. przy wyborze ścieżki dla danego trybu dostawy.
public final class Module01Demo {

    private static final Customer ANNA = new Customer("c-7f3a9c", "anna.kowalska@example.com");
    private static final Customer PIOTR = new Customer("c-19bd42", "piotr.nowak@example.com");

    private static final CheckoutEndpoint CHECKOUT = new CheckoutEndpoint();
    private static final LabelExportJob EXPORT_JOB = new LabelExportJob();

    private Module01Demo() {
    }

    public static void main(String[] args) throws Exception {
        DemoScenarios scenarios = DemoScenarios.from(args, 1, 6);
        try (TrainingSentry.TrainingSession session = TrainingSentry.init("module01", options -> {
            // Wartości domyślne SDK, ustawione jawnie, bo scenariusze 4 i 5 od nich zależą.
            // Eksperyment: false w pierwszej linii usuwa z Sentry wszystkie wyjątki, które opuściły
            // wątek joba (pod Maven build kończy się wtedy błędem), a false w drugiej daje
            // w wariancie 5A dwa eventy tego samego wyjątku: obsłużony i nieobsłużony.
            options.setEnableUncaughtExceptionHandler(true);
            options.setEnableDeduplication(true);
        })) {
            if (scenarios.includes(1)) sameEventForDifferentCauses();
            if (scenarios.includes(2)) contextSeparatesCauses();
            if (scenarios.includes(3)) eachDatumInItsMechanism();
            if (scenarios.includes(4)) handledVersusUnhandled();
            if (scenarios.includes(5)) duplicateReporting();
            if (scenarios.includes(6)) contextLeakBetweenRequests();
        }
    }

    // Scenariusz 1: dług diagnostyczny, dwa różne przypadki, identyczne eventy.
    //
    // O co chodzi: aplikacja łapie wyjątek i wysyła go do Sentry, ale bez kontekstu domenowego.
    // Tak wygląda typowa pierwsza integracja: SDK i captureException są, danych do diagnozy nie ma.
    //
    // Co pokazujemy: poprawne zamówienie do punktu odbioru (brak adresu jest tu zgodny z modelem)
    // i niekompletne zamówienie kurierskie (adresu zabrakło) kończą się tym samym NullPointerException.
    // CheckoutEndpoint.generateLabel wysyła sam wyjątek.
    //
    // Problem: eventy różnią się tylko identyfikatorem. Nie wiadomo, czy przyczyna jest jedna, kogo
    // dotyczy błąd ani co go poprzedziło; licznik 0 users znaczy „nie wiadomo kto”, a nie „nikt”.
    //
    // Dobra praktyka: release i environment ustawione w konfiguracji SDK (tu robi to TrainingSentry),
    // a kontekst domenowy (tagi, context, user, breadcrumbs) dodany w kodzie, jak w scenariuszu 2.
    //
    // Na co patrzeć: w konsoli dwa wydruki „↳ event” różne tylko identyfikatorem: są release
    // i environment, nie ma tagów domenowych, contexts, user ani breadcrumbs. W Sentry UI jedno issue
    // z 2 eventami i licznikiem 0 users.
    //
    // Uruchomienie: -Dexec.args=1
    static void sameEventForDifferentCauses() {
        DemoConsole.scenario(1, "Dług diagnostyczny: dwa różne przypadki, identyczne eventy",
                "pokazać, na które pytania nie odpowiesz, gdy aplikacja wysyła sam wyjątek.");

        // Poprawne zamówienie do punktu odbioru: brak adresu jest zgodny z modelem.
        Order pickup = Order.pickupPoint("ORD-1001", ANNA, "WAW-114");
        // Niekompletne zamówienie kurierskie: adresu zabrakło, choć COURIER go wymaga.
        Order brokenCourier = new Order("ORD-1002", PIOTR, DeliveryMode.COURIER, null, null);

        // Release i environment ustawia konfiguracja SDK (TrainingSentry), więc są w obu eventach.
        // Bez nich wydruk pokazałby release BRAK i environment=production: SDK przyjmuje tę
        // wartość domyślnie, więc eventy ze stagingu i z laptopa wyglądałyby jak produkcyjne.
        // Jedna pętla daje obu wyjątkom identyczne ramki stosu, jak w produkcji, gdzie oba
        // zamówienia przechodzą przez ten sam kod.
        for (Order order : List.of(pickup, brokenCourier)) {
            DemoConsole.step(order.id() + " " + order.deliveryMode()
                    + ": kod łapie wyjątek i wysyła go bez kontekstu domenowego");
            CHECKOUT.generateLabel(order);
        }

        DemoConsole.lookAt("w konsoli eventy różnią się tylko identyfikatorem. Są release i environment, "
                + "nie ma trybu dostawy, stanu zamówienia, użytkownika ani breadcrumbs. Nie ustalisz, "
                + "czy przyczyna jest jedna, ilu klientów dotyczy błąd ani co go poprzedziło.");
        DemoConsole.lookAt("w Sentry UI jedno issue z 2 eventami i licznikiem 0 users, "
                + "który oznacza brak identyfikatora użytkownika, a nie brak wpływu.");
    }

    // Scenariusz 2: jeden stack trace, dwie przyczyny.
    //
    // O co chodzi: grupowanie Sentry łączy eventy po wyjątku i stack trace, a nie po przyczynie
    // biznesowej. Jedno issue może więc ukrywać kilka różnych problemów.
    //
    // Co pokazujemy: te same dwa zamówienia co w scenariuszu 1, ale obsłużone przez
    // CheckoutEndpoint.handle: własny isolation scope i CheckoutTelemetry.describeRequest, czyli tagi
    // delivery.mode i checkout.variant, context shipping, user z identyfikatorem i breadcrumb.
    //
    // Problem: bez tagu i contextu oba eventy wyglądają jak ten sam błąd. W rzeczywistości jedno
    // to poprawne zamówienie na złej ścieżce kodu, drugie to niekompletne dane wejściowe; jedna
    // poprawka nie naprawi obu.
    //
    // Dobra praktyka: tag dla wymiaru o kilku stałych wartościach (delivery.mode), po którym filtruje
    // się issues, i context dla stanu konkretnej operacji (order_id, has_delivery_address).
    //
    // Na co patrzeć: w konsoli ten sam wyjątek, ale różne delivery.mode i contexts: PICKUP_POINT
    // z has_pickup_point=true oraz COURIER z has_delivery_address=false. W Sentry UI oba eventy
    // w jednym issue, rozkład tagu delivery.mode z dwoma trybami i różny stan w Contexts przy
    // porównaniu eventów.
    //
    // Uruchomienie: -Dexec.args=2
    static void contextSeparatesCauses() {
        DemoConsole.scenario(2, "Jeden stack trace, dwie przyczyny",
                "pokazać, że tag i custom context rozdzielają przyczyny, których nie rozdzieli grupowanie.");

        Order pickup = Order.pickupPoint("ORD-2001", ANNA, "WAW-114");
        Order brokenCourier = new Order("ORD-2002", PIOTR, DeliveryMode.COURIER, null, null);

        for (Order order : List.of(pickup, brokenCourier)) {
            DemoConsole.step(order.id() + " " + order.deliveryMode()
                    + ": request z tagami, contextem, userem i breadcrumb");
            CHECKOUT.handle(order, "B");
        }

        DemoConsole.lookAt("w konsoli ten sam wyjątek z tym samym komunikatem (ramki stosu pokazuje -Dsentry.demo.json=true), "
                + "ale różne delivery.mode i contexts. "
                + "PICKUP_POINT z has_pickup_point=true to poprawne zamówienie na złej ścieżce kodu, "
                + "COURIER z has_delivery_address=false to niekompletne dane wejściowe. "
                + "Jedna poprawka nie naprawi obu.");
        DemoConsole.lookAt("w Sentry UI oba eventy w jednym issue. Rozkład tagu delivery.mode pokazuje "
                + "oba tryby, a porównanie eventów (Recommended, First, Latest) różny stan w Contexts.");
    }

    // Scenariusz 3: każda informacja we właściwym mechanizmie.
    //
    // O co chodzi: tag, context, user i breadcrumb mają różne role. Wybór mechanizmu decyduje, czy
    // dana pomaga w analizie, czy tylko zaśmieca Sentry albo wynosi dane osobowe.
    //
    // Co pokazujemy: to samo zamówienie opisane dwa razy. Wersja z typowymi błędami z przeglądu kodu
    // (CheckoutTelemetry.describeRequestCarelessly): order.id jako tag, e-mail jako user, cały obiekt
    // zamówienia w breadcrumb. Obok wersja docelowa z CheckoutEndpoint.handle.
    //
    // Problem: tag order.id ma tyle wartości, ile zamówień, więc jego rozkład w issue nic nie mówi,
    // a stan zamówienia znika. E-mail trafia do Sentry dwa razy: w user i w toString() rekordu
    // zapisanym w breadcrumb.
    //
    // Dobra praktyka: identyfikator zamówienia w contexts, user z identyfikatorem technicznym zamiast
    // e-maila, breadcrumb z samymi danymi potrzebnymi do odtworzenia przebiegu, bez payloadu.
    //
    // Na co patrzeć: w konsoli pierwszy event ma e-mail klienta w user i w breadcrumbs oraz tag
    // order.id; drugi ma tagi o kilku wartościach, order_id i stan zamówienia w contexts oraz user
    // z identyfikatorem technicznym. W Sentry UI e-mail z pierwszego eventu zostaje zapisany
    // w sekcjach User i Breadcrumbs.
    //
    // Uruchomienie: -Dexec.args=3
    static void eachDatumInItsMechanism() {
        DemoConsole.scenario(3, "Każda informacja we właściwym mechanizmie",
                "porównać typowe błędy z przeglądu kodu z wersją docelową: kardynalność tagów i dane osobowe.");

        Order order = Order.pickupPoint("ORD-3001", ANNA, "WAW-114");

        DemoConsole.step("Wersja z typowymi błędami: order.id w tagu, e-mail w user, cały obiekt w breadcrumb");
        // Własny isolation scope także tutaj. Wątek main trzyma scopes utworzone przez Sentry.init,
        // a każdy nowy wątek startuje z ich kopią: bez tego e-mail Anny trafiłby też do eventów
        // joba w scenariuszach 4 i 5.
        try (ISentryLifecycleToken requestScope = Sentry.pushIsolationScope()) {
            CheckoutTelemetry.describeRequestCarelessly(order, "B");
            CHECKOUT.generateLabel(order);
        }

        DemoConsole.step("Wersja docelowa: order_id w contexts, identyfikator techniczny w user, breadcrumb bez payloadu");
        CHECKOUT.handle(order, "B");

        DemoConsole.lookAt("w konsoli pierwszy event ma e-mail klienta dwa razy (user i breadcrumb) "
                + "i tag order.id zamiast stanu zamówienia. Drugi ma tagi o kilku możliwych wartościach, "
                + "order_id i stan zamówienia w contexts oraz user z identyfikatorem technicznym.");
        DemoConsole.lookAt("w Sentry UI e-mail z pierwszego eventu zostaje zapisany w sekcjach User "
                + "i Breadcrumbs. Tag order.id przy realnym ruchu ma tyle wartości, ile zamówień, "
                + "więc jego rozkład w issue nic nie mówi.");
    }

    // Scenariusz 4: błąd obsłużony i nieobsłużony.
    //
    // O co chodzi: błąd, który aplikacja obsłużyła, zgłasza do Sentry kod aplikacji. Błąd, który
    // wyszedł poza kod i zakończył wątek, zgłasza integracja SDK. Sentry odróżnia je po polach
    // handled, mechanism i level.
    //
    // Co pokazujemy: ten sam błąd etykiety dwa razy. Endpoint łapie wyjątek, zwraca klientowi
    // MANUAL_HANDLING i sam woła captureException. Nocny job w wątku label-export niczego nie łapie,
    // więc wyjątek kończy wątek i raportuje go UncaughtExceptionHandlerIntegration.
    //
    // Problem: bez ręcznego capture obsłużony błąd jest niewidoczny, bo nie dociera do żadnego
    // automatycznego mechanizmu. Z kolei ręczny capture w miejscu, które błędu nie obsługuje,
    // ukrywa, że wyjątek zakończył wątek.
    //
    // Dobra praktyka: captureException tylko tam, gdzie kod błąd obsługuje i idzie dalej;
    // nieobsłużone wyjątki zostawić integracji SDK. Job ma tag job.name i nie ustawia usera.
    //
    // Na co patrzeć: w konsoli ręczny capture ma handled=tak i level=error, a wyjątek z wątku joba
    // handled=nie, mechanism=UncaughtExceptionHandler i level=fatal. W Sentry UI przy wyjątku
    // widać mechanism i handled, a issue z wyjątkiem nieobsłużonym ma oznaczenie Unhandled.
    //
    // Uruchomienie: -Dexec.args=4
    static void handledVersusUnhandled() throws InterruptedException {
        DemoConsole.scenario(4, "Błąd obsłużony i nieobsłużony",
                "pokazać, kto raportuje każdy z nich i jak Sentry je odróżnia.");

        Order order = Order.pickupPoint("ORD-4001", ANNA, "WAW-114");

        DemoConsole.step("Request: endpoint łapie wyjątek, zwraca odpowiedź i sam wywołuje captureException");
        String response = CHECKOUT.handle(order, "B");
        DemoConsole.step("Odpowiedź dla klienta: " + response + ", zamówienie czeka na ręczną obsługę");

        DemoConsole.step("Nocny job: ten sam błąd opuszcza wątek label-export, "
                + "raportuje go UncaughtExceptionHandlerIntegration");
        EXPORT_JOB.runInBackground(order, ErrorReporting.NONE);

        DemoConsole.lookAt("w konsoli ręczny capture ma handled=tak i level=error, a wyjątek, który "
                + "zakończył wątek, ma handled=nie, mechanism=UncaughtExceptionHandler i level=fatal. "
                + "Job ma tag job.name i pusty user, bo job nie jest użytkownikiem.");
        DemoConsole.lookAt("w Sentry UI przy wyjątku widać mechanism i handled, "
                + "a issue z wyjątkiem nieobsłużonym ma oznaczenie Unhandled.");
    }

    // Scenariusz 5: podwójne raportowanie tego samego błędu.
    //
    // O co chodzi: gdy ten sam błąd raportuje kilka warstw (catch w kodzie i handler SDK), Sentry
    // dostaje duplikaty albo mylące dane. Deduplikacja SDK chroni tylko przed wysłaniem drugi raz
    // tego samego obiektu wyjątku.
    //
    // Co pokazujemy: trzy warianty joba (LabelExportJob.ErrorReporting). A: capture i rzucenie dalej
    // tego samego obiektu. B: capture i nowy wyjątek bez cause. C: tylko opakowanie z cause,
    // raportuje wyłącznie handler SDK.
    //
    // Problem: w A zostaje jeden event, ale z handled=tak, choć wyjątek zakończył wątek. W B powstają
    // dwa eventy, drugi bez przyczyny i z ramkami wskazującymi blok catch, czyli drugie issue dla
    // tego samego defektu.
    //
    // Dobra praktyka: jeden właściciel raportowania. Kod, który nie obsługuje błędu, nie woła
    // captureException, a przy opakowaniu zachowuje cause (wariant C).
    //
    // Na co patrzeć: w konsoli A daje 1 event z handled=tak, B 2 eventy (drugi to IllegalStateException
    // bez caused by), C 1 event z łańcuchem caused by. W Sentry UI wariant B tworzy drugie issue,
    // a event z wariantu C pokazuje cały łańcuch wyjątków.
    //
    // Uruchomienie: -Dexec.args=5
    static void duplicateReporting() throws InterruptedException {
        DemoConsole.scenario(5, "Podwójne raportowanie tego samego błędu",
                "pokazać, kiedy deduplikacja SDK chroni przed duplikatem, a kiedy nie.");

        Order order = Order.pickupPoint("ORD-5001", ANNA, "WAW-114");

        DemoConsole.step("A. Job raportuje wyjątek i rzuca dalej ten sam obiekt. Oczekiwany 1 event:");
        EXPORT_JOB.runInBackground(order, ErrorReporting.CAPTURE_AND_RETHROW);
        DemoConsole.step("   Drugiego eventu nie ma: handler SDK dostał ten sam obiekt, a deduplikacja go odrzuciła.");

        DemoConsole.step("B. Job raportuje wyjątek i rzuca nowy, bez cause. Oczekiwane 2 eventy:");
        EXPORT_JOB.runInBackground(order, ErrorReporting.CAPTURE_AND_THROW_NEW);

        DemoConsole.step("C. Jeden właściciel: job tylko opakowuje wyjątek z cause. Oczekiwany 1 event:");
        EXPORT_JOB.runInBackground(order, ErrorReporting.WRAP_WITH_CAUSE);

        DemoConsole.lookAt("w konsoli wariant A zostawia ręczny event z handled=tak, choć wyjątek zakończył "
                + "wątek. W wariancie B drugi event to IllegalStateException bez przyczyny, a jego ramki stosu "
                + "(-Dsentry.demo.json=true) wskazują blok catch. Wariant C daje jeden event z łańcuchem caused by.");
        DemoConsole.lookAt("w Sentry UI wariant B tworzy drugie issue dla tego samego defektu, "
                + "a event z wariantu C pokazuje cały łańcuch wyjątków.");
    }

    // Scenariusz 6: wyciek kontekstu między requestami na puli wątków.
    //
    // O co chodzi: statyczne Sentry.setTag, setUser i addBreadcrumb piszą w SDK 8.x do isolation
    // scope bieżącego wątku. Wątek puli serwera żyje dłużej niż request, więc bez własnego isolation
    // scope dane jednego requestu zostają dla następnego.
    //
    // Co pokazujemy: dwa requesty na jednej puli z jednym wątkiem: Anna z zamówieniem kurierskim,
    // potem gość z zamówieniem do punktu odbioru, które kończy się błędem. Wariant A bez własnego
    // isolation scope, wariant B przez CheckoutEndpoint.handle z pushIsolationScope.
    //
    // Problem: w A event gościa ma usera Anny i breadcrumb jej zamówienia. Tagi i contexts wyglądają
    // poprawnie, bo gość nadpisał te klucze; wycieka to, czego bieżący request nie ustawił.
    // Na produkcji pula ma wiele wątków, więc wyciek pojawia się losowo.
    //
    // Dobra praktyka: własny isolation scope na request (w aplikacji webowej robi to integracja
    // frameworka), a przy przekazywaniu pracy do własnej puli SentryWrapper.wrapRunnable
    // albo wrapCallable. Sentry.withScope requestu nie izoluje.
    //
    // Na co patrzeć: w konsoli w A event gościa ma user id=c-7f3a9c (Anna) i breadcrumb z ORD-6001,
    // w B user jest pusty, a breadcrumbs dotyczą tylko ORD-6002. W Sentry UI wyciek przypisuje błąd
    // gościa Annie w liczniku users i pokazuje w Breadcrumbs przebieg cudzego requestu.
    //
    // Uruchomienie: -Dexec.args=6
    static void contextLeakBetweenRequests() throws Exception {
        DemoConsole.scenario(6, "Wyciek kontekstu między requestami na puli wątków",
                "pokazać, że bez własnego isolation scope request dziedziczy dane poprzedniego.");

        Order annaCourier = Order.courier("ORD-6001", ANNA, new Address("Prosta 1", "00 838"));
        Order guestPickup = Order.pickupPoint("ORD-6002", null, "KRK-031");

        // Serwer obsługuje requesty na puli wątków. Pula z jednym wątkiem gwarantuje, że drugi
        // request trafi na ten sam wątek co pierwszy.
        // PRODUKCJA: pula ma wiele wątków, więc wyciek pojawia się losowo i trudno go powtórzyć.
        DemoConsole.step("A. Dwa requesty bez własnego isolation scope na jednym wątku puli");
        try (ExecutorService workers = Executors.newSingleThreadExecutor()) {
            String label = workers.submit(() -> {
                CheckoutTelemetry.describeRequest(annaCourier, "A");
                return CHECKOUT.generateLabel(annaCourier);
            }).get();
            DemoConsole.step("   ORD-6001 (Anna, COURIER): etykieta " + label + ", bez eventu");
            DemoConsole.step("   ORD-6002 (gość, PICKUP_POINT): błąd");
            workers.submit(() -> {
                // PUŁAPKA: statyczne settery piszą do isolation scope wątku puli, który żyje
                // dłużej niż request. Gość nie ustawia usera, więc dostaje usera Anny.
                CheckoutTelemetry.describeRequest(guestPickup, "B");
                return CHECKOUT.generateLabel(guestPickup);
            }).get();
        }

        // Świeża pula, bo nowy isolation scope powstaje jako kopia bieżącego: na wątku
        // z wariantu A odziedziczyłby dane Anny.
        // PUŁAPKA: Sentry.withScope nie izoluje requestu. Tworzy tylko nowy current scope,
        // a statyczne settery wywołane w callbacku nadal piszą do wspólnego isolation scope.
        // Przy przekazywaniu pracy z requestu do własnej puli SentryWrapper.wrapRunnable
        // i wrapCallable kopiują kontekst zlecającego i też izolują zadanie.
        DemoConsole.step("B. Te same requesty, każdy we własnym isolation scope (CheckoutEndpoint.handle)");
        try (ExecutorService workers = Executors.newSingleThreadExecutor()) {
            workers.submit(() -> CHECKOUT.handle(annaCourier, "A")).get();
            workers.submit(() -> CHECKOUT.handle(guestPickup, "B")).get();
        }

        DemoConsole.lookAt("w konsoli w wariancie A event gościa ma user id=c-7f3a9c (Anna) i breadcrumb "
                + "z ORD-6001. Tagi i contexts wyglądają poprawnie, bo gość nadpisał te klucze: wycieka to, "
                + "czego bieżący request nie ustawił. W wariancie B user jest pusty, a breadcrumbs "
                + "dotyczą tylko ORD-6002.");
        DemoConsole.lookAt("w Sentry UI wyciek przypisuje błąd gościa Annie w liczniku users "
                + "i podsuwa w sekcji Breadcrumbs przebieg cudzego requestu.");
    }
}
