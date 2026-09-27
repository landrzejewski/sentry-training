package pl.training.sentry.module04;

import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.DebugImage;
import org.springframework.context.ConfigurableApplicationContext;
import pl.training.sentry.module04.ReleasePipeline.Step;
import pl.training.sentry.support.DemoConsole;
import pl.training.sentry.support.DemoScenarios;
import pl.training.sentry.support.TrainingSentry;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static pl.training.sentry.module04.OrdersApiBuilds.RELEASE_184;
import static pl.training.sentry.module04.OrdersApiBuilds.RELEASE_185;
import static pl.training.sentry.module04.OrdersApiBuilds.REVISION_184;
import static pl.training.sentry.module04.OrdersApiBuilds.REVISION_185;
import static pl.training.sentry.module04.OrdersApiBuilds.SOURCE_BUNDLE_184;
import static pl.training.sentry.module04.OrdersApiBuilds.SOURCE_BUNDLE_185;

// Moduł 4: Wydania, source maps i CI/CD.
//
// Release: wersja, według której Sentry porządkuje zdarzenia
//
// Pole release (wersja aplikacji) to jedyna informacja w zdarzeniu (event), która mówi Sentry, z jakiej
// wersji kodu ono pochodzi. Stack trace tego samego błędu w wersji 184 i 185 może być identyczny, więc bez
// release nie da się ich odróżnić. Na podstawie release Sentry ustala, w której wersji issue (grupę
// podobnych zdarzeń) widziano po raz pierwszy (First Seen) i ostatni (Last Seen), jakie commity weszły
// między wydaniami, które z nich są podejrzane (suspect commits, czyli zmiany, które najpewniej wprowadziły
// błąd), kiedy wersję wdrożono i czy błąd wrócił po poprawce. Release nie dowodzi, że dane wdrożenie
// spowodowało błąd. Wyznacza granicę obserwacji i zawęża zbiór zmian, które trzeba sprawdzić.
//
// Z release łatwo pomylić trzy inne tożsamości, choć każda odpowiada na inne pytanie:
// - deploy (wdrożenie) to rekord, że dany release trafił do konkretnego środowiska; jeden release może mieć
//   wdrożenie najpierw na staging, a potem na produkcję;
// - identyfikator pliku diagnostycznego (w Javie UUID source bundle, w JavaScript debug ID) wskazuje
//   konkretny plik wynikowy i pasujące do niego dane diagnostyczne, a nie wersję produktu;
// - skrót artefaktu (np. SHA-256) wskazuje dokładny zestaw bajtów przeznaczony do wdrożenia i nic nie mówi o
//   wersji.
// Te wartości mogą pozostawać w relacji jeden do jednego, ale nie są synonimami. Chodzi o to, żeby wszystkie
// cztery opisywały ten sam build.
//
// Nazwa release: reguły Sentry i konwencja organizacji
//
// Serwer Sentry nie przyjmie jako release nazwy dłuższej niż 200 znaków, nazwy ze znakiem nowej linii,
// tabulatorem, ukośnikiem albo ukośnikiem wstecznym, wartości ".", "..", "latest" (w dowolnej wielkości
// liter) ani samych białych znaków. Zdarzenie z taką wartością serwer przyjmie, ale usunie z niego release i
// zapisze błąd przetwarzania invalid_data, więc w Sentry zdarzenie nie będzie miało wersji. Java SDK nazwy
// nie sprawdza i wysyła latest bez ostrzeżenia. Walidacja należy więc do pipeline i do startu aplikacji.
//
// Reguły serwera to tylko minimum. Release jest globalny w całej organizacji: jeśli orders-api i
// payments-api wyślą wartość 5.4.0, Sentry uzna ją za jedno wydanie powiązane z dwoma projektami. Goły SHA
// commita albo sam numer 5.4.1 są dla Sentry poprawne, ale w monorepo albo przy podobnej numeracji usług
// łączą historię różnych aplikacji. Konwencja komponent@wersja+build, np. orders-api@5.4.1+185, nie jest
// regułą Sentry, tylko polityką zespołu: prefiks rozdziela usługi, a numer buildu sprawia, że nazwa wskazuje
// jeden build. Przebudowanie tej samej wersji z innych bajtów wymaga nowej nazwy, bo wszystko, co Sentry
// liczy według release (regresje, commity, wdrożenia), wymiesza się dla obu buildów.
//
// Pole dist (dystrybucja) rozróżnia warianty w ramach jednego release, np. numer buildu albo wariant paczki.
// Nie zastępuje release ani środowiska i nie naprawia ponownego użycia nazwy: dwa buildy z różnych rewizji
// pod jedną nazwą nadal mają wspólną historię, a dist tylko je oznacza. Dla prostego backendu Java zwykle
// wystarcza jednoznaczny release bez dist.
//
// Release jest właściwością procesu i pochodzi z artefaktu
//
// SDK dopisuje release i dist do każdego zdarzenia z opcji ustawionych przy starcie (w Sentry.init), więc
// wszystkie zdarzenia jednej uruchomionej kopii aplikacji mają tę samą wersję, a kod obsługujący żądanie jej
// nie ustawia. Wartość musi zatem pochodzić z tego, z czego proces wystartował. Pipeline wylicza nazwę raz i
// zapisuje ją w artefakcie (tu w pliku build-info.properties, w Mavenie np. przez filtrowanie zasobów), a
// aplikacja odczytuje ją przy starcie. Wersji nie należy wyliczać przy starcie kontenera, np. wywołując Git:
// obraz może nie zawierać repozytorium, a każda replika ustalałaby własną tożsamość. Środowisko
// (environment) nie pochodzi z artefaktu, bo ten sam artefakt przechodzi przez kolejne środowiska.
//
// W trzech sytuacjach zdarzenia po cichu dostają inną wersję niż zapisana w artefakcie:
// - konfiguracja zewnętrzna. Sentry.init() bez argumentów oraz opcja enableExternalConfiguration scalają po
//   callbacku konfiguracji właściwości systemowe sentry.*, zmienne SENTRY_* i plik sentry.properties, a
//   wartość z zewnątrz wygrywa z ustawioną w kodzie. Parametr -Dsentry.release pozostawiony w manifeście
//   wdrożenia po poprzedniej wersji przypisze zdarzenia nowego kodu do starej wersji: błąd wygląda wtedy na
//   stary, a suspect commits wskazują zły zakres zmian;
// - walidacja w callbacku Sentry.init. SDK łapie każdy wyjątek rzucony w callbacku, zapisuje go w swoim
//   loggerze (domyślnie wyłączonym) i startuje z tym, co zdążyło się ustawić. Sprawdzenie nazwy i porównanie
//   jej z sentry.release oraz SENTRY_RELEASE musi więc nastąpić przed Sentry.init i zatrzymać start, zanim
//   powstanie pierwsze zdarzenie;
// - starter Spring Boot. Gdy aplikacja nie ustawi sentry.release, a opcja
//   sentry.use-git-commit-id-as-release ma domyślną wartość true, starter bierze git.commit.id z pliku
//   git.properties w artefakcie, czyli goły SHA. Jeśli CI rejestruje orders-api@<SHA>, jeden commit daje w
//   Sentry dwa niezależne release. Aplikacja powinna jawnie ustawić sentry.release na nazwę z pipeline.
//
// Regresja i rozwiązanie issue w konkretnej wersji
//
// Zwykłe oznaczenie issue jako rozwiązanego (Resolve) sprawia, że każde kolejne pasujące zdarzenie może
// oznaczać regresję, czyli powrót problemu (w Sentry UI status Regressed). Rozwiązanie zależne od release
// (Resolve > The next release albo Another existing release) wyznacza granicę wersji: zdarzenia z wersji
// starszych niż granica zostawiają issue rozwiązane, a zdarzenie z wersji granicznej lub nowszej oznacza je
// jako regresję. Ma to znaczenie przy rolling update, czyli wymianie replik po kolei, gdy stara replika
// przez chwilę obsługuje jeszcze ruch i wysyła spóźnione zdarzenia starej wersji. Dla nazw zgodnych z semver
// Sentry porównuje porządek wersji, a dla nazw opartych na SHA kolejność release, więc mieszanie konwencji w
// jednym projekcie osłabia ten mechanizm.
//
// Granica działa tylko dla zdarzeń z poprawnym release. Zdarzenie bez wersji, np. z odrzuconym latest,
// Sentry traktuje jak starsze od granicy, więc ten sam błąd z nowej repliki nie oznaczy issue jako regresji
// i pozostanie niezauważony.
//
// Czytelny stack trace Javy: source context i source bundle
//
// Ramka stack trace Javy zawiera klasę, metodę, nazwę pliku i numer linii z bytecode, o ile build ich nie
// usunął (javac zapisuje je domyślnie, opcja -g:none je usuwa). Nazwa pliku i numer linii to jednak jeszcze
// nie kod. Fragment źródła wokół ramki (source context) Sentry pokazuje dopiero wtedy, gdy ma treść pliku z
// dokładnie tej rewizji, która działała. Dostarcza ją JVM source bundle: paczka źródeł uporządkowanych
// według pakietów, wysłana do projektu w Sentry (sekcja Debug Files) i identyfikowana przez UUID. SDK
// oznacza ramki kodu aplikacji jako in-app według prefiksu pakietu, co wpływa na grupowanie, suspect commits
// i sposób prezentacji stack trace.
//
// Zdarzenie łączy z paczką wyłącznie UUID. W Mavenie sentry-maven-plugin (cel uploadSourceBundle) w jednym
// przebiegu buildu generuje UUID, zbiera źródła, wysyła bundle i zapisuje w JAR plik
// sentry-debug-meta.properties z wpisem io.sentry.bundle-ids=<UUID>. Sentry.init wczytuje ten plik, a SDK
// dołącza UUID do każdego zdarzenia w sekcji debug_meta jako obraz typu jvm z polem debug_id. Sentry szuka
// paczki o tym UUID i dopiero wtedy wyświetla kod przy ramkach. Dowodem poprawnej konfiguracji jest ten sam
// UUID w artefakcie, w Debug Files i w zdarzeniu. Bundle ID jest tożsamością niezależną od release: zły
// release nie psuje source context, a poprawny release go nie zapewnia.
//
// Najczęstsze błędy są ciche, bo zdarzenie wychodzi normalnie. SDK wczytuje plik tylko wtedy, gdy opcje nie
// mają jeszcze żadnego bundle ID, więc UUID wpisany w kod (addBundleId, np. skopiowany z poprzedniego
// buildu) wyłącza plik z bieżącego buildu, a zdarzenie wskazuje paczkę innej rewizji. Build bez source
// bundle, np. job bez tokenu Sentry albo z opcją skipSourceBundle, daje zdarzenia bez debug_meta, czyli bez
// source context. Liczy się też czas: Sentry dopasowuje pliki diagnostyczne przy przyjęciu zdarzenia i nie
// przetwarza starszych zdarzeń ponownie, więc paczka musi trafić do Sentry przed pierwszym zdarzeniem z
// nowego artefaktu.
//
// Source maps: ten sam problem we frontendzie
//
// Source maps dotyczą kodu JavaScript i opisuje je część frontendowa szkolenia. Kod uruchamiany w
// przeglądarce przechodzi transpilację, łączenie modułów i minifikację, więc stack trace wskazuje linię i
// kolumnę w pliku wynikowym. Source map to indeks, który przekłada tę pozycję na plik, linię, kolumnę i
// opcjonalnie nazwę w kodzie źródłowym. Narzędzie budujące wstrzykuje ten sam debug ID do pliku wynikowego i
// do jego mapy, SDK wysyła go w debug_meta, a Sentry dopasowuje mapę po tym identyfikatorze, a nie po
// release. Zasady są takie same jak w Javie: identyfikator z finalnego pliku i upload przed pierwszym
// zdarzeniem. Zwykłego stack trace Javy source maps nie dotyczą.
//
// Pipeline: jeden artefakt, jedna nazwa, właściwa kolejność
//
// Release w Sentry to nie tylko pole zdarzenia, ale też obiekt z własnym cyklem życia, którym zarządza
// pipeline, np. przez sentry-cli. Rejestracja release przed wdrożeniem sprawia, że commity i metadane są
// gotowe, zanim przyjdą zdarzenia; release utworzony automatycznie przez pierwsze zdarzenie nie dostaje
// kontekstu z pipeline. Powiązanie commitów daje listę zmian, autorów i suspect commits. Rekord deploy mówi,
// że release trafił do środowiska, więc powinien powstać dopiero po potwierdzonym wdrożeniu, bo zapisany
// wcześniej opisuje intencję, a nie fakt. Powrót do istniejącego artefaktu (rollback) to nowy rekord deploy
// bez nowej nazwy release.
//
// Zasada build once, deploy many mówi, że artefakt buduje się raz, a potem się go zamyka: od tej chwili jego
// bajty i skrót się nie zmieniają, a upload danych diagnostycznych, publikacja, wdrożenia na kolejne
// środowiska i rollback dotyczą tych samych bajtów. Ponowny build tej samej rewizji w jobie wdrożeniowym
// daje inne bajty, a przy source bundle także nowy UUID, którego nikt nie wysłał do Sentry. Skrót SHA-256
// nie jest release Sentry, tylko wewnętrznym dowodem, że wdrożono dokładnie to, co zamknięto.
//
// Kontrakt jednego wdrożenia wygląda więc tak: pipeline wylicza release raz, a ta sama nazwa trafia do
// artefaktu (i z niego do SDK), do rejestracji w Sentry i do rekordu deploy; paczka o UUID zapisanym w
// artefakcie trafia do Sentry przed wdrożeniem; rekord deploy powstaje po wdrożeniu. Narzędzia mają własne
// wartości domyślne, np. akcja release dla GitHub Actions bez jawnej nazwy używa gołego SHA, a każda
// rozbieżność tworzy w Sentry drugi, niezależny release. Zielony status każdego joba żadnego z tych warunków
// nie dowodzi, dlatego pipeline powinien sprawdzać je jawnie przed wdrożeniem.
public final class Module04Demo {

