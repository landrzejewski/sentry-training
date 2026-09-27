package pl.training.sentry.module09;

import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.sentry.Sentry;
import io.sentry.SentryOptions;
import pl.training.sentry.module09.CheckoutService.Handoff;
import pl.training.sentry.module09.CheckoutService.Order;
import pl.training.sentry.module09.CheckoutService.Outbound;
import pl.training.sentry.module09.InventoryService.ErrorReporting;
import pl.training.sentry.support.DemoConsole;
import pl.training.sentry.support.DemoScenarios;
import pl.training.sentry.support.TrainingSentry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Moduł 9: Distributed tracing i OpenTelemetry.
//
// Trace i span: zapis jednego żądania w wielu usługach
//
// Zamówienie w tym module przechodzi przez dwie usługi: checkout-api liczy cenę na puli wątków i rezerwuje
// towar w inventory-service przez HTTP. Distributed tracing (śledzenie rozproszone) zapisuje taki przebieg
// jako drzewo operacji. Pojedyncza operacja to span: ma nazwę, czas trwania, status, atrybuty (pary klucz
// i wartość) oraz identyfikator rodzica. Trace to zbiór spanów o wspólnym identyfikatorze (trace ID),
// czyli cały przebieg żądania, a span bez rodzica jest jego korzeniem (root). Spany tworzy tu
// OpenTelemetry (OTel), otwarty standard telemetrii, a Sentry SDK zgłasza błędy powiązane z tymi spanami.
//
// Spójny trace wymaga działania pięciu niezależnych warstw: tworzenia spanów, kontekstu (który span jest w
// danej chwili rodzicem), propagacji (przeniesienia tej relacji do innego procesu), próbkowania (decyzji,
// które trace zapisać) i eksportu (wysłania zakończonych spanów). Każda psuje się inaczej i żadna nie
// gwarantuje następnej: nagłówek w żądaniu nie dowodzi, że serwer go odczytał, bieżący span nie gwarantuje
// eksportu, a błąd widoczny w Sentry nie oznacza zapisanego trace.
//
// SDK, Resource i instrumentation scope
//
// Kod tworzy spany przez API OTel (Tracer, Span, Context, Scope), a SDK dostarcza sampler, procesory,
// eksportery i zamknięcie. SDK ma w procesie jednego właściciela; jeśli instaluje je Java Agent albo
// Starter, aplikacja nie buduje drugiego. Tu każda usługa ma własne SDK (ServiceTelemetry), więc choć obie
// działają w jednym procesie, relację między nimi niosą wyłącznie nagłówki HTTP. Resource opisuje proces,
// a nie żądanie: nazwę usługi (service.name), jej wersję i środowisko. Instrumentation scope to nazwa
// biblioteki, która utworzyła span, np. pl.training.http-client; nie ma związku z klasą Scope, która
// ustawia bieżący kontekst.
//
// Cykl życia spana: start, bieżący kontekst, status i koniec
//
// Każdy etap to osobne wywołanie i żadne nie wykonuje pracy za inne. startSpan() tworzy span, ale nie
// ustawia go jako bieżącego. makeCurrent() ustawia go jako bieżący i zwraca Scope, którego zamknięcie
// przywraca poprzedni kontekst, ale spana nie kończy. end() kończy span, musi zostać wywołane dokładnie
// raz, zwykle w finally, i nie kończy dzieci. Bieżący span ma znaczenie, bo nowy span bez jawnie podanego
// rodzica bierze rodzica z bieżącego kontekstu wątku. Bez makeCurrent() span inventory.stock.decrement
// wywołany wewnątrz rezerwacji nie znajdzie rodzica i zacznie własny trace.
//
// Wynik operacji opisują osobne mechanizmy. recordException() dodaje do spana zdarzenie wyjątku (span
// event), ale statusu nie zmienia. Status ERROR ustawia się przez setStatus, a sukces zwykle zostawia
// status UNSET; atrybut error.type pozwala grupować porażki. Reguły oparte na statusie, np. próbkowanie na
// końcu albo alert na odsetek błędów, uznają span z samym recordException() za udany. W Sentry jest
// podobnie: przy przyjmowaniu danych OTLP zdarzenia spanów są odrzucane, a lokalne self-hosted 26.9.0
// pokazuje UNSET jako ok, więc taka awaria wygląda jak sukces. Nazwa spana opisuje klasę operacji
// (inventory.reserve), a SKU czy identyfikator zamówienia trafiają do atrybutów: w nazwie dałyby tyle
// nazw, ile produktów, i grupowanie przestałoby działać.
//
// Propagacja między usługami: traceparent, baggage i sentry-trace
//
// Kontekst nie przechodzi przez sieć sam. SpanKind opisuje rolę spana na granicy: CLIENT to wywołanie
// innego systemu, SERVER obsługa żądania przychodzącego, INTERNAL operacja wewnątrz procesu. Na poprawnej
// granicy HTTP klient tworzy span CLIENT, ustawia go jako bieżący i dopiero wtedy propagator wykonuje
// inject, czyli zapisuje kontekst w nagłówkach. Serwer przed utworzeniem spana SERVER wykonuje extract,
// czyli odczytuje kontekst z nagłówków, zaczynając od Context.root(), bo rodzicem może być tylko to, co
// przyszło w żądaniu. Gdy klient pominie inject, serwer zacznie nowy trace, a trace klienta będzie
// wyglądał na kompletny.
//
// OTel opiera się na standardzie W3C. Nagłówek traceparent niesie wersję, trace ID, identyfikator spana
// rodzica i flagi: flaga sampled (01) mówi odbiorcy, że nadawca zapisuje ten trace, a wartość 03 w wydruku
// dema oznacza dodatkowo losowy trace ID. Nagłówek baggage niesie pary klucz i wartość niezależne od
// spanów, np. request.channel=mobile. Propagator sentry z sentry-opentelemetry-otlp dodaje nagłówek
// sentry-trace (trace ID, span ID i flaga próbkowania 1 albo 0) dla usług z Sentry SDK, ale nie zastępuje
// traceparent. Propagatory składa się w jednym miejscu konfiguracji, a traceparent nie skleja się ręcznie:
// ręczny nagłówek łatwo wskazuje złego rodzica, wpisuje na sztywno flagę sampled i pomija baggage oraz
// sentry-trace. Nagłówki przychodzą z zewnątrz, więc trace ID jest wartością do korelacji, a nie
// poświadczeniem. Baggage nie staje się samo atrybutem spana: serwer kopiuje do atrybutów tylko klucze z
// allowlisty (listy dozwolonych kluczy), tu request.channel. Baggage widzi każda usługa po drodze, dlatego
// nie przenosi się w nim tokenów, sekretów ani danych osobowych.
//
// Kontekst na puli wątków
//
// Bieżący kontekst (Context) jest niemutowalny i związany z wątkiem. Zadanie wysłane na pulę, np. liczenie
// ceny w checkout-api, wykonuje się na innym wątku, nie widzi spana żądania i zaczyna nowy trace. Kontekst
// trzeba przechwycić w wątku zlecającym, w chwili zlecenia: Context.current().wrap(...) dla jednego
// zadania albo Context.taskWrapping(...) dla całej puli. Context.current() wywołane dopiero wewnątrz
// zadania zwróci kontekst wątku puli.
//
// Groźniejsza pułapka to Scope, który nigdy się nie zamyka. Wątki puli żyją dłużej niż zadanie, więc span
// ustawiony jako bieżący bez try-with-resources zostaje na wątku, a następne zadanie bez kontekstu
// podczepi się pod trace innego żądania, czyli innego klienta. To gorsze niż nowy korzeń, bo taki trace
// wygląda wiarygodnie. Dlatego makeCurrent() zawsze stoi w try-with-resources, a Scope zamyka się na tym
// samym wątku, na którym go otwarto.
//
// Próbkowanie: head sampling i ParentBased
//
// Head sampling (próbkowanie na początku) to decyzja samplera przy tworzeniu spana. Sampler zna wtedy
// nazwę, rodzaj, rodzica i atrybuty ustawione przed startSpan(), ale nie zna przyszłego wyjątku ani czasu
// trwania. Tail sampling (próbkowanie na końcu, zwykle w OpenTelemetry Collector) widzi status i czas
// całego trace, ale dostaje tylko to, co przepuścił head sampling: odrzuconych spanów nie odzyska, a
// reguła zachowująca trace z błędami działa tylko przy wiarygodnym statusie ERROR.
//
// TraceIdRatioBased(p) zapisuje ułamek p trace i liczy decyzję z trace ID, więc co przepuszcza proporcja
// 0.1, przepuszcza też 0.5. ParentBased opakowuje taki sampler: gdy span ma rodzica, także zdalnego z
// nagłówka, przejmuje jego decyzję, a proporcję stosuje tylko dla lokalnych korzeni. Domyślny sampler SDK
// to ParentBased(AlwaysOn). Skoro odbiorca przejmuje decyzję z nagłówka, ręcznie wpisana flaga 01 wymusza
// u niego zapis niezależnie od decyzji nadawcy. Gdy każda usługa ma sam TraceIdRatioBased, każda decyduje
// od nowa: przy niższej proporcji w usłudze wywoływanej zapisane trace nie mają jej spanów, a przy wyższej
// powstają jej fragmenty bez rodzica. Dlatego ParentBased stosuje się w każdej usłudze.
//
// Eksport: procesory, OTLP i zamknięcie SDK
//
// Zakończony span, który sampler wybrał do zapisu, trafia do procesora, a ten przekazuje go eksporterowi.
// SimpleSpanProcessor eksportuje każdy span od razu, więc koszt eksportu obciąża samą operację; nadaje się
// do testów. BatchSpanProcessor, właściwy dla produkcji, trzyma spany w ograniczonej kolejce i wysyła je w
// tle paczkami, domyślnie z opóźnieniem 5 sekund. Ręcznie zbudowane SDK nie rejestruje shutdown hooka, a
// wątek eksportu jest daemonem, więc proces zakończony bez zamknięcia SDK (shutdown) gubi wszystko z
// kolejki: w zadaniu wsadowym krótszym niż 5 sekund cały trace. Shutdown opróżnia kolejkę i zamyka
// eksportery. forceFlush() wysyła tylko spany zakończone, trwających nie kończy; ma sens na końcu zadania
// albo przed zamrożeniem procesu (np. w serverless), a wywoływany po każdym żądaniu rozbija eksport na
// drobne paczki.
//
// Do Sentry spany trafiają przez OTLP, protokół eksportu OTel, w wariancie HTTP z protobuf, na endpoint
// projektu /api/<id projektu>/integration/otlp/v1/traces z nagłówkiem x-sentry-auth zawierającym publiczny
// klucz DSN (sentry_key), a nie token API. Demo wylicza oba z DSN; w produkcji kopiuje się je z ustawień
// projektu (Client Keys (DSN)). Przyjmowanie OTLP ma w Sentry status open beta.
//
// Lekki wariant OTLP: błąd Sentry w trace OpenTelemetry
//
// Sentry i OTel łączy się na kilka sposobów, a w każdym spany mają jednego właściciela. W lekkim wariancie
// OTLP spany tworzy, próbkuje i eksportuje OTel, a Sentry SDK zgłasza błędy, które trafiają do Error
// Issues, czyli issues grupujących zdarzenia błędów. Sentry nie prowadzi wtedy własnego tracingu
// (tracesSampleRate zostaje nieustawione), żeby nie powstał drugi graf spanów z drugą polityką
// próbkowania.
//
// Zdarzenie Sentry łączy ze spanem procesor zdarzeń OpenTelemetryOtlpEventProcessor. W wersji 8.54.0
// integracja nie rejestruje go sama; w module rejestruje go SentryOtlp.configure na tej samej instancji
// SentryOptions, która trafia do inicjalizacji SDK. W chwili tworzenia zdarzenia procesor odczytuje
// Span.current() i, jeśli kontekst jest poprawny (nawet gdy sampler go odrzucił), wpisuje do zdarzenia
// trace ID i span ID tego spana. Bez bieżącego spana zdarzenie zachowuje trace ID ze scope Sentry,
// niezwiązany z trace OTel, i w Sentry UI błąd nie pojawi się w trace żądania. O korelacji decyduje span
// bieżący w chwili zgłoszenia, a nie zbieżność w czasie.
//
// Globalna obsługa błędów, np. ExceptionHandler we frameworku, działa zwykle już po zamknięciu Scope spana
// SERVER, a blok catch przy try-with-resources ze Scope wykonuje się po zamknięciu zasobu.
// Sentry.captureException musi więc stać w wewnętrznym try, w zasięgu Scope. span.recordException() nie
// tworzy Error Issue: zdarzenie wyjątku w spanie i zgłoszenie do Sentry to osobne sygnały, tak jak scope
// Sentry i Context OTel to dwa niezależne mechanizmy, z których żaden nie przenosi drugiego.
//
// Jeden właściciel granicy i czytanie niepełnego trace
//
// Każdą granicę powinien instrumentować jeden mechanizm. W produkcji instrumentację bibliotek, np. klienta
// HTTP, dostarcza Java Agent albo Starter. Jeśli kod aplikacji otworzy obok własny span CLIENT, jedno
// żądanie da dwa zagnieżdżone spany o tej samej nazwie i prawie tym samym czasie: podwójny koszt i
// zawyżoną liczbę wywołań w agregacjach. Odróżnia je instrumentation scope (w Sentry UI atrybut
// instrumentation.name). Naprawą jest jeden właściciel, a nie filtr po eksporcie.
//
// Brak usługi w trace nie dowodzi, że nie brała udziału w żądaniu. Przyczyną może być propagacja,
// instrumentacja, próbkowanie albo eksport, a objawy są różne: dwa trace zamiast jednego, span pod złym
// rodzicem, trace bez spanów usługi wywoływanej albo fragment, czyli span, którego rodzica nie
// wyeksportowano. Niepełny trace to objaw do wyjaśnienia, a nie wynik diagnozy.
public final class Module09Demo {

