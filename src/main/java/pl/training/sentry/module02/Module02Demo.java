package pl.training.sentry.module02;

import io.sentry.Sentry;
import io.sentry.SentryOptions;
import io.sentry.protocol.SentryId;
import pl.training.sentry.module02.CheckoutEndpoint.CheckoutResponse;
import pl.training.sentry.module02.spring.CheckoutApplication;
import pl.training.sentry.module02.spring.CheckoutWebClient;
import pl.training.sentry.module02.spring.SentryPrivacyConfiguration;
import pl.training.sentry.support.DemoConsole;
import pl.training.sentry.support.DemoScenarios;
import pl.training.sentry.support.TrainingSentry;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

// Moduł 2: Projekty, środowiska i konfiguracja SDK.
//
// Projekt, DSN i droga zdarzenia
//
// Projekt w Sentry odpowiada jednej samodzielnie wdrażanej usłudze, w tym module usłudze checkout-api, która
// przyjmuje płatności przez zewnętrzną bramkę. Staging i produkcja tej samej usługi nie są osobnymi
// projektami, bo rozbiłyby te same błędy na dwa zestawy issues (grup podobnych zdarzeń) i wymagałyby
// podwójnej konfiguracji filtrów i alertów. Etap wdrożenia opisuje pole environment zdarzenia.
//
// O tym, do którego projektu trafi zdarzenie, decyduje wyłącznie DSN, czyli adres z publicznym kluczem
// i identyfikatorem projektu. Pola environment i release oraz tagi (pary klucz i wartość, po których Sentry
// wyszukuje i porównuje zdarzenia) opisują zdarzenie, ale go nie przekierowują.
//
// Zanim zdarzenie błędu (error event) opuści proces, SDK przeprowadza je przez kilka etapów w stałej
// kolejności: filtry typu wyjątku i tekstu błędu, dołączenie danych ze scope (miejsca, w którym SDK
// przechowuje kontekst zdarzeń), procesory zdarzeń, callback beforeSend i na końcu losowanie (sampleRate).
// Każdy etap może zdarzenie odrzucić i wtedy captureException zwraca pusty identyfikator. Po stronie Sentry
// działają jeszcze filtry projektu (inbound filters) i czyszczenie danych, zanim zdarzenie trafi do issue.
//
// Środowisko i wersja ustawiane jawnie
//
// Pole environment mówi, na jakim etapie wdrożenia działał kod, a pole release, jaki dokładnie artefakt się
// wykonał. Java SDK bez jawnie ustawionego środowiska przyjmuje wartość production, a wersji nie ustawia
// wcale. Konfiguracja przepisana z instrukcji szybkiego startu (quickstart), która zawiera tylko DSN, oznacza
// więc zdarzenia z laptopa i ze stagingu jako produkcyjne, przez co trafiają one do widoków i alertów
// filtrowanych po environment:production.
//
// Środowisko powstaje w Sentry przy pierwszym zdarzeniu z nową wartością i potem nie da się go usunąć, można
// je tylko ukryć. Wielkość liter ma znaczenie, więc production, Production i prod to trzy różne środowiska.
// Dlatego wartości pochodzą z małego, zamkniętego słownika (tu enum DeploymentStage), a nie z tekstu
// wpisanego w konfiguracji wdrożenia. Sentry rozpoznaje nazwy wersji w całej organizacji, więc samo 4.12.0
// jest niejednoznaczne, gdy organizacja ma wiele usług. Poprawny identyfikator zawiera nazwę usługi i commit,
// np. checkout-api@4.12.0+8f24c7a, i ma tę samą wartość w SDK i w pipeline wdrożenia.
//
// Konfiguracja jako kontrakt sprawdzany przy starcie
//
// SDK samo nie zgłasza błędów konfiguracji. Pusty DSN nie jest dla niego błędem, tylko sygnałem, że ma się
// wyłączyć, więc środowisko po cichu przestaje raportować. Nieznana nazwa etapu tworzy nowe, trwałe
// środowisko. Dlatego konfigurację wdrożenia (zmienne APP_STAGE, SENTRY_DSN, SENTRY_RELEASE
// i SENTRY_SAMPLE_RATE) najpierw się sprawdza. Etap musi pochodzić ze słownika, poza laptopem wymagane są
// niepusty DSN i wersja w postaci checkout-api@wersja, a odsetek próbkowania musi mieścić się między 0.0
// a 1.0. Wszystkie naruszenia są zgłaszane naraz i przerywają start aplikacji, zanim SDK cokolwiek wyśle.
//
// Sprawdzony kontrakt przepisuje do SDK jedna klasa, SentryOptionsConfigurer. Wypełnia ona obiekt opcji
// SentryOptions, ale nie wywołuje Sentry.init, więc da się ją przetestować bez globalnego stanu i bez
// wysyłania czegokolwiek. Ustawia też tag service.name, stały dla całego procesu, i oznacza pakiet aplikacji
// jako in-app, czyli kod, który Sentry wyróżnia w stack trace. Klienta SDK inicjalizuje dokładnie raz
// mechanizm właściwy dla integracji: w czystej Javie Sentry.init, w Spring Boot starter. Test jednostkowy nie
// potwierdzi jednak, że zdarzenia docierają do właściwego projektu. Potwierdza to dopiero kontrolne zdarzenie
// wysłane po wdrożeniu na staging, oznaczone własnym tagiem (tu deployment.check).
//
// Profil lokalny i starter Spring Boot
//
// Proces na laptopie developera nie powinien niczego wysyłać, nawet gdy w pliku .env został DSN skopiowany
// z produkcji. Etap LOCAL w kontrakcie wyłącza więc SDK (enabled=false i brak DSN), a DSN znaleziony
// w konfiguracji daje tylko ostrzeżenie. W aplikacji Spring Boot inicjalizacją SDK zajmuje się starter,
// a jego auto-konfiguracja uruchamia się tylko wtedy, gdy istnieje właściwość sentry.dsn. Profil lokalny jej
// nie definiuje, ale to za mało: Spring Boot traktuje zmienną środowiskową SENTRY_DSN, odziedziczoną z shella
// albo wczytaną z pliku .env, jako właściwość sentry.dsn i starter mimo wszystko startuje. Drugą linią obrony
// jest sentry.enabled=false w profilu lokalnym. Ta właściwość wyłącza wysyłanie, ale nie usuwa całego narzutu
// instrumentacji.
//
// Próbkowanie błędów
//
// Opcja sampleRate (od 0.0 do 1.0) określa, jaka część zdarzeń błędów przejdzie losowanie, a decyzja zapada
// osobno dla każdego zdarzenia. Domyślna wartość null wyłącza losowanie. Losowanie nie wie, który błąd jest
// ważny: może odrzucić jedyne zdarzenie rzadkiego, krytycznego błędu, a wtedy jego issue w ogóle nie
// powstanie. Liczba zdarzeń w issue przestaje też odpowiadać liczbie nieudanych operacji. Bez ekstremalnego
// wolumenu bezpieczniej nie próbkować błędów wcale, a znany szum usuwać precyzyjną regułą. W Java SDK
// losowanie następuje dopiero po beforeSend, więc niski odsetek nie zmniejsza kosztu callbacku: wykonuje się
// on także dla zdarzeń, które losowanie za chwilę odrzuci.
//
// Filtry po typie wyjątku i po tekście błędu
//
// Regułę filtrowania umieszcza się tam, gdzie jest wiarygodna informacja potrzebna do decyzji. Przykład
// z modułu: SocketException: Connection reset pochodzi raz od klienta, który zamknął aplikację przed
// odebraniem odpowiedzi (szum), a raz od bramki płatności (awaria, klienci nie mogą zapłacić). Po typie
// i komunikacie tych przypadków nie da się odróżnić. Różnicę zna tylko warstwa zapisu odpowiedzi, więc to ona
// zamienia błąd na dedykowany typ ClientAbortedException. SDK ma dwa filtry, które działają jeszcze przed
// beforeSend:
// - ignoredExceptionsForType porównuje dokładną klasę zgłoszonego wyjątku, bez podklas i bez łańcucha
//   przyczyn (cause). Z dedykowanym typem usuwa tylko szum i przepuszcza identycznie brzmiący błąd bramki;
// - ignoredErrors porównuje wzorce z komunikatem zdarzenia i z wynikiem Throwable.toString(), czyli nazwą
//   klasy razem z komunikatem. Wzorzec jest wyrażeniem regularnym, które musi pasować do całego tekstu.
//   "Connection reset" nie pasuje więc do "java.net.SocketException: Connection reset" i niczego nie
//   filtruje, a ".*Connection reset.*" usuwa szum razem z awarią bramki.
// Każda reguła tekstowa potrzebuje testu na zdarzeniu, które ma zostać odrzucone, i na podobnym, które musi
// zostać.
//
// Callback beforeSend
//
// beforeSend to funkcja wywoływana dla każdego zdarzenia błędu tuż przed wysłaniem. Zwrócone zdarzenie, także
// zmienione, idzie dalej, a null je odrzuca. Callback działa na wątku, który zgłasza błąd, więc ma być szybki
// i lokalny, bez zapytań do sieci i bazy danych.
//
// FilteringBeforeSend najpierw klasyfikuje zdarzenie, a dopiero potem je czyści, bo reguły klasyfikacji mogą
// potrzebować danych, które czyszczenie usuwa. O pochodzeniu ruchu nie decyduje sam callback. Tag
// traffic.origin (external, internal albo synthetic) ustawia w scope requestu zaufana warstwa aplikacji
// (TrafficClassifier), która sprawdziła token pracownika albo sekret sondy syntetycznej, czyli automatu
// okresowo wywołującego usługę. Klasyfikacja po nagłówku, który klient ustawia sam, pozwoliłaby każdemu ukryć
// swoje błędy. Zdarzenie sondy jest odrzucane tylko przy dwóch warunkach naraz: tagu synthetic i ścieżce
// pasującej do prefiksu z granicą segmentu (/internal/synthetic-check obejmuje
// /internal/synthetic-check/payments, ale nie /internal/synthetic-checkout). Sama ścieżka nie wystarcza, bo
// ten sam endpoint może wywołać inny system, a jego błąd to prawdziwa awaria. Sam tag też nie, bo sonda
// sprawdzająca prawdziwy checkout może wykryć incydent.
//
// Jak beforeSend po cichu gubi błędy
//
// Zdarzenie odrzucone przez callback nie zostawia śladu w issues. Pierwszym źródłem takich strat jest scope.
// W Java SDK 8.x statyczne Sentry.setTag zapisuje tag w isolation scope bieżącego wątku, a serwer obsługuje
// kolejne requesty na tych samych wątkach puli. Jeśli request nie ma własnego isolation scope
// (Sentry.pushIsolationScope), a tag traffic.origin=internal jest ustawiany tylko dla pracownika, tag zostaje
// na wątku. Następny request klienta go dziedziczy i beforeSend odrzuca jego błąd, a przy wielu wątkach
// dzieje się to losowo. Dlatego każdy request i każde zadanie w tle dostaje własny isolation scope, a tag
// jest ustawiany w każdej gałęzi kodu.
//
// Drugim źródłem jest wyjątek w samym callbacku. SDK 8.54.0 łapie go i odrzuca całe zdarzenie, bo nie wie,
// czy dane zdążyły zostać oczyszczone. Aplikacja działa dalej, a w jej logach nic nie ma: SDK zapisuje to
// tylko przy debug=true, i to mylącym komunikatem „It will be added as breadcrumb and continue”. Typowy
// przypadek to callback testowany tylko na requestach HTTP, który na zdarzeniu z zadania w tle, bez requestu,
// rzuca NullPointerException. Callback musi więc obsługiwać brak każdego pola i mieć testy na każdym rodzaju
// zdarzenia, także na takim, które musi przejść.
//
// Ochrona danych: najpierw aplikacja, potem Sentry
//
// Najbezpieczniejsze jest pole, którego aplikacja w ogóle nie wysłała. Integracja Spring sama dołącza do
// zdarzenia dane requestu, a wyjątek, który opuścił kontroler, zgłasza jako nieobsłużony (level fatal,
// handled=false). Właściwość sentry.send-default-pii (w czystej Javie sendDefaultPii) decyduje, czy
// integracje dołączają dane potencjalnie osobowe, takie jak adres IP, cookies i część nagłówków. Body
// requestu trafia do zdarzenia tylko wtedy, gdy obok niej sentry.max-request-body-size ma wartość inną niż
// domyślne none. Profil do debugowania z obiema właściwościami włączonymi wysyła więc body z numerem karty,
// tokeny, cookies i user.ip wzięty z nagłówka X-Forwarded-For, który klient może ustawić sam.
//
// Ustawienie send-default-pii=false pomija tylko dane ze swojej listy, więc query string z e-mailem
// i nieznany nagłówek z tokenem nadal opuszczają proces. Nie chroni też przed danymi, które kod sam dopisze
// do tagów, context (szczegółów operacji, np. context checkout z numerem zamówienia), extras (dowolnego
// słownika) albo breadcrumbs (kroków poprzedzających błąd). Dlatego czyszczenie (scrubbing) w beforeSend
// usuwa query, body, cookies i extras, z danych użytkownika zostawia tylko user.id, a nagłówki przepuszcza
// według listy dozwolonych, bo lista zakazanych przepuściłaby każdy nowy nagłówek z sekretem, np.
// X-Session-Token. W Spring Boot callback rejestruje się jako bean typu SentryOptions.BeforeSendCallback.
//
// Czyszczenie po stronie serwera (Data Scrubbing w ustawieniach Security & Privacy) jest drugą warstwą, a nie
// zastępstwem. Domyślne reguły zamieniają na [Filtered] między innymi wartości podobne do numerów kart
// i pola, których nazwa zawiera np. token albo auth. Nie usuwają jednak e-maila w query ani adresu IP,
// a Sentry wlicza wtedy podszyty adres do liczby użytkowników (users) w issue.
//
// Gdzie widać odrzucone zdarzenia
//
// Zdarzenie odrzucone w SDK nie tworzy issue. SDK zapisuje jedynie, ile zdarzeń i z jakiego powodu odrzuciło:
// sample_rate dla losowania, event_processor dla filtrów typu i tekstu, before_send dla callbacku. Ten raport
// widać w Stats organizacji jako Client Discard. SDK nie wysyła go osobno, tylko dołącza do następnej paczki
// danych (envelope), więc odrzucenia, po których SDK nic już nie wysłało, nie docierają nigdzie. Brak issue
// nie dowodzi zatem, że błędu nie było.
public final class Module02Demo {

