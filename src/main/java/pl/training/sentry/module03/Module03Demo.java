package pl.training.sentry.module03;

import pl.training.sentry.module03.CheckoutService.CauseHandling;
import pl.training.sentry.module03.FakePaymentGateway.Reply;
import pl.training.sentry.module03.PaymentGrouping.Strategy;
import pl.training.sentry.module03.PaymentRetry.AttemptReporting;
import pl.training.sentry.module03legacy.LegacyLoyaltyClient;
import pl.training.sentry.support.DemoConsole;
import pl.training.sentry.support.DemoScenarios;
import pl.training.sentry.support.TrainingSentry;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

// Moduł 3: Triage issues i analiza błędów.
//
// Triage: od issue do decyzji
//
// Triage to analiza issue, która ma zmniejszyć niepewność i zakończyć się decyzją: w jakim zakresie
// danych patrzyliśmy, jakie fakty ustaliliśmy, jaka jest hipoteza przyczyny, kto wykonuje następny krok
// i po czym poznamy, że problem zniknął. Sama zmiana statusu albo priorytetu takiej decyzji nie
// zastępuje. Trzeba przy tym odróżniać dwa poziomy danych. Status, priorytet, przypisanie i komentarze
// należą do całego issue, a stack trace, breadcrumbs, tagi i contexts opisują jedno wybrane zdarzenie
// (event). Wniosek o całej grupie wymaga więc porównania kilku zdarzeń, np. Recommended, First i Latest,
// które Sentry UI podaje jako zdarzenie polecane, najstarsze i najnowsze w wybranym zakresie.
//
// Kod aplikacji nie podejmuje decyzji triage, ale wyznacza jej dane: czy zdarzenie niesie przyczynę
// błędu, które ramki są kodem aplikacji, co trafi do jednego issue, ile zdarzeń i użytkowników
// pokażą liczniki, co zostanie w breadcrumbs i komu Sentry przypisze issue.
//
// Łańcuch wyjątków i pole cause
//
// W Javie wyjątek może wskazywać inny wyjątek jako swoją przyczynę (cause). SDK zamienia cały taki
// łańcuch na listę wyjątków zdarzenia, każdy z typem, komunikatem i własnym stack trace, a Sentry UI
// pokazuje go w sekcji Exception jako kolejne pozycje „caused by”. Warstwa checkout opakowuje błędy
// bramki płatności w wyjątek CheckoutException (wrapper), który opisuje tylko granicę warstwy.
// Przydatna informacja leży głębiej: PaymentGatewayException niesie klasyfikację przyczyny, a gdy minął
// limit czasu, pod nim jest jeszcze HttpTimeoutException z JDK. Najgłębszy wyjątek też nie rozstrzyga
// sprawy, bo przekroczenie czasu może oznaczać awarię dostawcy, źle dobrany limit czasu klienta,
// wyczerpanie puli połączeń albo problem sieciowy.
//
// Jeśli kod tworzy nowy wyjątek bez cause i tylko dopisuje tekst przyczyny do komunikatu, w zdarzeniu
// zostaje jeden wyjątek. Przekroczenie czasu i HTTP 503 mają wtedy ten sam typ i te same ramki, więc
// trafiają do jednego issue, a jego tytuł, pokazujący komunikat jednego zdarzenia, sugeruje jedną
// przyczynę. Kod szukający PaymentGatewayException w łańcuchu nie ustawi też tagu z przyczyną ani
// własnego odcisku (fingerprint). Wyjątek opakowujący powinien więc zawsze przekazywać oryginał
// jako cause.
//
// Ramki in-app: który kod należy do aplikacji
//
// Każda ramka stack trace ma pole in_app, które mówi, czy to kod aplikacji, czy biblioteki, frameworka
// albo JDK. Od tego pola zależy więcej niż czytelność: grupowanie bierze pod uwagę przede wszystkim
// ramki in-app, a suspect commits (commity, które zmieniły linię albo plik z ramki in-app) Sentry
// wyznacza przez code mappings, czyli powiązanie ścieżek ze stack trace z plikami w repozytorium.
//
// Java SDK nie ma domyślnych prefiksów in-app. Bez options.addInAppInclude żadna ramka nie dostaje
// in_app=true i decyzja zostaje po stronie serwera. Prefiks jest porównywany z pełną nazwą klasy przez
// zwykłe startsWith, bez znajomości granic pakietów, dlatego pl.training.sentry.module03 obejmuje też
// pl.training.sentry.module03legacy, bibliotekę innego zespołu. Dopiero prefiks zakończony kropką
// obejmuje wyłącznie nasz pakiet. Zbyt szeroki prefiks oznaczy framework jako kod aplikacji, a zbyt
// wąski ukryje utrzymywaną bibliotekę wewnętrzną, więc wynik sprawdza się na produkcyjnym stack trace.
// Zmiana prefiksu nie musi rozbić istniejącego issue: sekcja Event Grouping Information pokazuje, jakie
// warianty grupowania policzył serwer i który połączył zdarzenia, np. liczony ze wszystkich ramek.
//
// Grupowanie i odcisk (fingerprint)
//
// Sentry łączy zdarzenia w issue według odcisku (fingerprint). Domyślnie wylicza go przede wszystkim ze
// stack trace, zwłaszcza z ramek in-app, oraz z typu i danych wyjątku, a z komunikatu dopiero wtedy,
// gdy brakuje silniejszego sygnału. Dlatego numer zamówienia w komunikacie nie rozbija issue, ale też
// HTTP 503 i HTTP 429 zgłaszane z tej samej linii klienta trafiają razem, choć to różne przyczyny:
// awaria bramki i przekroczony limit zapytań.
//
// Kod może ustawić odcisk sam przez event.setFingerprints, tu w callbacku beforeSend, który SDK
// uruchamia dla każdego zdarzenia tuż przed wysłaniem. Element {{ default }} oznacza odcisk domyślny,
// więc lista {{ default }}, payment-gateway i przyczyna zachowuje zwykłe grupowanie, a dodatkowo
// rozdziela zdarzenia według przyczyny. Dodawany wymiar musi mieć wartości z zamkniętego zbioru, tu
// z enuma przyczyn. Ta sama wartość trafia do tagu payment.failure_reason, po którym filtruje się
// issues i kieruje je do zespołów. Typowe są dwa błędy:
// - oversplitting (nadmierne rozbicie): identyfikator zamówienia, UUID albo surowa ścieżka URL
//   w odcisku dają osobne issue dla każdej wartości, więc wpływ rozkłada się na wiele issues. Czy
//   serwer taką wartość znormalizuje, zależy od konfiguracji grupowania i nie jest kontraktem;
// - overgrouping (nadmierne łączenie): jeden stały odcisk bez {{ default }} w całości zastępuje
//   domyślne grupowanie, łączy różne przyczyny w jednym issue, zawyża wpływ i wyłącza AI-Enhanced
//   Grouping, czyli dołączanie nowego zdarzenia do podobnego istniejącego issue.
// Zmiana odcisku działa wyłącznie na nowe zdarzenia: nie przepisuje historii i nie łączy istniejących
// issues, więc jej efekt sprawdza się kontrolowanym zdarzeniem.
//
// Liczniki issue i deduplikacja w SDK
//
// Event count to liczba przyjętych i zachowanych zdarzeń issue, a users to liczba unikalnych
// identyfikatorów użytkownika w tych zdarzeniach. Oba liczniki opisują to, co aplikacja wysłała,
// a nie skutek biznesowy. Zawyżają je ponowienia (retry) i zgłaszanie tego samego błędu na kilku
// warstwach, a zaniżają próbkowanie, filtry i limity. Wpływ ocenia się więc w jednostce biznesowej,
// tu w zamówieniach, których nie udało się złożyć. Z tego samego powodu priorytet issue, który dla
// Javy wynika z poziomu zdarzenia (error daje High), nie zastępuje oceny skutku.
//
// Java SDK ma deduplikację: odrzuca zdarzenie, jeśli ten sam obiekt wyjątku albo któraś z jego przyczyn
// została już wysłana. Każda próba tworzy jednak nowy obiekt wyjątku, więc captureException w każdej
// nieudanej próbie daje osobne zdarzenia, także gdy następna próba się udała i klient niczego nie
// odczuł. Co gorsza, gdy po wyczerpaniu prób checkout opakuje ostatni wyjątek w CheckoutException,
// deduplikacja odrzuci zgłoszenie z endpointu, bo jego cause był już wysłany, i znika jedyne zdarzenie
// mówiące, że zamówienie nie powstało. Wersja docelowa zapisuje próby jako breadcrumbs, historię
// ponowień jako context (dane jednego zdarzenia, widoczne w szczegółach, ale niesłużące do
// wyszukiwania), a zdarzenie wysyła raz warstwa, która wie, że operacja się nie udała.
//
// W tym module taką warstwą jest endpoint. W osobnym isolation scope żądania ustawia identyfikator
// techniczny klienta jako user, stały tag business.operation, context checkout i breadcrumb startu,
// a błąd zgłasza jednym wywołaniem captureException. Ręczne zgłoszenie nie ustawia poziomu ani pola
// handled: wydruk pokazuje domyślny poziom error i mechanizm chained, a Sentry uznaje takie zdarzenie
// za obsłużone. Obsłużony nie znaczy nieszkodliwy, bo zamówienie i tak nie powstało.
//
// Breadcrumbs: bufor o stałym rozmiarze
//
// Breadcrumbs to zapis kroków poprzedzających błąd, dołączany do zdarzenia. SDK trzyma je w buforze
// o stałym rozmiarze (maxBreadcrumbs, domyślnie 100), a po jego zapełnieniu każdy nowy wpis wypiera
// najstarszy. Wiele wpisów powstaje bez udziału kodu aplikacji: integracje klientów HTTP zapisują
// każde wywołanie z metodą, adresem i kodem odpowiedzi, a query string osobno w polu http.query.
// Odpytywanie bramki o status płatności co kilkaset milisekund potrafi w minutę zapełnić bufor
// identycznymi wpisami i wyprzeć początek przebiegu: start zamówienia i autoryzację płatności.
//
// SDK wywołuje callback beforeBreadcrumb przy dodawaniu każdego wpisu, zanim trafi on do bufora.
// Callback może wpis zmienić albo odrzucić (zwracając null), a odrzucony wpis nie zajmuje miejsca.
// To właściwe miejsce na usunięcie query string, w którym bywają tokeny i dane osobowe, oraz na
// odsianie szumu, np. udanych odpytań o status. Czyszczenie danych po stronie serwera, po którym
// Sentry UI pokazuje wartość [Filtered], działa dopiero wtedy, gdy dane opuściły już aplikację.
//
// Ownership Rules: kto odpowiada za issue
//
// Ownership Rules to reguły projektu, które na podstawie danych zdarzenia wskazują właścicieli
// (owners) issue: zespoły, zapisywane ze znakiem #, albo osoby, zapisywane adresem e-mail. Reguła
// path: porównuje wzorzec ze ścieżkami plików z ramek stosu, które dla Javy powstają z pakietu i nazwy
// pliku (pl/training/sentry/module03/CheckoutService.java), i obejmuje każdą ramkę, także spoza in-app.
// Reguła tags.NAZWA: porównuje wzorzec z wartością tagu, np. tags.payment.failure_reason:*. Wzorce są
// globami, a gwiazdka obejmuje dowolny ciąg znaków, także ukośnik.
//
// Reguły są oceniane od góry do dołu i rozstrzyga ostatnia pasująca, dlatego reguła ogólna stoi na
// górze, a szczegółowe niżej. Właściciele wszystkich pasujących reguł to suggested owners, czyli
// kandydaci widoczni w issue, a automatyczne przypisanie (auto-assignment) wybiera pierwszego
// właściciela ostatniej pasującej reguły. Stąd ta sama pułapka co przy prefiksie in-app: wzorzec
// path:pl/training/sentry/module03* bez ukośnika pasuje też do module03legacy, a ustawiony pod regułą
// biblioteki przejąłby jej issues. Reguły działają na ścieżkach i tagach ustawionych przez kod
// aplikacji: zdarzenie bez tagu przyczyny trafi według reguły ścieżki do zespołu checkout, a nie
// payments. Właścicieli może też wskazywać plik CODEOWNERS z repozytorium; Ownership Rules są oceniane
// po nim, więc mogą go przesłonić.
//
// Zespoły, przypisanie i komentarz triage
//
// Właścicielem w regule może być tylko ktoś z dostępem do projektu: zespół przypisany do projektu
// albo osoba z takiego zespołu. Inaczej Sentry odrzuci zapis całych reguł, dlatego konfiguracja idzie
// w stałej kolejności: zespoły, ich dostęp do projektu, członkostwo, a dopiero potem reguły i tryb
// automatycznego przypisania. Tryb Auto-assign to issue owner korzysta wyłącznie z Ownership Rules
// i CODEOWNERS, a tryb z suspect commits wymaga integracji z repozytorium.
//
// Issue ma jednego przypisanego (assignee): osobę albo zespół, który odpowiada za następny krok.
// Obecność na liście suggested owners nie oznacza przejęcia odpowiedzialności. Automatyczne
// przypisanie nie nadpisuje istniejącego assignee, więc ręczne przypisanie zostaje przy kolejnych
// zdarzeniach, a samo przypisanie nie zmienia statusu issue. Każda decyzja staje się wpisem w Activity,
// czyli historii issue; wpis o automatycznym przypisaniu podaje regułę, która o nim zdecydowała.
//
// Komentarz triage zapisuje zakres danych (projekt, środowisko, wersja, okres), fakty z liczników
// issue, hipotezę, następny krok z właścicielem i kryterium weryfikacji, żeby kolejna osoba mogła
// kontynuować bez powtarzania analizy. Widzi go każdy z dostępem do projektu, a przy synchronizacji
// komentarzy także podłączony tracker zadań, więc nie wkleja się do niego stack trace, tokenów ani
// danych osobowych. Mark reviewed oznacza, że issue zostało ocenione: młode issue przechodzi ze statusu
// New do Ongoing, ale nadal jest nierozwiązane. Nie jest to diagnoza ani naprawa, tak jak Resolve nie
// dowodzi, że poprawka działa, a Archive nie zatrzymuje napływu zdarzeń.
public final class Module03Demo {