    private static final CouponEndpoint COUPONS = new CouponEndpoint();

    /** Ostatni event przekazany do transportu, na potrzeby wydruku dist i debug_meta. */
    private static final AtomicReference<SentryEvent> LAST_SENT = new AtomicReference<>();

    private Module04Demo() {
    }

    public static void main(String[] args) {
        DemoScenarios scenarios = DemoScenarios.from(args, 1, 6);
        if (scenarios.includes(1)) releaseNameRules();
        if (scenarios.includes(2)) releaseFromArtifact();
        if (scenarios.includes(3)) springBootGitCommitAsRelease();
        if (scenarios.includes(4)) regressionAfterRelease();
        if (scenarios.includes(5)) sourceBundleInEvent();
        if (scenarios.includes(6)) pipelineContract();
    }

    // Scenariusz 1: nazwa release, reguły Sentry i konwencja zespołu.
    //
    // O co chodzi: release to oś, po której Sentry liczy regresje, commity i deploye. Serwer
    // odrzuca część nazw, a Java SDK ich nie waliduje. Konwencja komponent@wersja+build nie jest
    // regułą Sentry, tylko polityką organizacji, w której release jest globalny.
    //
    // Co pokazujemy: A: ReleaseName sprawdza listę kandydatów i dla poprawnych podaje komponent.
    // B: dwa buildy z różnych rewizji startują pod jedną nazwą orders-api@5.4.1, różniąc się tylko
    // dist (numer buildu CI 185 i 186) i bundle ID.
    //
    // Problem: latest (w każdej wielkości liter), kropka, ukośnik, tabulator, 201 znaków i same spacje
    // to nazwy, których serwer nie zapisze jako release. Goły SHA i 5.4.1 przejdą, ale bez prefiksu
    // usługi zleją się z inną usługą o tej samej wartości. W B historia dwóch buildów miesza się
    // w jednym release, a dist tego nie rozdzieli.
    //
    // Dobra praktyka: nazwa z prefiksem usługi, jednoznaczna dla jednego buildu
    // (orders-api@5.4.1+185), zwalidowana przez ReleaseName w CI i przy starcie aplikacji.
    //
    // Na co patrzeć: w konsoli w A powód odrzucenia przy każdej złej nazwie i „bez prefiksu usługi”
    // przy SHA i 5.4.1; w B dwa eventy z release orders-api@5.4.1, dist 185 i 186 oraz różnymi UUID
    // w debug_meta. W Sentry UI (Releases) jeden release orders-api@5.4.1 z eventami dwóch buildów.
    //
    // Uruchomienie: -Dexec.args=1
    static void releaseNameRules() {
        DemoConsole.scenario(1, "Nazwa release: reguły Sentry i konwencja zespołu",
                "sprawdzić, których nazw Sentry nie przyjmie jako release, a które przyjmie, choć zepsują analizę wersji.");

        DemoConsole.step("A. Kandydaci na nazwę release sprawdzeni przez ReleaseName");
        for (String candidate : List.of(RELEASE_185.value(), "latest", "LATEST", ".", "orders-api/5.4.1",
                "orders-api@5.4.1\t185", "orders-api@" + "9".repeat(190), "   ", REVISION_185, "5.4.1")) {
            DemoConsole.step("   " + describeCandidate(candidate));
        }

        // Dwa różne buildy (inne rewizje) pod jedną nazwą. dist ma je rozróżnić, ale Sentry grupuje
        // regresje, commity i deploye po release, więc historia obu buildów się miesza.
        // dist 185 i 186 to tu numery buildu CI, a nie release 184 i 185 z pozostałych scenariuszy.
        DemoConsole.step("B. Dwa buildy z różnych rewizji pod jedną nazwą orders-api@5.4.1, rozróżnione tylko dist "
                + "(numer buildu CI) i bundle ID");
        String reused = "orders-api@5.4.1";
        startAndApplyCoupon(OrdersApiStartup.sentryConfiguration(
                BuildArtifact.build(reused, REVISION_184, "185", SOURCE_BUNDLE_184)), "ORD-1001");
        startAndApplyCoupon(OrdersApiStartup.sentryConfiguration(
                BuildArtifact.build(reused, REVISION_185, "186", SOURCE_BUNDLE_185)), "ORD-1002");

        DemoConsole.lookAt("w konsoli ReleaseName odrzuca to, czego serwer Sentry nie zapisze jako release: latest "
                + "w każdej wielkości liter, kropkę, ukośnik, tabulator, 201 znaków i same spacje (event z taką "
                + "wartością serwer przyjmie, ale bez release, scenariusz 4C). Goły SHA i 5.4.1 przechodzą reguły Sentry, "
                + "ale bez prefiksu usługi zleją się z innym projektem o tej samej wartości. W wariancie B oba "
                + "eventy mają ten sam release, a różny dist i bundle ID.");
        DemoConsole.lookAt("w Sentry UI (Releases) jeden release orders-api@5.4.1 z eventami dwóch buildów. "
                + "Commity, regresje i deploye liczą się per release, więc dist ich nie rozdzieli.");
    }