    private static final String RELEASE = "checkout-api@4.12.0+8f24c7a";
    private static final String EMPLOYEE_TOKEN = "emp-session-31";
    private static final String PROBE_SECRET = "probe-7c1f";

    private static final SentryOptionsConfigurer CONFIGURER = new SentryOptionsConfigurer();
    private static final TrafficClassifier TRAFFIC = new TrafficClassifier(Set.of(EMPLOYEE_TOKEN), PROBE_SECRET);

    private Module02Demo() {
    }

    public static void main(String[] args) throws Exception {
        DemoScenarios scenarios = DemoScenarios.from(args, 1, 8);
        if (scenarios.includes(1)) validatedContract();
        if (scenarios.includes(2)) developerLaptop();
        if (scenarios.includes(3)) sampleRateAfterBeforeSend();
        if (scenarios.includes(4)) filterLayers();
        if (scenarios.includes(5)) beforeSendClassification();
        if (scenarios.includes(6)) beforeSendSilentLoss();
        if (scenarios.includes(7)) springStarterActivation();
        if (scenarios.includes(8)) springRequestData();
    }

    // Scenariusz 1: kontrakt konfiguracji sprawdzony przed startem SDK.
    //
    // O co chodzi: SDK samo nie zgłasza błędów konfiguracji. Pusty DSN po cichu je wyłącza, APP_STAGE=prod
    // tworzy w Sentry nowe środowisko obok production, którego nie da się usunąć (tylko ukryć), a release
    // 4.12.0 bez nazwy usługi jest niejednoznaczny w organizacji z wieloma usługami.
    //
    // Co pokazujemy: SentrySettings czyta zmienne wdrożenia stagingu, a SentryOptionsConfigurer przepisuje
    // je do zwykłego SentryOptions, bez Sentry.init. Trzy błędne wdrożenia (prod, pusty DSN, release 4.12.0
    // razem z SENTRY_SAMPLE_RATE=25%) przerywają start. Poprawne uruchamia SDK, a DeploymentSmokeCheck
    // wysyła kontrolny event z tagiem deployment.check.
    //
    // Problem: bez walidacji każdy z tych błędów wychodzi dopiero po tygodniach, jako pusty projekt
    // albo dodatkowe środowisko, a testy jednostkowe nie potwierdzą, że eventy faktycznie docierają.
    //
    // Dobra praktyka: kontrakt z zamkniętym słownikiem etapów, wymaganym DSN i release checkout-api@wersja,
    // który zgłasza wszystkie naruszenia naraz przy starcie; jedno miejsce przepisujące go do SentryOptions,
    // testowalne bez globalnego stanu; kontrolny event po wdrożeniu, który potwierdza wynik w Sentry.
    //
    // Na co patrzeć: w konsoli linie „start przerwany” z listą naruszeń (trzecie wdrożenie ma dwa naraz)
    // i kontrolny event z environment=staging, release checkout-api@4.12.0+8f24c7a i tagiem service.name
    // ustawionym przez konfigurator. W Sentry UI issue z tagiem deployment.check:true, release,
    // environment staging i ramkami pl.training.sentry.module02 oznaczonymi jako kod aplikacji.
    //
    // Uruchomienie: -Dexec.args=1
    static void validatedContract() {
        DemoConsole.scenario(1, "Kontrakt konfiguracji sprawdzony przed startem SDK",
                "pokazać, że błędy konfiguracji wychodzą przy starcie aplikacji, a nie tygodnie później w pustym projekcie.");

        Map<String, String> staging = deployment("staging");
        DemoConsole.step("Zmienne wdrożenia stagingu: APP_STAGE=staging, SENTRY_DSN, SENTRY_RELEASE=" + RELEASE);
        SentrySettings settings = SentrySettings.fromEnvironment(staging);
        SentryOptions options = new SentryOptions();
        CONFIGURER.configure(options, settings);
        DemoConsole.step("   SentryOptionsConfigurer na zwykłym SentryOptions, bez Sentry.init: " + describe(options));

        tryDeployment("APP_STAGE=prod, skrót przeniesiony z innego projektu", with(staging, "APP_STAGE", "prod"));
        tryDeployment("SENTRY_DSN pusty, bo w szablonie wdrożenia zabrakło wartości", with(staging, "SENTRY_DSN", ""));
        tryDeployment("SENTRY_RELEASE=4.12.0 i SENTRY_SAMPLE_RATE=25%",
                with(with(staging, "SENTRY_RELEASE", "4.12.0"), "SENTRY_SAMPLE_RATE", "25%"));

        DemoConsole.step("Poprawna konfiguracja: SDK startuje, test odbiorowy wysyła kontrolny event");
        try (TrainingSentry.TrainingSession session = startSdk(settings, sdk -> {})) {
            DeploymentSmokeCheck.sendControlEvent();
        }

        DemoConsole.lookAt("w konsoli każda błędna konfiguracja kończy start listą naruszeń, zanim SDK cokolwiek "
                + "wyśle. Kontrolny event ma environment=staging, release " + RELEASE
                + " i tag service.name, który ustawił konfigurator, a nie kod raportujący.");
        DemoConsole.lookAt("w Sentry UI issue kontrolnego błędu (tag deployment.check:true) pokazuje release i environment "
                + "staging, a w stack trace ramki pl.training.sentry.module02 są oznaczone jako kod aplikacji.");
    }

