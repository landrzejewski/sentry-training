package pl.training.sentry.module06;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import pl.training.sentry.module06.AlertingSetup.Firing;
import pl.training.sentry.module06.BankGateway.Mode;
import pl.training.sentry.module06.BankGateway.Payment;
import pl.training.sentry.module06.PaymentsAlertPolicy.Alert;
import pl.training.sentry.module06.PaymentsEndpoint.Reporting;
import pl.training.sentry.support.DemoConsole;
import pl.training.sentry.support.SentryRestApi;
import pl.training.sentry.support.TrainingSentry;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Program demonstracyjny „Alerty end-to-end”: pokazuje, co polityka z {@link PaymentsAlertPolicy}
 * robi z pięcioma sytuacjami z życia payments-api.
 *
 * <p>Teoria modułu (Monitory, Alerty, akcje, throttling, regresja i redukcja szumu) jest w komentarzu
 * na początku {@link Module06Demo}.</p>
 *
 * <p>Tryb offline (bez zmiennych): eventy trafiają na konsolę, a nad każdym linia
 * {@code · Alert} (wypisuje ją {@code beforeSend}, zanim transport wypisze event) mówi, czy event przeszedłby środowisko i filtry Alertu „błąd komponentu”. Na
 * końcu odbiornik webhooków dostaje zapisany przykład powiadomienia z lokalnego Sentry.</p>
 *
 * <p>Tryb online ({@code SENTRY_DSN} i {@code SENTRY_AUTH_TOKEN} po {@code AlertingSetup setup}):
 * te same eventy trafiają do Sentry, scenariusz regresji rozwiązuje Issue przez API, a na końcu
 * program porównuje historię Alertów sprzed i po demo. To test całego łańcucha: kontrolowany
 * sygnał, Monitor, Alert, akcja. Testowa wiadomość integracji sprawdziłaby tylko połączenie,
 * a nie warunek i filtry.</p>
 *
 * <p>Uruchomienie, zakresy tokenu i ograniczenia lokalnej instancji: {@code README.md},
 * „Alerty end-to-end”.</p>
 */
public final class AlertingDemo {

    private static final int SERIES = 12;
    private static final Duration RESULT_TIMEOUT = Duration.ofMinutes(3);

    /** Eventy wysłane w bieżącym kroku, zapisane przez podgląd w {@code beforeSend}. */
    private static final List<SentryEvent> SENT = new ArrayList<>();

    private AlertingDemo() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        Optional<SentryRestApi> api = SentryRestApi.fromEnv(env);
        boolean online = api.isPresent() && env.get("SENTRY_DSN") != null && !env.get("SENTRY_DSN").isBlank();