    // Scenariusz 2: release z artefaktu, a nie z konfiguracji uruchomieniowej.
    //
    // O co chodzi: release jest właściwością procesu, więc powinien pochodzić z artefaktu, z którego
    // proces wystartował (build-info.properties zapisane przez CI). Wartość z manifestu wdrożenia
    // może opisywać inną wersję niż kod, który faktycznie działa.
    //
    // Co pokazujemy: A: artefakt 185 z release z build-info.properties. B: ten sam artefakt,
    // w manifeście zostało -Dsentry.release z wersji 184, SDK z włączoną konfiguracją zewnętrzną.
    // C: ten sam manifest, kontrola release przed Sentry.init. D: ręczny build z release=latest,
    // walidacja wewnątrz callbacku Sentry.init.
    //
    // Problem: w B wartość z zewnątrz wygrywa z kodem i eventy kodu 185 trafiają do wersji 184, więc
    // błąd wygląda na stary, a suspect commits wskazują zły zakres zmian. W D Sentry.init połyka
    // wyjątek z callbacku i startuje z tym, co zdążyło się ustawić: release szkoleniowy, bez debug_meta.
    //
    // Dobra praktyka: release wyłącznie z artefaktu, walidacja i porównanie z sentry.release
    // i SENTRY_RELEASE przed Sentry.init. Rozbieżność zatrzymuje start, zanim powstanie event (C).
    //
    // Na co patrzeć: w konsoli B ma release orders-api@5.4.0+184, a debug_meta nadal z UUID
    // artefaktu 185; C kończy się komunikatem „Start przerwany” bez eventu; D ma release
    // sentry-training@1.0.0 i debug_meta (brak). W Sentry UI błąd z B trafia do wersji 184, a fragment
    // kodu przy ramkach (po wysłaniu bundle 185) jest poprawny, bo release i bundle ID to niezależne
    // tożsamości.
    //
    // Uruchomienie: -Dexec.args=2
    static void releaseFromArtifact() {
        DemoConsole.scenario(2, "Release z artefaktu, a nie z konfiguracji uruchomieniowej",
                "pokazać, skąd SDK bierze release i co się dzieje, gdy runtime podaje inną wartość.");
        BuildArtifact build185 = OrdersApiBuilds.build185();

        DemoConsole.step("A. Artefakt 185, release z build-info.properties zapisanego przez CI");
        startAndApplyCoupon(OrdersApiStartup.sentryConfiguration(build185), "ORD-2001");

        // Manifest wdrożenia (np. JAVA_TOOL_OPTIONS) z poprzedniej wersji. Demo ustawia właściwość
        // systemową w procesie, co SDK widzi tak samo jak -Dsentry.release w linii poleceń.
        withSystemProperty("sentry.release", RELEASE_184.value(), () -> {
            DemoConsole.step("B. Ten sam artefakt 185, w manifeście zostało -Dsentry.release=" + RELEASE_184
                    + ", SDK z konfiguracją zewnętrzną");
            startAndApplyCoupon(OrdersApiStartup.withExternalConfiguration(build185), "ORD-2002");

            DemoConsole.step("C. Ten sam manifest, kontrola release przed Sentry.init");
            try {
                OrdersApiStartup.sentryConfiguration(build185);
            } catch (IllegalStateException exception) {
                DemoConsole.step("   Start przerwany: " + exception.getMessage());
            }
        });

        // Ręczny build bez pipeline: ktoś podał release latest, "żeby zawsze był najnowszy".
        DemoConsole.step("D. Artefakt z ręcznego buildu z release=latest, walidacja wewnątrz callbacku Sentry.init");
        BuildArtifact manualBuild = BuildArtifact.build("latest", REVISION_185, null, SOURCE_BUNDLE_185);
        startAndApplyCoupon(OrdersApiStartup.validatingInsideInit(manualBuild), "ORD-2003");

        DemoConsole.lookAt("w konsoli wariant B ma release " + RELEASE_184 + ", choć działa kod 185: "
                + "wartość z zewnątrz wygrała z kodem, a bundle ID nadal pochodzi z artefaktu 185. C kończy się "
                + "przed pierwszym eventem. W D wyjątek walidacji zniknął, event ma release szkoleniowy "
                + TrainingSentry.DEFAULT_RELEASE + " i nie ma debug_meta, bo callback przerwał się przed resztą konfiguracji.");
        DemoConsole.lookAt("w Sentry UI błąd z B trafia do wersji 184, więc wygląda na stary problem, a suspect "
                + "commits i porównanie wersji wskazują zły zakres zmian. Fragment kodu przy ramkach (po wysłaniu "
                + "bundle 185) jest przy tym poprawny: release i bundle ID to niezależne tożsamości.");
    }