    private static final String MODULE = "module03";

    private static final String ANNA = "c-7f3a9c";
    private static final String PIOTR = "c-19bd42";
    private static final String EWA = "c-5e21d0";

    private static final Duration GATEWAY_TIMEOUT = Duration.ofMillis(250);
    private static final Duration SLOW_REPLY = Duration.ofSeconds(2);
    // PRODUKCJA: odpytanie o status co około 500 ms przez minutę daje te same 120 wpisów.
    // W demo odstęp jest krótki, żeby scenariusz 5 trwał ułamek sekundy.
    private static final int MAX_STATUS_POLLS = 120;
    private static final Duration POLL_INTERVAL = Duration.ofMillis(2);
    private static final String ACCESS_TOKEN = "tok_live_9f3c1a7e";

    private static final LegacyLoyaltyClient LOYALTY = new LegacyLoyaltyClient();

    private Module03Demo() {
    }

    public static void main(String[] args) throws Exception {
        DemoScenarios scenarios = DemoScenarios.from(args, 1, 6);
        try (FakePaymentGateway gateway = FakePaymentGateway.start(SLOW_REPLY);
             PaymentGatewayClient client = new PaymentGatewayClient(gateway.baseUri(), GATEWAY_TIMEOUT,
                     MAX_STATUS_POLLS, POLL_INTERVAL, ACCESS_TOKEN, new HttpCallBreadcrumbs())) {
            if (scenarios.includes(1)) wrapperHidesCause(gateway, client);
            if (scenarios.includes(2)) inAppFrames(client);
            if (scenarios.includes(3)) fingerprints(gateway, client);
            if (scenarios.includes(4)) retryInflatesImpact(gateway, client);
            if (scenarios.includes(5)) breadcrumbBuffer(gateway, client);
            if (scenarios.includes(6)) ownership(gateway, client);
        }
    }