    // Scenariusz 2: laptop developera.
    //
    // O co chodzi: proces na laptopie nie powinien nic wysyłać, nawet gdy w .env został DSN skopiowany
    // z produkcji. Bez jawnego environment Java SDK przyjmuje production, więc laptop wygląda jak produkcja.
    //
    // Co pokazujemy: A. APP_STAGE=local z DSN w zmiennych: kontrakt dodaje ostrzeżenie, zeruje DSN
    // i wyłącza SDK, a płatność kończy się odpowiedzią 500 bez eventu. B. Ten sam laptop z SDK
    // zainicjalizowanym jak w quickstarcie: sam DSN, bez environment, release i beforeSend.
    //
    // Problem: event z wariantu B ma environment=production i nie ma release, a query z e-mailem klienta
    // i token w Authorization opuszczają proces. Trafia do widoków i alertów filtrowanych po
    // environment:production, jakby pochodził z produkcji.
    //
    // Dobra praktyka: etap LOCAL w kontrakcie wyłącza SDK (enabled=false, DSN null) bez blokowania startu,
    // a environment i release zawsze ustawia się jawnie w jednym miejscu (SentryOptionsConfigurer).
    //
    // Na co patrzeć: w konsoli wariant A ma ostrzeżenie kontraktu, Sentry.isEnabled() = false
    // i „event niewysłany (pusty identyfikator)”, bez linii Sentry [module02] i bez wydruku eventu.
    // Wariant B ma environment production, release BRAK, query i nagłówek Authorization. W Sentry UI
    // event z B ma environment production i nie ma release.
    //
    // Uruchomienie: -Dexec.args=2
    static void developerLaptop() {
        DemoConsole.scenario(2, "Laptop developera",
                "pokazać, że profil lokalny nie wysyła eventów, a SDK bez jawnego environment oznacza laptop jako produkcję.");

        CheckoutEndpoint endpoint = endpoint(PaymentGateway.resettingConnection());

        DemoConsole.step("A. APP_STAGE=local, a w pliku .env został SENTRY_DSN skopiowany z produkcji");
        SentrySettings local = SentrySettings.fromEnvironment(Map.of("APP_STAGE", "local", "SENTRY_DSN", TrainingSentry.dsn()));
        DemoConsole.step("   ostrzeżenie kontraktu: " + String.join("; ", local.warnings()));
        // Bez TrainingSentry.init: wyłączone SDK zamyka się w Sentry.init, więc jego nagłówek pokazałby
        // opcje pustego klienta (environment=production, release=null), a nie te z kontraktu.
        Sentry.init(options -> {
            TrainingSentry.applyTrainingDefaults(options, "module02");
            CONFIGURER.configure(options, local);
        });
        DemoConsole.step("   Sentry.isEnabled() = " + Sentry.isEnabled());
        CheckoutResponse response = endpoint.handle(customerPayment(), "ORD-2001", 12_999, ResponseChannel.open());
        DemoConsole.step("   płatność na laptopie: " + describe(response));
        Sentry.close();

        DemoConsole.step("B. Ten sam laptop, SDK zainicjalizowane fragmentem z quickstartu: tylko DSN");
        try (TrainingSentry.TrainingSession session = TrainingSentry.init("module02", options -> {
            // Tak wygląda konfiguracja, w której nikt nie ustawił environment ani release.
            options.setEnvironment(null);
            options.setRelease(null);
            RequestPreviewTransport.install(options);
        })) {
            response = endpoint.handle(customerPayment(), "ORD-2002", 12_999, ResponseChannel.open());
            DemoConsole.step("   płatność na laptopie: " + describe(response));
        }

        DemoConsole.lookAt("w konsoli wariant A nie ma wydruku eventu, bo kontrakt wyłączył SDK. Wariant B ma "
                + "environment=production, brak release i brak beforeSend, więc query z e-mailem klienta "
                + "i token w Authorization opuszczają proces.");
        DemoConsole.lookAt("w Sentry UI event z wariantu B ma environment production i nie ma release, więc trafia "
                + "do widoków i alertów filtrowanych po environment:production, jakby pochodził z produkcji.");
    }