    // Scenariusz 3: Spring Boot, goły SHA zamiast nazwy z CI.
    //
    // O co chodzi: starter Sentry dla Spring Boot, gdy aplikacja nie ustawi release, a opcja
    // sentry.use-git-commit-id-as-release ma domyślne true, bierze git.commit.id z git.properties
    // w artefakcie. CI rejestruje tymczasem release w swojej konwencji, orders-api@<SHA>.
    //
    // Co pokazujemy: OrdersApiSpringApp startuje dwa razy z tym samym git.properties. A: bez
    // sentry.release. B: z sentry.release przekazanym przez build (w usłudze np. filtrowanie
    // zasobów Maven w application.properties).
    //
    // Problem: ten sam commit daje dwie różne wartości release. W Sentry powstają dwa niezależne
    // release, a commity i deploye z CI trafiają tylko do jednego z nich.
    //
    // Dobra praktyka: aplikacja ustawia sentry.release na tę samą nazwę, którą rejestruje CI (B),
    // zamiast polegać na wartości wyznaczonej przez starter.
    //
    // Na co patrzeć: w konsoli zamiast linii „Sentry [module04]” jest „Spring Boot wystartował,
    // SDK ma release=...”: w A goły SHA, w B ten sam SHA z prefiksem orders-api@; pod eventem nie ma
    // dist i debug_meta. W Sentry UI (Releases) dwa release utworzone przez eventy.
    //
    // Uruchomienie: -Dexec.args=3
    static void springBootGitCommitAsRelease() {
        DemoConsole.scenario(3, "Spring Boot: goły SHA zamiast nazwy z CI",
                "pokazać, że starter Sentry sam wyznacza release z git.properties w innym formacie niż CI.");
        DemoConsole.step("CI zarejestrowało release " + OrdersApiSpringApp.CI_RELEASE);

        DemoConsole.step("A. Aplikacja bez sentry.release, w artefakcie git.properties z buildu");
        runSpringApp(null, "ORD-3001");

        DemoConsole.step("B. Aplikacja z sentry.release przekazanym przez build");
        runSpringApp(OrdersApiSpringApp.CI_RELEASE, "ORD-3002");

        DemoConsole.lookAt("w konsoli wariant A ma release będący samym SHA, a B ten sam SHA z prefiksem "
                + "orders-api@. To dwie różne wartości, choć opisują jeden commit.");
        DemoConsole.lookAt("w Sentry UI (Releases) dwa release: " + REVISION_185.substring(0, 12) + "... i "
                + OrdersApiSpringApp.CI_RELEASE.substring(0, 23) + "..., oba utworzone przez eventy. W prawdziwym "
                + "pipeline commity i deploye z CI trafiłyby tylko do drugiego, a eventy z wariantu A do pierwszego.");
    }

