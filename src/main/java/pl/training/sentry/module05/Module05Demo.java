package pl.training.sentry.module05;

import ch.qos.logback.classic.Level;
import io.sentry.Sentry;
import pl.training.sentry.module05.CheckoutService.ChecksMode;
import pl.training.sentry.module05.StorefrontClient.BrowserTrace;
import pl.training.sentry.module05.logback.ErrorReporting;
import pl.training.sentry.module05.logback.LogbackSentryConfig;
import pl.training.sentry.module05.logback.LogbackSentryConfig.Thresholds;
import pl.training.sentry.module05.logback.RefundEndpoint;
import pl.training.sentry.module05.logback.RefundEndpoint.MdcFields;
import pl.training.sentry.module05.logback.RefundGateway;
import pl.training.sentry.module05.spring.OrderStatusApplication;
import pl.training.sentry.support.DemoConsole;
import pl.training.sentry.support.DemoScenarios;
import pl.training.sentry.support.TrainingSentry;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Moduł 5: Tracing, logi i Replay jako kontekst diagnozy.
//
// Po co łączyć zdarzenie błędu z trace i logami
//
// Zdarzenie błędu (error event) mówi, co się stało i gdzie wyjątek się ujawnił, ale nie opisuje całego
// wykonania. Tracing, czyli zapis przebiegu operacji w czasie i między usługami, pokazuje, co ile trwało
// i co od czego zależało. Logi opisują stan aplikacji i podjęte decyzje, a Replay (tylko w przeglądarce
// i aplikacji mobilnej) pokazuje, co widział użytkownik. Te dane łączy identyfikator trace (trace_id),
// a w obrębie trace identyfikator pojedynczej operacji (span_id). Wspólny identyfikator daje jednak
// kontekst, a nie dowód przyczyny: log z tego samego trace może opisywać skutek albo niezależne
// zdarzenie. Fakty odczytane z danych oddziela się więc od hipotez, które trzeba jeszcze sprawdzić.
//
// Trace, span i transakcja
//
// Trace to zbiór operacji o tym samym trace_id, także w różnych procesach, usługach i projektach.
// Pojedyncza operacja to span: ma własny identyfikator, identyfikator rodzica (parent span ID), rodzaj
// operacji (op, np. db.query albo http.client), opis, początek, koniec i status. Poprawnie połączony
// trace jest drzewem z jednym korzeniem. W Sentry UI ogląda się go jako waterfall: każdy span to odcinek
// na osi czasu, a wcięcie oznacza relację rodzic-dziecko. Span wejściowy usługi (obsługa żądania HTTP,
// uruchomienie joba) w Java SDK 8.x to transakcja (ITransaction), a operacje w jej wnętrzu to spany
// potomne (ISpan) tworzone przez startChild. Nazwa transakcji ma grupować podobne wykonania, dlatego
// jest szablonem trasy (POST /api/orders), a nie adresem z numerem zamówienia.
//
// Transakcja powstaje na granicy operacji
//
// Transakcję tworzy kod obsługujący wejście do usługi: w Spring Boot filtr startera, w czystej Javie
// własna granica żądania. Job uruchamiany z harmonogramu nie ma przychodzącego żądania, więc tworzy ją
// sam przez Sentry.startTransaction z opcją bindToScope. Transakcja staje się wtedy aktywnym spanem
// scope (kontenera kontekstu SDK), więc ręczne spany, logi i captureException znajdują ją przez
// Sentry.getSpan(). Każde wykonanie dostaje też własne scopes (tu Sentry.forkedRootScopes), żeby tag
// albo atrybut jednego uruchomienia nie przeszedł do następnego.
//
// Bez transakcji zdarzenia i logi nadal mają trace_id, ale pochodzi on z propagation context, czyli
// kontekstu propagacji utworzonego w scope przy Sentry.init. Wszystkie wykonania bez własnego kontekstu
// dostają ten sam identyfikator, więc wspólny trace_id nie oznacza wtedy wspólnego wykonania, a bez
// spanów nie wiadomo, który krok ile trwał. Na granicy żądania Sentry.continueTrace ustawia propagation
// context z nagłówków przychodzących, a bez nich zaczyna nowy trace, nawet gdy tracing jest wyłączony.
//
// Ręczny span, status i jeden właściciel zgłoszenia
//
// Automatyczna instrumentacja mierzy granice bibliotek, ale nie zna kroków biznesowych, np. oceny ryzyka
// czy rezerwacji towaru. Ręczny span dodaje się tam, gdzie operacja może zająć istotny czas albo pomaga
// rozróżnić przyczyny, a nie dla każdej metody. Jest on zawsze dzieckiem aktywnego spanu, a nie nową
// transakcją, która dałaby w trace drugi korzeń. Gdy Sentry.getSpan() zwraca null, kod działa dalej bez
// spanu. Rodzic musi jeszcze trwać: span rozpoczęty po zakończeniu transakcji do niej nie trafi.
//
// Status spanu, powiązany wyjątek i zdarzenie błędu to trzy różne fakty. setStatus opisuje wynik
// operacji (ok, internal_error, deadline_exceeded po przekroczeniu czasu, invalid_argument np. dla
// HTTP 422), a setThrowable wiąże wyjątek ze spanem. Żadne z nich nie tworzy issue: zdarzenie błędu
// wysyła dopiero captureException, wywołane raz, na granicy żądania albo joba. Dzięki setThrowable
// zdarzenie wysłane później wskazuje w trace span, w którym wyjątek powstał, a nie całą transakcję.
//
// Propagacja trace między usługami
//
// Usługa wywołująca przekazuje kontekst w nagłówkach HTTP. Nagłówek sentry-trace niesie trace_id,
// identyfikator spanu wywołania po stronie klienta (client span), który stanie się rodzicem, i decyzję
// o próbkowaniu (końcówka -1 albo -0). Nagłówek baggage przenosi dynamic sampling context, m.in.
// release, environment i nazwę transakcji, a z przeglądarki także identyfikator Replay. Usługa
// odbierająca wywołuje continueTrace przed startTransaction, więc jej transakcja ma ten sam trace_id,
// a jej rodzicem jest client span wywołującego. Tak dwie usługi tworzą jeden waterfall.
//
// Java SDK dodaje nagłówki tylko do adresów pasujących do opcji tracePropagationTargets. Domyślna
// wartość ".*" obejmuje każde instrumentowane żądanie i może wysłać metadane z baggage do obcych
// systemów, więc produkcja zawęża listę do zaufanych usług. Wzorce to zakotwiczone wyrażenia regularne
// dopasowywane do całego adresu, dlatego ta sama usługa wywołana pod innym adresem (np. IP zamiast
// nazwy hosta) nagłówków nie dostanie. Nic nie zgłasza wtedy błędu, a druga usługa zaczyna nowy trace.
//
// Kontekst na innych wątkach
//
// Scopes i aktywny span są związane z wątkiem. Zadanie przekazane do puli wątków (CompletableFuture,
// ExecutorService, @Async) nie widzi transakcji żądania: spany nie powstają, a log dostaje obcy
// trace_id i nie ma użytkownika. Aplikacja odpowiada poprawnie, a jedynym objawem jest luka w waterfall
// albo szeroka transakcja bez dzieci. Kontekst przenosi rozwidlenie scopes: SentryWrapper.wrapSupplier
// kopiuje je w chwili opakowania i aktywuje w wątku roboczym na czas zadania, dlatego zadanie opakowuje
// się na wątku zlecającym. W Spring Boot tę rolę pełni SentryTaskDecorator na ThreadPoolTaskExecutor.
// Przy równoległych gałęziach Sentry.getSpan() zwraca ostatni niezakończony span transakcji, a nie span
// danego wątku, więc rodzica gałęzi pobiera się na wątku zlecającym i przekazuje jawnie.
//
// Jak czytać waterfall
//
// Duration (czas trwania) rodzica obejmuje czas dzieci, a spany równoległe nakładają się, więc suma ich
// czasów może przekroczyć czas odpowiedzi. Self time (czas własny) to duration rodzica minus długość sumy
// przedziałów zajętych przez dzieci. Duży self time oznacza lokalną pracę, oczekiwanie bez spanu albo
// brak instrumentacji, ale nie jest czasem CPU. Critical path (ścieżka krytyczna) to łańcuch zależności
// wyznaczający najwcześniejszy możliwy koniec operacji: skrócenie spanu spoza niej nie skróci żądania,
// a z dwóch równoległych gałęzi koniec wyznacza dłuższa. Waterfall nie zaznacza ścieżki krytycznej:
// wynika ona z zależności w kodzie.
//
// Wynik flagi jako kontekst porównania
//
// Gdy nową ścieżkę kodu włącza się flagą (feature flag) dla części klientów, pierwsze pytanie przy
// błędzie brzmi, czy dotyczy on tylko jednego wariantu. Sentry.addFeatureFlag(nazwa, wynik) zapisuje
// wynik w scope, skąd trafia jako context flags do kolejnych zdarzeń błędu (w issue sekcja Feature
// Flags), oraz w aktywnym spanie jako dana flag.evaluation.<nazwa>, która rozdziela transakcje obu
// wariantów. Flagę sprawdza się więc, gdy transakcja już trwa, i zapisuje także wynik false, bo bez
// niego wariant kontrolny nie ma z czym się porównać. Wynik flagi to fakt, a nie przyczyna: nawet gdy
// wszystkie zdarzenia issue mają true, jest to hipoteza, którą potwierdza dopiero kod nowej ścieżki.
//
// Próbkowanie trace
//
// Próbkowanie (sampling) decyduje, które trace zostaną wysłane. Decyzję podejmuje SDK na początku trace
// w usłudze, która go rozpoczyna (origin), i przekazuje ją w sentry-trace. Kolejne usługi ją dziedziczą,
// więc trace jest zachowany albo odrzucony w całości. W chwili decyzji nie wiadomo jeszcze, czy wystąpi
// błąd, a odrzuconych spanów nie da się odtworzyć. W Java SDK 8.54.0 pierwszeństwo ma jawna decyzja na
// tworzonej transakcji, potem niepusty wynik tracesSampler, potem decyzja rodzica, a bez rodzica
// tracesSampleRate. Bez żadnej z tych opcji tracing jest wyłączony.
//
// Z tej kolejności wynika wzorzec samplera: dla trace z decyzją rodzica zwraca null („bez zdania”),
// a własne stawki stosuje tylko do trace, które usługa zaczyna sama (np. 0.0 dla health checku). Sampler
// losujący mimo decyzji rodzica zachowa fragment trace odrzuconego wyżej, bez rodzica. Próbkowanie
// trace dotyczy tylko transakcji i spanów: błędy i logi są wysyłane niezależnie i zachowują trace_id,
// więc po odrzuceniu trace nadal są w Sentry, tylko bez waterfall. Odrzucone transakcje SDK jedynie
// zlicza w raporcie klienta (client report) z powodem sample_rate, widocznym w statystykach użycia.
//
// Co daje starter Spring Boot
//
// W aplikacji Spring Boot starter sentry-spring-boot-4-starter inicjalizuje SDK i buduje szkielet trace
// bez kodu Sentry w kontrolerze: tworzy transakcję http.server nazwaną szablonem trasy, a wyjątek, który
// opuścił kontroler, zgłasza przez SentryExceptionResolver jako nieobsłużony (handled=false,
// level=fatal). Gdy na classpath jest sentry-logback, podpina też SentryAppender do root loggera
// z progami z właściwości sentry.logging.*. W czystej Javie te kroki wykonuje własna granica żądania.
// Kontekstu na wątki @Async starter nie przenosi: potrzebny jest do tego SentryTaskDecorator.
//
// Structured Logs
//
// Structured Logs (logi strukturalne) to osobna kategoria danych, przeszukiwana w Explore > Logs
// i widoczna przy trace. Włącza je options.getLogs().setEnabled(true), a wpisy wysyła Sentry.logger()
// albo integracja z biblioteką logowania. Wiadomość ma stały szablon z parametrami, a zmienne wartości
// trafiają do typowanych atrybutów; zamiast serii cienkich wpisów lepszy jest jeden wpis z wynikiem
// operacji. Log dostaje użytkownika ze scope i atrybuty z Sentry.setAttribute (np. order.id), ale nie
// tagi scope. Dostaje też trace_id i span_id aktywnego spanu, więc wpis wymagający korelacji powstaje
// wewnątrz transakcji; log sprzed jej startu albo z wątku bez kontekstu dostanie identyfikatory
// z propagation context. Structured Log nie tworzy issue, także przy poziomie ERROR.
//
// Logback: jeden wpis, trzy kanały
//
// Aplikacja zwykle loguje przez SLF4J, a wpisy przekazuje do Sentry SentryAppender z modułu
// sentry-logback. Jeden wpis loggera może trafić do trzech kanałów, każdy z własnym progiem:
// - zdarzenie błędu (minimumEventLevel, domyślnie ERROR) tworzy issue. Ma wiadomość i nazwę loggera,
//   a gdy wpis niesie wyjątek, także łańcuch przyczyn i mechanism=LogbackSentryAppender;
// - breadcrumb (minimumBreadcrumbLevel, domyślnie INFO) to wpis w ograniczonym buforze scope,
//   widoczny tylko w szczegółach kolejnego zdarzenia z tego samego scope;
// - Structured Log (minimumLevel, domyślnie INFO, tylko przy włączonych logach) jest przeszukiwalny,
//   ale nie ma nazwy loggera ani stack trace.
// Poziom samego loggera działa wcześniej: odrzucony przez niego wpis nie dociera ani do konsoli, ani do
// SentryAppender. Progi zapisuje się jawnie w konfiguracji, bo obniżenie progu zdarzenia do WARN
// zamienia każde ponowienie, także udanej operacji, w osobne issue, czyli w szum.
//
// MDC i contextTags
//
// MDC (Mapped Diagnostic Context) to związana z wątkiem mapa pól loggera, np. correlation_id, order_id
// i channel. SentryAppender kopiuje do zdarzenia całe MDC: klucze z opcji contextTags stają się tagami
// zdarzenia i atrybutami mdc.<klucz> logów, a pozostałe trafiają do zdarzenia tylko jako context MDC,
// niezależnie od sendDefaultPii. E-mail klienta włożony do MDC wyjdzie więc z każdym zdarzeniem z logu.
// Do contextTags nadają się tylko klucze o kilku wartościach, bo tag z identyfikatorem zamówienia ma
// przy każdym zdarzeniu nową wartość. MDC ustawia i czyści w finally granica, bo wątek wraca do puli.
//
// Jeden właściciel zgłoszenia i deduplikacja
//
// Jedna awaria powinna skończyć się jednym zdarzeniem. SDK ma deduplikację: odrzuca zdarzenie, jeśli
// jego wyjątek albo któraś z jego przyczyn (cause) była już wysłana w tym procesie. Dlatego log.error
// z wyjątkiem i captureException z tym samym obiektem dają jedno zdarzenie; zostaje to, które przyszło
// pierwsze. Deduplikacja nie pomoże, gdy każda warstwa raportuje po swojemu, np. serwis loguje ERROR
// z samym komunikatem i rzuca nowy wyjątek bez cause. Dla SDK to różne obiekty, więc z jednej awarii
// powstaje kilka issues, każde z fragmentem obrazu. Warstwa, która nie wie, czy błąd jest końcowy,
// opakowuje wyjątek z zachowaniem cause, a jeden wpis ERROR z pełnym łańcuchem zapisuje granica.
//
// Replay
//
// Session Replay zapisuje w przeglądarce stan DOM, jego zmiany i zdarzenia (to nie jest wideo). Backend
// Java niczego nie nagrywa: kontynuuje trace z przeglądarki, zachowuje baggage z identyfikatorem Replay
// i wysyła spany, logi i błędy z tym samym trace_id. Replay potwierdza zachowanie użytkownika
// (kliknięcia, ponowienia, status żądania), ale nie pokazuje wnętrza backendu.
public final class Module05Demo {