    /** Domyślny sampler SDK, zapisany jawnie: root zawsze, dziecko według decyzji rodzica. */
    private static final Sampler PARENT_BASED_ALWAYS_ON = Sampler.parentBased(Sampler.alwaysOn());

    private static final TraceConsole CONSOLE = new TraceConsole(System.out);

    private static boolean online;

    private Module09Demo() {
    }

    public static void main(String[] args) throws Exception {
        DemoScenarios scenarios = DemoScenarios.from(args, 1, 7);
        // SentryOtlp::configure rejestruje procesor, który wiąże eventy Sentry z bieżącym spanem
        // OTel. tracesSampleRate zostaje nieustawione: spany tworzy wyłącznie OTel.
        try (TrainingSentry.TrainingSession session = TrainingSentry.init("module09", SentryOtlp::configure)) {
            online = session.online();
            if (online) {
                System.out.println("OTLP: spany trafiają też do " + SentryOtlp.tracesEndpoint(TrainingSentry.dsn()));
            }
            if (scenarios.includes(1)) spanLifecycle();
            if (scenarios.includes(2)) propagationBetweenServices();
            if (scenarios.includes(3)) contextAcrossExecutor();
            if (scenarios.includes(4)) headSampling();
            if (scenarios.includes(5)) errorLinkedToSpan();
            if (scenarios.includes(6)) duplicatedBoundary();
            if (scenarios.includes(7)) shortLivedProcess();
        }
    }

