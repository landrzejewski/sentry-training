package pl.training.sentry.module06;

import io.sentry.SpanId;
import io.sentry.protocol.SentryId;
import pl.training.sentry.module06.BankGateway.Mode;
import pl.training.sentry.module06.BankGateway.Payment;
import pl.training.sentry.module06.PaymentsEndpoint.Reporting;
import pl.training.sentry.module06.PayoutBatchCheckIns.IdStorage;
import pl.training.sentry.module06.SettlementService.SettlementReport;
import pl.training.sentry.module06.StatementImportJob.FailureHandling;
import pl.training.sentry.support.DemoConsole;
import pl.training.sentry.support.DemoScenarios;
import pl.training.sentry.support.TrainingSentry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

// Moduł 6: Monitory, alerty i redukcja szumu.
//
// Monitor wykrywa problem, Alert decyduje o reakcji
//
// Sentry rozdziela dwie decyzje. Monitor odpowiada na pytanie, czy istnieje problem: opisuje źródło danych,
// zakres, warunek i priorytet problemu, który tworzy. Alert odpowiada na pytanie, kto, gdzie i jak ma
// zareagować: wybiera Monitory albo zmiany stanu Issue, środowisko, filtry, akcję (np. webhook, Slack,
// PagerDuty) i to, jak często ją powtarzać. Monitor nic nie wie o kanałach, więc jeden warunek może zasilać
// kilka Alertów, a zmiana kanału nie wymaga ruszania progu. Monitory należą do projektu, Alerty są zasobami
// organizacji, a w REST API ten sam podział widać jako detector i workflow.
//
// W tym module występują trzy typy Monitorów. Cron Monitor śledzi zadanie cykliczne na podstawie komunikatów
// check-in wysyłanych przez aplikację. Uptime Monitor co zadany interwał wysyła do usługi żądanie HTTP z
// zewnątrz. Metric Monitor liczy agregat, np. liczbę zdarzeń w oknie czasu, i porównuje go z progiem. Każdy z
// nich po wykryciu problemu tworzy własne Issue (Cron Issue, Uptime Issue, Metric Issue), na które Alerty
// reagują tak samo jak na błędy. Obie warstwy pracują jednak wyłącznie na sygnale wysłanym przez aplikację, a
// złego sygnału nie naprawi żaden próg ani filtr.
//
// Zdarzenie, Issue i powiadomienie
//
// Zdarzenie (event) to jedna obserwacja, Issue grupuje zdarzenia o tym samym odcisku (fingerprint), a
// powiadomienie jest dopiero skutkiem reguły Alertu. Tysiąc zdarzeń nie musi więc dać tysiąca powiadomień, a
// jedno Issue może wiele razy zmieniać stan. Dla reakcji liczą się zwłaszcza trzy zmiany: rozwiązanie
// (Resolved), czyli decyzja zespołu, że problem naprawiono; regresja (Regressed), czyli ponowne pojawienie
// się rozwiązanego Issue; oraz eskalacja (Escalating), gdy liczba zdarzeń Issue znacząco przekracza prognozę.
// Priorytet Issue (low, medium, high) porządkuje kolejkę do przeglądu, ale nie mówi, czy budzić dyżur.
//
// Cron Monitor: check-in opisuje jedno uruchomienie
//
// Wyjątek z zadania cyklicznego nie powie, czy zadanie w ogóle wystartowało i czy skończyło się na czas. Mówi
// o tym check-in, czyli krótki komunikat o stanie konkretnego uruchomienia. SDK zna tylko trzy statusy:
// IN_PROGRESS na starcie oraz OK albo ERROR na końcu, z tym samym identyfikatorem (SentryId), po którym
// Sentry łączy początek z końcem. Pozostałe stany wylicza serwer z konfiguracji monitora: brak uruchomienia
// (Missed), gdy check-in nie przyszedł w oczekiwanym terminie powiększonym o margines, i przekroczenie czasu
// (Timeout), gdy w czasie max runtime po IN_PROGRESS nie przyszedł check-in końcowy. Zawieszenie wykrywa więc
// tylko model start plus koniec. Wariant heartbeat, czyli jeden check-in po zakończeniu pracy, wykryje brak
// uruchomienia, ale zawieszone zadanie niczego nie wyśle i wygląda jak takie, które nie ruszyło.
//
// CheckInUtils.withCheckIn realizuje ten model: wysyła IN_PROGRESS, wywołuje callback, a potem OK albo ERROR
// z czasem trwania. Status zależy wyłącznie od tego, czy wyjątek opuścił callback. Wynik opisujący porażkę
// (np. raport z nieuzgodnioną płatnością) to normalny powrót, więc check-in będzie OK; jeśli taki wynik ma
// oznaczać porażkę, kod rzuca wyjątek wewnątrz callbacku. Tak samo wyjątek złapany i zalogowany w callbacku
// daje OK za import, którego nie było. Wrapper zaczyna też dla uruchomienia nowy trace, czyli identyfikator
// łączący dane jednej operacji. Zdarzenie wysłane wewnątrz callbacku ma ten sam trace co check-in, więc
// Sentry pokazuje jego Issue przy tym uruchomieniu: ERROR mówi o porażce, a zdarzenie o przyczynie.
//
// Liczy się też to, gdzie wyjątek kończy drogę. Gdy zadanie uruchamiane przez scheduleAtFixedRate rzuci
// wyjątek, ScheduledExecutorService wstrzymuje wszystkie kolejne uruchomienia, a wyjątek zostawia w
// ScheduledFuture, którego nikt nie odczytuje. Sentry zobaczy jeden ERROR bez zdarzenia z przyczyną, a potem
// już tylko Missed. Po wysłaniu zdarzenia i check-inu ERROR zadanie zatrzymuje więc wyjątek na swojej granicy
// i następne uruchomienie odbywa się normalnie.
//
// Konfiguracja monitora w kodzie
//
// Monitor można założyć w interfejsie albo przekazać do withCheckIn obiekt MonitorConfig. Trafia on tylko do
// pierwszego check-inu, a Sentry na jego podstawie tworzy monitor o podanym identyfikatorze (slug) albo
// aktualizuje istniejący (upsert). Konfiguracja obejmuje harmonogram (crontab albo interwał), strefę czasową,
// margines opóźnienia startu (checkin margin), limit czasu wykonania (max runtime) i dwa progi: ile kolejnych
// porażek tworzy Cron Issue (failure issue threshold) i ile kolejnych sukcesów oznacza powrót do normy
// (recovery threshold). Bez strefy czasowej Sentry czyta crontab w UTC, więc zadanie o 2:00 czasu polskiego
// byłoby oczekiwane o innej godzinie. Konfiguracja powinna mieć jednego właściciela: gdy zarządza nią kod,
// kolejny check-in nadpisze ręczną zmianę w interfejsie.
//
// Stan monitora Sentry prowadzi osobno dla każdego środowiska, a środowisko check-inu pochodzi z opcji SDK
// albo z przeciążenia withCheckIn z parametrem environment. Zadanie na stagingu bez ustawionego środowiska
// wyśle check-iny ze środowiskiem production, bo to wartość domyślna SDK, i wymiesza je z produkcją. Monitor
// ma harmonogram, więc gdy zadanie przestaje działać, nadal czeka na check-in i przy każdym terminie notuje
// Missed. SDK nie potrafi monitora wyłączyć, dlatego wycofanie zadania obejmuje usunięcie jego monitora.
//
// Ręczne check-iny
//
// Gdy framework wsadowy woła osobno metodę przed uruchomieniem i po nim, jeden callback nie obejmie całego
// cyklu. Kod wysyła wtedy check-in przez Sentry.captureCheckIn i musi przechować SentryId zwrócony przy
// IN_PROGRESS aż do końca tego samego uruchomienia. Uruchomienia nakładają się częściej, niż się wydaje (np.
// dwie repliki z tym samym harmonogramem), więc identyfikator w jednym polu wspólnego obiektu zostanie
// nadpisany. Oba wyniki trafią wtedy do drugiego uruchomienia, pierwsze zostanie otwarte i skończy jako
// Timeout, a drugi check-in końcowy przyjdzie do uruchomienia już zamkniętego i Sentry go odrzuci.
// Identyfikator przechowuje się więc osobno dla każdego uruchomienia, a na starcie zaczyna nowy trace
// (TracingUtils.startNewTrace), bo inaczej check-in odziedziczy trace wątku i Sentry powiąże z nim błędy
// innych operacji. Monitor tylko obserwuje, więc przed podwójnym uruchomieniem chroni blokada w aplikacji.
//
// Uptime Monitor i endpoint zdrowia
//
// Uptime Monitor widzi tylko to, co endpoint faktycznie sprawdza. Endpoint /live, który zwraca 200, dopóki
// proces odpowiada, pokaże dostępność także wtedy, gdy bank nie działa i klienci nie mogą zapłacić. Endpoint
// dla monitora (/health) sprawdza więc zależności lekkim zapytaniem, które niczego nie zmienia, i przy awarii
// zwraca 503 bez szczegółów błędu. Domyślna tolerancja (Failure Tolerance) tworzy Uptime Issue po 3 kolejnych
// niepowodzeniach, a do powrotu do normy wystarcza jeden sukces. Każda próba niesie nagłówek User-Agent z
// wartością SentryUptimeBot oraz nagłówek sentry-trace. Backend z SDK kontynuuje ten trace
// (Sentry.continueTrace), więc zdarzenie wysłane podczas próby ma trace sprawdzenia i da się je powiązać z
// Uptime Issue. User-Agent pozwala z kolei oznaczyć ruch sprawdzeń tagiem o dwóch wartościach i odróżnić go
// od ruchu użytkowników.
//
// Dane zdarzenia: odcisk, tagi i poziom
//
// Grupowanie, filtry Alertów i reguły własności (Ownership Rules, które wskazują zespół odpowiedzialny za
// Issue) widzą wyłącznie dane zdarzenia: odcisk, tagi i poziom (level). Każda inna wartość odcisku to osobne
// Issue. Lokalna instancja Sentry normalizowała komunikaty wyjątków (liczby i losowe identyfikatory
// zamieniała na symbole, także w wartości odcisku równej komunikatowi), ale nazw, np. sprzedawcy, nie
// ruszała, a sama normalizacja nie jest kontraktem. Odcisk zbudowany z komunikatu banku daje więc osobne
// Issue na każdego sprzedawcę i każde z nich osobno uruchamia Alerty. Stabilny odcisk jednej przyczyny, np.
// bank-timeout z kodem banku, łączy w jedno Issue timeouty z płatności, importu wyciągów i endpointu zdrowia.
//
// Wymiary o kilku stałych wartościach, np. component i operation, trafiają do tagów: po nich filtrują Alerty,
// dopasowują się reguły własności (tags.component:payments) i w Issue widać ich rozkład. Identyfikatory
// płatności i nazwy sprzedawców trafiają do contexts, gdzie pomagają w analizie jednego zdarzenia, ale nie
// rozbijają grupowania. Brak tagu nie daje błędu konfiguracji, tylko ciszę: filtr component = payments nie
// pasuje, więc prawdziwa awaria zgłoszona bez tego tagu nikogo nie powiadomi. Dlatego tagi, poziom i odcisk
// ustawia jedno miejsce w kodzie raportującym, a nie konfiguracja Alertu.
//
// Metric Monitor
//
// Metric Monitor ocenia zapytanie w oknie czasu, np. liczbę (count) zdarzeń component:payments level:error z
// ostatnich 5 minut, i po przekroczeniu progu tworzy Metric Issue o priorytecie ustawionym w Monitorze. Gdy
// wartość spadnie do progu powrotu (recovery), Sentry sam rozwiązuje to Issue. Monitor mówi o skali awarii, a
// nie o pojedynczym błędzie. Na jego Issue reaguje osobny Alert. Warunek level:error w zapytaniu sprawia, że
// Monitor nie liczy odmów zgłoszonych jako warning.
//
// Alert: środowisko, wyzwalacz, filtry, throttling i akcja
//
// Środowisko Alertu działa przed filtrami: zdarzenie z innego środowiska odpada, zanim ktokolwiek sprawdzi
// tagi. Brak filtra środowiska nie oznacza production, tylko zwykle wszystkie środowiska, dlatego Alert
// produkcyjny ogranicza się jawnie. Wyzwalacz (trigger) to zmiana, która uruchamia ocenę: „A new issue is
// created” działa raz, przy utworzeniu Issue, a „An event or issue activity is captured” przy każdym
// zdarzeniu, także dla Issue, które już istnieje. Filtry łączy kwalifikator all (wszystkie muszą pasować),
// any albo none, np. component = payments, level co najmniej error i expected różne od true. Akcja to np.
// webhook, czyli żądanie HTTP z opisem zdarzenia i Issue.
//
// Throttling ogranicza ponowne wykonanie akcji dla tego samego Issue, np. do jednej na 30 minut. Domyślnie
// akcja wykonuje się przy każdym uruchomieniu wyzwalacza. Throttling nie zmienia przyjmowania zdarzeń, oceny
// Monitora, stanu Issue ani kosztu danych: seria dwunastu zdarzeń jednej awarii daje jedno powiadomienie z
// Alertu, a Metric Monitor liczy te same zdarzenia i osobno zgłasza skalę. Działa jednak per Issue, więc gdy
// zmienny odcisk rozbije awarię na wiele Issues, każde dostanie własne powiadomienie.
//
// Regresja
//
// Wyzwalacz „A resolved issue regresses” reaguje na zdarzenie, które trafiło do Issue po jego rozwiązaniu;
// ręczne cofnięcie rozwiązania go nie uruchamia. Regresja przeczy decyzji o naprawie, więc zwykle jest
// silniejszym sygnałem niż kolejne wystąpienie. Filtry częstotliwości i atrybutów zdarzenia, np. tagu,
// działają tylko z wyzwalaczami utworzenia Issue i przyjęcia zdarzenia, a przy regresji formularz oznacza je
// ostrzeżeniem. Regresję obsługuje więc osobny Alert z filtrem atrybutu Issue, np. priorytetu co najmniej
// high. Alert na pojedyncze błędy nie odróżni jej od kolejnego zdarzenia.
//
// Redukcja szumu: im wcześniej odrzucasz dane, tym większej pewności trzeba
//
// Szum usuwa się w warstwie, w której powstaje: oczekiwany wyjątek zgłaszany jako błąd poprawia się w
// instrumentacji, rozbite Issues w odcisku, a zbyt szeroki Alert jego filtrami. Mechanizmy wyciszania różnią
// się tym, co tracimy:
// - filtr Alertu zostawia dane i tylko nie wykonuje akcji;
// - archiwizacja Issue do eskalacji usuwa je z kolejki, a nagły skok zdarzeń wróci jako Escalating;
// - filtr w SDK odrzuca zdarzenie przed wysłaniem, więc w Sentry nie da się go policzyć ani zbadać.
// Odmowa karty to wynik biznesowy, który zespół chce widzieć jako trend, więc kod ją klasyfikuje: poziom
// warning, tag expected=true i odcisk według powodu odmowy. Filtr level co najmniej error odrzuci ją w
// Alercie, a wykluczenie expected=true zadziała nawet wtedy, gdy ktoś podniesie jej poziom do error. Filtr w
// SDK zostaje dla przypadków znanych z pewnością i bez wartości diagnostycznej, np. powtórzonego żądania z
// tym samym kluczem idempotencji. SentryOptions.addIgnoredExceptionForType porównuje dokładną klasę wyjątku
// (podklasy przechodzą), a setIgnoredErrors dopasowuje wzorce do tekstu „klasa: komunikat”, więc wzorzec
// .*Timeout.* odciąłby też timeout banku i Alert o awarii.
//
// Jak sprawdzić, że polityka działa
//
// Testowa wiadomość integracji sprawdza tylko połączenie, a nie wyzwalacz i filtry. Cały łańcuch (sygnał,
// Monitor, Alert, akcja) sprawdza się kontrolowanym sygnałem i historią Alertu, która pokazuje, dla których
// Issues wykonano akcję, niezależnie od tego, czy kanał dostarczył wiadomość. Webhook wychodzi z Internal
// Integration, czyli własnej integracji organizacji, a Alert zna tylko jej identyfikator (slug), więc zmiana
// adresu odbiornika nie wymaga edycji Alertów. Integracja podpisuje treść algorytmem HMAC-SHA256 z kluczem
// Client Secret i przesyła podpis w nagłówku Sentry-Hook-Signature. Odbiornik sprawdza podpis, szybko
// odpowiada 2xx, a dłuższą pracę, np. założenie zgłoszenia, wykonuje asynchronicznie.
public final class Module06Demo {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);
    private static final long RUN_PERIOD_MS = 200;

    private Module06Demo() {
    }

    public static void main(String[] args) throws Exception {
        DemoScenarios scenarios = DemoScenarios.from(args, 1, 7);
        // PaymentTelemetry.configure odrzuca DuplicatePaymentException przed wysłaniem.
        // Eksperyment: bez tej linii scenariusz 6C wyśle event, a reszta demo działa tak samo.
        try (TrainingSentry.TrainingSession session = TrainingSentry.init("module06", PaymentTelemetry::configure)) {
            if (scenarios.includes(1)) cronConfiguredFromCode();
            if (scenarios.includes(2)) failingJob();
            if (scenarios.includes(3)) hangingJob();
            if (scenarios.includes(4)) overlappingRunsWithManualCheckIns();
            if (scenarios.includes(5)) stableSignalData();
            if (scenarios.includes(6)) expectedErrors();
            if (scenarios.includes(7)) healthEndpointForUptime();
        }
    }

    // Scenariusz 1: Cron Monitor z kodu: konfiguracja i wynik uruchomienia.
    //
    // O co chodzi: CheckInUtils.withCheckIn wysyła IN_PROGRESS przed pracą i OK albo ERROR po niej,
    // z tym samym identyfikatorem. MonitorConfig jedzie tylko w pierwszym check-inie i z niego Sentry
    // zakłada albo aktualizuje monitor (upsert), więc konfiguracją zarządza kod.
    //
    // Co pokazujemy: SettlementJob (nocne uzgodnienie, crontab 0 2 * * * w strefie Europe/Warsaw)
    // uruchomiony dwa razy. A: wszystkie płatności są na wyciągu. B: jednej brakuje, raport ma
    // nieuzgodnioną płatność, ale callback wraca normalnie.
    //
    // Problem: status zależy wyłącznie od tego, czy callback rzucił wyjątek, więc B to OK mimo
    // nieuzgodnionej płatności. Druga pułapka opisana w SettlementJob: job na stagingu bez ustawionego
    // environment wysyła check-iny jako production (domyślna wartość SDK) i miesza się z produkcją.
    //
    // Dobra praktyka: jeśli taki wynik ma oznaczać porażkę, kod rzuca wyjątek wewnątrz callbacku.
    // Environment z opcji SDK (albo jawnie w przeciążeniu withCheckIn), strefa czasowa ustawiona, bo
    // bez niej crontab jest w UTC, a konfiguracja monitora ma jednego właściciela: tu kod, nie UI.
    //
    // Na co patrzeć: w konsoli każde uruchomienie to para in_progress i ok z tym samym id i trace;
    // linia config (crontab, strefa, margines, max_runtime, progi) jest tylko pod in_progress,
    // a environment=training pochodzi z opcji SDK. W Sentry UI (Insights > Crons) monitor
    // settlement-reconciliation ma w ustawieniach wartości z kodu, a środowisko training osobny stan.
    //
    // Uruchomienie: -Dexec.args=1
    static void cronConfiguredFromCode() throws Exception {
        DemoConsole.scenario(1, "Cron Monitor z kodu: konfiguracja i wynik uruchomienia",
                "pokazać, co wysyła withCheckIn i od czego zależy status uruchomienia.");

        BankGateway bank = new BankGateway();

        DemoConsole.step("A. Nocne uzgodnienie, wszystkie płatności są na wyciągu");
        SettlementReport report = new SettlementJob(new SettlementService(bank, Set.of("PAY-1001", "PAY-1002")))
                .run(DAY);
        DemoConsole.step("   Wynik: uzgodnione " + report.matched() + ", nieuzgodnione " + report.unmatched());

        DemoConsole.step("B. To samo zadanie, jednej płatności brakuje na wyciągu");
        report = new SettlementJob(new SettlementService(bank, Set.of("PAY-1001", "PAY-1004"))).run(DAY);
        DemoConsole.step("   Wynik: uzgodnione " + report.matched() + ", nieuzgodnione " + report.unmatched()
                + ". Callback wrócił normalnie, więc check-in to OK.");

        DemoConsole.lookAt("w konsoli każde uruchomienie to para IN_PROGRESS i OK z tym samym id. Konfiguracja "
                + "(crontab, strefa, margines, max_runtime, progi) jest tylko w IN_PROGRESS, a environment "
                + "pochodzi z opcji SDK. Wariant B to OK mimo nieuzgodnionej płatności.");
        DemoConsole.lookAt("w Sentry UI (Insights > Crons) monitor settlement-reconciliation powstał z pierwszego "
                + "check-inu. W jego ustawieniach są wartości z kodu, a środowisko training ma osobny stan.");
    }

    // Scenariusz 2: Job zakończony wyjątkiem.
    //
    // O co chodzi: o stanie monitora decyduje kod wokół withCheckIn, a nie sam wyjątek. Import
    // wyciągów (StatementImportJob) działa co minutę, a bank raz nie odpowiada.
    //
    // Co pokazujemy: trzy warianty FailureHandling, każdy z własnym monitorem. A (SWALLOW): callback
    // łapie wyjątek, loguje go na stderr i zwraca 0. B (PROPAGATE): wyjątek opuszcza zadanie
    // scheduleAtFixedRate (co 200 ms). C (REPORT_AND_CONTINUE): PaymentTelemetry.reportBankTimeout
    // w callbacku, wyjątek wraca do wrappera, a granica zadania go zatrzymuje.
    //
    // Problem: A daje OK za nieudany import, a log nie trafia do Sentry. W B wrapper wysyła ERROR,
    // ale ScheduledExecutorService wstrzymuje wszystkie kolejne uruchomienia, a wyjątek zostaje
    // w ScheduledFuture: nie ma eventu ani logu, a Sentry widzi potem tylko brakujące check-iny.
    //
    // Dobra praktyka: wariant C. Event wysłany w callbacku ma ten sam trace co check-in, więc da się
    // go powiązać z uruchomieniem. ERROR mówi, że uruchomienie się nie udało, event niesie przyczynę,
    // a zadanie żyje dalej.
    //
    // Na co patrzeć: w konsoli A to in_progress, linia „(log aplikacji)” i ok. B to in_progress
    // i error, po 1000 ms jedno uruchomienie i zadanie zakończone na stałe, bez eventu. C to event
    // z tagami component=payments i operation=statement-import o trace pary in_progress/error, potem
    // druga para zakończona ok. W Sentry UI (Crons, lista check-inów) ...-swallowed ma OK,
    // ...-suppressed Failed bez powiązanego Issue, a bank-statement-import Failed z powiązanym Issue
    // awarii banku. Od następnej minuty wszystkie trzy zgłaszają Missed, dlatego po obejrzeniu
    // monitory się usuwa (README, „Sprzątanie po demo”).
    //
    // Uruchomienie: -Dexec.args=2
    static void failingJob() throws Exception {
        DemoConsole.scenario(2, "Job zakończony wyjątkiem",
                "pokazać, że o stanie monitora decyduje kod wokół withCheckIn, a nie sam wyjątek.");

        DemoConsole.step("A. Callback łapie wyjątek, loguje go i zwraca pusty wynik");
        BankGateway bank = new BankGateway();
        bank.timeOutNextCalls(1);
        new StatementImportJob(bank, StatementImportJob.MONITOR_SLUG + "-swallowed")
                .scheduledTask(FailureHandling.SWALLOW).run();

        DemoConsole.step("B. Wyjątek opuszcza zadanie ScheduledExecutorService (co " + RUN_PERIOD_MS
                + " ms), bank odpowiada błędem tylko raz");
        bank.timeOutNextCalls(1);
        StatementImportJob suppressed = new StatementImportJob(bank, StatementImportJob.MONITOR_SLUG + "-suppressed");
        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                    suppressed.scheduledTask(FailureHandling.PROPAGATE), 0, RUN_PERIOD_MS, TimeUnit.MILLISECONDS);
            Thread.sleep(5 * RUN_PERIOD_MS);
            DemoConsole.step("   Po " + 5 * RUN_PERIOD_MS + " ms: uruchomień " + suppressed.runs()
                    + ", zadanie zakończone na stałe: " + (future.isDone() ? "tak" : "nie")
                    + ". Bank działa, ale nikt już nie importuje wyciągów.");
        }

        DemoConsole.step("C. Event w callbacku, check-in ERROR, granica schedulera zatrzymuje wyjątek");
        bank.timeOutNextCalls(1);
        StatementImportJob job = new StatementImportJob(bank, StatementImportJob.MONITOR_SLUG);
        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                    job.scheduledTask(FailureHandling.REPORT_AND_CONTINUE), 0, RUN_PERIOD_MS, TimeUnit.MILLISECONDS);
            while (job.runs() < 2) {
                Thread.sleep(10);
            }
            future.cancel(false);
        }
        DemoConsole.step("   Uruchomień: " + job.runs() + ". Po chwilowej awarii banku import działa dalej.");

        DemoConsole.lookAt("w konsoli A daje OK, choć importu nie było (log widać tylko na stderr). B daje ERROR, "
                + "potem cisza i żadnego eventu z przyczyną. C daje event z tagami component/operation "
                + "i tym samym trace co para check-inów ERROR, a następne uruchomienie kończy się OK.");
        DemoConsole.lookAt("w Sentry UI (Crons, lista check-inów) ...-swallowed ma OK za nieudany import, "
                + "...-suppressed ma Failed bez powiązanego Issue, a Failed w bank-statement-import ma powiązane Issue "
                + "awarii banku. Po demo nikt już nie wysyła check-inów, więc od następnej minuty każdy z tych "
                + "monitorów zgłasza Missed: tak wygląda job, który przestał działać. Missed nie ustaną same, "
                + "dlatego po obejrzeniu monitory usuwa się (src/main/java/pl/training/sentry/module06/README.md, „Sprzątanie po demo”).");
    }

    // Scenariusz 3: Job, który wisi.
    //
    // O co chodzi: timeout wykrywa tylko model start plus koniec, bo Sentry wie o uruchomieniu
    // dopiero z IN_PROGRESS. SDK nie ma statusów timeout ani missed: oba wylicza Sentry
    // z konfiguracji monitora (max_runtime, harmonogram, margines).
    //
    // Co pokazujemy: A: importOnce z withCheckIn, a bank w trybie HANGING przyjmuje połączenie i nie
    // odpowiada (klient bez timeoutu), więc wątek czeka bez końca. B: heartbeat (importWithHeartbeat),
    // czyli jeden check-in po zakończeniu pracy: najpierw udany przebieg, potem zawieszony na banku.
    //
    // Problem: zawieszony heartbeat nie wysyła nic. Sentry nie odróżni go od zadania, które nie
    // wystartowało, i nie oznaczy przekroczenia max_runtime, bo nie wie, że uruchomienie się zaczęło.
    //
    // Dobra praktyka: gdy zawieszenie trzeba wykryć, IN_PROGRESS na starcie (withCheckIn) i max_runtime
    // dopasowany do typowego czasu pracy: import trwa sekundy, więc limit to 1 minuta.
    //
    // Na co patrzeć: w konsoli A zostawia in_progress bez pary, a po 1 s wątek nadal czeka. B wysyła
    // ok bez in_progress za pierwszy przebieg, a za zawieszony nic. W Sentry UI po max_runtime (1 min)
    // check-in w ...-hanging zmienia się w Timeout, a ...-heartbeat pokazuje tylko Missed przy
    // następnym oczekiwanym terminie.
    //
    // Uruchomienie: -Dexec.args=3. Zawieszone wątki są demonami, a exec:java ma w pom.xml
    // cleanupDaemonThreads=false, więc program kończy się sam, bez check-inu końcowego, jak job
    // w zabitym procesie.
    static void hangingJob() throws Exception {
        DemoConsole.scenario(3, "Job, który wisi",
                "pokazać, że timeout wykrywa tylko model start plus koniec.");

        DemoConsole.step("A. withCheckIn, a bank przyjmuje połączenie i nie odpowiada (klient bez timeoutu)");
        BankGateway hangingBank = new BankGateway();
        hangingBank.switchTo(Mode.HANGING);
        Thread hanging = startDaemon("statement-import-hanging", () ->
                new StatementImportJob(hangingBank, StatementImportJob.MONITOR_SLUG + "-hanging").importOnce());
        Thread.sleep(1_000);
        DemoConsole.step("   Po 1 s wątek nadal czeka na bank: " + (hanging.isAlive() ? "tak" : "nie")
                + ". Check-in końcowy nie wyjdzie, dopóki wątek żyje (albo nigdy, jeśli proces zostanie zabity).");

        DemoConsole.step("B. Heartbeat: jeden check-in po zakończeniu pracy. Najpierw udany przebieg:");
        BankGateway bank = new BankGateway();
        StatementImportJob heartbeat = new StatementImportJob(bank, StatementImportJob.MONITOR_SLUG + "-heartbeat");
        heartbeat.importWithHeartbeat();
        DemoConsole.step("   Następny przebieg wisi na banku:");
        bank.switchTo(Mode.HANGING);
        startDaemon("statement-import-heartbeat", heartbeat::importWithHeartbeat);
        Thread.sleep(1_000);
        DemoConsole.step("   Po 1 s: brak jakiegokolwiek check-inu.");

        // Wątki zostają zawieszone do końca programu, jak job w produkcji. Są demonami, więc nie
        // blokują zakończenia JVM.
        DemoConsole.lookAt("w konsoli A zostawia IN_PROGRESS bez pary; SDK nie ma statusu timeout ani missed, "
                + "oba wylicza Sentry z konfiguracji monitora. B wysłało OK za pierwszym razem, a zawieszony "
                + "przebieg nie wysłał nic, więc wygląda jak zadanie, które nie wystartowało.");
        DemoConsole.lookAt("w Sentry UI po max_runtime (1 min) check-in w ...-hanging zmienia się w Timeout, "
                + "a ...-heartbeat pokazuje tylko Missed przy następnym oczekiwanym terminie: brak informacji, "
                + "że uruchomienie wystartowało i utknęło.");
    }

    // Scenariusz 4: Ręczne check-iny i nakładające się uruchomienia.
    //
    // O co chodzi: framework wsadowy woła osobno beforeExecution i afterExecution, więc cyklu życia
    // nie obejmie jeden callback withCheckIn. Check-in końcowy musi użyć SentryId zwróconego przy
    // IN_PROGRESS, a listener musi go przechować między wywołaniami.
    //
    // Co pokazujemy: jeden listener PayoutBatchCheckIns (wypłaty, crontab */15 8-19 * * 1-5) dla dwóch
    // replik, każda na własnym wątku. Oba uruchomienia startują, pierwsze kończy się sukcesem, drugie
    // błędem. A (SHARED_FIELD): identyfikator w polu singletonu. B (PER_EXECUTION): mapa po
    // identyfikatorze uruchomienia.
    //
    // Problem: w A drugie uruchomienie nadpisuje identyfikator pierwszego. Oba check-iny końcowe idą
    // z id drugiego, IN_PROGRESS pierwszego zostaje bez pary, a błąd drugiego ginie. Żaden wariant
    // nie blokuje równoległych uruchomień, bo monitor tylko obserwuje.
    //
    // Dobra praktyka: identyfikator check-inu per uruchomienie i nowy trace na starcie
    // (TracingUtils.startNewTrace). Bez identyfikatora lepiej nie wysłać nic niż samotny OK. Przed
    // podwójną wypłatą chroni blokada w aplikacji, a nie monitoring.
    //
    // Na co patrzeć: w konsoli w A ok i error mają id drugiego in_progress (ok z trace pierwszego
    // uruchomienia), a w B każde id dostaje własny wynik: ok i error. W Sentry UI
    // payout-batch-shared-field pokazuje pierwsze uruchomienie jako Timeout po 10 min, a błąd drugiego
    // ginie; payout-batch pokazuje OK i Failed. Po tym Timeout, ostatnim w demo, monitory modułu się
    // usuwa (README, „Sprzątanie po demo”).
    //
    // Uruchomienie: -Dexec.args=4
    static void overlappingRunsWithManualCheckIns() throws Exception {
        DemoConsole.scenario(4, "Ręczne check-iny i nakładające się uruchomienia",
                "pokazać, że SentryId z IN_PROGRESS musi trafić do check-inu tego samego uruchomienia.");

        for (IdStorage storage : IdStorage.values()) {
            String slug = storage == IdStorage.SHARED_FIELD ? "payout-batch-shared-field" : "payout-batch";
            DemoConsole.step((storage == IdStorage.SHARED_FIELD ? "A. " : "B. ") + storage
                    + ": dwie repliki startują wypłaty jednocześnie, pierwsza kończy się sukcesem, druga błędem");
            // Jeden listener (singleton w aplikacji), każde uruchomienie na własnym wątku,
            // jak w dwóch replikach. Wywołania idą po kolei, żeby wydruk był powtarzalny.
            PayoutBatchCheckIns listener = new PayoutBatchCheckIns(slug, storage);
            try (ExecutorService replica1 = Executors.newSingleThreadExecutor();
                 ExecutorService replica2 = Executors.newSingleThreadExecutor()) {
                DemoConsole.step("   start uruchomienia 1");
                replica1.submit(() -> listener.beforeExecution(1)).get();
                DemoConsole.step("   start uruchomienia 2");
                replica2.submit(() -> listener.beforeExecution(2)).get();
                DemoConsole.step("   koniec uruchomienia 1: sukces");
                replica1.submit(() -> listener.afterExecution(1, false)).get();
                DemoConsole.step("   koniec uruchomienia 2: błąd");
                replica2.submit(() -> listener.afterExecution(2, true)).get();
            }
        }

        DemoConsole.lookAt("w konsoli w wariancie A oba check-iny końcowe mają id drugiego uruchomienia: "
                + "IN_PROGRESS pierwszego zostaje bez pary, a sukces pierwszego ląduje w drugim. "
                + "W wariancie B każde id dostaje własny wynik: OK i ERROR.");
        DemoConsole.lookAt("w Sentry UI payout-batch-shared-field pokazuje pierwsze uruchomienie jako Timeout "
                + "(po 10 min), a błąd drugiego ginie; payout-batch pokazuje OK i Failed. Żaden monitor "
                + "nie zablokował podwójnych wypłat. Po tym Timeout, ostatnim w demo, usuń monitory modułu "
                + "(src/main/java/pl/training/sentry/module06/README.md, „Sprzątanie po demo”).");
    }

    // Scenariusz 5: Jedna awaria banku, jedno Issue.
    //
    // O co chodzi: grouping, filtry Alertów i Ownership Rules działają tylko na danych z eventu:
    // fingerprincie, tagach i level. Awaria banku ma dać jedno Issue, niezależnie od liczby płatności
    // i sprzedawców.
    //
    // Co pokazujemy: bank w trybie TIMING_OUT i po dwie płatności dwóch sprzedawców. A
    // (Reporting.CARELESS): wspólny pomocnik reportCarelessly z fingerprintem z nazwy klasy
    // i komunikatu. B (CLASSIFIED): reportBankTimeout z fingerprintem bank-timeout, bank-alfa,
    // tagami component, operation i bank oraz identyfikatorami w contexts; klient dostaje RETRY_LATER.
    //
    // Problem: komunikat zawiera id płatności i nazwę sprzedawcy. Sentry parametryzuje wartość
    // fingerprintu równą komunikatowi (id płatności zamienia na symbol), ale nazwy sprzedawcy nie,
    // więc A daje Issue na sprzedawcę i throttling per Issue nic nie ogranicza. Bez tagu component
    // nie zadziała też reguła Ownership ani filtr Alertu.
    //
    // Dobra praktyka: stabilny fingerprint jednej przyczyny, wymiary o kilku wartościach w tagach
    // (operation pokazuje, co dotknęła awaria), identyfikatory i sprzedawca w contexts.
    //
    // Na co patrzeć: w konsoli w A fingerprint zawiera cały komunikat i jest inny w każdym evencie,
    // a jedynym tagiem jest training.module. W B fingerprint bank-timeout, bank-alfa jest wspólny,
    // także z eventami ze scenariuszy 2C i 7. W Sentry UI A to osobne Issue na każdego sprzedawcę,
    // B jedno Issue z rozkładem tagu operation; reguła Ownership tags.component:payments i filtr
    // Alertu na component działają tylko dla B.
    //
    // Uruchomienie: -Dexec.args=5
    static void stableSignalData() {
        DemoConsole.scenario(5, "Jedna awaria banku, jedno Issue",
                "pokazać dane, na których działają grouping, filtry Alertów i Ownership Rules.");

        BankGateway bank = new BankGateway();
        bank.switchTo(Mode.TIMING_OUT);
        PaymentsEndpoint endpoint = new PaymentsEndpoint(new PaymentService(bank));

        // Komunikat banku zawiera identyfikator płatności i nazwę sprzedawcy. Wartość
        // fingerprintu równą komunikatowi Sentry parametryzuje (pay_3Mtw... staje się
        // pay_<random_id>), ale nazwa sprzedawcy zostaje, więc klucz i tak rośnie z liczbą
        // sprzedawców (sprawdzone na lokalnym Sentry; szczegóły w PaymentTelemetry).
        DemoConsole.step("A. Wspólny pomocnik raportujący: fingerprint z komunikatu, bez tagów domenowych");
        for (Payment payment : List.of(new Payment("pay_3MtwBwLkdIwHu7ix", "kwiaciarnia-roza", "tok-visa"),
                new Payment("pay_9QkdXzTaPbLmEe2w", "rowery-kolo", "tok-visa"))) {
            endpoint.charge(payment, Reporting.CARELESS);
        }

        DemoConsole.step("B. Klasyfikacja: stabilny fingerprint, tagi komponentu i operacji, id w contexts");
        for (Payment payment : List.of(new Payment("pay_4NvbKcRtYuWsQa1z", "kwiaciarnia-roza", "tok-visa"),
                new Payment("pay_7HjdLpQwErTyUi3o", "rowery-kolo", "tok-visa"))) {
            String response = endpoint.charge(payment, Reporting.CLASSIFIED);
            DemoConsole.step("   " + payment.id() + ": odpowiedź " + response);
        }

        DemoConsole.lookAt("w konsoli w A każdy event ma inny fingerprint, bo komunikat zawiera id płatności "
                + "i sprzedawcę. W B fingerprint bank-timeout, bank-alfa jest wspólny, także z eventami "
                + "ze scenariuszy 2C i 7, a płatność i sprzedawca są w contexts.");
        DemoConsole.lookAt("w Sentry UI A to osobne Issue na każdego sprzedawcę (w wartości równej komunikatowi "
                + "Sentry zastępuje id płatności symbolem, nazwy sprzedawcy nie), B to jedno Issue z rozkładem "
                + "tagu operation. Reguła Ownership "
                + "tags.component:payments i filtr Alertu na component zadziałają tylko dla B.");
    }

    // Scenariusz 6: Oczekiwane błędy: klasyfikacja albo filtr przed wysłaniem.
    //
    // O co chodzi: oczekiwany wyjątek można zgłosić jak defekt, sklasyfikować jako sygnał niskiego
    // priorytetu albo odciąć w SDK przed wysłaniem. Im wcześniej odrzuca się dane, tym większej
    // pewności trzeba: odrzuconego eventu nie da się potem policzyć ani przeanalizować.
    //
    // Co pokazujemy: A: odmowa karty przez reportCarelessly. B: odmowa przez reportDecline (warning,
    // tagi expected=true i decline.reason, fingerprint card-declined z powodem). C: to samo żądanie
    // dwa razy; drugie rzuca DuplicatePaymentException, granica requestu woła captureException,
    // a addIgnoredExceptionForType z PaymentTelemetry.configure odrzuca event w SDK.
    //
    // Problem: w A odmowa ma level error i brak tagów, więc wygląda jak defekt. Filtr w SDK jest
    // ostateczny: porównuje dokładną klasę (bez podklas), a wzorzec .*Timeout.* w setIgnoredErrors
    // odciąłby też BankTimeoutException i alert o awarii banku.
    //
    // Dobra praktyka: odmowę klasyfikować jak w B: dane zostają jako trend, Issue można zarchiwizować
    // do eskalacji, a Alert produkcyjny wyklucza expected:true. Filtr w SDK, po typie, tylko dla
    // przypadku znanego z pewnością i bez wartości diagnostycznej, jak powtórzone żądanie z C.
    //
    // Na co patrzeć: w konsoli A ma level error i tylko tag training.module, B level warning, tagi
    // expected=true, decline.reason i fingerprint card-declined, insufficient_funds. W C pierwsze
    // żądanie zwraca CHARGED, drugie ERROR i nie ma wydruku eventu. W Sentry UI Issue z B można
    // zarchiwizować do eskalacji (skok odmów wróci jako Escalating), a z C nie da się niczego policzyć.
    //
    // Uruchomienie: -Dexec.args=6
    static void expectedErrors() {
        DemoConsole.scenario(6, "Oczekiwane błędy: klasyfikacja albo filtr przed wysłaniem",
                "pokazać różnicę między szumem, sygnałem niskiego priorytetu i danymi odciętymi w SDK.");

        BankGateway bank = new BankGateway();
        PaymentsEndpoint endpoint = new PaymentsEndpoint(new PaymentService(bank));

        DemoConsole.step("A. Odmowa karty raportowana jak każdy inny błąd");
        endpoint.charge(new Payment("pay_5TgbNhYjUmIkOl8p", "kwiaciarnia-roza", BankGateway.CARD_WITHOUT_FUNDS), Reporting.CARELESS);

        DemoConsole.step("B. Odmowa karty sklasyfikowana: warning, expected=true, fingerprint po powodzie");
        endpoint.charge(new Payment("pay_6YhnMjUkIlOpAs9d", "kwiaciarnia-roza", BankGateway.CARD_WITHOUT_FUNDS), Reporting.CLASSIFIED);

        DemoConsole.step("C. Klient mobilny ponawia to samo żądanie po zerwanym połączeniu");
        Payment payment = new Payment("pay_8UjmKiLoPaSdFg0h", "rowery-kolo", "tok-visa");
        DemoConsole.step("   Pierwsze żądanie: " + endpoint.charge(payment, Reporting.CLASSIFIED));
        DemoConsole.step("   Powtórzone żądanie: " + endpoint.charge(payment, Reporting.CLASSIFIED)
                + ". Granica requestu zgłosiła wyjątek, filtr SDK go odrzucił.");

        DemoConsole.lookAt("w konsoli A ma level error i poza training.module żadnego tagu, więc wygląda jak defekt. B ma level warning "
                + "i tagi expected=true, decline.reason. Po C nie ma wydruku: event nie opuścił procesu.");
        DemoConsole.lookAt("w Sentry UI Issue z B można zarchiwizować do eskalacji, a Alert produkcyjny wyklucza "
                + "expected:true; skok odmów wróci jako Escalating. Z C nie da się w Sentry niczego policzyć.");
    }

    // Scenariusz 7: Endpoint zdrowia dla Uptime Monitora.
    //
    // O co chodzi: Uptime Monitor sprawdza endpoint HTTP z zewnątrz i widzi tylko to, co endpoint
    // weryfikuje. Próba niesie nagłówki User-Agent SentryUptimeBot i sentry-trace, więc backend z SDK
    // może kontynuować trace sprawdzenia i odróżnić ten ruch od ruchu użytkowników.
    //
    // Co pokazujemy: HealthEndpoint na lokalnym HttpServer i dwie próby z nagłówkami jak z Uptime
    // Monitora: A przy działającym banku, B przy banku w TIMING_OUT. /live zwraca zawsze 200, /health
    // robi bank.ping(), a przy awarii zgłasza reportBankTimeout (operation=health-check) i zwraca 503.
    //
    // Problem: monitor ustawiony na /live pokaże dostępność, gdy klienci nie mogą zapłacić. Bez
    // kontynuacji trace event z próby dostałby nowy trace i nie dałoby się go powiązać z Uptime Issue.
    //
    // Dobra praktyka: monitor na /health, który sprawdza zależność lekkim pingiem bez zmian stanu.
    // Sentry.continueTrace z nagłówka sentry-trace i tag traffic (uptime-check albo user) z User-Agent,
    // o dwóch wartościach, więc nadaje się do filtra Alertu. Odpowiedź bez szczegółów błędu.
    //
    // Na co patrzeć: w konsoli A daje /live 200 i /health 200, B /live 200 i /health 503 oraz event
    // z trace równym temu z kroku B, tagami traffic=uptime-check i operation=health-check. W Sentry UI
    // monitor na /health utworzy Uptime Issue po 3 kolejnych niepowodzeniach (domyślna Failure
    // Tolerance), a monitor na /live nie zauważy awarii. Lokalne self-hosted nie sprawdza adresów
    // prywatnych (README, „Uptime Monitor dla /health”).
    //
    // Uruchomienie: -Dexec.args=7. Endpoint startuje na wolnym porcie i zamyka się po scenariuszu.
    // Do prawdziwego Uptime Monitora służy HealthEndpoint.main na porcie 8086 (polecenie w README).
    static void healthEndpointForUptime() throws Exception {
        DemoConsole.scenario(7, "Endpoint zdrowia dla Uptime Monitora",
                "pokazać różnicę między /live i /health oraz trace próby w evencie aplikacji.");

        BankGateway bank = new BankGateway();
        try (HealthEndpoint endpoint = new HealthEndpoint(bank, 0);
             HttpClient http = HttpClient.newHttpClient()) {
            String sentryTrace = new SentryId() + "-" + new SpanId() + "-0";

            DemoConsole.step("A. Bank działa. Próba jak z Uptime Monitora (SentryUptimeBot, sentry-trace):");
            DemoConsole.step("   /live " + probe(http, endpoint, "/live", sentryTrace)
                    + ", /health " + probe(http, endpoint, "/health", sentryTrace));

            DemoConsole.step("B. Bank nie odpowiada. Ta sama próba, trace " + sentryTrace.substring(0, 8) + ":");
            bank.switchTo(Mode.TIMING_OUT);
            String live = probe(http, endpoint, "/live", sentryTrace);
            String health = probe(http, endpoint, "/health", sentryTrace);
            DemoConsole.step("   /live " + live + ", /health " + health);
        }

        DemoConsole.lookAt("w konsoli /live zwraca 200 przy niedziałającym banku, /health zwraca 503. Event z próby "
                + "ma trace równy temu z nagłówka sentry-trace i tag traffic=uptime-check.");
        DemoConsole.lookAt("w Sentry UI monitor na /health utworzy Uptime Issue po 3 kolejnych niepowodzeniach "
                + "(domyślna Failure Tolerance), a monitor na /live nie zauważy awarii. Lokalne self-hosted "
                + "nie sprawdza adresów prywatnych, więc prawdziwy monitor wymaga publicznego adresu: src/main/java/pl/training/sentry/module06/README.md.");
    }

    private static String probe(HttpClient http, HealthEndpoint endpoint, String path, String sentryTrace)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + endpoint.port() + path));
        HealthEndpoint.uptimeProbeHeaders(sentryTrace).forEach((name, values) -> values.forEach(v -> request.header(name, v)));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return String.valueOf(response.statusCode());
    }

    private static Thread startDaemon(String name, java.util.concurrent.Callable<?> work) {
        return Thread.ofPlatform().name(name).daemon().start(() -> {
            try {
                work.call();
            } catch (Exception ignored) {
                // Zawieszony wątek kończy się tylko razem z JVM; wyjątku tu nie oczekujemy.
            }
        });
    }
}