    private static final StorefrontClient STOREFRONT = new StorefrontClient();

    private Module05Demo() {
    }

    public static void main(String[] args) throws Exception {
        DemoScenarios scenarios = DemoScenarios.from(args, 1, 10);
        if (scenarios.includesAny(1, 6)) {
            tracingScenarios(scenarios);
        }
        if (scenarios.includes(7)) springBootAutoInstrumentation();
        if (scenarios.includes(8)) logbackChannelsAndThresholds();
        if (scenarios.includes(9)) mdcAsContext();
        if (scenarios.includes(10)) doubleReporting();
    }

    // Scenariusze 1-6: jedna inicjalizacja SDK z tracingiem i dwie usługi HTTP w procesie.
    private static void tracingScenarios(DemoScenarios scenarios) throws Exception {
        try (TrainingSentry.TrainingSession session = TrainingSentry.init("module05", options -> {
            // Tracing: stawka dla trace bez własnej reguły i sampler respektujący decyzję rodzica.
            // Eksperyment: bez obu opcji tracing jest wyłączony. Scenariusze 1-6 nadal działają,
            // ale nie ma transakcji ani spanów, a logi i błędy zostają (osobne kategorie danych).
            // Scenariusze 7-10 mają własną inicjalizację SDK i tych ustawień nie używają.
            options.setTracesSampleRate(1.0);
            // Eksperyment: false w pierwszym argumencie zmienia scenariusz 6: orders-api zachowa
            // swoją część trace odrzuconego przez przeglądarkę (fragment bez rodzica).
            options.setTracesSampler(new CheckoutTracesSampler(true, CheckoutTracesSampler.CHECKOUT_RATES));
            // Nagłówki trace tylko do zaufanych usług: zakotwiczony wzorzec na całym adresie.
            // Domyślna wartość w Java SDK to ".*", czyli każde instrumentowane żądanie.
            options.setTracePropagationTargets(List.of("^http://localhost:[0-9]+/.*$"));
            // Eksperyment: false usuwa z wydruku logi scenariuszy 1-6 (Structured Logs są opt-in).
            options.getLogs().setEnabled(true);
        });
             PaymentsApi payments = new PaymentsApi()) {
            if (scenarios.includes(1)) standaloneJob();
            if (scenarios.includes(2)) propagationBetweenServices(payments);
            if (scenarios.includes(3)) contextOnThreadPool(payments);
            if (scenarios.includes(4)) waterfall(payments);
            if (scenarios.includes(5)) featureFlags(payments);
            if (scenarios.includes(6)) samplingDecidedByOrigin(payments);
        }
    }