    // Scenariusz 1: cykl życia spana.
    //
    // O co chodzi: startSpan, makeCurrent, recordException, setStatus i end to osobne kroki i żaden nie
    // robi pracy za inny. startSpan() nie ustawia spana jako bieżącego, zamknięcie Scope nie kończy
    // spana, a recordException() nie ustawia statusu ERROR.
    //
    // Co pokazujemy: StockReservation w inventory-service rezerwuje SKU-1 (sukces) i SKU-LEGACY (system
    // magazynu nie odpowiada). A: reserve, wersja docelowa. B: reserveCarelessly, span bez makeCurrent()
    // i sam recordException() bez statusu.
    //
    // Problem: bez makeCurrent() span inventory.stock.decrement nie widzi rodzica i zaczyna własny
    // trace. Sam recordException() zostawia status UNSET, więc dla reguł opartych na statusie (tail
    // sampling, alerty na odsetek błędów) awaria wygląda jak sukces.
    //
    // Dobra praktyka: makeCurrent() w try-with-resources, przy awarii recordException(), atrybut
    // error.type i setStatus(ERROR), end() dokładnie raz w finally. SKU w atrybucie, nie w nazwie spana.
    //
    // Na co patrzeć: w konsoli A to dwa trace po dwa spany, a awaria ma status=ERROR, error.type
    // i zdarzenie wyjątku; B to cztery trace po jednym spanie, a awaria ma samo zdarzenie wyjątku.
    // W Sentry UI (Explore > Traces, filtr resource.training.module:module09) awaria z B wygląda jak
    // sukces: lokalne self-hosted 26.9.0 pokazuje UNSET jako ok, a ingestia OTLP odrzuca zdarzenia spanów.
    //
    // Uruchomienie: -Dexec.args=1
    static void spanLifecycle() {
        DemoConsole.scenario(1, "Cykl życia spana",
                "pokazać, że startSpan, makeCurrent, recordException, setStatus i end robią każde co innego.");

        try (ServiceTelemetry inventory = telemetry("inventory-service", PARENT_BASED_ALWAYS_ON)) {
            StockReservation reservation = new StockReservation(inventory.tracer(StockReservation.INSTRUMENTATION_SCOPE));

            DemoConsole.step("A. Wersja docelowa: rezerwacja SKU-1, potem SKU-LEGACY z awarią magazynu");
            reservation.reserve("SKU-1", 1);
            ignoreFailure(() -> reservation.reserve("SKU-LEGACY", 1));
            CONSOLE.print();

            DemoConsole.step("B. Typowe błędy: span bez makeCurrent() i sam recordException() bez statusu");
            reservation.reserveCarelessly("SKU-1", 1);
            ignoreFailure(() -> reservation.reserveCarelessly("SKU-LEGACY", 1));
            CONSOLE.print();
        }

        DemoConsole.lookAt("w konsoli wariant A daje dwa trace po dwa spany, a awaria ma status=ERROR, error.type "
                + "i zdarzenie wyjątku. W wariancie B każdy span jest osobnym trace, bo decrement nie widzi "
                + "rodzica, a awaria ma tylko zdarzenie wyjątku, bez statusu.");
        DemoConsole.lookAt("w Sentry UI (Explore > Traces, filtr resource.training.module:module09) awaria "
                + "z wariantu B wygląda jak sukces: lokalne self-hosted 26.9.0 pokazuje status UNSET jako "
                + "span.status ok, a zdarzenia wyjątku nie ma, bo ingestia OTLP odrzuca zdarzenia spanów.");
    }

