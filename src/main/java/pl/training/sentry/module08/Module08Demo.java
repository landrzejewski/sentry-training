package pl.training.sentry.module08;

import io.sentry.Sentry;
import io.sentry.protocol.SentryId;
import pl.training.sentry.module08.LabelPrinter.PrintJob;
import pl.training.sentry.module08.PrintStation.SessionHandling;
import pl.training.sentry.module08.ReadinessReport.ControlResult;
import pl.training.sentry.support.ConsoleEnvelopeTransport;
import pl.training.sentry.support.DemoConsole;
import pl.training.sentry.support.DemoScenarios;
import pl.training.sentry.support.TrainingSentry;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

// Moduł 8: Standard wdrożenia i audyt gotowości Sentry.
//
// Gotowość potwierdza dowód, a nie obecność ustawienia
//
// Wpis w pliku konfiguracji potwierdza tylko to, że konfiguracja istnieje. Nie mówi, jakie wartości
// ma SDK po starcie, do którego projektu trafiają zdarzenia ani czy w ogóle docierają do Sentry.
// Standard wdrożenia składa się więc z kontroli: każda opisuje wymagane zachowanie, a o jej wyniku
// decyduje dowód, czyli coś, co da się zaobserwować, np. opcje działającego SDK albo zdarzenie
// odczytane z Sentry. Niespełniona albo niesprawdzona kontrola staje się ustaleniem audytu, czyli
// brakiem, który trafia do raportu i który zamyka dopiero ponowna weryfikacja z nowym dowodem.
//
// Profil wdrożenia i stosowalność kontroli
//
// Nie każda kontrola dotyczy każdej usługi. O tym, które obowiązują, decyduje profil wdrożenia
// (w kodzie DeploymentProfile). Powstaje on z inwentaryzacji, a nie z audytowanej konfiguracji,
// i opisuje usługę, środowisko, DSN zatwierdzonego projektu, wersję artefaktu zbudowanego przez
// pipeline, pakiet kodu aplikacji, limit próbkowania z polityki oraz możliwości techniczne. Dzięki
// temu audyt ma niezależny wzorzec i może stwierdzić, że konfiguracja jest inna, niż powinna.
//
// Każda możliwość (tracing, wywołania API spoza organizacji, publiczne endpointy, Sentry Logs) musi
// być jawnie aktywna albo jawnie wykluczona. Kontrola wykluczonej możliwości dostaje status
// NOT_APPLICABLE, czyli „nie dotyczy”. Pominięcie możliwości nie jest wykluczeniem. Funkcji nie
// oznacza się też jako niestosowalnej dlatego, że nie została wdrożona albo nie przechodzi testu:
// brak zniknąłby wtedy z raportu, zamiast stać się ustaleniem. Szczególnym przypadkiem jest profil
// bez zatwierdzonej telemetrii, np. laptop developera. Ma on pusty DSN, a jedyna kontrola, która go
// dotyczy, sprawdza, że SDK jest wyłączone.
//
// Konfiguracja w stylu Spring Boot i DSN
//
// Usługa checkout-api ma konfigurację znaną ze Spring Boot: plik bazowy application.properties
// i nakładki profili, np. application-local.properties. Nakładka nadpisuje klucze pliku bazowego,
// a wszystkie pozostałe dziedziczy. DSN to adres wskazujący projekt Sentry, do którego SDK wysyła
// dane. Jeśli stoi w pliku bazowym, dostaje go każdy profil, także lokalny, chyba że nakładka jawnie
// go zmieni. Laptop wysyła wtedy do projektu checkout-api, a ponieważ profil lokalny nie ustawia
// środowiska, jego zdarzenia mają environment=production i nazwę hosta laptopa w polu server_name.
// W Sentry wyglądają jak ruch produkcyjny.
//
// SDK wyłącza pusty DSN. Wartość null go nie wyłącza, tylko kończy Sentry.init wyjątkiem
// IllegalArgumentException, więc aplikacja nie startuje. Typową „naprawą” bywa wtedy wklejenie DSN
// produkcji do wspólnego pliku, co prowadzi prosto do opisanego wycieku. W produkcji pewniejszy jest
// DSN spoza repozytorium, wstrzykiwany przez platformę wdrożeniową: nowy profil domyślnie niczego
// wtedy nie wysyła.
//
// Audyt czyta działające SDK, a nie plik
//
// ReadinessAuditor sprawdza SentryOptions po Sentry.init, a nie plik konfiguracji. Dopiero w opcjach
// działającego SDK widać wartości domyślne, które uzupełniają braki w pliku: bez environment SDK
// zwraca production, bez listy celów propagacji (adresów, do których dołącza nagłówki trace)
// przyjmuje .*, czyli wszystkie adresy, a przy pustym DSN jest wyłączone. Warunek „environment
// jest ustawione” przechodzi więc zawsze, dlatego kontrola porównuje wartość z profilem.
//
// Drugi powód to sposób, w jaki SDK traktuje callback konfiguracji. Sentry.init(options -> ...)
// łapie każdy wyjątek rzucony w callbacku, zapisuje go tylko w logu SDK (domyślnie niewidocznym)
// i startuje z opcjami ustawionymi przed wyjątkiem. Literówka w wartości próbkowania nie zatrzyma
// aplikacji, ale po cichu zgubi wszystkie dalsze ustawienia. Dlatego SentryConfiguration parsuje
// i sprawdza właściwości przed Sentry.init, a w callbacku tylko przepisuje gotowe wartości.
//
// Co sprawdzają kontrole konfiguracji
//
// Kontrole audytu odpowiadają typowym ustaleniom z przeglądów konfiguracji:
// - DSN: SDK wysyła do zatwierdzonego projektu, a w profilu bez telemetrii jest wyłączone;
// - ENVIRONMENT: środowisko (pole environment) ma wartość z profilu. Staging z domyślnym production
//   trafia do produkcyjnych filtrów i Alertów;
// - RELEASE: wersja (pole release) to identyfikator artefaktu z pipeline, np. checkout-api@4.2.1+192,
//   ten sam w działającej aplikacji, w pipeline i w rekordzie wdrożenia. Wersja przepisana z pom.xml
//   nie ma numeru buildu, więc jest wspólna dla wszystkich buildów 4.2.1 i Sentry nie odróżni
//   poprawki od wersji, którą poprawia;
// - ERROR_SAMPLING i TRACES_SAMPLING: sampleRate próbkuje zdarzenia błędów, a tracesSampleRate
//   decyduje, jaka część transakcji trafia do tracingu, czyli śledzenia przebiegu żądania przez
//   kolejne usługi (jeden taki przebieg to trace). To niezależne decyzje: sampleRate=0.1 odrzuca
//   90% błędów, a bez tracesSampleRate tracing jest wyłączony. tracesSampleRate musi być większe
//   od zera i nie wyższe niż limit z profilu;
// - DEFAULT_PII: opcja sendDefaultPii jest wyłączona. Włączona sprawia, że SDK samo dołącza dane
//   osobowe, np. Sentry zapisuje adres IP nadawcy każdego zdarzenia. PASS nie obejmuje danych
//   dodawanych przez kod aplikacji; te sprawdza osobny test z kontrolowanymi markerami;
// - IN_APP: pakiet aplikacji jest na liście inAppIncludes. Bez niego Sentry nie odróżni w stack
//   trace ramek kodu aplikacji (in-app) od ramek bibliotek;
// - TRACE_PROPAGATION: nagłówki trace (sentry-trace oraz baggage, który niesie m.in. release
//   i environment) trafiają tylko do własnych usług, a nie np. do API operatora płatności;
// - TRACE_CONTINUATION: usługa z publicznymi endpointami zna identyfikator organizacji (z DSN
//   sentry.io albo z opcji orgId) i ma strictTraceContinuation=true. Nie kontynuuje wtedy trace
//   innej organizacji ani trace bez identyfikatora organizacji, np. z nagłówka wysłanego przez klienta;
// - LOGS: przy aktywnej możliwości Sentry Logs wysyłanie logów jest włączone, bo przy wyłączonym
//   Sentry.logger() po cichu nic nie robi.
//
// Statusy kontroli i decyzja bez uśredniania
//
// Kontrola kończy się jednym z czterech statusów. PASS oznacza, że wymagany dowód potwierdził
// kryterium, FAIL, że dowód wykazał jego naruszenie, NOT_APPLICABLE, że profil nie aktywuje kontroli,
// a NOT_VERIFIED, że brakuje podstaw do oceny. NOT_VERIFIED nie jest słabszym PASS: tak samo jak FAIL
// tworzy ustalenie. Profil z zatwierdzoną telemetrią, w którym SDK jest wyłączone, dostaje FAIL dla
// DSN i NOT_VERIFIED dla pozostałych kontroli, bo nie ma czego oceniać. Pełny standard zna jeszcze
// status PARTIAL i decyzję READY_WITH_ACTIONS, ale kontrole tego audytu są binarne i każda blokuje
// wdrożenie.
//
// Raport nie liczy procentu gotowości, bo dziesięć zaliczonych kontroli nie równoważy jednej
// niezaliczonej: wysyłania danych osobowych albo ruchu do złego projektu nic nie kompensuje. Dowolny
// FAIL daje decyzję NOT_READY. Brak FAIL przy co najmniej jednym NOT_VERIFIED daje
// INSUFFICIENT_EVIDENCE, czyli za mało dowodów. READY wymaga PASS we wszystkich stosowalnych
// kontrolach. Nawet konfiguracja bez żadnego błędu kończy się więc decyzją INSUFFICIENT_EVIDENCE,
// dopóki kontrola DELIVERY, czyli dowód dostarczenia, ma NOT_VERIFIED. Ustalenia nie zamyka zadanie
// oznaczone jako Done, merge, udane wdrożenie ani akceptacja ryzyka, tylko ponowny audyt.
//
// Event odbiorowy: dowód dostarczenia
//
// Identyfikator zwracany przez Sentry.captureException mówi tylko, że SDK przekazało zdarzenie do
// transportu. Pusty identyfikator (SentryId.EMPTY_ID) oznacza, że SDK odrzuciło zdarzenie jeszcze
// w procesie: jest wyłączone albo zdarzenie odrzuciło próbkowanie lub beforeSend, czyli funkcja
// aplikacji wywoływana przed wysłaniem, która może zdarzenie zmienić albo odrzucić. Niepusty
// identyfikator nie dowodzi jednak, że zdarzenie dotarło. Transport wysyła asynchronicznie, a po
// drodze zdarzenie może przepaść przez błąd transportu, DSN innego projektu, limit albo filtr po
// stronie Sentry. Może też dotrzeć z innym release lub environment niż wdrożone albo bez ramek in-app.
//
// Dlatego po wdrożeniu aplikacja wysyła event odbiorowy, czyli syntetyczne zdarzenie, które
// przechodzi przez ten sam potok SDK co prawdziwe błędy: scope (kontekst dołączany do zdarzeń),
// beforeSend, próbkowanie i transport. Niesie wyjątek, bo odbiór obejmuje także ramki aplikacji.
// Jego identyfikator trafia do rekordu wdrożenia (zapisu, który release wdrożono, gdzie i kiedy)
// jako odwołanie do dowodu. Po Sentry.flush, które opróżnia kolejkę transportu, krok pipeline
// odczytuje zdarzenie z API Sentry. Token API jest poświadczeniem pipeline, a nie częścią
// konfiguracji aplikacji. Kontrola DELIVERY dostaje PASS dopiero wtedy, gdy odczytane zdarzenie ma
// oczekiwany release, environment i co najmniej jedną ramkę in-app. Bez odczytu zostaje
// NOT_VERIFIED, a pusty identyfikator, brak zdarzenia w Sentry albo niezgodne pola dają FAIL.
//
// Event odbiorowy nie może nikogo budzić. Ma tag (parę klucz i wartość, po której Sentry filtruje
// zdarzenia) synthetic=true, a każda reguła Alertu ma filtr, który ten tag wyklucza, bo sam tag
// niczego nie wycisza. Ma stały odcisk (fingerprint) acceptance-probe, więc zdarzenia ze wszystkich
// wdrożeń trafiają do jednego issue, czyli jednej grupy zdarzeń. Przy domyślnym grupowaniu po
// ramkach stack trace refaktoryzacja kodu mogłaby otworzyć nowe issue, a reguła reagująca na nowe
// issue wysłałaby powiadomienie. Ma poziom info, z którego Sentry wylicza niski priorytet issue.
// Identyfikator wdrożenia jest w context (danych zdarzenia, po których się nie wyszukuje), a nie
// w tagu, bo każde wdrożenie ma inny.
//
// Release Health: sesje w czystej Javie
//
// Release Health, czyli kondycja wersji, ocenia stabilność każdego release na podstawie sesji. Sesja to
// jeden okres pracy aplikacji, a Sentry wylicza z sesji m.in. crash-free sessions (odsetek sesji bez
// awarii), adopcję wersji i liczbę użytkowników. Bez sesji tych miar nie ma, a reguły oparte na
// odsetku sesji nie mają mianownika. Serwerowe Java SDK nie tworzy sesji samo, mimo domyślnego
// enableAutoSessionTracking=true: aplikacja wywołuje Sentry.startSession() i Sentry.endSession()
// w punktach swojego cyklu życia. Na stanowisku pakowania jedna zmiana operatora to jedna sesja.
// Bez release SDK nie rozpocznie sesji i zostawi tylko ostrzeżenie w swoim logu.
//
// Status sesji wynika ze zdarzeń wysłanych w jej trakcie. Sesja zaczyna się ze statusem ok,
// a endSession zmienia go na exited. Błąd obsłużony zwiększa licznik błędów sesji (pole errors),
// ale sesja nadal liczy się jako wolna od awarii. Błąd nieobsłużony, czyli zdarzenie z handled=false
// i poziomem fatal zgłoszone przez handler nieobsłużonych wyjątków wątku, zmienia status na crashed,
// a SDK zamyka sesję i wysyła ją razem z tym zdarzeniem. Dlatego liczy się miejsce wywołania
// endSession. Wywołanie w bloku finally wykonuje się, zanim wyjątek dotrze do handlera: sesja kończy
// się jako exited, a zdarzenie awarii przychodzi już bez sesji, którą mogłoby oznaczyć. Awaria
// jest w Issues, ale crash-free rate jej nie uwzględnia. endSession należy więc wywoływać tylko
// po normalnym przebiegu.
//
// Użytkowników Release Health liczy po polu did sesji, które SDK wypełnia wartością opcji
// distinctId. Dla stanowiska jest to identyfikator instalacji, np. station-waw-07, a nie login ani
// e-mail operatora: te są danymi osobowymi, a zmiana operatora nie zmienia stanowiska.
//
// Czego przykłady nie pokazują
//
// Plan działań naprawczych porządkuje ustalenia, ale nie zmienia wyniku audytu. Incydent zamyka
// dopiero pozytywna obserwacja nowego release przy reprezentatywnym ruchu, a nie deploy ani status
// Resolved. Pozostałe obszary standardu, np. artefakty diagnostyczne, Replay i routing Alertów,
// też kończy test zachowania, a nie przegląd konfiguracji.
public final class Module08Demo {