    // Scenariusz 1: nocny job, bez transakcji i z transakcją.
    //
    // O co chodzi: zadanie bez przychodzącego requestu nie ma operacji wejściowej, więc nikt nie tworzy
    // mu transakcji. Błąd i logi joba trafiają wtedy do Sentry, ale bez czasu kroków i z trace ID,
    // który nie odpowiada żadnemu wykonaniu.
    //
    // Co pokazujemy: PaymentReconciliationJob uzgadnia płatności dwóch dostawców: provider-a kończy się
    // sukcesem, raport provider-b zwraca HTTP 503. A: runWithoutTransaction, czyli capture i logi bez
    // transakcji. B: run z transakcją payments.reconcile związaną ze scope, spanami db.query
    // i http.client z ChildSpans oraz podsumowaniem uruchomienia jako jeden log z atrybutami.
    //
    // Problem: w A event i wszystkie logi obu uruchomień mają ten sam trace i span, skopiowane
    // z propagation context utworzonego przy Sentry.init. Wspólny trace ID nie oznacza tu wspólnego
    // wykonania, a bez spanów nie wiadomo, który krok ile trwał.
    //
    // Dobra praktyka: jedno uruchomienie joba to jedna transakcja we własnych scopes (forkedRootScopes).
    // Span raportu dostaje setThrowable i status, a jedyny captureException robi granica joba. Log ERROR
    // zostaje logiem i nie tworzy issue.
    //
    // Na co patrzeć: w konsoli w A jeden event i cztery logi z identycznym trace i span, bez linii
    // „↳ transakcja”. W B dwie transakcje payments.reconcile z różnymi trace, które niosą też logi
    // swojego uruchomienia; span raportu provider-b ma status internal_error, a event wskazuje jego span
    // ID, choć capture nastąpił później. W Sentry UI (Explore > Traces) waterfall payments.reconcile
    // z błędem przy spanie raportu i logami tego uruchomienia; trace z A ma event i logi obu uruchomień,
    // bez spanów.
    //
    // Uruchomienie: -Dexec.args=1
    static void standaloneJob() {
        DemoConsole.scenario(1, "Nocny job: bez transakcji i z transakcją",
                "pokazać, co daje własna ITransaction zadaniu bez przychodzącego requestu.");
        PaymentReconciliationJob job = new PaymentReconciliationJob();

        DemoConsole.step("A. Job bez transakcji: provider-a (sukces), provider-b (awaria raportu)");
        job.runWithoutTransaction("provider-a");
        job.runWithoutTransaction("provider-b");
        flushLogs();

        DemoConsole.step("B. Ten sam job z transakcją payments.reconcile: provider-a, provider-b");
        job.run("provider-a");
        job.run("provider-b");
        flushLogs();

        DemoConsole.lookAt("w konsoli w A event i wszystkie logi obu uruchomień mają ten sam trace i span: "
                + "to propagation context z Sentry.init, a nie wykonanie. Brak transakcji, więc nie ma czasu "
                + "kroków. W B każde uruchomienie ma własny trace, który niosą też jego logi, transakcja ma spany "
                + "db.query i http.client, "
                + "span raportu provider-b ma status internal_error, a event wskazuje jego span ID, "
                + "choć capture nastąpił później, na granicy joba. Log ERROR nie utworzył eventu.");
        DemoConsole.lookAt("w Sentry UI (Explore > Traces, transakcja payments.reconcile) waterfall z błędem "
                + "przy spanie raportu i logami tego uruchomienia. Z issue można przejść do tego trace. "
                + "Trace z A zawiera event i logi obu uruchomień, bez spanów.");
    }