    // Scenariusz 2: propagacja W3C między dwiema usługami HTTP.
    //
    // O co chodzi: usługi mają osobne SDK OTel i rozmawiają przez prawdziwe HTTP, więc relację między
    // nimi niosą wyłącznie nagłówki. Wspólny trace wymaga spana CLIENT jako bieżącego i inject po
    // stronie klienta oraz extract przed utworzeniem spana SERVER po stronie serwera.
    //
    // Co pokazujemy: trzy zamówienia checkout-api. A: TracedHttpClient (span CLIENT, inject propagatorów
    // W3C Trace Context, Baggage i sentry). B: własny span CLIENT i zwykły HttpClient bez inject.
    // C: ręcznie sklejony traceparent. Pod każdym wariantem nagłówki, które odebrał inventory-service.
    //
    // Problem: w B serwer nie dostaje nagłówków i zaczyna nowy trace, a trace checkout-api wygląda
    // kompletnie. W C parent span ID pochodzi ze spana requestu (spana CLIENT nie ma), flaga 01 wymusza
    // próbkowanie u odbiorcy z ParentBased, a Baggage i sentry-trace nie jadą wcale.
    //
    // Dobra praktyka: instrumentowany klient jako jedyny właściciel granicy i propagator zamiast ręcznego
    // składania nagłówka. Serwer robi extract z Context.root() i kopiuje z Baggage do atrybutów tylko
    // klucze z allowlisty (request.channel).
    //
    // Na co patrzeć: w konsoli A to jeden trace, SERVER inventory-service pod CLIENT checkout-api
    // z request.channel=mobile, traceparent z flagami 03, sentry-trace z flagą 1. B: brak nagłówków
    // propagacji i dwa trace. C: jeden trace, ale SERVER inventory-service wisi pod POST /checkouts
    // i nie ma request.channel. W Sentry UI trace ID z checkout-api w B nie pokaże inventory-service.
    //
    // Uruchomienie: -Dexec.args=2
    static void propagationBetweenServices() {
        DemoConsole.scenario(2, "Propagacja W3C między dwiema usługami HTTP",
                "pokazać, że wspólny trace daje dopiero inject po stronie klienta i extract po stronie serwera.");

        try (ServiceTelemetry checkoutTelemetry = telemetry("checkout-api", PARENT_BASED_ALWAYS_ON);
             ServiceTelemetry inventoryTelemetry = telemetry("inventory-service", PARENT_BASED_ALWAYS_ON);
             InventoryService inventory = InventoryService.start(inventoryTelemetry, ErrorReporting.INSIDE_SPAN);
             ExecutorService pricingPool = Executors.newSingleThreadExecutor();
             CheckoutService checkout = new CheckoutService(checkoutTelemetry, inventory, pricingPool)) {

            DemoConsole.step("A. Instrumentowany klient: span CLIENT jako bieżący, inject, extract na serwerze");
            checkout.submit(new Order("ORD-2001", "SKU-1", "mobile"));
            printHeaders(inventory);
            CONSOLE.print();

            DemoConsole.step("B. Własny span CLIENT bez inject (nowy kod ze zwykłym HttpClient)");
            checkout.submit(new Order("ORD-2002", "SKU-1", "web"), Outbound.NOT_PROPAGATED);
            printHeaders(inventory);
            CONSOLE.print();

            DemoConsole.step("C. Ręcznie sklejony traceparent zamiast propagatora");
            checkout.submit(new Order("ORD-2003", "SKU-1", "web"), Outbound.MANUAL_TRACEPARENT);
            printHeaders(inventory);
            CONSOLE.print();
        }

        DemoConsole.lookAt("w konsoli A to jeden trace: SERVER inventory-service jest dzieckiem CLIENT checkout-api "
                + "i ma request.channel z Baggage; traceparent kończy się flagami 03 (sampled i losowy trace ID), "
                + "sentry-trace flagą 1. B to dwa trace, a trace checkout-api wygląda kompletnie. W C jeden trace, "
                + "ale SERVER inventory-service wisi pod POST /checkouts, bo spana CLIENT nie ma, flaga 01 jest "
                + "wpisana na sztywno, a Baggage nie dotarło.");
        DemoConsole.lookAt("w Sentry UI trace z wariantu B to dwa niezależne trace; wyszukanie po trace ID z "
                + "checkout-api nie pokaże inventory-service.");
    }