    private Module08Demo() {
    }

    public static void main(String[] args) throws Exception {
        DemoScenarios scenarios = DemoScenarios.from(args, 1, 4);
        if (scenarios.includes(1)) localProfileSendsToProduction();
        if (scenarios.includes(2)) stagingConfigurationAudit();
        if (scenarios.includes(3)) acceptanceProbeAfterDeployment();
        if (scenarios.includes(4)) releaseHealthSessions();
    }

    // Scenariusz 1: profil lokalny wysyła do projektu produkcyjnego.
    //
    // O co chodzi: konfiguracja w stylu Spring Boot to plik bazowy i nakładki profili, a nakładka
    // dziedziczy każdy klucz, którego nie nadpisze. O tym, które kontrole audytu obowiązują, decyduje
    // profil z inwentaryzacji (DeploymentProfile), a nie sama konfiguracja.
    //
    // Co pokazujemy: dwa starty checkout-api z profilem local i audyt realnych SentryOptions po
    // Sentry.init (ReadinessAuditor.auditRunningSdk). Najpierw konfiguracja zastana, potem nakładka
    // local z pustym sentry.dsn.
    //
    // Problem: DSN projektu checkout-api stoi w application.properties, a nakładka local go nie
    // nadpisuje. SDK na laptopie jest aktywne z environment=production (wartość domyślna SDK)
    // i tracesSampleRate=1.0, więc jego ruch wyglądałby w Sentry jak produkcja.
    //
    // Dobra praktyka: profil bez zatwierdzonej telemetrii ma wyłączone SDK, a wyłącza je pusty DSN.
    // W produkcji pewniejszy jest DSN spoza repozytorium, wstrzykiwany przez platformę wdrożeniową:
    // wtedy nowy profil domyślnie niczego nie wysyła.
    //
    // Na co patrzeć: w konsoli pierwszy raport ma [FAIL] DSN z DSN projektu, environment=production
    // i tracesSampleRate=1.0 oraz decyzję NOT_READY; drugi [PASS] DSN, pozostałe kontrole NOT_APPLICABLE
    // i decyzję READY. W Sentry UI nic: fikcyjny DSN i transport konsolowy bez delegata także online.
    //
    // Uruchomienie: -Dexec.args=1
    static void localProfileSendsToProduction() {
        DemoConsole.scenario(1, "Profil lokalny wysyła do projektu produkcyjnego",
                "pokazać, że o stosowalności kontroli decyduje profil, a DSN odziedziczony z pliku bazowego to FAIL.");

        DemoConsole.step("Start checkout-api z profilem local, konfiguracja zastana (application.properties + application-local.properties)");
        print(startAndAudit(CheckoutDeployment.localAsFound(), CheckoutDeployment.LOCAL));

        DemoConsole.step("Ten sam start po poprawce: application-local.properties ustawia sentry.dsn= (pusty)");
        print(startAndAudit(CheckoutDeployment.localFixed(), CheckoutDeployment.LOCAL));

        DemoConsole.lookAt("w konsoli pierwszy audyt: SDK aktywne z DSN projektu checkout-api, environment=production "
                + "i tracesSampleRate=1.0, czyli ruch z laptopa wyglądałby w Sentry jak produkcja. Drugi: PASS dla DSN, "
                + "a pozostałe kontrole NOT_APPLICABLE, bo profil local nie ma zatwierdzonej telemetrii.");
        DemoConsole.lookAt("w Sentry UI nic: scenariusz używa fikcyjnego DSN i transportu konsolowego. W realnym "
                + "projekcie taki wyciek widać jako eventy w environment production z nazwą hosta laptopa w server_name.");
    }