    // Scenariusz 4: regresja po wydaniu poprawki.
    //
    // O co chodzi: kod jest jeden, a o tym, w której wersji wystąpił błąd, mówi Sentry tylko release
    // eventu. Od niego zależy First Seen, Last Seen i to, czy event po rozwiązaniu issue w danym
    // release jest regresją. Dlatego release musi ustawiać proces, który wysłał event.
    //
    // Co pokazujemy: ten sam błąd kuponu (kod małymi literami) z czterech procesów. A: wersja 184.
    // B: stara replika 184 w trakcie rolling update do 185. C: replika 185 zrestartowana ręcznie
    // z -Dsentry.release=latest przy konfiguracji zewnętrznej. D: replika 185, w której poprawka
    // nie objęła tego przypadku. Jedna ścieżka wywołań, więc jedno issue.
    //
    // Problem: Java SDK wysyła latest bez ostrzeżenia, a serwer przyjmuje event bez release (błąd
    // przetwarzania invalid_data). Sentry traktuje go jak starszy od granicy, więc błąd z repliki
    // 185 nie oznaczy rozwiązanego issue jako regresji.
    //
    // Dobra praktyka: każdy proces ustawia release z artefaktu (A, B, D). Rozwiązanie issue w release
    // wyznacza wtedy granicę wersji: spóźnione eventy repliki 184 nie są regresją, event z 185 jest.
    //
    // Na co patrzeć: w konsoli ten sam wyjątek z tym samym komunikatem, release 184, 184, latest
    // i 185, bundle ID z artefaktu 184 albo 185 (ramki stosu dopiero z -Dsentry.demo.json=true).
    // W Sentry UI jedno issue z First Seen w 184 i Last Seen w 185, event C bez release. Eksperyment:
    // Resolve > Another existing release > orders-api@5.4.1+185 i ponownie -Dexec.args=4; issue
    // przechodzi w Regressed dopiero przez event z 185.
    //
    // Uruchomienie: -Dexec.args=4
    static void regressionAfterRelease() {
        DemoConsole.scenario(4, "Regresja po wydaniu poprawki",
                "pokazać, że o regresji decyduje release eventu, więc musi go ustawiać proces, który wysłał event.");
        // Replika C startuje z konfiguracją zewnętrzną i -Dsentry.release=latest w linii poleceń.
        // Pozostałe startują w wersji docelowej. Kolejność kroków ma znaczenie dla eksperymentu
        // w UI: event 185 idzie ostatni, więc eventy 184 i event bez release trafiają najpierw do
        // rozwiązanego issue i widać, że go nie otwierają.
        record Deployment(String description, String orderId, BuildArtifact artifact, String releaseOverride) {
        }
        List<Deployment> deployments = List.of(
                new Deployment("A. Wersja 184: klient wpisuje kod kuponu małymi literami",
                        "ORD-4001", OrdersApiBuilds.build184(), null),
                new Deployment("B. Rolling update do 185: stara replika 184 obsługuje jeszcze request",
                        "ORD-4002", OrdersApiBuilds.build184(), null),
                new Deployment("C. Replika 185 zrestartowana ręcznie z -Dsentry.release=latest",
                        "ORD-4003", OrdersApiBuilds.build185(), "latest"),
                new Deployment("D. Replika 185: poprawka nie objęła kodów wpisanych małymi literami",
                        "ORD-4004", OrdersApiBuilds.build185(), null));
        for (Deployment deployment : deployments) {
            DemoConsole.step(deployment.description());
            // Jedna ścieżka wywołań dla wszystkich kroków: te same ramki stosu, więc jedno issue.
            Consumer<SentryOptions> configuration = deployment.releaseOverride() == null
                    ? OrdersApiStartup.sentryConfiguration(deployment.artifact())
                    : OrdersApiStartup.withExternalConfiguration(deployment.artifact());
            withSystemProperty("sentry.release", deployment.releaseOverride(),
                    () -> startAndApplyCoupon(configuration, deployment.orderId()));
        }

        DemoConsole.lookAt("w konsoli ten sam wyjątek z tym samym komunikatem (ramki stosu pokazuje -Dsentry.demo.json=true), "
                + "różny release i bundle ID z artefaktu. "
                + "W C SDK wysłało latest bez ostrzeżenia.");
        DemoConsole.lookAt("w Sentry UI jedno issue z First Seen w 184 i Last Seen w 185. Event C nie ma release "
                + "i ma błąd przetwarzania invalid_data. Eksperyment: Resolve > Another existing release > "
                + RELEASE_185 + ", potem ponownie -Dexec.args=4. Eventy 184 i event bez release zostawiają issue "
                + "rozwiązane, event 185 oznacza je jako Regressed.");
    }