    // Scenariusz 3: kontekst przez executor.
    //
    // O co chodzi: bieżący kontekst OTel jest związany z wątkiem, więc zadanie na puli nie widzi spana
    // requestu. Kontekst trzeba przechwycić w wątku zlecającym, w chwili zlecenia zadania, a Scope
    // zamknąć na tym samym wątku, na którym go otwarto.
    //
    // Co pokazujemy: checkout-api liczy cenę (span checkout.price.calculate) na jednowątkowej puli.
    // A: zadanie bez kontekstu. B: zadanie opakowane Context.current().wrap(...). C: ORD-3003 ustawia
    // swój span jako bieżący i nie zamyka Scope, potem ORD-3004 zleca zadanie bez kontekstu.
    //
    // Problem: w A span ceny zaczyna nowy trace. W C span ceny ORD-3004 trafia do trace ORD-3003, bo
    // niezamknięty Scope zostawił na wątku puli cudzy kontekst; to gorsze niż nowy root, bo wygląda
    // wiarygodnie. W produkcji pula ma wiele wątków, więc taki wyciek pojawia się losowo.
    //
    // Dobra praktyka: Context.current().wrap(...) przy zleceniu zadania albo raz dla całej puli
    // Context.taskWrapping(executorService); makeCurrent() zawsze w try-with-resources.
    //
    // Na co patrzeć: w konsoli A to trace requestu bez spana ceny i osobny trace z samym
    // checkout.price.calculate, B jeden trace. W C span z checkout.order_id=ORD-3004 wisi pod spanem ceny
    // ORD-3003, a trace ORD-3004 nie ma spana ceny. W Sentry UI praca jednego klienta w trace innego.
    //
    // Uruchomienie: -Dexec.args=3
    static void contextAcrossExecutor() {
        DemoConsole.scenario(3, "Kontekst przez executor",
                "pokazać, że kontekst trzeba przechwycić przy zleceniu zadania i że niezamknięty Scope przecieka.");

        // Pula z jednym wątkiem gwarantuje, że kolejne zadania trafią na ten sam wątek.
        // PRODUKCJA: pula ma wiele wątków, więc wyciek z wariantu C pojawia się losowo.
        try (ServiceTelemetry checkoutTelemetry = telemetry("checkout-api", PARENT_BASED_ALWAYS_ON);
             ServiceTelemetry inventoryTelemetry = telemetry("inventory-service", PARENT_BASED_ALWAYS_ON);
             InventoryService inventory = InventoryService.start(inventoryTelemetry, ErrorReporting.INSIDE_SPAN);
             ExecutorService pricingPool = Executors.newSingleThreadExecutor();
             CheckoutService checkout = new CheckoutService(checkoutTelemetry, inventory, pricingPool)) {

            DemoConsole.step("A. Zadanie liczenia ceny bez kontekstu");
            checkout.submit(new Order("ORD-3001", "SKU-1", "web"), Handoff.PLAIN);
            CONSOLE.print();

            DemoConsole.step("B. Zadanie opakowane Context.current().wrap(...) w wątku requestu");
            checkout.submit(new Order("ORD-3002", "SKU-1", "web"), Handoff.CONTEXT_WRAPPED);
            CONSOLE.print();

            DemoConsole.step("C. ORD-3003 zostawia na wątku puli otwarty Scope, ORD-3004 zleca zadanie bez kontekstu");
            checkout.submit(new Order("ORD-3003", "SKU-1", "web"), Handoff.LEAKING_SCOPE);
            checkout.submit(new Order("ORD-3004", "SKU-1", "web"), Handoff.PLAIN);
            CONSOLE.print();
        }

        DemoConsole.lookAt("w konsoli A daje osobny trace z samym checkout.price.calculate, B jeden trace. W C span "
                + "ceny ORD-3004 (checkout.order_id=ORD-3004) siedzi w trace ORD-3003, pod jego spanem ceny, "
                + "a trace ORD-3004 nie ma spana ceny wcale.");
        DemoConsole.lookAt("w Sentry UI wyciek dokleja pracę jednego klienta do trace innego klienta; "
                + "to gorsze niż nowy root, bo wygląda wiarygodnie.");
    }