    // Scenariusz 2: propagacja trace między orders-api i payments-api.
    //
    // O co chodzi: ciągłość trace między usługami zależy od nagłówków sentry-trace i baggage na granicy
    // HTTP. Java SDK dodaje je tylko do adresów pasujących do tracePropagationTargets; demo zawęża tę
    // listę do ^http://localhost:[0-9]+/.*$ (domyślnie ".*", czyli każde instrumentowane żądanie).
    //
    // Co pokazujemy: checkout w orders-api wywołuje payments-api przez TracingHttpClient (client span
    // i nagłówki przez TracingUtils.traceIfAllowed). payments-api w TracedHttpHandler robi continueTrace
    // i startuje transakcję http.server. A: adres localhost z allowlisty. B: ten sam kod, adres 127.0.0.1.
    //
    // Problem: w B adres nie pasuje do allowlisty, więc nagłówki nie wychodzą i payments-api zaczyna
    // nowy trace bez rodzica. Obie usługi wyglądają poprawnie lokalnie, a połączenie w jeden waterfall
    // zniknęło bez żadnego błędu.
    //
    // Dobra praktyka: allowlista z zakotwiczonymi wzorcami, które obejmują wszystkie zaufane usługi,
    // także pod każdym adresem, pod jakim są skonfigurowane. Po stronie serwera własne scopes requestu,
    // continueTrace przed startTransaction i nazwa transakcji z szablonu trasy.
    //
    // Na co patrzeć: w konsoli w A transakcja POST /authorize ma trace orders-api, a jej parent to span
    // ID spanu http.client w orders-api. W B transakcje obu usług mają różne trace, a POST /authorize nie
    // ma parent. W Sentry UI trace z A to dwie usługi w jednym waterfall (tag service); z B powstają dwa
    // osobne trace, a logi payments-api nie są w trace checkoutu.
    //
    // Uruchomienie: -Dexec.args=2
    static void propagationBetweenServices(PaymentsApi payments) throws Exception {
        DemoConsole.scenario(2, "Propagacja trace między orders-api i payments-api",
                "pokazać, że ciągłość trace zależy od nagłówków na granicy HTTP i od allowlisty propagacji.");

        DemoConsole.step("A. orders-api wywołuje payments-api pod adresem z allowlisty (localhost)");
        try (OrdersApi orders = new OrdersApi(payments.baseUri(), FeatureFlagProvider.allDisabled(), ChecksMode.SEQUENTIAL)) {
            report(STOREFRONT.placeOrder(orders.baseUri(), "ORD-2001", "c-7f3a9c", BrowserTrace.NONE));
        }
        flushLogs();

        DemoConsole.step("B. Ta sama wersja, ale adres payments-api skonfigurowany jako 127.0.0.1");
        try (OrdersApi orders = new OrdersApi(payments.baseUriByIp(), FeatureFlagProvider.allDisabled(), ChecksMode.SEQUENTIAL)) {
            report(STOREFRONT.placeOrder(orders.baseUri(), "ORD-2002", "c-7f3a9c", BrowserTrace.NONE));
        }
        flushLogs();

        DemoConsole.lookAt("w konsoli w A transakcja payments-api ma trace orders-api, a jej parent to span ID "
                + "spanu http.client w orders-api. W B adres nie pasuje do tracePropagationTargets, więc "
                + "orders-api nie wysłało nagłówków: payments-api zaczęło nowy trace bez rodzica. Obie "
                + "strony wyglądają poprawnie lokalnie, a połączenie zniknęło.");
        DemoConsole.lookAt("w Sentry UI trace z A pokazuje dwie usługi w jednym waterfall (tag service). "
                + "Trace z B to dwa osobne trace z jednym korzeniem każdy; logi payments-api nie są w trace checkoutu.");
    }

    // Scenariusz 3: kontekst Sentry na puli wątków.
    //
    // O co chodzi: scopes i aktywny span są związane z wątkiem. Praca przekazana do executora nie widzi
    // transakcji requestu, jeśli kontekstu nie przeniesie się jawnie.
    //
    // Co pokazujemy: CheckoutService uruchamia fraud check i rezerwację w magazynie równolegle na puli
    // wątków. A: CompletableFuture.supplyAsync bez przeniesienia scopes. B: zadania opakowane
    // SentryWrapper.wrapSupplier na wątku requestu, a rodzic spanów pobrany tam i przekazany jawnie.
    //
    // Problem: w A Sentry.getSpan() w wątku puli zwraca null, więc spany sprawdzeń nie powstają,
    // a log o stanie z cache dostaje trace z Sentry.init i nie ma user.id. Kod działa i zwraca 200:
    // jedynym objawem jest luka w waterfall i log w obcym trace.
    //
    // Dobra praktyka: opakować zadanie przed przekazaniem do puli, bo wrapSupplier rozwidla scopes
    // w chwili wywołania. Rodzica równoległych spanów przekazać jawnie: w wątku roboczym Sentry.getSpan()
    // zwraca ostatni niezakończony span transakcji, którym może być span sąsiedniej gałęzi.
    //
    // Na co patrzeć: w konsoli w A transakcja POST /api/orders ma tylko db.query i wywołanie
    // payments-api, a log o cache ma inny trace niż checkout. W B fraud check i GET warehouse /stock
    // startują z tym samym przesunięciem +N ms, a log ma trace checkoutu i user.id. W Sentry UI w A ok.
    // 280 ms bez spanów między db.query a wywołaniem payments-api wygląda na self time orders-api, a log
    // o cache trafia do trace z Sentry.init (przy pełnym uruchomieniu tego samego co w scenariuszu 1A).
    //
    // Uruchomienie: -Dexec.args=3
    static void contextOnThreadPool(PaymentsApi payments) throws Exception {
        DemoConsole.scenario(3, "Kontekst Sentry na puli wątków",
                "pokazać, co ginie, gdy praca przechodzi na executor bez przeniesienia scopes.");

        DemoConsole.step("A. Sprawdzenia na puli wątków bez SentryWrapper");
        try (OrdersApi orders = new OrdersApi(payments.baseUri(), FeatureFlagProvider.allDisabled(), ChecksMode.PARALLEL_WITHOUT_CONTEXT)) {
            report(STOREFRONT.placeOrder(orders.baseUri(), "ORD-3001", "c-7f3a9c", BrowserTrace.NONE));
        }
        flushLogs();

        DemoConsole.step("B. Te same sprawdzenia opakowane SentryWrapper.wrapSupplier, rodzic spanu przekazany jawnie");
        try (OrdersApi orders = new OrdersApi(payments.baseUri(), FeatureFlagProvider.allDisabled(), ChecksMode.PARALLEL_WITH_CONTEXT)) {
            report(STOREFRONT.placeOrder(orders.baseUri(), "ORD-3002", "c-7f3a9c", BrowserTrace.NONE));
        }
        flushLogs();

        DemoConsole.lookAt("w konsoli w A transakcja orders-api nie ma spanów fraud check i GET warehouse /stock, "
                + "a log o stanie z cache ma trace z Sentry.init, inny niż checkout. W B oba spany startują "
                + "w tym samym momencie, a log ma trace checkoutu.");
        DemoConsole.lookAt("w Sentry UI w A między db.query a wywołaniem payments-api jest ok. 280 ms bez spanów: "
                + "80 ms przeliczenia cen i 200 ms sprawdzeń, których spany zginęły. Całość wygląda na self time "
                + "orders-api. Log o cache nie należy do trace checkoutu: trafił do trace ze scope utworzonego przy "
                + "Sentry.init (w pełnym przebiegu tego samego co w scenariuszu 1A, obok logów nocnego joba).");
    }