    // Scenariusz 1: wrapper bez cause ukrywa przyczynę.
    //
    // O co chodzi: warstwa checkout zamienia każdy błąd bramki w CheckoutException. Od sposobu opakowania
    // zależy, czy event niesie typ przyczyny, jej stack trace i klasyfikację, czyli dane, na których
    // opierają się grupowanie, tagi i triage.
    //
    // Co pokazujemy: te same dwie awarie bramki (timeout i HTTP 503) w dwóch wariantach CheckoutService.
    // A: CauseHandling.MESSAGE_ONLY, nowy wyjątek z tekstem przyczyny doklejonym do komunikatu.
    // B: CauseHandling.KEEP_CAUSE, nowy wyjątek z cause.
    //
    // Problem: w A obie awarie dają jeden wyjątek z tymi samymi ramkami, więc lądują w jednym issue,
    // a jego tytuł pokazuje komunikat tylko jednej z nich. PaymentGrouping nie znajduje w łańcuchu
    // PaymentGatewayException, więc nie ustawia tagu payment.failure_reason ani fingerprintu.
    //
    // Dobra praktyka: opakowanie zawsze z cause (wariant B). Event ma pełny łańcuch, tag z przyczyną
    // i fingerprint {{ default }} z przyczyną, więc każda przyczyna dostaje osobne issue.
    //
    // Na co patrzeć: w konsoli A ma tylko CheckoutException z przyczyną w komunikacie, bez tagu
    // payment.failure_reason i bez linii fingerprint. B ma caused by PaymentGatewayException (przy
    // timeoucie także HttpTimeoutException z JDK), tag z przyczyną i fingerprint. W Sentry UI A to jedno
    // issue z obiema awariami, B dwa issues (timeout i unavailable) z pełnym łańcuchem w sekcji Exception.
    //
    // Uruchomienie: -Dexec.args=1
    static void wrapperHidesCause(FakePaymentGateway gateway, PaymentGatewayClient client) {
        DemoConsole.scenario(1, "Wrapper bez cause ukrywa przyczynę",
                "pokazać, co zostaje w evencie, gdy checkout opakowuje błąd bramki bez cause, a co przy pełnym łańcuchu.");

        gateway.respond("ORD-1001", Reply.SLOW);
        gateway.respond("ORD-1002", Reply.UNAVAILABLE);
        gateway.respond("ORD-1003", Reply.SLOW);
        gateway.respond("ORD-1004", Reply.UNAVAILABLE);

        try (TrainingSentry.TrainingSession session = TrainingSentry.init(MODULE, CheckoutSentryConfig.TARGET)) {
            DemoConsole.step("A. Wrapper z samym komunikatem przyczyny: timeout (ORD-1001) i HTTP 503 (ORD-1002)");
            placeOrders(new CheckoutService(client, LOYALTY, CauseHandling.MESSAGE_ONLY),
                    Order.card("ORD-1001", ANNA), Order.card("ORD-1002", PIOTR));

            DemoConsole.step("B. Wrapper z cause: te same awarie, timeout (ORD-1003) i HTTP 503 (ORD-1004)");
            placeOrders(new CheckoutService(client, LOYALTY, CauseHandling.KEEP_CAUSE),
                    Order.card("ORD-1003", ANNA), Order.card("ORD-1004", PIOTR));
        }

        DemoConsole.lookAt("w konsoli w wariancie A każdy event ma tylko CheckoutException, bez caused by: przyczyna "
                + "jest tekstem w komunikacie, a tagu payment.failure_reason brak, bo beforeSend nie ma skąd go wziąć. "
                + "W wariancie B widać caused by: PaymentGatewayException, a przy timeout także HttpTimeoutException "
                + "z JDK, tag z przyczyną i fingerprint z nią związany.");
        DemoConsole.lookAt("w Sentry UI wariant A to jedno issue z oboma awariami, a tytuł pokazuje komunikat tylko "
                + "jednej z nich. Wariant B to dwa issues (timeout i unavailable) z pełnym łańcuchem w sekcji Exception.");
    }