    // Scenariusz 3: error sampling w trakcie awarii bramki.
    //
    // O co chodzi: sampleRate to losowa decyzja dla error events, a domyślne null wyłącza losowanie.
    // W Java SDK losowanie następuje dopiero po beforeSend i nie odróżnia ważnych błędów od szumu.
    //
    // Co pokazujemy: kontrakt z SENTRY_SAMPLE_RATE=0.25 i licznik wywołań owinięty wokół beforeSend
    // z konfiguratora. 12 płatności kończy się Connection reset z bramki, a 13. rzadkim błędem braku
    // klucza podpisu dla sprzedawcy M-42.
    //
    // Problem: beforeSend działa dla wszystkich 13 eventów, więc niski sample rate nie ogranicza kosztu
    // callbacku. Losowanie odrzuca średnio 3 eventy z 4, także jedyny event błędu krytycznego, a licznik
    // eventów issue przestaje odpowiadać liczbie nieudanych płatności.
    //
    // Dobra praktyka: bez ekstremalnego wolumenu nie próbkować błędów (sampleRate null). Znany szum usuwać
    // precyzyjną regułą (scenariusze 4 i 5), a nie mniejszym odsetkiem wszystkich błędów.
    //
    // Na co patrzeć: w konsoli „beforeSend wywołany 13 razy” i liczba wysłanych eventów inna przy każdym
    // uruchomieniu (bywa 0); błąd klucza podpisu przepada średnio w 3 uruchomieniach na 4. W Sentry UI
    // licznik issue Connection reset jest kilka razy mniejszy niż liczba płatności, issue klucza podpisu
    // często nie powstaje, a odrzucenia są tylko w Stats (Client Discard, powód sample_rate), i to tylko
    // wtedy, gdy po nich SDK wysłało jeszcze jakiś event.
    //
    // Uruchomienie: -Dexec.args=3
    static void sampleRateAfterBeforeSend() {
        DemoConsole.scenario(3, "Error sampling w trakcie awarii bramki",
                "pokazać, że sampleRate działa po beforeSend i losowo gubi także rzadkie, krytyczne błędy.");

        SentrySettings settings = SentrySettings.fromEnvironment(with(deployment("production"), "SENTRY_SAMPLE_RATE", "0.25"));
        AtomicInteger beforeSendCalls = new AtomicInteger();
        int sent = 0;
        boolean criticalSent;

        try (TrainingSentry.TrainingSession session = startSdk(settings, options -> {
            // Licznik wywołań wokół beforeSend z konfiguratora, żeby zobaczyć kolejność etapów SDK.
            SentryOptions.BeforeSendCallback configured = options.getBeforeSend();
            options.setBeforeSend((event, hint) -> {
                beforeSendCalls.incrementAndGet();
                return configured.execute(event, hint);
            });
        })) {
            DemoConsole.step("SENTRY_SAMPLE_RATE=0.25. Bramka zrywa połączenia: 12 nieudanych płatności");
            CheckoutEndpoint failingGateway = endpoint(PaymentGateway.resettingConnection());
            for (int i = 1; i <= 12; i++) {
                if (failingGateway.handle(customerPayment(), "ORD-30" + String.format("%02d", i), 12_999,
                        ResponseChannel.open()).eventSent()) {
                    sent++;
                }
            }
            DemoConsole.step("Do tego 1 rzadki błąd: brak klucza podpisu dla sprzedawcy M-42");
            criticalSent = endpoint(PaymentGateway.missingSigningKey())
                    .handle(customerPayment(), "ORD-3013", 4_500, ResponseChannel.open()).eventSent();
            if (criticalSent) {
                sent++;
            }
        }

        DemoConsole.step("beforeSend wywołany " + beforeSendCalls.get() + " razy, wysłano " + sent + " z 13 eventów");
        DemoConsole.step("Błąd klucza podpisu: " + (criticalSent ? "wysłany" : "odrzucony przez losowanie"));
        DemoConsole.lookAt("w konsoli liczba wydrukowanych eventów zmienia się między uruchomieniami (średnio 1 na 4), "
                + "a beforeSend działa dla wszystkich 13, także tych, które losowanie zaraz odrzuci. "
                + "Błąd klucza podpisu przepada średnio w 3 uruchomieniach na 4.");
        DemoConsole.lookAt("w Sentry UI licznik eventów issue Connection reset jest kilka razy mniejszy niż liczba "
                + "nieudanych płatności, a issue braku klucza podpisu często w ogóle nie powstaje. Odrzucenia widać "
                + "tylko w Stats (Client Discard, powód sample_rate), i to tylko te, po których SDK wysłało jeszcze "
                + "jakiś event: raport o odrzuceniach SDK dołącza do następnego envelope.");
    }