    // Scenariusz 4: waterfall, sekwencyjnie i równolegle.
    //
    // O co chodzi: waterfall czyta się przez przesunięcia startu, duration, self time i critical path.
    // Self time to duration rodzica minus unia przedziałów dzieci: może oznaczać lokalną pracę albo brak
    // instrumentacji, ale nie jest czasem CPU.
    //
    // Co pokazujemy: ten sam checkout dwa razy. A: fraud check (ok. 150 ms) i rezerwacja w magazynie
    // (ok. 200 ms) jedno po drugim. B: oba sprawdzenia równolegle, w wersji z kontekstem ze scenariusza 3.
    // Przeliczenie cen po db.query celowo nie ma spanu.
    //
    // Problem: typowe błędy odczytu to sumowanie duration spanów jako czasu requestu (w B suma przekracza
    // czas transakcji), branie przerwy bez spanu za czas CPU i skracanie spanu spoza critical path.
    // W B skrócenie fraud check nie skróci checkoutu, bo koniec sprawdzeń wyznacza dłuższa gałąź.
    //
    // Dobra praktyka: niezależne sprawdzenia równolegle, a przy analizie critical path wyznaczony
    // z zależności w kodzie, bo waterfall go nie zaznacza.
    //
    // Na co patrzeć: w konsoli przesunięcie +N ms to start spanu względem transakcji. W A spany
    // sprawdzeń idą po sobie, w B startują razem, a transakcja jest krótsza o czas krótszej gałęzi
    // (ok. 150 ms; przy uruchomieniu samego scenariusza 4 różnica bywa większa, bo pierwsze wywołanie
    // payments-api w procesie trwa dłużej). Przerwa ok. 80 ms po db.query to przeliczenie cen. W Sentry
    // UI w B dłuższa gałąź (magazyn) leży na critical path, a w ogonie checkoutu jest span http.client
    // do payments-api z jego transakcją jako dzieckiem.
    //
    // Uruchomienie: -Dexec.args=4
    static void waterfall(PaymentsApi payments) throws Exception {
        DemoConsole.scenario(4, "Waterfall: sekwencyjnie i równolegle",
                "pokazać, jak czytać duration, przesunięcia startu, self time i critical path.");

        DemoConsole.step("A. Sprawdzenia jedno po drugim");
        try (OrdersApi orders = new OrdersApi(payments.baseUri(), FeatureFlagProvider.allDisabled(), ChecksMode.SEQUENTIAL)) {
            report(STOREFRONT.placeOrder(orders.baseUri(), "ORD-4001", "c-7f3a9c", BrowserTrace.NONE));
        }
        flushLogs();
        DemoConsole.step("B. Sprawdzenia równolegle (wersja z kontekstem ze scenariusza 3)");
        try (OrdersApi orders = new OrdersApi(payments.baseUri(), FeatureFlagProvider.allDisabled(), ChecksMode.PARALLEL_WITH_CONTEXT)) {
            report(STOREFRONT.placeOrder(orders.baseUri(), "ORD-4002", "c-7f3a9c", BrowserTrace.NONE));
        }
        flushLogs();

        DemoConsole.lookAt("w konsoli przesunięcie +N ms to start spanu względem transakcji. W A fraud check "
                + "(ok. 150 ms) i magazyn (ok. 200 ms) idą po sobie, w B startują razem, a transakcja jest "
                + "krótsza o ok. 150 ms (uruchomiona sama, bez scenariusza 2, nawet o ok. 200 ms, bo pierwsze "
                + "wywołanie payments-api w procesie trwa dłużej). W B suma czasów spanów przekracza czas transakcji. Przerwa ok. 80 ms "
                + "po db.query to przeliczenie cen bez spanu: self time, nie czas CPU.");
        DemoConsole.lookAt("w Sentry UI w B koniec sprawdzeń wyznacza dłuższa gałąź (magazyn): to ona leży na "
                + "critical path, więc skrócenie fraud check nie skróci checkoutu. Waterfall nie zaznacza "
                + "critical path, wynika on z zależności w kodzie. W ogonie każdego checkoutu jest span "
                + "http.client do payments-api z jego transakcją jako dzieckiem.");
    }

    // Scenariusz 5: feature flag, nowa ścieżka płatności.
    //
    // O co chodzi: przy rolloucie za flagą pierwsze pytanie brzmi, czy błąd dotyczy tylko jednego
    // wariantu. Sentry.addFeatureFlag zapisuje wynik w scope (context flags kolejnych error eventów)
    // i w aktywnym spanie (dana flag.evaluation.<nazwa>).
    //
    // Co pokazujemy: flaga checkout.new-payment-flow włączona dla dwóch z czterech klientów.
    // CheckoutFlags sprawdza ją na początku requestu, gdy aktywna jest transakcja, i zapisuje także
    // false. Nowa ścieżka (PaymentInstructions.newFlow) przelicza kwotę na grosze drugi raz,
    // payments-api odpowiada 422, a granica orders-api raportuje PaymentFailedException i zwraca 500.
    //
    // Problem: bez wyniku flagi w evencie i w transakcji nie da się porównać wariantów, a bez zapisu
    // false wariant kontrolny nie ma z czym się porównać. Flaga sprawdzona przed startem transakcji
    // byłaby w eventach, ale nie w jej danych.
    //
    // Dobra praktyka: flaga w evencie i w spanie daje hipotezę, a nie dowód. Tu nowa ścieżka zmienia
    // też dostawcę (provider-b), więc przyczynę potwierdza kod newFlow, a nie sama korelacja z flagą.
    //
    // Na co patrzeć: w konsoli dwa eventy PaymentFailedException z contexts
    // flags={checkout.new-payment-flow=true}, a każda transakcja POST /api/orders ma
    // flag.evaluation.checkout.new-payment-flow z wynikiem true albo false. payments-api kończy transakcję
    // ze statusem invalid_argument bez eventu. W Sentry UI sekcja Feature Flags w każdym evencie issue
    // ma true, a w Explore > Traces atrybut flag.evaluation.checkout.new-payment-flow rozdziela warianty.
    //
    // Uruchomienie: -Dexec.args=5
    static void featureFlags(PaymentsApi payments) throws Exception {
        DemoConsole.scenario(5, "Feature flag: nowa ścieżka płatności",
                "pokazać, że wynik flagi w evencie i w spanie pozwala powiązać błędy z wariantem.");

        // Rollout nowej ścieżki: dwóch z czterech klientów.
        FeatureFlagProvider rollout = new FeatureFlagProvider(Map.of(
                CheckoutFlags.NEW_PAYMENT_FLOW, Set.of("c-19bd42", "c-5e21aa")));
        try (OrdersApi orders = new OrdersApi(payments.baseUri(), rollout, ChecksMode.PARALLEL_WITH_CONTEXT)) {
            for (String customer : List.of("c-7f3a9c", "c-19bd42", "c-2c77d0", "c-5e21aa")) {
                DemoConsole.step("Checkout klienta " + customer);
                report(STOREFRONT.placeOrder(orders.baseUri(), "ORD-5-" + customer, customer, BrowserTrace.NONE));
            }
        }
        flushLogs();

        DemoConsole.lookAt("w konsoli błędy PaymentFailedException mają w contexts flags "
                + "checkout.new-payment-flow=true, a linia ↳ transakcja każdej transakcji orders-api ma "
                + "flag.evaluation.checkout.new-payment-flow z wynikiem. payments-api zwraca 422 bez eventu: "
                + "błąd jest w orders-api (kwota przeliczona na grosze dwa razy), a nie w usłudze, która odmówiła.");
        DemoConsole.lookAt("w Sentry UI każdy event issue PaymentFailedException ma w sekcji Feature Flags "
                + "wartość true. W Explore > Traces atrybut flag.evaluation.checkout.new-payment-flow rozdziela "
                + "checkouty obu wariantów. To hipoteza, że błąd dotyczy nowej ścieżki, a nie dowód przyczyny.");
    }