    // Scenariusz 2: in-app frames, prefiks pakietu aplikacji.
    //
    // O co chodzi: ramki in-app oznaczają kod aplikacji i wpływają na grupowanie, Ownership Rules, code
    // mappings i suspect commits. Java SDK nie ma domyślnych prefiksów in-app, a addInAppInclude porównuje
    // nazwę klasy z prefiksem przez startsWith, bez interpretacji granic pakietów.
    //
    // Co pokazujemy: ten sam błąd (karta lojalnościowa w nowym formacie, LegacyLoyaltyClient rzuca
    // NumberFormatException) w trzech konfiguracjach: bez addInAppInclude, z prefiksem
    // pl.training.sentry.module03 i z prefiksem zakończonym kropką. EventPreview wypisuje pierwszą ramkę
    // in-app każdego wyjątku w łańcuchu.
    //
    // Problem: bez prefiksu żadna ramka nie ma in_app=true, więc decyzja zostaje po stronie serwera.
    // Prefiks bez kropki obejmuje też module03legacy, bibliotekę innego zespołu, i pierwsza ramka in-app
    // NumberFormatException wskazuje jej kod zamiast naszego.
    //
    // Dobra praktyka: prefiks pakietu aplikacji z kropką na końcu (CheckoutSentryConfig.IN_APP_PACKAGE).
    // Pierwsza ramka in-app to wtedy CheckoutService.placeOrder, czyli miejsce, w którym aplikacja woła
    // bibliotekę. Wynik konfiguracji sprawdza się na produkcyjnym stack trace.
    //
    // Na co patrzeć: w konsoli linie „· in-app”: bez prefiksu 0 ramek in-app, bez kropki pierwsza ramka
    // in-app NumberFormatException to LegacyLoyaltyClient.parseCardNumber, z kropką
    // CheckoutService.placeOrder. W Sentry UI trzy eventy w jednym issue, każdy z innymi ramkami aplikacji
    // w Stack Trace; Event Grouping Information pokazuje, że połączył je wariant ze wszystkimi ramkami.
    //
    // Uruchomienie: -Dexec.args=2
    static void inAppFrames(PaymentGatewayClient client) {
        DemoConsole.scenario(2, "In-app frames: prefiks pakietu aplikacji",
                "pokazać, które ramki trafiają do in-app bez prefiksu, z prefiksem bez kropki i z kropką na końcu.");

        // Klient z kartą w nowym formacie: stara biblioteka lojalnościowa rzuca NumberFormatException,
        // zanim checkout dojdzie do płatności. W stack trace są ramki JDK, biblioteki i aplikacji.
        CheckoutService checkout = new CheckoutService(client, LOYALTY, CauseHandling.KEEP_CAUSE);
        List<String> prefixes = Arrays.asList(null, "pl.training.sentry.module03", CheckoutSentryConfig.IN_APP_PACKAGE);
        int orderNumber = 2001;
        for (String prefix : prefixes) {
            DemoConsole.step(prefix == null
                    ? "Konfiguracja bez addInAppInclude (ORD-" + orderNumber + ")"
                    : "Konfiguracja addInAppInclude(\"" + prefix + "\") (ORD-" + orderNumber + ")");
            try (TrainingSentry.TrainingSession session = TrainingSentry.init(MODULE,
                    CheckoutSentryConfig.TARGET.withInAppPrefix(prefix).andThen(EventPreview.install()))) {
                placeOrders(checkout, Order.card("ORD-" + orderNumber, ANNA).withLoyaltyCard("LOY2-00A7F3"));
            }
            orderNumber++;
        }

        DemoConsole.lookAt("w konsoli linie „· in-app”: bez prefiksu żadna ramka nie ma in_app=true. Prefiks bez "
                + "kropki oznacza też bibliotekę innego zespołu (module03legacy), więc pierwsza in-app ramka "
                + "NumberFormatException wskazuje jej kod. Z kropką pierwsza in-app ramka to CheckoutService.placeOrder, "
                + "czyli miejsce, w którym aplikacja woła bibliotekę.");
        DemoConsole.lookAt("w Sentry UI trzy eventy w jednym issue, ale każdy z innymi ramkami aplikacji w Stack Trace. "
                + "Event bez prefiksu nie ma ramek in-app, bo serwer oznaczył wszystkie jako systemowe. W Event Grouping "
                + "Information wariant z ramkami in-app różni się między konfiguracjami (bez prefiksu nie bierze udziału), "
                + "a wariant ze wszystkimi ramkami ma ten sam hash i to on połączył eventy.");
    }