    // Scenariusz 4: warstwy filtrowania, typ wyjątku kontra tekst.
    //
    // O co chodzi: ten sam SocketException: Connection reset pochodzi raz od klienta, który zamknął
    // aplikację po płatności (szum), a raz od bramki płatności (awaria, klienci nie mogą zapłacić).
    // ignoredExceptionsForType dopasowuje dokładną klasę głównego wyjątku, a ignoredErrors regex do całego
    // tekstu wyjątku razem z nazwą klasy.
    //
    // Co pokazujemy: A. warstwa zapisu odpowiedzi zamienia zerwane połączenie na ClientAbortedException,
    // które odrzuca ignoredExceptionsForType z konfiguratora. B. starszy kod przepuszcza gołe
    // SocketException. C. reguła ignoredErrors ".*Connection reset.*" dla klienta i dla bramki.
    // D. ta sama reguła bez .*.
    //
    // Problem: B wysyła szum nie do odróżnienia od awarii bramki. C odrzuca także awarię bramki, więc
    // projekt milczy w czasie incydentu. D nic nie filtruje, bo "Connection reset" nie pasuje do całego
    // tekstu "java.net.SocketException: Connection reset".
    //
    // Dobra praktyka: dedykowany typ nadany w warstwie, która zna przyczynę, i filtr po dokładnym typie.
    // Konfigurator celowo nie ustawia ignoredErrors; każda reguła tekstowa wymaga testu eventu, który
    // musi zostać.
    //
    // Na co patrzeć: w konsoli A bez wydruku eventu, B i D z eventem SocketException, w C oba przypadki
    // kończą się „event niewysłany”. W Sentry UI w czasie C projekt milczy. Odrzucenie z A jest w Stats
    // (Client Discard, powód event_processor), bo raport poszedł z eventem B; odrzuceń z C nie widać nigdzie.
    //
    // Uruchomienie: -Dexec.args=4
    static void filterLayers() {
        DemoConsole.scenario(4, "Warstwy filtrowania: typ wyjątku kontra tekst",
                "pokazać, że filtr po dokładnym typie usuwa tylko szum, a filtr tekstowy łatwo usuwa za dużo albo nic.");

        SentrySettings settings = SentrySettings.fromEnvironment(deployment("production"));
        CheckoutEndpoint gatewayUp = endpoint(PaymentGateway.available());
        CheckoutEndpoint gatewayDown = endpoint(PaymentGateway.resettingConnection());

        try (TrainingSentry.TrainingSession session = startSdk(settings, options -> {})) {
            DemoConsole.step("A. Klient zamknął aplikację po płatności, warstwa zapisu zgłasza ClientAbortedException");
            DemoConsole.step("   " + describe(gatewayUp.handle(customerPayment(), "ORD-4001", 12_999,
                    ResponseChannel.abortedByClient())));
            DemoConsole.step("B. Ten sam przypadek w starszym kodzie: gołe SocketException: Connection reset");
            DemoConsole.step("   " + describe(gatewayUp.handle(customerPayment(), "ORD-4002", 12_999,
                    ResponseChannel.resetByClient())));
        }

        try (TrainingSentry.TrainingSession session = startSdk(settings,
                options -> options.addIgnoredError(".*Connection reset.*"))) {
            DemoConsole.step("C. Zespół wycisza szum regułą ignoredErrors \".*Connection reset.*\"");
            DemoConsole.step("   klient zamknął połączenie: " + describe(gatewayUp.handle(customerPayment(),
                    "ORD-4003", 12_999, ResponseChannel.resetByClient())));
            DemoConsole.step("   bramka zerwała połączenie: " + describe(gatewayDown.handle(customerPayment(),
                    "ORD-4004", 12_999, ResponseChannel.open())));
        }

        try (TrainingSentry.TrainingSession session = startSdk(settings,
                options -> options.addIgnoredError("Connection reset"))) {
            DemoConsole.step("D. Ta sama reguła bez .*: \"Connection reset\"");
            DemoConsole.step("   " + describe(gatewayUp.handle(customerPayment(), "ORD-4005", 12_999,
                    ResponseChannel.resetByClient())));
        }

        DemoConsole.lookAt("w konsoli A nie ma wydruku, a B wysyła event SocketException, choć to ten sam, nieszkodliwy "
                + "przypadek. W C reguła tekstowa odrzuca też awarię bramki: klienci nie mogą zapłacić, a SDK nic "
                + "nie wysyła. W D reguła nic nie filtruje, bo musi pasować do całego tekstu "
                + "\"java.net.SocketException: Connection reset\".");
        DemoConsole.lookAt("w Sentry UI w czasie wariantu C projekt milczy. Odrzucenie z A widać w Stats (Client "
                + "Discard, powód event_processor), bo raport o nim poszedł razem z eventem B. Odrzuceń z C nie widać "
                + "nawet tam: po nich SDK nic już nie wysłało, więc raport nie opuścił procesu. Issue SocketException "
                + "z wariantów B i D zawiera sam szum, który typ ClientAbortedException z wariantu A usuwa u źródła.");
    }