    // Scenariusz 4: head sampling w dwóch usługach.
    //
    // O co chodzi: head sampler decyduje przy tworzeniu spana, więc to, co odrzuci, jest stracone także
    // dla tail samplingu. ParentBased zachowuje decyzję rodzica i stosuje proporcję tylko dla lokalnych
    // rootów, a sam TraceIdRatioBased w każdej usłudze decyduje od nowa i może rozcinać trace.
    //
    // Co pokazujemy: dwie serie po 20 requestów, checkout-api zawsze z ParentBased(TraceIdRatioBased(0.5)).
    // inventory-service w A z TraceIdRatioBased(0.1), w B z ParentBased(TraceIdRatioBased(0.1)).
    // Pod serią podsumowanie: zapisane trace, pełne, bez inventory-service i fragmenty bez rodzica.
    //
    // Problem: w A inventory-service zapisuje tylko część requestów zapisanych przez checkout-api, więc
    // trace wygląda, jakby usługa nie brała udziału. Fragmentów bez rodzica nie ma, bo TraceIdRatioBased
    // liczy decyzję z trace ID: co przepuszcza 0.1, przepuszcza też 0.5. Wyższa proporcja w usłudze
    // podrzędnej dałaby odwrotnie fragmenty inventory-service bez rodzica.
    //
    // Dobra praktyka: ParentBased w każdej usłudze, jak w wariancie B. Niepełny trace to objaw
    // (propagacja, instrumentacja, sampling, eksport), a nie dowód, że usługa nie była wywołana.
    //
    // Na co patrzeć: w konsoli w A większość zapisanych trace nie ma inventory-service, w B każdy
    // zapisany trace jest pełny, w obu 0 fragmentów bez rodzica. Liczby zmieniają się między
    // uruchomieniami, bo trace ID są losowe. W Sentry UI trace z A wygląda jak request bez inventory-service.
    //
    // Uruchomienie: -Dexec.args=4
    static void headSampling() {
        DemoConsole.scenario(4, "Head sampling w dwóch usługach",
                "pokazać, że bez ParentBased każda usługa decyduje sama i kompletne trace stają się rzadkością.");

        int requests = 20;
        DemoConsole.step("A. checkout-api ParentBased(TraceIdRatioBased(0.5)), inventory-service TraceIdRatioBased(0.1)");
        runSampling(Sampler.parentBased(Sampler.traceIdRatioBased(0.5)), Sampler.traceIdRatioBased(0.1), requests, 4100);

        DemoConsole.step("B. inventory-service ParentBased(TraceIdRatioBased(0.1)): proporcja tylko dla własnych rootów");
        runSampling(Sampler.parentBased(Sampler.traceIdRatioBased(0.5)),
                Sampler.parentBased(Sampler.traceIdRatioBased(0.1)), requests, 4200);

        DemoConsole.lookAt("w konsoli A większość zapisanych trace nie ma inventory-service, choć każdy request go "
                + "wywołał. W B każdy zapisany trace jest pełny. Liczby zmieniają się między uruchomieniami, bo "
                + "trace ID są losowe. Fragmentów bez rodzica w A nie ma: TraceIdRatioBased liczy decyzję z trace "
                + "ID, więc co przepuszcza 0.1, przepuszcza też 0.5. Wyższa proporcja w inventory-service dałaby "
                + "odwrotny obraz: fragmenty inventory-service bez rodzica.");
        DemoConsole.lookAt("w Sentry UI trace z A wygląda, jakby inventory-service nie brał udziału w requeście. "
                + "Niepełny trace nie dowodzi, że usługa nie była wywołana.");
    }

    // Scenariusz 5: błąd Sentry w trace OpenTelemetry.
    //
    // O co chodzi: w lekkim wariancie OTLP spany tworzy OTel, a błędy raportuje Sentry SDK.
    // OpenTelemetryOtlpEventProcessor (rejestruje go SentryOtlp.configure, integracja nie robi tego sama)
    // wpisuje do eventu trace ID i span ID spana bieżącego w chwili capture, a bez niego event zostaje
    // z trace ID ze scope Sentry.
    //
    // Co pokazujemy: zamówienie SKU-LEGACY, czyli awaria systemu magazynu w inventory-service.
    // A: captureException wewnątrz spana SERVER. B: wyjątek wychodzi do globalnej obsługi błędów
    // w InventoryService.handle, gdzie Scope spana jest już zamknięty.
    //
    // Problem: w B event ma trace ID niezwiązany z requestem, choć span SERVER ma status=ERROR, więc
    // Error Issue i trace są rozłączne. Ten sam efekt daje captureException w catch przy try-with-resources
    // ze Scope: catch wykonuje się już po zamknięciu Scope.
    //
    // Dobra praktyka: captureException w wewnętrznym try, w zasięgu Scope spana. span.recordException()
    // nie zastępuje Sentry.captureException(): Error Issue wysyła osobno Sentry SDK.
    //
    // Na co patrzeć: w konsoli w A linia trace eventu ma te same 8 znaków co drzewo spanów, w B inne.
    // W Sentry UI event z A jest w widoku trace przy spanie http.server POST /inventory/reservations,
    // a event z B wskazuje trace bez żadnego spana.
    //
    // Uruchomienie: -Dexec.args=5
    static void errorLinkedToSpan() {
        DemoConsole.scenario(5, "Błąd Sentry w trace OpenTelemetry",
                "pokazać, że event Sentry trafia do trace OTel tylko wtedy, gdy span jest bieżący przy capture.");

        for (ErrorReporting reporting : ErrorReporting.values()) {
            try (ServiceTelemetry checkoutTelemetry = telemetry("checkout-api", PARENT_BASED_ALWAYS_ON);
                 ServiceTelemetry inventoryTelemetry = telemetry("inventory-service", PARENT_BASED_ALWAYS_ON);
                 InventoryService inventory = InventoryService.start(inventoryTelemetry, reporting);
                 ExecutorService pricingPool = Executors.newSingleThreadExecutor();
                 CheckoutService checkout = new CheckoutService(checkoutTelemetry, inventory, pricingPool)) {
                DemoConsole.step(reporting == ErrorReporting.INSIDE_SPAN
                        ? "A. captureException wewnątrz spana SERVER inventory-service (awaria SKU-LEGACY)"
                        : "B. captureException w globalnej obsłudze błędów, po zamknięciu Scope spana");
                checkout.submit(new Order(reporting == ErrorReporting.INSIDE_SPAN ? "ORD-5001" : "ORD-5002",
                        "SKU-LEGACY", "web"));
                CONSOLE.print();
            }
        }

        DemoConsole.lookAt("w konsoli w A linia trace eventu Sentry ma te same 8 znaków co trace spanów. W B event "
                + "ma trace ID ze scope Sentry, niezwiązany z trace requestu, choć span SERVER ma status=ERROR.");
        DemoConsole.lookAt("w Sentry UI event z A jest w widoku trace przy spanie http.server POST "
                + "/inventory/reservations, a z eventu można przejść do trace. Event z B wskazuje trace, "
                + "w którym nie ma żadnego spana.");
    }