    // Scenariusz 5: source context, bundle ID z artefaktu w evencie.
    //
    // O co chodzi: Sentry pokazuje fragment kodu przy ramkach Javy, gdy debug_meta eventu wskazuje
    // UUID wysłanego JVM source bundle. SDK czyta ten UUID przy starcie z sentry-debug-meta.properties
    // (io.sentry.bundle-ids), który w produkcji zapisuje do JAR sentry-maven-plugin.
    //
    // Co pokazujemy: A: artefakt 185 z plikiem, bundle 1740e7df-.... B: ten sam artefakt, ale w kodzie
    // addBundleId z UUID poprzedniego buildu. C: ta sama rewizja zbudowana bez source bundle, np.
    // w jobie bez tokenu Sentry.
    //
    // Problem: SDK wczytuje plik tylko wtedy, gdy opcje nie mają jeszcze bundle ID, więc wartość
    // z kodu w B wyłącza plik z bieżącego buildu i event wskazuje bundle innej rewizji. W C event
    // nie ma debug_meta. W obu przypadkach event wychodzi normalnie i nic nie ostrzega.
    //
    // Dobra praktyka: bundle ID tylko z pliku zapisanego przez build, bez wartości w kodzie, a upload
    // bundle przed pierwszym eventem, bo Sentry nie przetwarza starszych eventów ponownie.
    //
    // Na co patrzeć: w konsoli debug_meta A to jvm 1740e7df-..., B jvm 0b6d2c52-... (UUID z kodu),
    // C „(brak: Sentry nie ma po czym dopasować source bundle)”. W Sentry UI, po wysłaniu bundle
    // poleceniami z README.md, tylko event A ma fragment kodu przy ramkach aplikacji (np.
    // CouponService); B i C mają same nazwy i numery linii.
    //
    // Uruchomienie: -Dexec.args=5
    static void sourceBundleInEvent() {
        DemoConsole.scenario(5, "Source context: bundle ID z artefaktu w evencie",
                "pokazać, skąd event bierze UUID source bundle i kiedy wskazuje zły bundle albo żaden.");
        BuildArtifact build185 = OrdersApiBuilds.build185();
        BuildArtifact withoutBundle = OrdersApiBuilds.build185WithoutSourceBundle();
        record Variant(String description, Consumer<SentryOptions> configuration) {
        }
        List<Variant> variants = List.of(
                new Variant("A. Artefakt 185 z sentry-debug-meta.properties (bundle " + SOURCE_BUNDLE_185 + ")",
                        OrdersApiStartup.sentryConfiguration(build185)),
                new Variant("B. Ten sam artefakt, w kodzie addBundleId z UUID poprzedniego buildu",
                        OrdersApiStartup.withHardcodedBundleId(build185, SOURCE_BUNDLE_184.toString())),
                new Variant("C. Ta sama rewizja zbudowana bez source bundle (job bez tokenu Sentry)",
                        OrdersApiStartup.sentryConfiguration(withoutBundle)));
        for (Variant variant : variants) {
            DemoConsole.step(variant.description());
            startAndApplyCoupon(variant.configuration(), "ORD-5001");
        }

        DemoConsole.lookAt("w konsoli debug_meta A wskazuje UUID z artefaktu, B UUID z kodu (plik z buildu "
                + "został pominięty), a C nie ma debug_meta. Event wychodzi w każdym wariancie.");
        DemoConsole.lookAt("w Sentry UI po wysłaniu bundle " + SOURCE_BUNDLE_185 + " (polecenia w README.md pakietu) "
                + "event A ma fragment kodu przy ramkach aplikacji (np. CouponService), a B i C tylko nazwy i numery linii.");
    }