        printPolicy();
        int port = Integer.parseInt(env.getOrDefault("ALERT_WEBHOOK_PORT", String.valueOf(WebhookReceiver.DEFAULT_PORT)));
        try (WebhookReceiver receiver = new WebhookReceiver(port, env.get("SENTRY_WEBHOOK_SECRET"),
                notification -> System.out.println(WebhookReceiver.describe(notification)))) {
            Map<String, Long> alertIds = online ? alertIds(api.get()) : Map.of();
            if (online && alertIds.size() < PaymentsAlertPolicy.ALERTS.size()) {
                System.out.println("Brak Alertów polityki w Sentry. Najpierw: AlertingSetup setup (src/main/java/pl/training/sentry/module06/README.md).");
                return;
            }
            Map<String, List<Firing>> before = online ? history(api.get(), alertIds) : Map.of();

            otherEnvironment();
            String bankEventId = firstComponentError();
            series();
            String issueId = online ? issueOf(api.get(), bankEventId) : null;
            if (online) {
                waitForMonitorWindow();
            }
            regression(api.orElse(null), issueId);
            expectedErrors();

            if (online) {
                verifyInSentry(api.get(), alertIds, before, issueId, receiver);
            } else {
                replaySampleWebhook(receiver);
            }
        }
    }

    private static void printPolicy() {
        System.out.println("Polityka alertowa payments-api (PaymentsAlertPolicy), środowisko " + PaymentsAlertPolicy.ENVIRONMENT + ":");
        System.out.println("  Monitor „" + PaymentsAlertPolicy.PAYMENT_ERRORS.name() + "”: count() dla "
                + PaymentsAlertPolicy.PAYMENT_ERRORS.query() + ", okno 5 min, alarm powyżej "
                + PaymentsAlertPolicy.PAYMENT_ERRORS.threshold());
        for (Alert alert : PaymentsAlertPolicy.ALERTS) {
            System.out.println("  Alert „" + alert.name() + "”");
            System.out.println("    " + PaymentsAlertPolicy.describe(alert) + " | Then: webhook");
        }
    }

    // Scenariusz 1: Ten sam błąd w innym środowisku.
    //
    // O co chodzi: środowisko Alertu działa przed filtrami: event z innego środowiska odpada, zanim
    // ktokolwiek sprawdzi tagi i level. Metric Monitor też liczy eventy tylko ze środowiska training.
    //
    // Co pokazujemy: sesja SDK z environment=staging i awaria banku sklasyfikowana przez
    // PaymentsEndpoint (Reporting.CLASSIFIED), czyli z tymi samymi danymi co w training. Linię
    // „· Alert” wypisuje beforeSend z lokalnym podglądem filtrów (PaymentsAlertPolicy.preview), zanim
    // transport wypisze event. To podgląd, a nie decyzja Sentry.
    //
    // Problem: kod nie ma tu pułapki, scenariusz pokazuje mechanizm, który chroni Alert produkcyjny
    // przed ruchem ze stagingu. Typowy błąd to Alert bez filtra środowiska: nie oznacza on production,
    // tylko zwykle wszystkie środowiska.
    //
    // Dobra praktyka: Alert jawnie ogranicza się do jednego środowiska (tu training, w produkcji
    // production), a staging może mieć osobną, cichszą politykę.
    //
    // Na co patrzeć: w konsoli „· Alert ...: odfiltrowany: environment staging” nad eventem
    // z environment staging, tagami component=payments i operation=charge oraz fingerprintem
    // bank-timeout, bank-alfa. W Sentry (tryb online) żadnej akcji, a Monitor tego eventu nie liczy.
    //
    // Uruchomienie: tylko w pełnym przebiegu AlertingDemo, klasa nie obsługuje -Dexec.args. Offline:
    // ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module06.AlertingDemo
    // Online: po AlertingSetup setup, z SENTRY_AUTH_TOKEN i SENTRY_DSN (README, „Alerty end-to-end”).
    static void otherEnvironment() {
        DemoConsole.scenario(1, "Ten sam błąd w innym środowisku",
                "pokazać, że środowisko Alertu jest pierwszym filtrem, zanim zadziała cokolwiek innego.");
        try (TrainingSentry.TrainingSession session = TrainingSentry.init("module06",
                withAlertPreview(options -> options.setEnvironment("staging")))) {
            DemoConsole.step("Staging: bank nie odpowiada na obciążenie karty");
            charge(Mode.TIMING_OUT, new Payment("pay_1StgAbCdEfGhIjKl", "kwiaciarnia-roza", "tok-visa"), Reporting.CLASSIFIED);
        }
        DemoConsole.lookAt("w konsoli event ma environment staging i te same tagi co w produkcji. Alert obsługuje "
                + "tylko " + PaymentsAlertPolicy.ENVIRONMENT + ", więc nikt nie dostaje powiadomienia, a Monitor go nie liczy.");
    }

    // Scenariusz 2: Pierwszy błąd komponentu payments.
    //
    // O co chodzi: Alert „payments: błąd komponentu” ma trigger „An event or issue activity is
    // captured”, filtry component = payments, level >= error, expected != true i throttling 30 minut.
    // Trigger na każdy event daje powiadomienie także dla istniejącego Issue awarii; „A new issue is
    // created” zadziałałby tylko raz.
    //
    // Co pokazujemy: awaria banku w training zgłoszona przez PaymentTelemetry.reportBankTimeout.
    // Program zapamiętuje id eventu, a w trybie online szuka po nim Issue do scenariusza regresji.
    //
    // Problem: filtry Alertu działają tylko na danych z eventu. Ten sam błąd bez tagu component albo
    // z niższym level nikogo nie powiadomi (scenariusz 5).
    //
    // Dobra praktyka: tagi, level i fingerprint ustawia jedno miejsce w kodzie raportującym
    // (PaymentTelemetry), a nie konfiguracja Alertu.
    //
    // Na co patrzeć: w konsoli „· Alert ...: przechodzi środowisko i filtry; akcję dla tego Issue
    // ogranicza throttling 30 min” i event z level error, fingerprintem bank-timeout, bank-alfa oraz
    // tagami component=payments i operation=charge. W Sentry (tryb online, Alerty świeżo po setup)
    // jedna akcja Alertu błędu komponentu dla Issue bank-timeout.
    //
    // Uruchomienie: tylko w pełnym przebiegu AlertingDemo, klasa nie obsługuje -Dexec.args. Offline:
    // ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module06.AlertingDemo
    // Online: po AlertingSetup setup, z SENTRY_AUTH_TOKEN i SENTRY_DSN (README, „Alerty end-to-end”).
    static String firstComponentError() {
        DemoConsole.scenario(2, "Pierwszy błąd komponentu payments",
                "pokazać, że pierwszy event z tagiem component=payments i level error uruchamia Alert.");
        String eventId;
        try (TrainingSentry.TrainingSession session = TrainingSentry.init("module06", withAlertPreview(options -> {
        }))) {
            DemoConsole.step("Training: bank nie odpowiada, klasyfikacja przez PaymentTelemetry.reportBankTimeout");
            charge(Mode.TIMING_OUT, new Payment("pay_2TrnMnOpQrStUvWx", "rowery-kolo", "tok-visa"), Reporting.CLASSIFIED);
            eventId = SENT.getLast().getEventId().toString();
        }
        DemoConsole.lookAt("w konsoli fingerprint bank-timeout, tagi component=payments i operation=charge, level error: "
                + "wszystko, czego potrzebują filtry Alertu. Te dane ustawia PaymentTelemetry, nie konfiguracja Alertu.");
        return eventId;
    }

    // Scenariusz 3: Seria tego samego błędu: throttling i Metric Monitor.
    //
    // O co chodzi: throttling ogranicza tylko ponowne wykonanie akcji Alertu dla tego samego Issue,
    // nie ingest ani ocenę Monitora. Metric Monitor liczy eventy component:payments level:error
    // w oknie 5 minut i powyżej 10 tworzy Metric Issue, na które reaguje osobny Alert.
    //
    // Co pokazujemy: 12 płatności różnych sprzedawców w czasie awarii banku. Transport pisze do
    // pustego strumienia, a podgląd zbiera eventy bez wydruku, więc krok podaje tylko liczby. W trybie
    // online program czeka potem do pełnej minuty i jeszcze 65 s, żeby Monitor ocenił okno z całą serią
    // przed scenariuszem 4.
    //
    // Problem: każdy event przechodzi filtry, więc bez throttlingu każdy dałby powiadomienie.
    // Throttling działa per Issue, więc fingerprint zmienny dla każdego sprzedawcy (scenariusz 5A
    // Module06Demo) by go ominął.
    //
    // Dobra praktyka: stabilny fingerprint (jedno Issue na awarię) i throttling na Alercie
    // o pojedynczych błędach, a skalę awarii zgłasza osobno Metric Monitor, który nie zna kanału.
    //
    // Na co patrzeć: w konsoli „wysłane eventy: 12, z fingerprintem bank-timeout: 12, przechodzi
    // filtry Alertu ...: 12”. W Sentry (tryb online) historia Alertu błędu komponentu nadal ma jedną
    // akcję, a Monitor przy najbliższej ocenie okna (lokalnie od kilku sekund do 3 minut po serii)
    // tworzy Metric Issue i jego Alert wykonuje jedną akcję.
    //
    // Uruchomienie: tylko w pełnym przebiegu AlertingDemo, klasa nie obsługuje -Dexec.args. Offline:
    // ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module06.AlertingDemo
    // Online: po AlertingSetup setup, z SENTRY_AUTH_TOKEN i SENTRY_DSN (README, „Alerty end-to-end”).
    static void series() {
        DemoConsole.scenario(3, "Seria tego samego błędu: throttling i Metric Monitor",
                "pokazać, że " + SERIES + " eventów jednej awarii to jedno powiadomienie z Alertu i jedno z Monitora.");
        DemoConsole.step(SERIES + " kolejnych płatności w czasie awarii banku, różni sprzedawcy "
                + "(pełny wydruk eventu jak w scenariuszu 2, tu tylko fingerprint i wynik filtrów)");
        int from = SENT.size();
        // Wydruk transportu pominięty: każdy event wyglądałby jak w scenariuszu 2. W trybie
        // online eventy trafiają do Sentry normalnie.
        try (TrainingSentry.TrainingSession session = TrainingSentry.init("module06", withAlertPreview(false, options -> {
        }), new PrintStream(OutputStream.nullOutputStream()))) {
            for (int i = 1; i <= SERIES; i++) {
                new PaymentsEndpoint(new PaymentService(bankIn(Mode.TIMING_OUT))).charge(
                        new Payment("pay_3Ser" + String.format("%012d", i), "sklep-" + (i % 3), "tok-visa"),
                        Reporting.CLASSIFIED);
            }
        }
        List<SentryEvent> series = SENT.subList(from, SENT.size());
        long sameFingerprint = series.stream().filter(event -> List.of("bank-timeout", BankGateway.BANK_CODE)
                .equals(event.getFingerprints())).count();
        long passing = series.stream()
                .filter(event -> PaymentsAlertPolicy.preview(PaymentsAlertPolicy.COMPONENT_ERRORS, event).notifies()).count();
        DemoConsole.step("   wysłane eventy: " + series.size() + ", z fingerprintem bank-timeout: " + sameFingerprint
                + ", przechodzi filtry Alertu „" + PaymentsAlertPolicy.COMPONENT_ERRORS.name() + "”: " + passing);
        DemoConsole.lookAt("w konsoli każdy event przechodzi filtry, ale wszystkie trafiają do jednego Issue. "
                + "Throttling (30 min na Issue) blokuje kolejne akcje Alertu, więc powiadomienie jest jedno. "
                + "Monitor liczy te same eventy w oknie 5 min i po przekroczeniu progu tworzy własne Issue.");
    }

    /**
     * Lokalne Sentry bez ruchu: okno Monitora kończy się na pełnej minucie, ale ocena odbywa się
     * w stałej dla subskrypcji sekundzie minuty (przesunięcie Snuba od 0 do 59 s) i rusza dopiero,
     * gdy przyjdzie event późniejszy niż ten moment. Czekamy do pełnej minuty i jeszcze 65 s, żeby
     * eventy kolejnych scenariuszy przyszły po ocenie okna z całą serią. PUŁAPKA: przy krótszym
     * czekaniu wynik zależał lokalnie od przesunięcia albo od eventów wysłanych przez kogoś innego.
     * Na instancji z ruchem ten krok nie jest potrzebny.
     */
    private static void waitForMonitorWindow() throws InterruptedException {
        long seconds = 60 - LocalTime.now().getSecond() + 65;
        DemoConsole.step("Czekam " + seconds + " s: Monitor ocenia okno raz na minutę, z przesunięciem do 59 s, "
                + "a lokalne Sentry bez ruchu robi to dopiero przy następnym evencie (z kolejnego scenariusza).");
        Thread.sleep(seconds * 1_000);
    }

    // Scenariusz 4: Regresja po Resolve.
    //
    // O co chodzi: trigger „A resolved issue regresses” reaguje na ponowne pojawienie się
    // rozwiązanego Issue. Według dokumentacji filtry atrybutów eventu, np. tag, nie współpracują z tym
    // triggerem, dlatego Alert regresji filtruje priorytet Issue (co najmniej high).
    //
    // Co pokazujemy: w trybie online program rozwiązuje Issue awarii banku przez API (status resolved),
    // czeka 3 s, bo regresję wykrywa tylko event późniejszy niż rozwiązanie, i wysyła kolejny event
    // tej samej awarii. Offline krok tylko informuje o Resolve, a event trafia na konsolę.
    //
    // Problem: Alert błędu komponentu nie odróżnia regresji od kolejnego eventu, a przez throttling ze
    // scenariusza 2 milczy. Filtr tagu przy triggerze regresji formularz oznacza ostrzeżeniem (lokalnie
    // zadziałał, ale polityka na tym nie polega).
    //
    // Dobra praktyka: osobny Alert dla regresji z filtrem atrybutu Issue. W produkcji payments-api ma
    // własny projekt, więc źródło samo zawęża Alert do komponentu; tu obejmuje wszystkie moduły.
    //
    // Na co patrzeć: offline w konsoli linia „Offline: w trybie online program rozwiązuje tu Issue
    // przez API” i event, który podgląd przepuszcza (akcję i tak ogranicza throttling). W Sentry Issue
    // przechodzi z Resolved do Regressed, Alert regresji wykonuje akcję, a Alert błędu komponentu milczy.
    //
    // Uruchomienie: tylko w pełnym przebiegu AlertingDemo, klasa nie obsługuje -Dexec.args. Offline:
    // ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module06.AlertingDemo
    // Online: po AlertingSetup setup, z SENTRY_AUTH_TOKEN i SENTRY_DSN (README, „Alerty end-to-end”).
    static void regression(SentryRestApi api, String issueId) throws IOException, InterruptedException {
        DemoConsole.scenario(4, "Regresja po Resolve",
                "pokazać, że powrót rozwiązanego Issue uruchamia osobny Alert, choć Alert błędu komponentu milczy.");
        if (api != null && issueId != null) {
            DemoConsole.step("Resolve Issue " + issueId + " przez API, jak po wdrożeniu poprawki");
            api.put("/organizations/" + api.org() + "/issues/" + issueId + "/",
                    SentryRestApi.JSON.createObjectNode().put("status", "resolved"));
            // Regresję wykrywa tylko event późniejszy niż rozwiązanie.
            Thread.sleep(3_000);
        } else {
            DemoConsole.step("Offline: w trybie online program rozwiązuje tu Issue przez API");
        }
        try (TrainingSentry.TrainingSession session = TrainingSentry.init("module06", withAlertPreview(options -> {
        }))) {
            DemoConsole.step("Bank znowu nie odpowiada");
            charge(Mode.TIMING_OUT, new Payment("pay_4RegYzAbCdEfGhIj", "kwiaciarnia-roza", "tok-visa"), Reporting.CLASSIFIED);
        }
        DemoConsole.lookAt("w Sentry Issue przechodzi z Resolved do Regressed. Alert regresji ma filtr na priorytet "
                + "Issue, nie na tag, bo filtry eventu nie współpracują z triggerem regresji. Alert błędu komponentu "
                + "przepuszcza event, ale throttling ze scenariusza 2 blokuje drugie powiadomienie.");
    }

    // Scenariusz 5: Oczekiwany błąd nie budzi nikogo.
    //
    // O co chodzi: o redukcji szumu decydują dane z aplikacji: level, tag expected i tag component.
    // Odmowa karty to wynik biznesowy, który ma zostać w Sentry jako trend, ale nikogo nie budzić.
    //
    // Co pokazujemy: A: odmowa sklasyfikowana przez reportDecline (warning, expected=true). B: ta sama
    // odmowa przez wspólnego pomocnika reportCarelessly (domyślny level error, bez tagów domenowych).
    //
    // Problem: B odpada tylko dlatego, że nie ma tagu component. Tak samo odpadłaby prawdziwa awaria
    // zgłoszona tym pomocnikiem: brak tagu daje ciszę, a nie błąd konfiguracji.
    //
    // Dobra praktyka: jawna klasyfikacja w kontrolowanym kodzie i obrona w głąb: filtr level >= error
    // odrzuca warning, a expected != true wykluczy odmowę także wtedy, gdy ktoś podniesie ją do error.
    // Monitor ma w zapytaniu level:error, żeby nie liczyć odmów.
    //
    // Na co patrzeć: w konsoli A „odfiltrowany: level >= error nie pasuje (level warning)”; podgląd
    // pokazuje pierwszy niespełniony filtr, expected też by go odrzucił. B „odfiltrowany:
    // component = payments nie pasuje (brak tagu component)”. Oba requesty zwracają DECLINED.
    // W Sentry (tryb online) żadna z odmów nie daje akcji Alertu.
    //
    // Uruchomienie: tylko w pełnym przebiegu AlertingDemo, klasa nie obsługuje -Dexec.args. Offline:
    // ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module06.AlertingDemo
    // Online: po AlertingSetup setup, z SENTRY_AUTH_TOKEN i SENTRY_DSN (README, „Alerty end-to-end”).
    static void expectedErrors() {
        DemoConsole.scenario(5, "Oczekiwany błąd nie budzi nikogo",
                "pokazać, że o redukcji szumu decydują dane z aplikacji: level, tag expected i tag component.");
        try (TrainingSentry.TrainingSession session = TrainingSentry.init("module06", withAlertPreview(options -> {
        }))) {
            DemoConsole.step("A. Odmowa karty sklasyfikowana: warning, expected=true");
            charge(Mode.AVAILABLE, new Payment("pay_5ExpKlMnOpQrStUv", "rowery-kolo", BankGateway.CARD_WITHOUT_FUNDS),
                    Reporting.CLASSIFIED);
            DemoConsole.step("B. Ta sama odmowa przez wspólnego pomocnika: error, bez tagów domenowych");
            charge(Mode.AVAILABLE, new Payment("pay_5CarWxYzAbCdEfGh", "rowery-kolo", BankGateway.CARD_WITHOUT_FUNDS),
                    Reporting.CARELESS);
        }
        DemoConsole.lookAt("w konsoli A odpada już na level (podgląd pokazuje pierwszy niespełniony filtr, expected też by go odrzucił): trend odmów zostaje w Sentry, nikt nie dostaje "
                + "powiadomienia. B odpada, bo nie ma tagu component. PUŁAPKA: tak samo odpadłaby prawdziwa awaria "
                + "zgłoszona przez tego pomocnika, bo brak tagu to cisza, a nie błąd konfiguracji.");
    }

    // Scenariusz 6 (tryb online): Wynik w Sentry: historia Alertów.
    //
    // O co chodzi: dowodem działania polityki jest historia Alertów w API, niezależna od kanału.
    // Testowa wiadomość integracji sprawdziłaby tylko połączenie, a nie warunek i filtry.
    //
    // Co pokazujemy: przed scenariuszami program zapisał historię każdego Alertu, a teraz co 10 s
    // porównuje ją z bieżącą, najdłużej 3 minuty, aż każdy Alert ma nową akcję. Potem wypisuje nowe
    // akcje, liczbę akcji Alertu błędu komponentu dla Issue awarii banku i liczbę webhooków odebranych
    // przez WebhookReceiver (port 8097).
    //
    // Problem: na lokalnym self-hosted webhook nie dociera, choć Alert wykonał akcję: Sentry blokuje
    // adresy prywatne (w logu taskworkera restricted_ip). Sam brak wiadomości nie mówi więc, czy
    // zawiódł warunek, czy dostawa.
    //
    // Dobra praktyka: test całego łańcucha kontrolowanym sygnałem (sygnał, Monitor, Alert, akcja)
    // i weryfikacja w historii Alertu. Po obejrzeniu AlertingSetup cleanup usuwa konfigurację.
    //
    // Na co patrzeć: w konsoli „nowe akcje Alertów (błąd komponentu, regresja, Metric Monitor)”
    // z oczekiwanym [1, 1, 1] i jedna akcja Alertu błędu komponentu mimo 14 eventów w training. Przy
    // zerze odebranych webhooków linia o blokadzie adresów prywatnych. W UI Monitors > Alerts,
    // zakładka historii każdego Alertu: te same Issues.
    //
    // Uruchomienie: tylko w pełnym przebiegu AlertingDemo online (klasa nie obsługuje -Dexec.args):
    // po AlertingSetup setup, z SENTRY_AUTH_TOKEN i SENTRY_DSN (README, „Alerty end-to-end”). Cały
    // przebieg trwa około 4 minut.
    private static void verifyInSentry(SentryRestApi api, Map<String, Long> alertIds, Map<String, List<Firing>> before,
                                       String issueId, WebhookReceiver receiver) throws IOException, InterruptedException {
        DemoConsole.scenario(6, "Wynik w Sentry: historia Alertów",
                "sprawdzić w API, które Alerty wykonały akcję i dla których Issues (dowód niezależny od kanału).");
        Instant deadline = Instant.now().plus(RESULT_TIMEOUT);
        Map<String, Integer> delta;
        do {
            Thread.sleep(10_000);
            Map<String, List<Firing>> after = history(api, alertIds);
            delta = new LinkedHashMap<>();
            for (Alert alert : PaymentsAlertPolicy.ALERTS) {
                delta.put(alert.name(), total(after.get(alert.name())) - total(before.get(alert.name())));
            }
            DemoConsole.step("nowe akcje Alertów (błąd komponentu, regresja, Metric Monitor): " + delta.values()
                    + ", oczekiwane [1, 1, 1]; Issue Monitora i akcja Alertu potrzebują zwykle do minuty");
        } while (delta.values().stream().anyMatch(count -> count < 1) && Instant.now().isBefore(deadline));

        Map<String, List<Firing>> after = history(api, alertIds);
        for (Alert alert : PaymentsAlertPolicy.ALERTS) {
            List<Firing> now = after.get(alert.name());
            DemoConsole.step("„" + alert.name() + "”: " + delta.get(alert.name()) + " nowych akcji");
            now.stream().filter(firing -> !before.get(alert.name()).contains(firing))
                    .forEach(firing -> DemoConsole.step("    " + firing));
        }
        Optional<Firing> bank = after.get(PaymentsAlertPolicy.COMPONENT_ERRORS.name()).stream()
                .filter(firing -> firing.issueId().equals(issueId)).findFirst();
        DemoConsole.step("Issue awarii banku w historii Alertu błędu komponentu: "
                + bank.map(firing -> firing.count() + " akcja(e) mimo " + (SERIES + 2) + " eventów w training").orElse("brak"));
        DemoConsole.step("Webhooki odebrane lokalnie: " + receiver.received().size());
        if (receiver.received().isEmpty()) {
            DemoConsole.lookAt("Alerty wykonały akcję (historia wyżej), ale webhook nie dotarł: lokalne Sentry blokuje "
                    + "adresy prywatne (w logu taskworkera: restricted_ip). Opcja zmiany konfiguracji: src/main/java/pl/training/sentry/module06/README.md.");
        }
        DemoConsole.lookAt("w UI Monitors > Alerts, każdy Alert, zakładka historii: te same Issues. Po obejrzeniu "
                + "AlertingSetup cleanup usuwa konfigurację (src/main/java/pl/training/sentry/module06/README.md, „Sprzątanie po demo”).");
    }

    // Scenariusz 6 (tryb offline): Jak wygląda powiadomienie.
    //
    // O co chodzi: webhook ma powiedzieć odbiorcy, dlaczego przyszedł (nazwa Alertu), czego dotyczy
    // (Issue, level, environment) i kto powinien reagować (tagi component i operation). Internal
    // Integration podpisuje treść HMAC-SHA256 w nagłówku Sentry-Hook-Signature (klucz: Client Secret).
    //
    // Co pokazujemy: zapisany przykład event_alert z lokalnego Sentry 26.9.0
    // (resources/module06/webhook-event-alert.json, skrócony do pól używanych przez odbiornik)
    // wysłany POST-em do WebhookReceiver z nagłówkiem Sentry-Hook-Resource: event_alert.
    //
    // Problem: bez SENTRY_WEBHOOK_SECRET odbiornik nie sprawdza podpisu (NOT_CHECKED), a adres
    // webhooka nie jest tajny. Z ustawionym sekretem żądanie bez poprawnego podpisu dostaje 401.
    //
    // Dobra praktyka: poza szkoleniem odbiornik ma publiczny adres HTTPS, sprawdza podpis i szybko
    // odpowiada 2xx, a dłuższą pracę (ticket, paging) wykonuje asynchronicznie.
    //
    // Na co patrzeć: w konsoli blok „<- webhook event_alert, podpis: NOT_CHECKED” z liniami od,
    // dlaczego (nazwa Alertu), co (Issue, level error, environment training, wyjątek), tagi
    // (component, operation, release) i szczegóły (link do eventu).
    //
    // Uruchomienie: tylko w pełnym przebiegu AlertingDemo offline (klasa nie obsługuje -Dexec.args):
    // ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module06.AlertingDemo
    private static void replaySampleWebhook(WebhookReceiver receiver) throws IOException, InterruptedException {
        DemoConsole.scenario(6, "Jak wygląda powiadomienie",
                "pokazać, co odbiorca dostaje w webhooku i co z tego wynika dla reakcji.");
        DemoConsole.step("Zapisany przykład z lokalnego Sentry 26.9.0 (resources/module06/webhook-event-alert.json) "
                + "wysłany do odbiornika na porcie " + receiver.port());
        String body;
        try (InputStream in = AlertingDemo.class.getResourceAsStream("/module06/webhook-event-alert.json")) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (HttpClient http = HttpClient.newHttpClient()) {
            http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + receiver.port() + WebhookReceiver.PATH))
                    .header("Content-Type", "application/json")
                    .header("Sentry-Hook-Resource", "event_alert")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding());
        }
        DemoConsole.lookAt("w konsoli odbiornik pokazuje nazwę Alertu (dlaczego), Issue, level i environment (co) "
                + "oraz tagi component i operation (kto powinien reagować). Podpis NOT_CHECKED: bez SENTRY_WEBHOOK_SECRET "
                + "odbiornik go nie sprawdza.");
    }

    /** Podgląd filtrów Alertu dla każdego wysyłanego eventu, dokładany do {@code beforeSend}. */
    private static Consumer<SentryOptions> withAlertPreview(Consumer<SentryOptions> override) {
        return withAlertPreview(true, override);
    }

    /**
     * @param print    czy wypisać linię podglądu pod eventem (seria zbiera eventy bez wydruku)
     * @param override zmiana opcji scenariusza, np. inne środowisko
     */
    private static Consumer<SentryOptions> withAlertPreview(boolean print, Consumer<SentryOptions> override) {
        return options -> {
            PaymentTelemetry.configure(options);
            override.accept(options);
            SentryOptions.BeforeSendCallback delegate = options.getBeforeSend();
            options.setBeforeSend((SentryEvent event, Hint hint) -> {
                SentryEvent result = delegate == null ? event : delegate.execute(event, hint);
                if (result != null) {
                    SENT.add(result);
                }
                if (result != null && print) {
                    PaymentsAlertPolicy.Verdict verdict = PaymentsAlertPolicy.preview(PaymentsAlertPolicy.COMPONENT_ERRORS, result);
                    System.out.println("  · Alert „" + PaymentsAlertPolicy.COMPONENT_ERRORS.name() + "”: " + verdict.reason());
                }
                return result;
            });
        };
    }

    private static void charge(Mode mode, Payment payment, Reporting reporting) {
        String response = new PaymentsEndpoint(new PaymentService(bankIn(mode))).charge(payment, reporting);
        DemoConsole.step("   " + payment.id() + ": odpowiedź " + response);
    }

    private static BankGateway bankIn(Mode mode) {
        BankGateway bank = new BankGateway();
        bank.switchTo(mode);
        return bank;
    }

    private static Map<String, Long> alertIds(SentryRestApi api) throws IOException, InterruptedException {
        JsonNode workflows = AlertingSetup.listAlerts(api);
        Map<String, Long> ids = new LinkedHashMap<>();
        for (Alert alert : PaymentsAlertPolicy.ALERTS) {
            AlertingSetup.findFirst(workflows, "name", alert.name())
                    .ifPresent(node -> ids.put(alert.name(), node.path("id").asLong()));
        }
        return ids;
    }

    private static Map<String, List<Firing>> history(SentryRestApi api, Map<String, Long> alertIds)
            throws IOException, InterruptedException {
        Map<String, List<Firing>> history = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : alertIds.entrySet()) {
            history.put(entry.getKey(), AlertingSetup.firings(api, entry.getValue()));
        }
        return history;
    }

    private static int total(List<Firing> firings) {
        return firings == null ? 0 : firings.stream().mapToInt(Firing::count).sum();
    }

    /** Issue eventu z API; Sentry przetwarza event asynchronicznie, więc 404 na początku jest normalne. */
    private static String issueOf(SentryRestApi api, String eventId) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plusSeconds(60);
        while (true) {
            try {
                return api.get("/projects/" + api.org() + "/" + api.project() + "/events/" + eventId + "/")
                        .path("groupID").asString();
            } catch (SentryRestApi.ApiException notYet) {
                if (notYet.status() != 404 || Instant.now().isAfter(deadline)) {
                    throw notYet;
                }
                Thread.sleep(2_000);
            }
        }
    }
}