    // Scenariusz 5: beforeSend, ruch własny, sondy syntetyczne i scrubbing.
    //
    // O co chodzi: FilteringBeforeSend najpierw decyduje, czy event opuści proces, a potem czyści to, co
    // zostaje. O pochodzeniu ruchu nie decyduje callback, tylko tag traffic.origin ustawiony w scope requestu
    // przez TrafficClassifier, który weryfikuje token pracownika albo sekret sondy.
    //
    // Co pokazujemy: pięć requestów przy awarii bramki. A klient z e-mailem w query i tokenem, B pracownik,
    // C sonda z sekretem na /internal/synthetic-check/payments, D ta sama ścieżka bez sekretu,
    // E sonda z sekretem na /internal/synthetic-checkout.
    //
    // Problem: reguła na samej ścieżce odrzuciłaby D, czyli prawdziwą awarię widzianą przez inny system,
    // a reguła na samym tagu odrzuciłaby E, czyli sondę prawdziwego checkoutu, która może wykryć incydent.
    // Klasyfikacja po nagłówku, który klient ustawia sam, pozwoliłaby każdemu ukryć swoje błędy.
    //
    // Dobra praktyka: sonda odpada tylko przy obu warunkach, tagu z zaufanej warstwy i prefiksie ścieżki
    // z granicą segmentu. Scrubbing na końcu: bez query, body, cookies i extras, nagłówki z listy
    // dozwolonych, z usera tylko user.id.
    //
    // Na co patrzeć: w konsoli wydruk mają tylko A, D i E, a B i C kończą się „event niewysłany”. Event A
    // nie ma query ani Authorization, ale ma wyjątek, tagi i context checkout. W Sentry UI tag
    // traffic.origin ma wartości external i synthetic, bez internal, a sekcja Request eventu A nie ma
    // query ani Authorization.
    //
    // Uruchomienie: -Dexec.args=5
    static void beforeSendClassification() {
        DemoConsole.scenario(5, "beforeSend: ruch własny, sondy syntetyczne i scrubbing",
                "pokazać, że beforeSend odrzuca tylko ruch potwierdzony przez zaufaną warstwę, a wysyłane eventy czyści.");

        SentrySettings settings = SentrySettings.fromEnvironment(deployment("production"));
        CheckoutEndpoint endpoint = endpoint(PaymentGateway.resettingConnection());

        try (TrainingSentry.TrainingSession session = startSdk(settings, options -> {})) {
            DemoConsole.step("A. Klient, POST /api/checkout z e-mailem w query i tokenem sesji");
            DemoConsole.step("   " + describe(endpoint.handle(customerPayment(), "ORD-5001", 12_999, ResponseChannel.open())));

            DemoConsole.step("B. Pracownik testuje płatność na produkcji (token z katalogu pracowników)");
            DemoConsole.step("   " + describe(endpoint.handle(employeePayment(), "ORD-5002", 100, ResponseChannel.open())));

            DemoConsole.step("C. Sonda syntetyczna z sekretem, GET /internal/synthetic-check/payments");
            DemoConsole.step("   " + describe(endpoint.handle(probe("/internal/synthetic-check/payments", PROBE_SECRET),
                    "SYN-5003", 100, ResponseChannel.open())));

            DemoConsole.step("D. Ta sama ścieżka wywołana przez inny system, bez sekretu sondy");
            DemoConsole.step("   " + describe(endpoint.handle(probe("/internal/synthetic-check/payments", null),
                    "SYN-5004", 100, ResponseChannel.open())));

            DemoConsole.step("E. Sonda z sekretem na prawdziwej ścieżce /internal/synthetic-checkout");
            DemoConsole.step("   " + describe(endpoint.handle(probe("/internal/synthetic-checkout", PROBE_SECRET),
                    "SYN-5005", 100, ResponseChannel.open())));
        }

        DemoConsole.lookAt("w konsoli wysłane są tylko A, D i E. W A scrubber usunął query z e-mailem i nagłówek "
                + "Authorization, a zostawił wyjątek, tagi i context checkout. D i E pokazują, że sama ścieżka "
                + "albo sam tag nie wystarczą do odrzucenia: /internal/synthetic-checkout to inny segment niż "
                + "/internal/synthetic-check.");
        DemoConsole.lookAt("w Sentry UI rozkład tagu traffic.origin ma wartości external i synthetic, bez internal. "
                + "Sekcja Request eventu z A nie zawiera query string ani nagłówka Authorization.");
    }

    // Scenariusz 6: jak beforeSend po cichu gubi prawdziwe błędy.
    //
    // O co chodzi: beforeSend, który zwraca null, usuwa event bez śladu w issues. Wystarczą złe dane
    // wejściowe (tag odziedziczony po innym requeście) albo wyjątek w samym callbacku: SDK 8.54.0 łapie
    // go i odrzuca cały event, a loguje to tylko przy debug=true, mylącym tekstem.
    //
    // Co pokazujemy: A. dwa requesty przez handleWithoutRequestScope na jednym wątku (pula jednowątkowa daje
    // powtarzalność): pracownik, potem klient; następnie te same requesty przez handle. B. nocny
    // PaymentRetryJob, event bez requestu, z FilteringBeforeSend. C. request klienta i ten sam job
    // z HttpOnlyBeforeSend, pisanym i testowanym tylko na requestach HTTP.
    //
    // Problem: w A tag traffic.origin=internal, ustawiany tylko dla pracownika, zostaje w isolation scope
    // wątku, więc beforeSend odrzuca błąd klienta; w produkcji pula ma wiele wątków i błędy giną losowo.
    // W C callback rzuca NullPointerException na evencie joba, który przepada bez logu w aplikacji.
    // Przy okazji HttpOnlyBeforeSend usuwa tylko query, więc Authorization klienta opuszcza proces.
    //
    // Dobra praktyka: isolation scope na request i tag ustawiany w każdej gałęzi (CheckoutEndpoint.handle),
    // własny scope dla joba, null-safe beforeSend testowany na każdym rodzaju eventu, także na evencie,
    // który musi przejść.
    //
    // Na co patrzeć: w konsoli w A oba requesty bez wydruku, a z isolation scope event klienta
    // z traffic.origin=external. W B event joba z tagiem job.name. W C event requestu z nagłówkiem
    // Authorization, a job kończy się „event niewysłany”. W Sentry UI zgubione błędy nie tworzą issues;
    // odrzucenia z A są tylko w Stats (Client Discard, powód before_send), a odrzucenia joba z C
    // nie ma nigdzie.
    //
    // Uruchomienie: -Dexec.args=6
    static void beforeSendSilentLoss() throws Exception {
        DemoConsole.scenario(6, "Jak beforeSend po cichu gubi prawdziwe błędy",
                "pokazać tag filtrujący, który przecieka między requestami, i wyjątek w samym callbacku.");

        SentrySettings settings = SentrySettings.fromEnvironment(deployment("production"));
        CheckoutEndpoint endpoint = endpoint(PaymentGateway.resettingConnection());
        PaymentRetryJob retryJob = new PaymentRetryJob(new CheckoutService(PaymentGateway.resettingConnection()));

        try (TrainingSentry.TrainingSession session = startSdk(settings, options -> {})) {
            // Pula z jednym wątkiem gwarantuje, że drugi request trafi na wątek pierwszego.
            // PRODUKCJA: pula ma wiele wątków, więc błędy znikają losowo i nie da się tego powtórzyć.
            DemoConsole.step("A. Dwa requesty bez własnego isolation scope na jednym wątku serwera");
            try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
                CheckoutResponse employee = worker.submit(() -> endpoint.handleWithoutRequestScope(
                        employeePayment(), "ORD-6001", 100, ResponseChannel.open())).get();
                DemoConsole.step("   pracownik: " + describe(employee));
                CheckoutResponse customer = worker.submit(() -> endpoint.handleWithoutRequestScope(
                        customerPayment(), "ORD-6002", 12_999, ResponseChannel.open())).get();
                DemoConsole.step("   klient zaraz po nim: " + describe(customer));
            }
            DemoConsole.step("   Te same requesty w CheckoutEndpoint.handle (isolation scope, tag ustawiany zawsze)");
            try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
                worker.submit(() -> endpoint.handle(employeePayment(), "ORD-6003", 100, ResponseChannel.open())).get();
                CheckoutResponse customer = worker.submit(() -> endpoint.handle(
                        customerPayment(), "ORD-6004", 12_999, ResponseChannel.open())).get();
                DemoConsole.step("   klient zaraz po pracowniku: " + describe(customer));
            }