    // Scenariusz 3: fingerprint, oversplitting, overgrouping i stabilny wymiar.
    //
    // O co chodzi: fingerprint decyduje, które eventy Sentry liczy razem. Zostawienie {{ default }}
    // i dołożenie stabilnego wymiaru z zamkniętego zbioru wartości zachowuje domyślne grupowanie,
    // a rozdziela to, czego ono nie rozdzieli.
    //
    // Co pokazujemy: te same cztery awarie bramki (dwa razy 503, 429, timeout) przy czterech strategiach
    // PaymentGrouping w beforeSend: SDK_DEFAULT (bez fingerprintu), DEFAULT_PLUS_REQUEST_PATH, ONE_BUCKET
    // i DEFAULT_PLUS_REASON.
    //
    // Problem: domyślne grupowanie łączy 503 z 429, bo zgłasza je ta sama linia klienta. Surowa ścieżka
    // z numerem zamówienia w fingerprincie daje issue na każde zamówienie (oversplitting), a jeden stały
    // fingerprint wrzuca różne przyczyny do jednego issue i wyłącza AI-Enhanced Grouping (overgrouping).
    //
    // Dobra praktyka: DEFAULT_PLUS_REASON, czyli {{ default }} plus przyczyna z enuma. Awarie z tą samą
    // przyczyną trafiają razem niezależnie od zamówienia i klienta, a 503 i 429 do osobnych issues.
    //
    // Na co patrzeć: w konsoli linia fingerprint: brak przy SDK_DEFAULT, 4 różne ścieżki przy
    // DEFAULT_PLUS_REQUEST_PATH, jedna wartość przy ONE_BUCKET, przyczyna przy DEFAULT_PLUS_REASON (wspólną
    // mają tylko dwa eventy unavailable). W Sentry UI kolejno 2, 4, 1 i 3 issues.
    //
    // Uruchomienie: -Dexec.args=3
    static void fingerprints(FakePaymentGateway gateway, PaymentGatewayClient client) {
        DemoConsole.scenario(3, "Fingerprint: oversplitting, overgrouping i stabilny wymiar",
                "porównać, jak te same cztery awarie grupują się przy różnych fingerprintach.");

        // Dwie awarie bramki (503), limit zapytań (429) i timeout. 503 i 429 zgłasza ta sama linia
        // klienta, więc mają identyczny stack trace.
        gateway.respond("ORD-3001", Reply.UNAVAILABLE);
        gateway.respond("ORD-3002", Reply.UNAVAILABLE);
        gateway.respond("ORD-3003", Reply.RATE_LIMITED);
        gateway.respond("ORD-3004", Reply.SLOW);
        CheckoutService checkout = new CheckoutService(client, LOYALTY, CauseHandling.KEEP_CAUSE);

        for (Strategy strategy : Strategy.values()) {
            DemoConsole.step("PaymentGrouping." + strategy + ": 503 (ORD-3001, ORD-3002), 429 (ORD-3003), timeout (ORD-3004)");
            try (TrainingSentry.TrainingSession session = TrainingSentry.init(MODULE,
                    CheckoutSentryConfig.TARGET.withGrouping(strategy))) {
                placeOrders(checkout,
                        Order.card("ORD-3001", ANNA), Order.card("ORD-3002", PIOTR),
                        Order.card("ORD-3003", EWA), Order.card("ORD-3004", ANNA));
            }
        }

        DemoConsole.lookAt("w konsoli linia fingerprint: SDK_DEFAULT jej nie ma, DEFAULT_PLUS_REQUEST_PATH ma w niej "
                + "ścieżkę z numerem zamówienia (4 różne wartości), ONE_BUCKET jedną wartość dla wszystkich, "
                + "a DEFAULT_PLUS_REASON przyczynę: tylko dwa eventy unavailable dzielą fingerprint.");
        DemoConsole.lookAt("w Sentry UI SDK_DEFAULT daje 2 issues: 503 razem z 429 (ten sam stack trace, numer "
                + "zamówienia w komunikacie niczego nie rozdziela) i osobno timeout. DEFAULT_PLUS_REQUEST_PATH daje "
                + "4 issues po jednym evencie, ONE_BUCKET 1 issue z czterema, DEFAULT_PLUS_REASON 3 issues zgodne z przyczynami.");
    }