    // Scenariusz 2: audyt konfiguracji stagingu przed wydaniem 4.2.1+192.
    //
    // O co chodzi: audyt porównuje opcje działającego SDK z profilem wdrożenia, a nie plik konfiguracji
    // z samym sobą. Dopiero po Sentry.init widać wartości domyślne SDK: production zamiast brakującego
    // environment i .* zamiast brakujących celów propagacji.
    //
    // Co pokazujemy: konfiguracja zastana stagingu i ta sama po poprawkach (CheckoutDeployment), każda
    // audytowana osobnym startem SDK. Raport nie liczy procentu gotowości: jeden FAIL daje NOT_READY.
    //
    // Problem: brak environment daje production, release z pom.xml nie ma numeru buildu, sendDefaultPii
    // zostało włączone „na chwilę”, a sample-rate=0.1 miało ograniczyć trace, tymczasem odrzuca 90%
    // błędów, a bez traces-sample-rate tracing jest wyłączony. Domyślne tracePropagationTargets=.*
    // wysyła nagłówki trace także do usług zewnętrznych.
    //
    // Dobra praktyka: environment staging, release z pipeline (checkout-api@4.2.1+192), tracesSampleRate
    // w limicie profilu, cele propagacji tylko do własnych usług. Nawet wtedy decyzja to
    // INSUFFICIENT_EVIDENCE: brak dowodu dostarczenia to NOT_VERIFIED, a nie słabszy PASS.
    //
    // Na co patrzeć: w konsoli pierwszy raport ma FAIL dla ENVIRONMENT, RELEASE, ERROR_SAMPLING,
    // TRACES_SAMPLING, DEFAULT_PII i TRACE_PROPAGATION; drugi PASS dla wszystkich kontroli konfiguracji,
    // DELIVERY NOT_VERIFIED i decyzję INSUFFICIENT_EVIDENCE. W Sentry UI nic: to audyt w procesie,
    // dowód dla DELIVERY daje scenariusz 3.
    //
    // Uruchomienie: -Dexec.args=2
    static void stagingConfigurationAudit() {
        DemoConsole.scenario(2, "Audyt konfiguracji stagingu przed wydaniem 4.2.1+192",
                "porównać realne SentryOptions z profilem i pokazać, że komplet PASS konfiguracji to jeszcze nie READY.");

        DemoConsole.step("Konfiguracja zastana: brak environment, release z pom.xml, sendDefaultPii „na chwilę”, sample-rate zamiast traces-sample-rate");
        print(startAndAudit(CheckoutDeployment.stagingAsFound(), CheckoutDeployment.STAGING));

        DemoConsole.step("Konfiguracja po poprawkach: environment, release z pipeline, tracesSampleRate, własne cele propagacji");
        print(startAndAudit(CheckoutDeployment.stagingFixed(), CheckoutDeployment.STAGING));

        DemoConsole.lookAt("w konsoli environment=production to wartość domyślna SDK, a nie wpis z pliku, "
                + "i sampleRate=0.1 obcina błędy, a nie trace. Po poprawkach wszystkie kontrole konfiguracji mają PASS, "
                + "ale DELIVERY ma NOT_VERIFIED, więc decyzja to INSUFFICIENT_EVIDENCE, a nie READY.");
        DemoConsole.lookAt("w Sentry UI nic: to audyt w procesie. Dowód dla DELIVERY daje dopiero scenariusz 3.");
    }