    // Scenariusz 6: pipeline, kolejność kroków i jeden niezmienny artefakt.
    //
    // O co chodzi: release, artefakt, source bundle i deploy muszą opisywać ten sam build, a zielony
    // status każdego joba tego nie dowodzi. ReleasePipeline sprawdza dowody pięciu kroków: zamknięcie
    // artefaktu, rejestrację release, upload bundle, wdrożenie i rekord deploy.
    //
    // Co pokazujemy: trzy przebiegi bez uruchamiania SDK. A: kolejność docelowa. B: job wdrożeniowy
    // buduje artefakt jeszcze raz zamiast pobrać zamknięty. C: akcja CI z domyślnym release (goły
    // SHA), deploy zapisany przed wdrożeniem, upload bundle na końcu.
    //
    // Problem: w B wdrożone bajty mają inny skrót, a artefakt wskazuje nowy UUID bundle, którego nikt
    // nie wysłał. W C eventy sprzed uploadu zostaną bez source context, rekord deploy opisuje intencję,
    // a nie wdrożenie, i CI używa innej nazwy release niż SDK.
    //
    // Dobra praktyka: build once, deploy many. Release wyliczony raz i identyczny w artefakcie, CI
    // i rekordzie deploy; upload przed wdrożeniem; rekord deploy po potwierdzonym wdrożeniu.
    //
    // Na co patrzeć: w konsoli A „brak naruszeń kontraktu”, B dwa naruszenia (skrót, bundle),
    // C cztery (upload po wdrożeniu, deploy przed wdrożeniem, release CI i release deploy inne niż
    // w artefakcie). W Sentry UI nic: skutki braku tej kontroli pokazują scenariusze 2, 3 i 5.
    //
    // Uruchomienie: -Dexec.args=6
    static void pipelineContract() {
        DemoConsole.scenario(6, "Pipeline: kolejność kroków i jeden niezmienny artefakt",
                "pokazać dowody, które pipeline musi zebrać, żeby release, artefakt, bundle i deploy opisywały ten sam build.");
        BuildArtifact sealed = OrdersApiBuilds.build185();
        String bundle = SOURCE_BUNDLE_185.toString();

        DemoConsole.step("A. Seal, rejestracja release, upload bundle, wdrożenie, rekord deploy");
        printViolations(ReleasePipeline.of(
                new Step.Seal(sealed),
                new Step.RegisterRelease(RELEASE_185.value()),
                new Step.UploadSourceBundle(bundle),
                new Step.Deploy(sealed),
                new Step.RecordDeploy(RELEASE_185.value())));

        DemoConsole.step("B. Job wdrożeniowy buduje artefakt jeszcze raz zamiast pobrać zamknięty");
        printViolations(ReleasePipeline.of(
                new Step.Seal(sealed),
                new Step.RegisterRelease(RELEASE_185.value()),
                new Step.UploadSourceBundle(bundle),
                new Step.Deploy(sealed.rebuild()),
                new Step.RecordDeploy(RELEASE_185.value())));

        DemoConsole.step("C. Akcja CI z domyślnym release (goły SHA), deploy zapisany przed wdrożeniem, upload na końcu");
        printViolations(ReleasePipeline.of(
                new Step.Seal(sealed),
                new Step.RegisterRelease(REVISION_185),
                new Step.RecordDeploy(REVISION_185),
                new Step.Deploy(sealed),
                new Step.UploadSourceBundle(bundle)));

        DemoConsole.lookAt("w konsoli A nie ma naruszeń. B daje inny skrót i UUID bundle, którego nikt nie wysłał, "
                + "choć każdy job skończył się sukcesem. C łamie kolejność i nazwę release.");
        DemoConsole.lookAt("w Sentry UI nic: to kontrola przed wdrożeniem. Skutki jej braku widać w scenariuszach "
                + "2, 3 i 5 (zły release, drugi release, event bez source context).");
    }