    // Scenariusz 4: retry mnoży eventy, event count, users i deduplikacja.
    //
    // O co chodzi: event count liczy zachowane eventy, a users unikalne tożsamości w nich, a nie wpływ
    // biznesowy. To, jak kod raportuje ponowienia, decyduje, co triage zobaczy w tych licznikach.
    //
    // Co pokazujemy: trzy zamówienia z retry do 3 prób: Anna i Piotr bez odpowiedzi bramki, Ewa udana
    // w drugiej próbie. Dwa warianty PaymentRetry.AttemptReporting: CAPTURE_EACH_FAILURE
    // (captureException w każdej nieudanej próbie) i BREADCRUMB_PER_ATTEMPT.
    //
    // Problem: CAPTURE_EACH_FAILURE daje 7 eventów i 3 users, w tym Ewę, której zamówienie przeszło. Każda
    // próba to nowy obiekt wyjątku, więc deduplikacja ich nie scala, za to odrzuca końcowy
    // CheckoutException, bo jego cause był już wysłany: znika jedyny event o niezłożonym zamówieniu.
    //
    // Dobra praktyka: próby jako breadcrumbs payment.retry, historia ponowień w contexcie payment_retry,
    // a jeden event wysyła endpoint po wyczerpaniu prób. Wynik: 2 eventy i 2 users.
    //
    // Na co patrzeć: w konsoli CAPTURE_EACH_FAILURE to 7 eventów PaymentGatewayException, a nad odpowiedzią
    // ORDER_NOT_PLACED brak eventu CheckoutException. BREADCRUMB_PER_ATTEMPT to 2 eventy CheckoutException
    // z próbami w breadcrumbs i contextem payment_retry, bez eventu dla ORD-4013 (Ewa). W Sentry UI issue
    // z 7 events i 3 users oraz issue z 2 events i 2 users.
    //
    // Uruchomienie: -Dexec.args=4
    static void retryInflatesImpact(FakePaymentGateway gateway, PaymentGatewayClient client) {
        DemoConsole.scenario(4, "Retry mnoży eventy: event count, users i deduplikacja",
                "pokazać, ile eventów i użytkowników zobaczy triage przy dwóch nieudanych i jednym odzyskanym zamówieniu.");

        for (AttemptReporting reporting : AttemptReporting.values()) {
            int base = reporting == AttemptReporting.CAPTURE_EACH_FAILURE ? 4001 : 4011;
            // Anna i Piotr: bramka nie odpowiada w żadnej z trzech prób. Ewa: pierwsza próba
            // kończy się timeoutem, druga się udaje, więc Ewa dostaje potwierdzenie zamówienia.
            gateway.respond("ORD-" + base, Reply.SLOW);
            gateway.respond("ORD-" + (base + 1), Reply.SLOW);
            gateway.respond("ORD-" + (base + 2), Reply.SLOW, Reply.AUTHORIZED);

            DemoConsole.step("PaymentRetry." + reporting + ", 3 próby: ORD-" + base + " (Anna), ORD-" + (base + 1)
                    + " (Piotr) bez odpowiedzi, ORD-" + (base + 2) + " (Ewa) udane w drugiej próbie");
            try (TrainingSentry.TrainingSession session = TrainingSentry.init(MODULE, CheckoutSentryConfig.TARGET)) {
                CheckoutService checkout = new CheckoutService(
                        new PaymentRetry(client, 3, reporting), LOYALTY, CauseHandling.KEEP_CAUSE);
                placeOrders(checkout, Order.card("ORD-" + base, ANNA), Order.card("ORD-" + (base + 1), PIOTR),
                        Order.card("ORD-" + (base + 2), EWA));
            }
        }

        DemoConsole.lookAt("w konsoli CAPTURE_EACH_FAILURE daje 7 eventów PaymentGatewayException od 3 użytkowników, "
                + "w tym event Ewy, której zamówienie przeszło. Nad odpowiedzią ORDER_NOT_PLACED nie ma eventu "
                + "CheckoutException: deduplikacja odrzuciła go, bo jego cause był już wysłany. "
                + "BREADCRUMB_PER_ATTEMPT daje 2 eventy CheckoutException z próbami w breadcrumbs i contextem payment_retry.");
        DemoConsole.lookAt("w Sentry UI issue z CAPTURE_EACH_FAILURE ma 7 events i 3 users, choć klientów bez "
                + "zamówienia jest dwoje. Issue z BREADCRUMB_PER_ATTEMPT ma 2 events i 2 users.");
    }