    // Scenariusz 6: sampling, decyzję podejmuje origin trace.
    //
    // O co chodzi: decyzję samplingu podejmuje usługa, która zaczyna trace, a kolejne usługi ją
    // dziedziczą z sentry-trace. Trace jest wtedy zachowany albo odrzucony w całości. Sampling tracingu
    // dotyczy transakcji: błędów i logów nie odrzuca.
    //
    // Co pokazujemy: CheckoutTracesSampler zwraca null, gdy jest decyzja rodzica, a własne stawki
    // (GET /health 0.0, POST /api/orders 1.0) stosuje tylko do trace, które sam zaczyna. A: przeglądarka
    // wysyła sentry-trace z -1, B: z -0, dla tego samego klienta i tego samego błędu nowej ścieżki.
    // C: health check bez nagłówków, więc originem jest orders-api.
    //
    // Problem: sampler, który nadpisuje decyzję rodzica (false w pierwszym argumencie konstruktora
    // w tracingScenarios), zachowa w orders-api fragment trace odrzuconego przez przeglądarkę, bez
    // rodzica. Odrzuconych transakcji nie ma w Traces, SDK tylko je zlicza w client report.
    //
    // Dobra praktyka: sampler respektuje decyzję rodzica i ma reguły tylko dla trace rozpoczynanych
    // przez usługę: niska stawka dla operacji o dużym wolumenie i małej wartości, wysoka dla krytycznego
    // flow.
    //
    // Na co patrzeć: w konsoli w A transakcje obu usług z trace przeglądarki, a parent transakcji
    // orders-api to span przeglądarki. W B nie ma żadnej transakcji, ale są event i logi z trace ID
    // z nagłówka. C nie wysyła nic; client report z odrzuconymi transakcjami dołącza dopiero transport
    // HTTP, tryb offline go nie pokazuje. W Sentry UI trace z B to sam błąd i logi, bez waterfall, korzeń
    // trace z A wskazuje rodzica, którego Sentry nie zna, a odrzucenia z B i C widać tylko w Stats
    // z powodem sample_rate.
    //
    // Uruchomienie: -Dexec.args=6
    static void samplingDecidedByOrigin(PaymentsApi payments) throws Exception {
        DemoConsole.scenario(6, "Sampling: decyzję podejmuje origin trace",
                "pokazać dziedziczenie decyzji samplingu i to, co zostaje po odrzuceniu trace.");

        FeatureFlagProvider rollout = new FeatureFlagProvider(Map.of(
                CheckoutFlags.NEW_PAYMENT_FLOW, Set.of("c-19bd42")));
        try (OrdersApi orders = new OrdersApi(payments.baseUri(), rollout, ChecksMode.PARALLEL_WITH_CONTEXT)) {
            DemoConsole.step("A. Przeglądarka zachowała trace (sentry-trace ...-1), klient z nową ścieżką płatności");
            report(STOREFRONT.placeOrder(orders.baseUri(), "ORD-6001", "c-19bd42", BrowserTrace.SAMPLED));
            DemoConsole.step("B. Przeglądarka odrzuciła trace (sentry-trace ...-0), ten sam klient i ten sam błąd");
            report(STOREFRONT.placeOrder(orders.baseUri(), "ORD-6002", "c-19bd42", BrowserTrace.NOT_SAMPLED));
            DemoConsole.step("C. Health check bez nagłówków: orders-api jest originem, reguła GET /health = 0.0");
            DemoConsole.step("   HTTP " + STOREFRONT.healthCheck(orders.baseUri()));
        }
        flushLogs();

        DemoConsole.lookAt("w konsoli w A obie usługi wysłały transakcje z trace przeglądarki (parent "
                + "transakcji orders-api to span przeglądarki). W B nie ma żadnej transakcji, ale event błędu "
                + "i logi są, z trace ID z nagłówka: sampling tracingu nie dotyczy błędów ani logów. C nie wysyła nic. Odrzucone "
                + "transakcje SDK liczy w client report, który transport HTTP dołącza do kolejnego envelope "
                + "(tryb offline go nie pokazuje).");
        DemoConsole.lookAt("w Sentry UI event z B ma trace ID, ale widok trace pokaże tylko błąd i logi, "
                + "bez waterfall. Korzeń trace z A wskazuje rodzica, którego Sentry nie zna (przeglądarka "
                + "jest tu symulowana). Odrzuconych transakcji z B i C nie ma w Traces: SDK zgłasza je tylko "
                + "liczbowo w client report z powodem sample_rate, widocznym w statystykach użycia (Stats).");
    }