            DemoConsole.step("B. Nocny job ponawia obciążenie, FilteringBeforeSend (null-safe)");
            DemoConsole.step("   job: " + describe(retryJob.retry("ORD-6005", 12_999)));
        }

        try (TrainingSentry.TrainingSession session = startSdk(settings,
                options -> options.setBeforeSend(new HttpOnlyBeforeSend()))) {
            DemoConsole.step("C. Ten sam job z HttpOnlyBeforeSend, testowanym tylko na requestach HTTP");
            DemoConsole.step("   request klienta: " + describe(endpoint.handle(customerPayment(), "ORD-6006", 12_999,
                    ResponseChannel.open())));
            DemoConsole.step("   job: " + describe(retryJob.retry("ORD-6007", 12_999)));
        }

        DemoConsole.lookAt("w konsoli w A błąd klienta po requeście pracownika nie ma wydruku: odziedziczył "
                + "traffic.origin=internal. Z isolation scope ten sam błąd ma traffic.origin=external. W B event joba "
                + "przechodzi, a w C request klienta nadal jest wysyłany, ale event joba znika: callback rzucił "
                + "NullPointerException na evencie bez requestu, a aplikacja nie zalogowała nic. Przy okazji "
                + "HttpOnlyBeforeSend usuwa tylko query, więc event requestu w C niesie nagłówek Authorization klienta.");
        DemoConsole.lookAt("w Sentry UI żaden z tych błędów nie zostawia śladu w issues. Odrzucenia z A widać tylko "
                + "w Stats (Client Discard, powód before_send). Odrzucenia joba z C nie widać nigdzie, bo po nim SDK "
                + "nic już nie wysłało. Dlatego reguła beforeSend ma też test eventu, który musi przejść.");
    }

    // Scenariusz 7: Spring Boot, sentry.dsn i profil lokalny.
    //
    // O co chodzi: w Spring Boot SDK inicjalizuje starter. Bez właściwości sentry.dsn auto-konfiguracja
    // Sentry się nie uruchamia, ale Spring Boot mapuje zmienną SENTRY_DSN na sentry.dsn, więc DSN
    // odziedziczony z shella albo wczytany z .env ją uruchamia.
    //
    // Co pokazujemy: checkout-api z profilem local i bramką w awarii, płatność kończy się HTTP 500.
    // A. zmienne procesu bez SENTRY_DSN. B. SENTRY_DSN w zmiennych procesu. Stan auto-konfiguracji
    // rozpoznajemy po beanie IScopes, który tworzy tylko ona.
    //
    // Problem: brak sentry.dsn w plikach nie chroni laptopa, bo w B starter startuje z DSN ze zmiennych.
    // Bez drugiej linii obrony request z wariantu B wysłałby event z laptopa.
    //
    // Dobra praktyka: profil lokalny nie definiuje sentry.dsn i ustawia sentry.enabled=false
    // (application-local.properties). Ta właściwość wyłącza wysyłanie, ale nie usuwa całego narzutu
    // instrumentacji.
    //
    // Na co patrzeć: w konsoli A „auto-konfiguracja Sentry: nieuruchomiona”, B „uruchomiona”, w obu
    // Sentry.isEnabled() = false i HTTP 500 bez wydruku eventu. W Sentry UI nic nie przychodzi i to jest
    // poprawny stan profilu lokalnego.
    //
    // Uruchomienie: -Dexec.args=7
    static void springStarterActivation() throws Exception {
        DemoConsole.scenario(7, "Spring Boot: sentry.dsn i profil lokalny",
                "pokazać, że starter zależy od sentry.dsn, a sentry.enabled=false chroni laptop przed odziedziczonym DSN.");

        DemoConsole.step("A. Profil local, zmienne środowiskowe bez SENTRY_DSN");
        try (CheckoutApplication.Running app = CheckoutApplication.start("local", Map.of())) {
            DemoConsole.step("   auto-konfiguracja Sentry: " + onOff(app.sentryAutoConfigured())
                    + ", Sentry.isEnabled() = " + Sentry.isEnabled());
            DemoConsole.step("   płatność: HTTP " + new CheckoutWebClient(app.port()).submitPayment() + ", bez eventu");
        }

        DemoConsole.step("B. Profil local, SENTRY_DSN odziedziczony z shella (Spring Boot czyta go jako sentry.dsn)");
        try (CheckoutApplication.Running app = CheckoutApplication.start("local", Map.of("SENTRY_DSN", TrainingSentry.dsn()))) {
            DemoConsole.step("   auto-konfiguracja Sentry: " + onOff(app.sentryAutoConfigured())
                    + ", Sentry.isEnabled() = " + Sentry.isEnabled());
            DemoConsole.step("   płatność: HTTP " + new CheckoutWebClient(app.port()).submitPayment() + ", bez eventu");
        }

        DemoConsole.lookAt("w konsoli A i B nie mają wydruku eventu mimo błędu 500. W B starter działa (filtr i resolver "
                + "Sentry są w aplikacji), ale sentry.enabled=false z application-local.properties blokuje wysyłkę.");
        DemoConsole.lookAt("w Sentry UI nic nie przychodzi i to jest poprawny stan profilu lokalnego. Bez "
                + "sentry.enabled=false request z wariantu B wysłałby event z laptopa.");
    }

    // Scenariusz 8: Spring Boot, send-default-pii, body requestu i BeforeSendCallback.
    //
    // O co chodzi: integracja Spring sama dołącza do eventu dane requestu i raportuje wyjątek, który
    // opuścił kontroler, jako nieobsłużony. O tym, co z tych danych trafi do Sentry, decydują
    // sentry.send-default-pii, sentry.max-request-body-size i bean BeforeSendCallback.
    //
    // Co pokazujemy: CheckoutWebClient wysyła płatność jak przeglądarka: e-mail w query, Authorization,
    // cookies, X-Session-Token, X-Forwarded-For i body z numerem karty. A. profil pii-debug
    // (send-default-pii=true, max-request-body-size=always). B. konfiguracja domyślna. C. konfiguracja
    // domyślna i SentryPrivacyConfiguration, czyli FilteringBeforeSend jako bean.
    //
    // Problem: profil pii-debug włączony „na chwilę” wysyła body z kartą, tokeny, cookie z e-mailem i user.ip
    // z X-Forwarded-For, który klient ustawia sam. send-default-pii=false usuwa tylko dane ze swojej listy,
    // więc query z e-mailem i nieznany nagłówek X-Session-Token nadal opuszczają proces.
    //
    // Dobra praktyka: send-default-pii=false i max-request-body-size=none ustawione jawnie, a do tego
    // beforeSend ze scrubberem i listą dozwolonych nagłówków. Scrubbing po stronie serwera to druga warstwa,
    // a nie zastępstwo.
    //
    // Na co patrzeć: w konsoli A ma body, Authorization, X-Session-Token, X-Forwarded-For, cookie remember_me
    // i user ip=10.20.0.7. B nie ma body, cookies, IP ani Authorization, ale ma query i X-Session-Token.
    // C ma URL bez query i tylko nagłówki z listy dozwolonych. Wszystkie mają level=fatal, handled=nie
    // i environment training. W Sentry UI serwer zamienił w A na [Filtered] Authorization, X-Session-Token
    // i numer karty, ale e-mail, cookie remember_me i podszyte IP zapisał, a licznik users liczy ten adres.
    //
    // Uruchomienie: -Dexec.args=8
    static void springRequestData() throws Exception {
        DemoConsole.scenario(8, "Spring Boot: send-default-pii, body requestu i BeforeSendCallback",
                "pokazać, co integracja Spring dołącza do eventu, co usuwa send-default-pii=false, a co dopiero beforeSend.");

        Map<String, String> env = Map.of("SENTRY_DSN", TrainingSentry.dsn());

        DemoConsole.step("A. Profil pii-debug (send-default-pii=true, max-request-body-size=always), bez beforeSend");
        try (CheckoutApplication.Running app = CheckoutApplication.start("pii-debug", env)) {
            DemoConsole.step("   płatność: HTTP " + new CheckoutWebClient(app.port()).submitPayment());
        }

        DemoConsole.step("B. Konfiguracja domyślna (send-default-pii=false), bez beforeSend");
        try (CheckoutApplication.Running app = CheckoutApplication.start(null, env)) {
            DemoConsole.step("   płatność: HTTP " + new CheckoutWebClient(app.port()).submitPayment());
        }

        DemoConsole.step("C. Konfiguracja domyślna i bean BeforeSendCallback (SentryPrivacyConfiguration)");
        try (CheckoutApplication.Running app = CheckoutApplication.start(null, env, SentryPrivacyConfiguration.class)) {
            DemoConsole.step("   płatność: HTTP " + new CheckoutWebClient(app.port()).submitPayment());
        }

        DemoConsole.lookAt("w konsoli A zawiera body z numerem karty i e-mailem, nagłówki Authorization, X-Session-Token "
                + "i X-Forwarded-For, cookie remember_me z e-mailem oraz user.ip=" + CheckoutWebClient.SPOOFED_CLIENT_IP
                + " wzięty z nagłówka klienta. W B SDK nie dołączyło body, cookies, IP ani nagłówków ze swojej listy, ale "
                + "zostały query z e-mailem i X-Session-Token. W C zostają metoda, URL bez query i nagłówki z listy dozwolonych.");
        DemoConsole.lookAt("w Sentry UI domyślny scrubbing serwera zamienił w evencie z A na [Filtered] Authorization, "
                + "X-Session-Token i numer karty, ale e-mail z body, query i cookie remember_me oraz IP z X-Forwarded-For "
                + "zapisał, a licznik users issue liczy ten podszyty adres. Druga warstwa nie zastępuje pierwszej.");
    }

    private static CheckoutEndpoint endpoint(PaymentGateway gateway) {
        return new CheckoutEndpoint(new CheckoutService(gateway), TRAFFIC);
    }

    /**
     * Inicjalizacja SDK dla scenariusza: ustawienia szkoleniowe, potem kontrakt modułu, na końcu
     * zmiany scenariusza. Konfigurator nadpisuje DSN, environment i release z {@code TrainingSentry}.
     */
    private static TrainingSentry.TrainingSession startSdk(SentrySettings settings, Consumer<SentryOptions> scenario) {
        return TrainingSentry.init("module02", options -> {
            CONFIGURER.configure(options, settings);
            RequestPreviewTransport.install(options);
            scenario.accept(options);
        });
    }

    /** Zmienne wdrożenia: DSN szkolenia (online albo zastępczy offline) i release z pipeline. */
    private static Map<String, String> deployment(String stage) {
        return Map.of("APP_STAGE", stage, "SENTRY_DSN", TrainingSentry.dsn(), "SENTRY_RELEASE", RELEASE);
    }

    private static Map<String, String> with(Map<String, String> base, String key, String value) {
        Map<String, String> copy = new HashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    private static void tryDeployment(String description, Map<String, String> env) {
        DemoConsole.step("Wdrożenie z błędem: " + description);
        try {
            SentrySettings.fromEnvironment(env);
            DemoConsole.step("   przyjęte");
        } catch (IllegalArgumentException exception) {
            DemoConsole.step("   start przerwany: " + exception.getMessage());
        }
    }

    private static IncomingRequest customerPayment() {
        return new IncomingRequest("POST", "/api/checkout", "coupon=WIOSNA26&email=anna.kowalska%40example.com", Map.of(
                "Authorization", "Bearer cust-7f3a9c",
                "Content-Type", "application/json",
                "User-Agent", "checkout-web/5.2"));
    }

    private static IncomingRequest employeePayment() {
        return new IncomingRequest("POST", "/api/checkout", null, Map.of(
                "Authorization", "Bearer " + EMPLOYEE_TOKEN,
                "Content-Type", "application/json",
                "User-Agent", "checkout-web/5.2"));
    }

    private static IncomingRequest probe(String path, String secret) {
        Map<String, String> headers = new HashMap<>(Map.of("User-Agent", "synthetic-monitor/3.1"));
        if (secret != null) {
            headers.put(TrafficClassifier.SYNTHETIC_CHECK_HEADER, secret);
        } else {
            headers.put("User-Agent", "status-page/1.4");
        }
        return new IncomingRequest("GET", path, null, headers);
    }

    private static String describe(SentryOptions options) {
        return "enabled=" + options.isEnabled()
                + ", environment=" + options.getEnvironment()
                + ", release=" + options.getRelease()
                + ", sampleRate=" + (options.getSampleRate() == null ? "null (bez losowania)" : options.getSampleRate())
                + ", sendDefaultPii=" + options.isSendDefaultPii()
                + ", ignoredExceptionsForType=" + options.getIgnoredExceptionsForType().stream()
                .map(Class::getSimpleName).toList()
                + ", beforeSend=" + (options.getBeforeSend() == null ? "brak" : options.getBeforeSend().getClass().getSimpleName());
    }

    private static String describe(CheckoutResponse response) {
        return "odpowiedź " + response.status() + ", " + describe(response.sentryEventId());
    }

    private static String describe(SentryId eventId) {
        if (eventId == null) {
            return "bez błędu";
        }
        return SentryId.EMPTY_ID.equals(eventId)
                ? "event niewysłany (pusty identyfikator)"
                : "event wysłany " + eventId.toString().substring(0, 8);
    }

    private static String onOff(boolean value) {
        return value ? "uruchomiona" : "nieuruchomiona";
    }
}