    // Scenariusz 5: breadcrumbs, limit bufora i beforeBreadcrumb.
    //
    // O co chodzi: bufor breadcrumbs ma stały rozmiar (maxBreadcrumbs, domyślnie 100), a nowe wpisy
    // wypierają najstarsze. Breadcrumbs HTTP zapisuje HttpCallBreadcrumbs tak jak integracje klientów HTTP,
    // z query string w http.query, więc kod aplikacji nie pisze ich ręcznie.
    //
    // Co pokazujemy: płatność BLIK, której klient nie potwierdza: bramka odpowiada PENDING, checkout
    // odpytuje o status 120 razy i kończy wyjątkiem z przyczyną no_decision. A: bez beforeBreadcrumb.
    // B: z BreadcrumbHygiene jako beforeBreadcrumb.
    //
    // Problem: w A udane odpytania wypierają z bufora start zamówienia i autoryzację (POST), więc przebiegu
    // nie da się odtworzyć. Każdy wpis niesie w http.query token dostępu, który opuszcza aplikację.
    //
    // Dobra praktyka: beforeBreadcrumb działa przy dodawaniu wpisu: BreadcrumbHygiene usuwa http.query
    // i odrzuca udane odpytania o status, więc nie zajmują miejsca w buforze. Wynik odpytywania opisuje
    // sam wyjątek.
    //
    // Na co patrzeć: w konsoli linia „· breadcrumbs”: w A 100 wpisów, najstarszy to GET .../status, a każdy
    // ma http.query z tokenem (wydruk eventu pokazuje 10 ostatnich); w B 2 wpisy, start zamówienia i POST
    // autoryzacji, bez query string. W Sentry UI Breadcrumbs eventu A to 100 identycznych GET .../status
    // z http.query [Filtered], zamaskowanym dopiero przez serwer, a eventu B krótki przebieg od startu.
    //
    // Uruchomienie: -Dexec.args=5
    static void breadcrumbBuffer(FakePaymentGateway gateway, PaymentGatewayClient client) {
        DemoConsole.scenario(5, "Breadcrumbs: limit bufora i beforeBreadcrumb",
                "pokazać, że szum techniczny wypiera z bufora kroki potrzebne do odtworzenia przebiegu.");

        // Płatność BLIK: klient nie potwierdza jej w aplikacji banku, więc bramka do końca
        // odpowiada PENDING, a checkout odpytuje o status aż do limitu.
        gateway.respond("ORD-5001", Reply.PENDING);
        gateway.respond("ORD-5002", Reply.PENDING);
        CheckoutService checkout = new CheckoutService(client, LOYALTY, CauseHandling.KEEP_CAUSE);

        DemoConsole.step("A. Bez beforeBreadcrumb, maxBreadcrumbs=100: BLIK ORD-5001, " + MAX_STATUS_POLLS + " odpytań o status");
        try (TrainingSentry.TrainingSession session = TrainingSentry.init(MODULE,
                CheckoutSentryConfig.TARGET.withoutBreadcrumbHygiene().andThen(EventPreview.install()))) {
            placeOrders(checkout, Order.blik("ORD-5001", ANNA));
        }

        DemoConsole.step("B. Z BreadcrumbHygiene jako beforeBreadcrumb: BLIK ORD-5002, ten sam przebieg");
        try (TrainingSentry.TrainingSession session = TrainingSentry.init(MODULE,
                CheckoutSentryConfig.TARGET.andThen(EventPreview.install()))) {
            placeOrders(checkout, Order.blik("ORD-5002", ANNA));
        }

        DemoConsole.lookAt("w konsoli w wariancie A „· breadcrumbs 100”, a najstarszy wpis to odpytanie o status: "
                + "start zamówienia i autoryzacja (POST) wypadły z bufora, a każdy wpis ma http.query z tokenem. "
                + "W wariancie B zostają 2 wpisy: start zamówienia i autoryzacja, bez query string.");
        DemoConsole.lookAt("w Sentry UI sekcja Breadcrumbs eventu A to 100 identycznych GET .../status, a eventu B "
                + "krótki przebieg od startu zamówienia. W A http.query ma wartość [Filtered]: token opuścił aplikację, "
                + "a zamaskowało go dopiero domyślne czyszczenie danych po stronie serwera.");
    }