    // Scenariusz 7: Spring Boot, automatyczna transakcja i @Async.
    //
    // O co chodzi: w Spring Boot sentry-spring-boot-4-starter tworzy szkielet trace bez kodu Sentry
    // w kontrolerze: transakcje http.server, raport nieobsłużonych wyjątków i SentryAppender podpięty
    // do root loggera (progi sentry.logging.*). Kontekst na wątki @Async przenosi dopiero
    // SentryTaskDecorator ustawiony na executorze.
    //
    // Co pokazujemy: aplikacja order-status-api z własną inicjalizacją SDK przez starter. Status
    // ORD-7001: child span db.query z kontrolera i punkty z executora z SentryTaskDecorator. Faktura
    // ORD-7001: executor bez dekoratora. Status ORD-7003: rekord z nieznanym statusem LEGACY_HOLD kończy
    // się IllegalArgumentException, który opuszcza kontroler.
    //
    // Problem: executor bez dekoratora działa poprawnie, a jedynym objawem jest transakcja faktury
    // (ponad 120 ms) bez żadnego spanu i log faktury z innym trace. Szeroka transakcja bez dzieci to
    // typowy objaw utraty kontekstu na innym wątku.
    //
    // Dobra praktyka: każdy ThreadPoolTaskExecutor dla @Async z SentryTaskDecorator. Kontroler nie woła
    // captureException, bo nieobsłużony wyjątek raportuje SentryExceptionResolver, a ręczny span dodaje
    // tylko tam, gdzie automatyka nie widzi operacji.
    //
    // Na co patrzeć: w konsoli transakcje http.server nazwane szablonem trasy
    // (GET /api/orders/{orderId}/status). Status ma spany db.query i GET loyalty /points, a log
    // kontrolera „Status zamówienia” jest w konsoli Spring i w Structured Logs z trace requestu.
    // Faktura nie ma spanu, a jej log ma inny trace. ORD-7003 daje event od SentryExceptionResolver
    // (mechanism=Spring7ExceptionResolver) z handled=nie i level=fatal, powiązany ze spanem db.query.
    // W Sentry UI transakcja faktury bez spanów, a log faktury poza jej trace.
    //
    // Uruchomienie: -Dexec.args=7
    static void springBootAutoInstrumentation() throws Exception {
        DemoConsole.scenario(7, "Spring Boot: automatyczna transakcja i @Async",
                "pokazać szkielet trace ze startera, ręczny child span i SentryTaskDecorator.");
        DemoConsole.step("Start aplikacji order-status-api: SDK inicjalizuje sentry-spring-boot-4-starter");

        try (OrderStatusApplication.Running app = OrderStatusApplication.start(options -> {})) {
            HttpClient http = HttpClient.newHttpClient();
            for (String path : List.of("/api/orders/ORD-7001/status", "/api/orders/ORD-7001/invoice",
                    "/api/orders/ORD-7003/status")) {
                DemoConsole.step("GET " + path);
                HttpResponse<String> response = http.send(
                        HttpRequest.newBuilder(app.baseUri().resolve(path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                DemoConsole.step("   HTTP " + response.statusCode()
                        + (response.statusCode() == 200 ? " " + response.body() : ""));
                // SentryTracingFilter kończy transakcję po powrocie z łańcucha filtrów, a Spring MVC
                // wysłał już wtedy odpowiedź, więc klient może ją dostać, zanim transakcja trafi do
                // transportu. Pauza tylko porządkuje wydruk: transakcja pojawia się pod krokiem,
                // którego dotyczy. Na dane w Sentry nie ma wpływu.
                Latency.pause(300);
            }
            flushLogs();
        }

        DemoConsole.lookAt("w konsoli transakcje http.server mają nazwę z szablonu trasy "
                + "(GET /api/orders/{orderId}/status), choć kod ich nie tworzy. Status ma span db.query "
                + "i span punktów z executora z SentryTaskDecorator, log ma ten sam trace. Log SLF4J kontrolera "
                + "(Status zamówienia) jest w konsoli Spring i w Structured Logs, z trace requestu: appender "
                + "podpiął starter (sentry.logging.*). Faktura nie ma spanu, "
                + "a log faktury ma inny trace: executor bez dekoratora. ORD-7003 daje event od "
                + "SentryExceptionResolver (w wydruku mechanism=Spring7ExceptionResolver) z handled=nie, "
                + "powiązany ze spanem db.query.");
        DemoConsole.lookAt("w Sentry UI transakcja faktury trwa ponad 120 ms i nie ma żadnego spanu, który ten "
                + "czas wyjaśnia, a log faktury nie należy do jej trace: szeroka transakcja bez dzieci to "
                + "typowy objaw utraty kontekstu na innym wątku.");
    }

    // Scenariusz 8: Logback, log.error jako event, niższe poziomy jako kontekst.
    //
    // O co chodzi: SentryAppender kieruje jeden wpis SLF4J do trzech kanałów Sentry, każdy z własnym
    // progiem: error event (domyślnie od ERROR), breadcrumb i Structured Log (domyślnie od INFO). Poziom
    // loggera działa przed appenderem, a Structured Log o poziomie ERROR nadal jest tylko logiem.
    //
    // Co pokazujemy: zwrot płatności przez RefundEndpoint, RefundService i RefundGateway. ORD-8001
    // przechodzi od razu, ORD-8002 po jednym ponowieniu, ORD-8003 nie przechodzi w trzech próbach
    // i granica loguje ERROR z wyjątkiem. A: progi z logback-sentry.xml. B: konfiguracja programowa
    // z minimumEventLevel=WARN i ponownie zwrot ORD-8002.
    //
    // Problem: obniżenie progu eventu do WARN zamienia ponowienie udanego zwrotu w osobny event
    // i issue, czyli szum. Przy awarii jak ORD-8003 każde ponowienie dałoby kolejny event.
    //
    // Dobra praktyka: event tylko z wpisu ERROR z wyjątkiem na granicy, a ponowienia jako WARN, czyli
    // breadcrumbs i Structured Logs. Progi zapisane jawnie w konfiguracji, żeby ich zmiana była widoczna
    // w review.
    //
    // Na co patrzeć: w konsoli linie log> to pełny strumień logów, a linie „↳” to, co dostało Sentry.
    // W A jeden event (ORD-8003) z wiadomością i loggerem wpisu ERROR, wyjątkiem z caused by,
    // mechanism=LogbackSentryAppender i breadcrumbs z wpisów INFO i WARN tego requestu; DEBUG jest tylko
    // w konsoli. Structured Logs mają wpisy od INFO bez nazwy loggera i stack trace, z atrybutami
    // mdc.channel i order.id. W B event z level=warning dla ponowienia ORD-8002, choć odpowiedź to 202.
    // W Sentry UI w A jedno issue RefundFailedException, w B dodatkowe issue z wiadomością o ponowieniu.
    //
    // Uruchomienie: -Dexec.args=8
    static void logbackChannelsAndThresholds() {
        DemoConsole.scenario(8, "Logback: log.error jako event, niższe poziomy jako kontekst",
                "pokazać, co SentryAppender robi z wpisami SLF4J przy domyślnych progach i po zmianie jednego z nich.");
        Map<String, Integer> gateway = Map.of("ORD-8002", 1, "ORD-8003", RefundGateway.ALWAYS);

        try (TrainingSentry.TrainingSession session = logbackSession("channel")) {
            DemoConsole.step("A. Konfiguracja z logback-sentry.xml: event od ERROR, breadcrumb i Structured Log od INFO");
            LogbackSentryConfig.fromXml(LogbackSentryConfig.XML);
            RefundEndpoint endpoint = new RefundEndpoint(ErrorReporting.SINGLE_OWNER, MdcFields.TECHNICAL, gateway);
            refund(endpoint, "ORD-8001", "web", "corr-8001");
            refund(endpoint, "ORD-8002", "web", "corr-8002");
            refund(endpoint, "ORD-8003", "web", "corr-8003");
            flushLogs();

            DemoConsole.step("B. Ten sam kod, konfiguracja programowa z minimumEventLevel=WARN");
            LogbackSentryConfig.programmatic(new Thresholds(Level.WARN, Level.INFO, Level.INFO));
            refund(endpoint, "ORD-8002", "web", "corr-8012");
            flushLogs();
        }

        DemoConsole.lookAt("w konsoli linie log> to pełny strumień logów, a pod nimi to, co dostało Sentry. W A jest "
                + "jeden event (ORD-8003): wiadomość i logger z wpisu ERROR, wyjątek z caused by, mechanism "
                + "LogbackSentryAppender, breadcrumbs z wpisów INFO i WARN tego requestu. DEBUG jest tylko w konsoli. "
                + "Structured Logs mają te same wpisy od INFO, ale bez nazwy loggera i bez stack trace, "
                + "z trace eventu i atrybutami mdc.channel oraz order.id. W B ponowienie (WARN) zwrotu ORD-8002 "
                + "jest osobnym eventem, choć zwrot się udał. Przy awarii jak ORD-8003 każde ponowienie dałoby kolejny.");
        DemoConsole.lookAt("w Sentry UI (Issues, filtr training.module:module05) w A jeden issue "
                + "RefundFailedException z breadcrumbs ponowień. W B dodatkowe issue z wiadomością o ponowieniu "
                + "udanego zwrotu: szum. Explore > Logs z filtrem order.id pokazuje wpisy jednego zwrotu.");
    }

    // Scenariusz 9: MDC, kontekst z loggera w evencie i w logach.
    //
    // O co chodzi: SentryAppender kopiuje do eventu całe MDC. Klucze z listy contextTags stają się
    // tagami eventu i atrybutami mdc.* logów, a pozostałe klucze trafiają do contexts MDC eventu,
    // niezależnie od sendDefaultPii.
    //
    // Co pokazujemy: nieudany zwrot z kanału mobile w dwóch konfiguracjach. A: contextTags=[channel],
    // w MDC tylko correlation_id, order_id i channel. B: contextTags=[channel, order_id], a RefundEndpoint
    // dokłada do MDC e-mail klienta. W obu wariantach granica ustawia order.id jako atrybut scope.
    //
    // Problem: order_id jako tag ma nową wartość dla każdego zamówienia, więc jego rozkład w issue niczego
    // nie grupuje. E-mail w MDC wychodzi w contexts MDC z każdym eventem utworzonym z logu, choć
    // sendDefaultPii=false.
    //
    // Dobra praktyka: w contextTags tylko klucze o kilku możliwych wartościach (channel). Identyfikator
    // zamówienia jako atrybut scope: trafia do Structured Logs, a nie do tagów. Żadnych danych osobowych
    // w MDC. MDC ustawia i czyści w finally ta sama granica, bo wątek wraca do puli.
    //
    // Na co patrzeć: w konsoli w A tag channel=mobile i atrybut mdc.channel w logach, a correlation_id
    // i order_id tylko w contexts MDC. W B tag order_id=ORD-9002, w contexts MDC customer_email,
    // a w logach dodatkowo mdc.order_id obok order.id. W Sentry UI rozkład tagu order_id to pojedyncze
    // wystąpienia, a logi zwrotu filtruje się po order.id w Explore > Logs.
    //
    // Uruchomienie: -Dexec.args=9
    static void mdcAsContext() {
        DemoConsole.scenario(9, "MDC: kontekst z loggera w evencie i w logach",
                "pokazać, gdzie SentryAppender umieszcza pola MDC i co zmienia lista contextTags.");
        Map<String, Integer> gateway = Map.of("ORD-9001", RefundGateway.ALWAYS, "ORD-9002", RefundGateway.ALWAYS);

        DemoConsole.step("A. contextTags=[channel], w MDC tylko pola techniczne");
        try (TrainingSentry.TrainingSession session = logbackSession("channel")) {
            LogbackSentryConfig.fromXml(LogbackSentryConfig.XML);
            refund(new RefundEndpoint(ErrorReporting.SINGLE_OWNER, MdcFields.TECHNICAL, gateway),
                    "ORD-9001", "mobile", "corr-9001");
            flushLogs();
        }

        DemoConsole.step("B. contextTags=[channel, order_id], a w MDC dodatkowo e-mail klienta");
        try (TrainingSentry.TrainingSession session = logbackSession("channel", "order_id")) {
            LogbackSentryConfig.fromXml(LogbackSentryConfig.XML);
            refund(new RefundEndpoint(ErrorReporting.SINGLE_OWNER, MdcFields.WITH_CUSTOMER_EMAIL, gateway),
                    "ORD-9002", "mobile", "corr-9002");
            flushLogs();
        }

        DemoConsole.lookAt("w konsoli w A klucz z contextTags (channel) jest tagiem eventu i atrybutem mdc.channel "
                + "logów, a pozostałe klucze MDC (correlation_id, order_id) są tylko w contexts MDC eventu. "
                + "W B order_id stał się tagiem: każde zamówienie to nowa wartość tagu. E-mail klienta jest w "
                + "contexts MDC, choć sendDefaultPii=false, i trafi tam z każdym eventem z logu.");
        DemoConsole.lookAt("w Sentry UI tag order_id dostaje nową wartość z każdym zamówieniem, więc jego rozkład "
                + "w issue to pojedyncze wystąpienia, które niczego nie grupują. Identyfikator zamówienia w logach "
                + "daje atrybut order.id ze scope, bez tagu (Explore > Logs, filtr order.id).");
    }

    // Scenariusz 10: podwójne raportowanie: log.error, captureException i każda warstwa.
    //
    // O co chodzi: jedna awaria powinna mieć jednego właściciela raportu. Deduplikacja SDK działa tylko
    // w procesie i odrzuca tylko ten sam obiekt wyjątku albo wrapper, który ma już wysłany wyjątek
    // w cause; innych raportów tej samej awarii nie rozpozna.
    //
    // Co pokazujemy: trzy wersje RefundEndpoint z ErrorReporting, dla zwrotu, który zawsze kończy się
    // timeoutem bramki. A (LOG_AND_CAPTURE): granica loguje ERROR z wyjątkiem i woła captureException
    // z tym samym obiektem. B (LOG_ON_EVERY_LAYER): bramka, serwis i granica logują ERROR każda po swojemu.
    // C (SINGLE_OWNER): niższe warstwy opakowują wyjątek z cause, ERROR loguje tylko granica.
    //
    // Problem: w A deduplikacja ratuje przed duplikatem, ale drugi raport jest zbędny. W B powstają trzy
    // eventy: wyjątek bramki, sama wiadomość serwisu bez stack trace i nowy wyjątek bez cause. SDK nie
    // ma czego porównać, a w Sentry powstają trzy issues, każde z innym fragmentem obrazu.
    //
    // Dobra praktyka: wersja C. Warstwa, która nie wie, czy błąd jest końcowy, nie loguje ERROR, tylko
    // opakowuje wyjątek z cause; granica loguje jeden ERROR z pełnym łańcuchem.
    //
    // Na co patrzeć: w konsoli w A jeden event z mechanism=LogbackSentryAppender, bo wpis ERROR był
    // pierwszy. W B trzy eventy: GatewayTimeoutException z bramki, wiadomość serwisu z handled=n/d
    // i RefundFailedException bez caused by. W C jeden event z caused by i breadcrumbs ponowień. W Sentry
    // UI z B trzy issues dla jednej awarii.
    //
    // Uruchomienie: -Dexec.args=10
    static void doubleReporting() {
        DemoConsole.scenario(10, "Podwójne raportowanie: log.error, captureException i każda warstwa",
                "pokazać, kiedy deduplikacja SDK ratuje przed duplikatem, a kiedy nie ma czego porównać.");
        Map<String, Integer> gateway = Map.of("ORD-10001", RefundGateway.ALWAYS,
                "ORD-10002", RefundGateway.ALWAYS, "ORD-10003", RefundGateway.ALWAYS);

        try (TrainingSentry.TrainingSession session = logbackSession("channel")) {
            LogbackSentryConfig.fromXml(LogbackSentryConfig.XML);

            DemoConsole.step("A. Granica: log.error(\"...\", e) i Sentry.captureException(e) z tym samym wyjątkiem");
            refund(new RefundEndpoint(ErrorReporting.LOG_AND_CAPTURE, MdcFields.TECHNICAL, gateway),
                    "ORD-10001", "web", "corr-10001");

            DemoConsole.step("B. Każda warstwa loguje błąd po swojemu (bramka, serwis, granica)");
            refund(new RefundEndpoint(ErrorReporting.LOG_ON_EVERY_LAYER, MdcFields.TECHNICAL, gateway),
                    "ORD-10002", "web", "corr-10002");

            DemoConsole.step("C. Po poprawce: niższe warstwy opakowują z cause, ERROR loguje tylko granica");
            refund(new RefundEndpoint(ErrorReporting.SINGLE_OWNER, MdcFields.TECHNICAL, gateway),
                    "ORD-10003", "web", "corr-10003");
            flushLogs();
        }

        DemoConsole.lookAt("w konsoli w A jest jeden event: z wpisu ERROR, bo był pierwszy. captureException z tym "
                + "samym obiektem wyjątku SDK odrzuciło jako duplikat. W B są trzy eventy: GatewayTimeoutException "
                + "z bramki, sama wiadomość z serwisu (bez stack trace) i RefundFailedException bez caused by. "
                + "W C jeden event z pełnym łańcuchem wyjątków i breadcrumbs ponowień.");
        DemoConsole.lookAt("w Sentry UI z B powstają trzy issues dla jednej awarii, każde z innym fragmentem obrazu. "
                + "Deduplikacja SDK działa tylko w procesie i odrzuca tylko ten sam obiekt wyjątku albo wrapper, który ma już wysłany wyjątek w cause.");
    }

    /**
     * Sesja SDK dla scenariuszy z Logback: Structured Logs włączone, tracing wyłączony (event i logi
     * łączy trace z continueTrace w RefundEndpoint).
     *
     * @param contextTags klucze MDC, które SentryAppender zamienia na tagi eventu i atrybuty logów
     */
    private static TrainingSentry.TrainingSession logbackSession(String... contextTags) {
        return TrainingSentry.init("module05", options -> {
            options.getLogs().setEnabled(true);
            for (String key : contextTags) {
                options.addContextTag(key);
            }
        });
    }

    private static void refund(RefundEndpoint endpoint, String orderId, String channel, String correlationId) {
        DemoConsole.step("   POST /api/refunds " + orderId + " (kanał " + channel + ")");
        int status = endpoint.handle(new RefundEndpoint.Request(orderId, channel, correlationId, "anna.nowak@example.com"));
        DemoConsole.step("   HTTP " + status);
    }

    private static void report(StorefrontClient.Result result) {
        DemoConsole.step("   odpowiedź orders-api: HTTP " + result.status() + " " + result.body()
                + (result.browserTraceId() == null ? "" : " | trace przeglądarki " + result.browserTraceId().substring(0, 8)));
    }

    /**
     * Structured Logs SDK wysyła paczkami, a nie od razu. Flush na końcu kroku wypisuje logi pod
     * scenariuszem, którego dotyczą. Aplikacja produkcyjna nie potrzebuje go po każdym requeście,
     * tylko przy zamykaniu procesu.
     */
    private static void flushLogs() {
        Sentry.flush(2_000);
    }
}