    // Scenariusz 6: dwa spany jednej granicy HTTP.
    //
    // O co chodzi: każdą granicę powinien instrumentować jeden mechanizm. TracedHttpClient gra rolę
    // biblioteki instrumentacji, którą w produkcji dostarcza Java Agent albo Starter, i ma własny
    // instrumentation scope.
    //
    // Co pokazujemy: checkout-api otwiera ręczny span CLIENT (scope pl.training.checkout) wokół
    // wywołania TracedHttpClient, który tworzy własny span CLIENT (scope pl.training.http-client).
    //
    // Problem: jedno żądanie HTTP daje dwa zagnieżdżone spany CLIENT o tej samej nazwie i prawie tym
    // samym czasie: podwójny koszt i zawyżona liczba wywołań w agregacjach.
    //
    // Dobra praktyka: jeden właściciel granicy, tu TracedHttpClient. Filtr po eksporcie nie jest naprawą.
    //
    // Na co patrzeć: w konsoli dwa zagnieżdżone spany CLIENT POST /inventory/reservations, różniące się
    // scope w nawiasie. W Sentry UI dwa spany http.client jeden w drugim, rozróżnialne atrybutem
    // instrumentation.name.
    //
    // Uruchomienie: -Dexec.args=6
    static void duplicatedBoundary() {
        DemoConsole.scenario(6, "Dwa spany jednej granicy HTTP",
                "pokazać, jak wygląda duplikat, gdy granicę instrumentuje biblioteka i ręczny kod naraz.");

        try (ServiceTelemetry checkoutTelemetry = telemetry("checkout-api", PARENT_BASED_ALWAYS_ON);
             ServiceTelemetry inventoryTelemetry = telemetry("inventory-service", PARENT_BASED_ALWAYS_ON);
             InventoryService inventory = InventoryService.start(inventoryTelemetry, ErrorReporting.INSIDE_SPAN);
             ExecutorService pricingPool = Executors.newSingleThreadExecutor();
             CheckoutService checkout = new CheckoutService(checkoutTelemetry, inventory, pricingPool)) {
            DemoConsole.step("Ręczny span CLIENT wokół wywołania, które instrumentuje już klient HTTP");
            checkout.submit(new Order("ORD-6001", "SKU-1", "web"), Outbound.DOUBLE_INSTRUMENTED);
            CONSOLE.print();
        }

        DemoConsole.lookAt("w konsoli dwa zagnieżdżone spany CLIENT o tej samej nazwie i prawie tym samym czasie, "
                + "z różnym instrumentation scope (pl.training.checkout i pl.training.http-client).");
        DemoConsole.lookAt("w Sentry UI dwa spany http.client jeden w drugim, rozróżnialne atrybutem "
                + "instrumentation.name: podwójny koszt i zawyżona liczba wywołań w agregacjach. Naprawa to jeden "
                + "właściciel granicy, nie filtr po eksporcie.");
    }