    // Scenariusz 6: Ownership Rules, kto dostaje issue.
    //
    // O co chodzi: Ownership Rules dopasowują ścieżkę pliku z ramek stosu (path:) i tagi (tags.NAZWA:).
    // Reguły są oceniane od góry do dołu, rozstrzyga ostatnia pasująca, a z jej ownerów auto-assignment
    // bierze pierwszego. Dane, po których reguły działają, ustawia kod aplikacji.
    //
    // Co pokazujemy: reguły z OwnershipRules.RULES i dwa eventy z podglądem dopasowania w procesie
    // aplikacji (OwnershipRules.installPreview); ostatecznie decyduje Sentry. A: timeout bramki z ramkami
    // checkout i tagiem payment.failure_reason. B: karta lojalnościowa w nowym formacie, wyjątek
    // z biblioteki module03legacy.
    //
    // Problem: wzorzec path:pl/training/sentry/module03* bez ukośnika pasuje też do module03legacy, jak
    // prefiks in-app bez kropki w scenariuszu 2. Ustawiony pod regułą biblioteki wygrałby jako ostatni
    // pasujący i oddałby jej issues zespołowi checkout.
    //
    // Dobra praktyka: wzorce ścieżek z ukośnikiem na granicy pakietu, reguła ogólna na górze, szczegółowe
    // niżej. Suggested owners to kandydaci; za następny krok odpowiada jeden assignee.
    //
    // Na co patrzeć: w konsoli linia „· ownership”: w A pasują 2 z 4 reguł, rozstrzyga reguła tagu,
    // auto-assign #payments; w B ostatnia pasująca to reguła biblioteki, auto-assign jej opiekunka.
    // W Sentry UI (po TeamSetup i w trybie online) issue A ma assignee #payments i sugerowanych ownerów
    // #checkout i #payments, a issue B opiekunkę biblioteki. Dalszy triage pokazuje TriageWalkthrough.
    //
    // Uruchomienie: -Dexec.args=6
    static void ownership(FakePaymentGateway gateway, PaymentGatewayClient client) {
        DemoConsole.scenario(6, "Ownership Rules: kto dostaje issue",
                "pokazać, które reguły pasują do eventów checkout-api i kogo przypisze auto-assignment.");

        DemoConsole.step("Reguły projektu (OwnershipRules.RULES, w Sentry ustawia je TeamSetup):");
        OwnershipRules.parse(OwnershipRules.RULES).forEach(rule -> DemoConsole.step("   " + rule));

        gateway.respond("ORD-6001", Reply.SLOW);
        gateway.respond("ORD-6002", Reply.AUTHORIZED);
        CheckoutService checkout = new CheckoutService(client, LOYALTY, CauseHandling.KEEP_CAUSE);
        try (TrainingSentry.TrainingSession session = TrainingSentry.init(MODULE,
                CheckoutSentryConfig.TARGET.andThen(OwnershipRules.installPreview()))) {
            DemoConsole.step("A. Timeout bramki (ORD-6001): ramki checkout i tag payment.failure_reason");
            placeOrders(checkout, Order.card("ORD-6001", ANNA));
            DemoConsole.step("B. Karta lojalnościowa w nowym formacie (ORD-6002): wyjątek z biblioteki module03legacy");
            placeOrders(checkout, Order.card("ORD-6002", PIOTR).withLoyaltyCard("LOY2-00A7F3"));
        }

        DemoConsole.lookAt("w konsoli linia „· ownership”: w A pasują 2 z 4 reguł, czyli reguła ścieżki (#checkout) i reguła tagu "
                + "(#payments #checkout); rozstrzyga ostatnia, a z dwóch ownerów auto-assignment bierze pierwszego, "
                + "więc #payments. W B pasuje ścieżka checkout i ścieżka biblioteki; ostatnia wskazuje opiekunkę "
                + "biblioteki. PUŁAPKA: path:pl/training/sentry/module03* (bez ukośnika) pasuje też do module03legacy, "
                + "jak prefiks in-app bez kropki w scenariuszu 2; ustawiona pod regułą biblioteki oddałaby B zespołowi checkout.");
        DemoConsole.lookAt("w Sentry UI (po TeamSetup i trybie online) issue z A ma assignee #payments i sugerowanych "
                + "ownerów #checkout i #payments, a issue z B opiekunkę biblioteki. Suggested owners, przypisanie, "
                + "komentarz i status pokazuje TriageWalkthrough.");
    }

    /**
     * Wspólne miejsce wywołania: zamówienia z jednego kroku mają identyczne ramki stosu. Odpowiedź
     * endpointu jest wypisywana pod eventami, które request wysłał.
     */
    private static void placeOrders(CheckoutService checkout, Order... orders) {
        CheckoutEndpoint endpoint = new CheckoutEndpoint(checkout);
        for (Order order : orders) {
            String response = endpoint.handle(order);
            DemoConsole.step("   " + order.id() + ": odpowiedź " + response);
        }
    }
}