    // Scenariusz 3: event odbiorowy po wdrożeniu.
    //
    // O co chodzi: kontrola DELIVERY wymaga dowodu, że event dotarł do Sentry. Event odbiorowy przechodzi
    // przez ten sam potok SDK co prawdziwe błędy (scope, beforeSend, sampling, transport), więc sprawdza
    // konfigurację w działaniu, a jego ID trafia do rekordu wdrożenia jako referencja dowodu.
    //
    // Co pokazujemy: AcceptanceProbe.send wysyła ProbeException z tagiem synthetic=true, deployment_id
    // w contexts, stałym fingerprintem acceptance-probe i level=info. Po Sentry.flush
    // AcceptanceProbe.verify szuka eventu przez API Sentry, tylko online z SENTRY_AUTH_TOKEN.
    //
    // Problem: ID z captureException mówi tylko, że event trafił do transportu (pusty ID: SDK odrzuciło
    // go lokalnie). Event mógł nie dotrzeć albo dotrzeć z innym release, environment lub bez ramek
    // in-app. Bez stałego fingerprintu refaktoryzacja kodu mogłaby otworzyć nowe issue i powiadomienie.
    //
    // Dobra praktyka: PASS dopiero po odczycie eventu z Sentry z release i environment z rekordu
    // wdrożenia oraz ramkami in-app. Reguły Alertów mają filtr wykluczający tag synthetic, bo sam tag
    // niczego nie wycisza.
    //
    // Na co patrzeć: w konsoli event level=info z tagiem synthetic=true i fingerprintem acceptance-probe,
    // a pod nim [NOT_VERIFIED] DELIVERY (offline albo bez tokenu). W Sentry UI jedno issue ProbeException
    // dla wszystkich uruchomień, z tagiem synthetic i priorytetem low.
    //
    // Uruchomienie: -Dexec.args=3
    static void acceptanceProbeAfterDeployment() {
        DemoConsole.scenario(3, "Event odbiorowy po wdrożeniu",
                "potwierdzić dostarczenie odczytem z Sentry, a nie wynikiem captureException.");

        // Oczekiwane wartości pochodzą z rekordu wdrożenia, tu z tych samych zmiennych co TrainingSentry.
        String release = System.getenv().getOrDefault("SENTRY_RELEASE", TrainingSentry.DEFAULT_RELEASE);
        String environment = System.getenv().getOrDefault("SENTRY_ENVIRONMENT", TrainingSentry.DEFAULT_ENVIRONMENT);
        String deploymentId = "deploy-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        try (TrainingSentry.TrainingSession session = TrainingSentry.init("module08", options -> {
            // Bez tego ramki aplikacji nie są in-app, a sprawdzenie w trybie online kończy się FAIL.
            options.addInAppInclude("pl.training.sentry");
            // Wartość domyślna, ustawiona jawnie. Eksperyment: 0.0 (albo beforeSend zwracające null)
            // daje pusty ID eventu i FAIL bez odpytywania API.
            options.setSampleRate(1.0);
        })) {
            DemoConsole.step("Wysłanie eventu odbiorowego dla " + deploymentId);
            SentryId eventId = AcceptanceProbe.send(deploymentId);
            // Transport HTTP wysyła asynchronicznie. Flush przed odpytywaniem API, bo krótki
            // proces odbiorowy mógłby się skończyć, zanim event opuści kolejkę.
            Sentry.flush(5_000);

            DemoConsole.step("Odczyt eventu " + eventId + " z API Sentry (tylko online z SENTRY_AUTH_TOKEN, do 60 s)");
            ControlResult delivery = AcceptanceProbe.verify(eventId, release, environment,
                    SentryEventApi.fromEnvironment(System.getenv()));
            System.out.printf("  [%s] %s %s%n", delivery.status(), delivery.control(), delivery.detail());
        }

        DemoConsole.lookAt("w konsoli event ma tag synthetic=true, fingerprint acceptance-probe i level=info. "
                + "Offline i bez tokenu DELIVERY ma NOT_VERIFIED: event opuścił SDK, ale nikt nie potwierdził, "
                + "że dotarł. PASS wymaga odczytu z Sentry z właściwym release, environment i ramkami in-app.");
        DemoConsole.lookAt("w Sentry UI jedno issue ProbeException dla wszystkich uruchomień (stały fingerprint), "
                + "z tagiem synthetic i priorytetem low. Reguły Alertów powinny mieć filtr wykluczający ten tag.");
    }

    // Scenariusz 4: Release Health, sesje stanowiska pakowania.
    //
    // O co chodzi: czyste Java SDK nie tworzy sesji samo, więc aplikacja woła Sentry.startSession()
    // i Sentry.endSession() w punktach swojego cyklu życia; tu jedna zmiana operatora to jedna sesja.
    // Błąd obsłużony zwiększa licznik errors sesji, nieobsłużony zmienia jej status na crashed.
    //
    // Co pokazujemy: trzy zmiany w wątku print-queue, wszystkie z distinctId station-waw-07. A: kończą
    // się etykiety, błąd obsłużony i ponowny wydruk. B: zlecenie bez szablonu kończy wątek
    // NullPointerException, endSession tylko po normalnym przebiegu. C: ten sam crash, endSession w finally.
    //
    // Problem: w C finally kończy sesję jako exited, zanim wyjątek dotrze do handlera wątku, więc event
    // crasha przychodzi bez sesji, którą mógłby oznaczyć. Crash jest w Issues, ale crash-free rate go
    // nie liczy. Bez release SDK w ogóle nie rozpocznie sesji.
    //
    // Dobra praktyka: endSession tylko po normalnym przebiegu; przy crashu sesję zamyka SDK, oznacza
    // ją crashed i wysyła razem z eventem. distinctId to identyfikator instalacji, a nie login czy
    // e-mail operatora.
    //
    // Na co patrzeć: w konsoli linie ↳ sesja: A kończy się exited z errors=1, B wysyła crashed razem
    // z eventem level=fatal, C wysyła exited z errors=0, a event crasha dopiero po niej. W Sentry UI
    // (Releases) 3 sesje na uruchomienie, crashed tylko B: crash-free sessions 66,7% przy dwóch crashach
    // i users 1 (wspólny did). Własny SENTRY_RELEASE oddziela je od sesji innych modułów.
    //
    // Uruchomienie: -Dexec.args=4
    static void releaseHealthSessions() throws InterruptedException {
        DemoConsole.scenario(4, "Release Health: sesje stanowiska pakowania",
                "pokazać, że sesje trzeba prowadzić ręcznie i że miejsce endSession decyduje o crash-free rate.");

        try (TrainingSentry.TrainingSession session = TrainingSentry.init("module08", options -> {
            PrintStation.configure(options, "station-waw-07");
            // Wartość domyślna, ustawiona jawnie, bo wariant B od niej zależy. Eksperyment: false
            // zostawia sesję wariantu B otwartą, a crash nie trafia do Sentry (pod Maven build
            // kończy się wtedy błędem).
            options.setEnableUncaughtExceptionHandler(true);
        })) {
            DemoConsole.step("A. Zmiana z błędem obsłużonym: w drukarce kończą się etykiety, operator dokłada rolkę");
            new PrintStation(new LabelPrinter(2)).runShiftInBackground(List.of(
                    new PrintJob("ORD-8001", "ETYKIETA/{order}"),
                    new PrintJob("ORD-8002", "ETYKIETA/{order}"),
                    new PrintJob("ORD-8003", "ETYKIETA/{order}")), SessionHandling.END_ON_NORMAL_EXIT);

            List<PrintJob> withMissingTemplate = List.of(
                    new PrintJob("ORD-8101", "ETYKIETA/{order}"),
                    new PrintJob("ORD-8102", null));

            DemoConsole.step("B. Zmiana z crashem: zlecenie bez szablonu kończy wątek kolejki, endSession tylko po normalnym przebiegu");
            new PrintStation(new LabelPrinter(100)).runShiftInBackground(withMissingTemplate, SessionHandling.END_ON_NORMAL_EXIT);

            DemoConsole.step("C. Ten sam crash, ale endSession w finally");
            new PrintStation(new LabelPrinter(100)).runShiftInBackground(withMissingTemplate, SessionHandling.END_IN_FINALLY);
        }

        DemoConsole.lookAt("w konsoli linie ↳ sesja: pierwsza każdej zmiany ma status=ok i znacznik init, ostatnia "
                + "wynik zmiany. A kończy się exited z errors=1, B wysyła crashed razem z eventem level=fatal, a C wysyła "
                + "exited z errors=0 i dopiero potem osobny event crasha. distinctId (w sesji pole did) to station-waw-07.");
        DemoConsole.lookAt("w Sentry UI (Releases) każde uruchomienie dodaje 3 sesje, z których tylko B jest crashed: "
                + "crash-free sessions 66,7%, choć crashe były dwa. Users to 1, bo sesje mają ten sam did. "
                + "Własny SENTRY_RELEASE oddziela je od sesji innych modułów.");
    }

    /**
     * Inicjalizuje SDK konfiguracją aplikacji i audytuje opcje po inicjalizacji.
     *
     * <p>DSN w konfiguracji jest fikcyjny, więc transport konsolowy nie ma delegata HTTP także
     * w trybie online. Audyt nie wysyła eventów, transport jest tu tylko zabezpieczeniem.</p>
     */
    private static ReadinessReport startAndAudit(Map<String, String> properties, DeploymentProfile profile) {
        SentryConfiguration configuration = SentryConfiguration.from(properties);
        Sentry.init(options -> {
            configuration.applyTo(options);
            options.setTransportFactory((sentryOptions, requestDetails) ->
                    new ConsoleEnvelopeTransport(sentryOptions, null, System.out));
        });
        try {
            return ReadinessAuditor.auditRunningSdk(profile);
        } finally {
            Sentry.close();
        }
    }

    private static void print(ReadinessReport report) {
        report.lines().forEach(System.out::println);
    }
}