    // Scenariusz 7: krótki proces, flush i shutdown.
    //
    // O co chodzi: BatchSpanProcessor trzyma zakończone spany w kolejce i eksportuje je paczkami
    // (domyślne opóźnienie 5 s). Ręcznie zbudowane SDK nie rejestruje shutdown hooka, a wątek eksportu
    // jest daemonem, więc proces zakończony bez shutdown gubi wszystko, co czekało w kolejce.
    //
    // Co pokazujemy: StockImportJob importuje trzy SKU (span stock.import i po dwa spany na pozycję,
    // razem 7) z lokalnym exporterem. A: bez shutdown (JVM demo żyje dalej, więc spany wyjdą po 5 s,
    // ale nikt ich nie wypisze). B: shutdown przez try-with-resources. C: forceFlush() po każdej pozycji.
    //
    // Problem: w A exporter nie dostaje nic: zadanie krótsze niż opóźnienie paczki bez shutdown traci
    // wszystkie spany. W C flush „na wszelki wypadek” rozbija eksport na drobne paczki i nie wysyła
    // trwającego spana stock.import, bo flush nie kończy trwających spanów.
    //
    // Dobra praktyka: przed końcem procesu zakończyć spany i wywołać shutdown, który opróżnia kolejkę
    // i zamyka exportery. forceFlush() na koniec zadania wsadowego albo przed zamrożeniem procesu
    // (np. serverless), nie w ścieżce requestu.
    //
    // Na co patrzeć: w konsoli A kończy się z 0 z 7 spanów, B ma 7 spanów w 1 wywołaniu export, C przed
    // shutdown 6 spanów w 3 wywołaniach (z adnotacją, że rodzic nie został wyeksportowany), a stock.import
    // dopiero po shutdown. W Sentry UI nic: eksport tylko lokalny. W produkcji brak trace krótkiego joba
    // najpierw tłumaczy brak shutdown, a nie brak instrumentacji.
    //
    // Uruchomienie: -Dexec.args=7
    static void shortLivedProcess() {
        DemoConsole.scenario(7, "Krótki proces: flush i shutdown",
                "pokazać, że spany czekają w kolejce eksportu i giną, gdy proces kończy się bez shutdown.");

        List<String> skus = List.of("SKU-1", "SKU-1", "SKU-1");

        // Scenariusz korzysta tylko z lokalnych exporterów, także w trybie online. Tu JVM żyje
        // dalej, więc BatchSpanProcessor z wariantu A wyeksportuje spany po 5 s (nikt ich już nie
        // wypisze); w prawdziwym krótkim procesie wątek eksportu (daemon) zginąłby razem z JVM.
        DemoConsole.step("A. Import bez shutdown: main się kończy, SDK nie zostaje zamknięte");
        TraceConsole withoutShutdown = new TraceConsole(System.out);
        ServiceTelemetry abandoned = batchedTelemetry(withoutShutdown);
        new StockImportJob(abandoned).run(skus, false);
        // Celowo bez close(): tak kończy się proces, który zapomniał o shutdown.
        DemoConsole.step("   Koniec main. Exporter dostał " + withoutShutdown.exportedCount() + " z 7 spanów");

        DemoConsole.step("B. Ten sam import z shutdown (try-with-resources na ServiceTelemetry)");
        TraceConsole withShutdown = new TraceConsole(System.out);
        try (ServiceTelemetry telemetry = batchedTelemetry(withShutdown)) {
            new StockImportJob(telemetry).run(skus, false);
        }
        DemoConsole.step("   Po shutdown exporter dostał " + withShutdown.exportedCount() + " spanów w "
                + withShutdown.exportCalls() + " wywołaniu export");

        DemoConsole.step("C. forceFlush() po każdej pozycji, shutdown na końcu");
        TraceConsole flushing = new TraceConsole(System.out);
        try (ServiceTelemetry telemetry = batchedTelemetry(flushing)) {
            new StockImportJob(telemetry).run(skus, true);
            DemoConsole.step("   Po ostatnim flush, przed shutdown: " + flushing.exportedCount() + " spanów w "
                    + flushing.exportCalls() + " wywołaniach export, brakuje trwającego wtedy stock.import:");
            flushing.print();
        }
        DemoConsole.step("   Po shutdown doszedł span stock.import:");
        flushing.print();

        DemoConsole.lookAt("w konsoli A kończy się z 0 spanów: wszystkie czekały w kolejce BatchSpanProcessor. "
                + "B wysyła 7 spanów jedną paczką. C wysyła 6 spanów w 3 paczkach, a stock.import czwartą, "
                + "dopiero przy shutdown, bo flush nie kończy trwających spanów.");
        DemoConsole.lookAt("w Sentry UI nic: scenariusz eksportuje tylko lokalnie. W produkcji zadanie z A nie "
                + "zostawi żadnego trace, a brak trace krótkiego joba najpierw tłumaczy brak shutdown, "
                + "nie brak instrumentacji.");
    }

    private static void runSampling(Sampler checkoutSampler, Sampler inventorySampler, int requests, int firstOrder) {
        try (ServiceTelemetry checkoutTelemetry = telemetry("checkout-api", checkoutSampler);
             ServiceTelemetry inventoryTelemetry = telemetry("inventory-service", inventorySampler);
             InventoryService inventory = InventoryService.start(inventoryTelemetry, ErrorReporting.INSIDE_SPAN);
             ExecutorService pricingPool = Executors.newSingleThreadExecutor();
             CheckoutService checkout = new CheckoutService(checkoutTelemetry, inventory, pricingPool)) {
            for (int i = 0; i < requests; i++) {
                checkout.submit(new Order("ORD-" + (firstOrder + i), "SKU-1", "web"));
            }
        }
        CONSOLE.printSamplingSummary(requests, "checkout-api", "inventory-service");
    }

    /**
     * SDK usługi dla scenariuszy 1-6. Konsola dostaje spany przez {@code SimpleSpanProcessor}, żeby
     * drzewo było gotowe zaraz po requeście. Eksport do Sentry idzie przez {@code BatchSpanProcessor},
     * jak w produkcji; zamknięcie {@link ServiceTelemetry} na końcu scenariusza go opróżnia.
     */
    private static ServiceTelemetry telemetry(String service, Sampler sampler) {
        List<SpanProcessor> processors = new ArrayList<>();
        processors.add(SimpleSpanProcessor.create(CONSOLE));
        if (online) {
            processors.add(BatchSpanProcessor.builder(SentryOtlp.spanExporter(TrainingSentry.dsn())).build());
        }
        return create(service, sampler, processors);
    }

    /** SDK zadania wsadowego ze scenariusza 7: tylko lokalny exporter za {@code BatchSpanProcessor}. */
    private static ServiceTelemetry batchedTelemetry(TraceConsole exporter) {
        return create("stock-import", PARENT_BASED_ALWAYS_ON, List.of(BatchSpanProcessor.builder(exporter).build()));
    }

    private static ServiceTelemetry create(String service, Sampler sampler, List<SpanProcessor> processors) {
        // Wersja i środowisko z konfiguracji Sentry, żeby spany OTel i eventy Sentry opisywały
        // to samo wdrożenie. Obserwacja z lokalnego self-hosted Sentry 26.9.0 (inna wersja może
        // się zachować inaczej): deployment.environment.name staje się environment spana,
        // a service.version zostaje atrybutem resource.service.version i release spana jest pusty.
        SentryOptions options = Sentry.getCurrentScopes().getOptions();
        return ServiceTelemetry.create(service, options.getRelease(), options.getEnvironment(), sampler, processors);
    }

    private static void printHeaders(InventoryService inventory) {
        Map<String, String> headers = inventory.lastPropagationHeaders();
        if (headers.isEmpty()) {
            DemoConsole.step("   inventory-service odebrał: brak nagłówków propagacji");
        }
        headers.forEach((name, value) -> DemoConsole.step("   inventory-service odebrał " + name + ": " + value));
    }

    private static void ignoreFailure(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            // Awaria jest częścią scenariusza; jej ślad widać w spanach.
        }
    }
}