    // ---- pomocnicze -------------------------------------------------------------------------

    /** Jeden proces orders-api: start SDK z podaną konfiguracją, jeden request, zamknięcie SDK. */
    private static void startAndApplyCoupon(Consumer<SentryOptions> configuration, String orderId) {
        // Zapamiętanie eventu dodajemy przed konfiguracją aplikacji, żeby działało także wtedy,
        // gdy konfiguracja rzuci wyjątek wewnątrz Sentry.init (scenariusz 2D).
        Consumer<SentryOptions> withPreview = ((Consumer<SentryOptions>) Module04Demo::rememberSentEvent)
                .andThen(configuration);
        try (TrainingSentry.TrainingSession session = TrainingSentry.init("module04", withPreview)) {
            COUPONS.applyCoupon(orderId, "black-week");
            printDistAndDebugMeta();
        }
    }

    private static void runSpringApp(String sentryRelease, String orderId) {
        try (ConfigurableApplicationContext context = OrdersApiSpringApp.start(sentryRelease)) {
            DemoConsole.step("   Spring Boot wystartował, SDK ma release="
                    + Sentry.getCurrentScopes().getOptions().getRelease());
            context.getBean(CouponEndpoint.class).applyCoupon(orderId, "black-week");
            // Zamknięcie kontekstu zamyka też SDK, a zamknięcie czeka na kolejkę transportu najwyżej
            // shutdownTimeoutMillis (domyślnie 2 s). Flush z dłuższym limitem, jak w TrainingSession.
            Sentry.flush(5_000);
        }
    }

    private static void rememberSentEvent(SentryOptions options) {
        options.setBeforeSend((event, hint) -> {
            LAST_SENT.set(event);
            return event;
        });
    }

    private static void printDistAndDebugMeta() {
        SentryEvent event = LAST_SENT.getAndSet(null);
        if (event == null) {
            return;
        }
        System.out.printf("      %-12s%s%n", "dist", event.getDist() == null ? "(brak)" : event.getDist());
        List<DebugImage> images = event.getDebugMeta() == null ? null : event.getDebugMeta().getImages();
        System.out.printf("      %-12s%s%n", "debug_meta", images == null || images.isEmpty()
                ? "(brak: Sentry nie ma po czym dopasować source bundle)"
                : images.stream().map(image -> image.getType() + " " + image.getDebugId())
                .collect(Collectors.joining(", ")));
    }

    private static void printViolations(ReleasePipeline pipeline) {
        List<String> violations = pipeline.violations();
        if (violations.isEmpty()) {
            DemoConsole.step("   brak naruszeń kontraktu");
        }
        violations.forEach(violation -> DemoConsole.step("   naruszenie: " + violation));
    }

    private static String describeCandidate(String candidate) {
        String shown = candidate.length() > 30
                ? candidate.substring(0, 18) + "...(" + candidate.length() + " znaków)"
                : candidate.replace("\t", "\\t");
        return ReleaseName.violation(candidate)
                .map(reason -> "\"" + shown + "\" odrzucona: " + reason)
                .orElseGet(() -> new ReleaseName(candidate).component()
                        .map(component -> "\"" + shown + "\" poprawna, komponent " + component)
                        .orElse("\"" + shown + "\" poprawna dla Sentry, ale bez prefiksu usługi"));
    }

    /** Uruchamia akcję z ustawioną właściwością systemową; {@code null} oznacza brak właściwości. */
    private static void withSystemProperty(String key, String value, Runnable action) {
        if (value != null) {
            System.setProperty(key, value);
        }
        try {
            action.run();
        } finally {
            System.clearProperty(key);
        }
    }
}
