# Moduł 6: Monitory, alerty i redukcja szumu

Domena: payments-api obciąża karty klientów, a joby w tle importują wyciągi bankowe, uzgadniają płatności i uruchamiają wypłaty. Wszystko zależy od jednego banku ([`BankGateway`](BankGateway.java)), który potrafi odpowiadać błędem, wisieć albo odmawiać karty. Monitory i Alerty konfiguruje się w Sentry UI albo przez REST API (niżej, „Alerty end-to-end”). Kod aplikacji odpowiada za sygnał, który do nich trafia: check-iny z właściwym statusem, identyfikatorem i konfiguracją oraz eventy z danymi, na których działają grouping, filtry Alertów i Ownership Rules.

Najważniejsza teoria modułu jest w komentarzu na początku [`Module06Demo`](Module06Demo.java).

## Klasy

- [`BankGateway`](BankGateway.java), [`SettlementService`](SettlementService.java), [`PaymentService`](PaymentService.java): kod domenowy bez Sentry;
- [`SettlementJob`](SettlementJob.java): `CheckInUtils.withCheckIn` z `MonitorConfig` (crontab, strefa czasowa, margines, max runtime, progi);
- [`StatementImportJob`](StatementImportJob.java): job co minutę, trzy sposoby obsługi wyjątku wokół `withCheckIn`, zawieszenie i heartbeat;
- [`PayoutBatchCheckIns`](PayoutBatchCheckIns.java): ręczne check-iny w listenerze frameworka wsadowego i nakładające się uruchomienia;
- [`PaymentTelemetry`](PaymentTelemetry.java): tagi, level, fingerprint, filtr oczekiwanych wyjątków w SDK, w wersji docelowej i z typowymi błędami;
- [`PaymentsEndpoint`](PaymentsEndpoint.java): klasyfikacja błędów requestu;
- [`HealthEndpoint`](HealthEndpoint.java): `/live` i `/health` na `HttpServer`, kontynuacja `sentry-trace` z próby Uptime Monitora;
- [`Module06Demo`](Module06Demo.java): scenariusze uruchamiane po kolei; test `Module06ScenariosTest` sprawdza te same scenariusze na transporcie w pamięci;
- [`PaymentsAlertPolicy`](PaymentsAlertPolicy.java): Metric Monitor, trzy Alerty i kanał webhook jako dane, z których powstają żądania API i podgląd filtrów;
- [`AlertingSetup`](AlertingSetup.java): `setup`, `status` i `cleanup` polityki przez REST API;
- [`WebhookReceiver`](WebhookReceiver.java): lokalny odbiornik webhooków z weryfikacją podpisu;
- [`AlertingDemo`](AlertingDemo.java): Alerty end-to-end, pięć sytuacji i historia Alertów; test `AlertingTest` sprawdza żądania, podgląd filtrów, odbiornik i parsowanie odpowiedzi bez sieci.

## Scenariusze

Pełne wyjaśnienie każdego scenariusza (o co chodzi, co pokazujemy, problem, dobra praktyka, na co patrzeć) jest w komentarzu nad jego metodą w [`Module06Demo`](Module06Demo.java), a dla Alertów end-to-end w [`AlertingDemo`](AlertingDemo.java).

1. **Cron Monitor z kodu.** Para `IN_PROGRESS` i `OK` z jednym identyfikatorem, konfiguracja tylko w pierwszym check-inie (z niej Sentry zakłada albo aktualizuje monitor), environment z opcji SDK. Raport z nieuzgodnioną płatnością to nadal `OK`, bo status zależy tylko od tego, czy callback rzucił wyjątek.
2. **Job zakończony wyjątkiem.** Połknięty wyjątek daje `OK`. Wyjątek wypuszczony z zadania `ScheduledExecutorService` daje jeden `ERROR`, zatrzymuje wszystkie kolejne uruchomienia i nie zostawia eventu. Wersja docelowa: event w callbacku (ten sam trace co check-in), `ERROR` i dalsza praca.
3. **Job, który wisi.** `withCheckIn` zostawia `IN_PROGRESS`, który Sentry zamienia w timeout po `max_runtime`. Heartbeat nie wysyła nic i daje tylko missed. SDK nie ma statusów timeout ani missed: oba wylicza Sentry z konfiguracji monitora.
4. **Ręczne check-iny i nakładające się uruchomienia.** Identyfikator w polu singletonu miesza wyniki dwóch replik: pierwsze uruchomienie zostaje otwarte, a wynik drugiego ginie. Wersja docelowa: identyfikator i nowy trace per uruchomienie. Żaden wariant nie blokuje równoległych uruchomień, bo monitor tylko obserwuje.
5. **Jedna awaria banku, jedno Issue.** Fingerprint z komunikatu, który zawiera identyfikator płatności i nazwę sprzedawcy, kontra stabilny fingerprint, tagi `component` i `operation`, identyfikatory w contexts. Wartość fingerprintu równą komunikatowi Sentry parametryzuje (liczby i losowe identyfikatory zamienia na symbole), ale nazwy sprzedawcy nie, więc pierwszy wariant daje Issue na sprzedawcę, a drugi jedno Issue na awarię.
6. **Oczekiwane błędy.** Odmowa karty jako błąd, jako `warning` z `expected=true` (do archiwizacji i filtra Alertu) oraz powtórzone żądanie odcięte w SDK, po którym w Sentry nie zostaje nic do policzenia.
7. **Endpoint zdrowia dla Uptime Monitora.** `/live` zwraca 200 przy niedziałającym banku, `/health` zwraca 503; event z próby ma trace z nagłówka `sentry-trace` i tag `traffic`.

## Uruchomienie

```bash
# offline: nic nie opuszcza procesu, konsola pokazuje check-iny i eventy
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module06.Module06Demo

# online, lokalne Sentry z docker/sentry (instrukcja w docker/sentry/README.md)
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module06.Module06Demo

# jeden scenariusz online (numer w -Dexec.args, np. 1 albo "1 3"); po clean w Sentry są tylko jego dane
./docker/sentry/sentry.sh clean --yes
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module06.Module06Demo -Dexec.args=1

# pełny JSON każdego itemu (check-in z monitor_config i contexts.trace)
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module06.Module06Demo -Dsentry.demo.json=true

# endpoint zdrowia na stałe (port 8086, Enter przełącza bank), dla prawdziwego Uptime Monitora
SENTRY_DSN=... ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module06.HealthEndpoint

# testy scenariuszy
./mvnw -q test -Dtest=Module06ScenariosTest

# Alerty end-to-end, testy bez sieci (uruchomienie online niżej)
./mvnw -q test -Dtest=AlertingTest
```

## Na co patrzeć

- W konsoli: linia `↳ check-in` ma slug, status, skrót identyfikatora, environment i trace oraz czas trwania, jeśli SDK go ustawiło, a check-in z konfiguracją ma pod spodem linię `config`. Pary check-inów łączy się po identyfikatorze, a check-in z eventem po trace. Każdy scenariusz kończy się linią „Na co patrzeć”.
- W Sentry UI, Crons (Insights, Crons): monitory powstają z pierwszego check-inu z konfiguracją. Lista check-inów pokazuje statusy `OK`, `Failed`, `Timeout` i `Missed` oraz Issues powiązane z check-inem przez trace. Joby co minutę po zakończeniu demo zgłaszają Missed co minutę, a po dwóch kolejnych niepowodzeniach (`failure_issue_threshold=2`) powstaje Cron Issue (w filtrze Alertu `Issue category` ma wartość `outage`). Wypłaty mają harmonogram na dni robocze 8-19, więc ich brakujące uruchomienia pojawią się dopiero w tych godzinach. Orientacyjny czas obserwacji: Timeout w `...-hanging` i Cron Issues po 2-3 minutach, Timeout w `payout-batch-shared-field` po około 10 minutach. Potem monitory trzeba usunąć (niżej, „Sprzątanie po demo”).
- W Sentry UI, Issues: filtr `training.module:module06`. Scenariusz 5A daje osobne Issue na każdego sprzedawcę, a 5B, 2C i 7 jedno wspólne Issue `bank-timeout` z rozkładem tagu `operation`. Odmowa karty z 6B ma level warning i tag `expected`.

## Alerty end-to-end (`AlertingDemo`)

Polityka z [`PaymentsAlertPolicy`](PaymentsAlertPolicy.java). Monitor wykrywa problem, Alert decyduje o reakcji, a `AlertingDemo` sprawdza cały łańcuch kontrolowanym sygnałem:

- Metric Monitor `[module06] payments: liczba błędów w 5 min`: `count()` eventów `component:payments level:error` w środowisku `training`, alarm (priorytet high) powyżej 10 w oknie 5 minut, recovery przy 10 i mniej;
- Alert `[module06] payments: błąd komponentu`: źródło wszystkie Issues projektu, środowisko `training`, trigger „An event or issue activity is captured”, filtry (all) `component = payments`, level co najmniej error, `expected != true`, throttling 30 minut, akcja webhook. Trigger na każdy event z throttlingiem zamiast „A new issue is created”: Issue awarii banku zwykle już istnieje (w lokalnym Sentry od pierwszego uruchomienia `Module06Demo`), a nowe Issue powstaje tylko raz;
- Alert `[module06] regresja Issue o wysokim priorytecie`: trigger „A resolved issue regresses”, filtr priorytetu Issue (filtr tagu nie współpracuje z regresją);
- Alert `[module06] payments: skok błędów (Metric Monitor)`: źródło Metric Monitor, filtr priorytetu high, akcja webhook;
- kanał: Internal Integration `module06 alert webhook` z adresem odbiornika. Alert zna tylko slug integracji, więc zmiana adresu nie wymaga edycji Alertów.

API w self-hosted 26.9.0 (sprawdzone lokalnie): Monitor to `detector` (`/organizations/{org}/projects/{project}/detectors/`, typ `metric_issue`), Alert to `workflow` (`/organizations/{org}/workflows/`), a historia Alertu to `/organizations/{org}/workflows/{id}/group-history/`. Instancja ma włączone `organizations:workflow-engine-ui`, więc UI (Monitors > Alerts) pokazuje te same obiekty. Starsze `/projects/{org}/{project}/rules/` i `alert-rules` nie są potrzebne, a legacy plugin WebHooks nie jest dostępny (`/plugins/` zwraca 404), dlatego webhook idzie przez Internal Integration.

Token (Settings, Personal Tokens) z zakresami: `setup`: `org:read`, `project:read`, `alerts:write` i `org:write` (tylko utworzenie Internal Integration); demo online: dodatkowo `alerts:read`, `event:read` i `event:write` (resolve w scenariuszu regresji); `cleanup`: `alerts:write`, `project:read`, `event:admin` (otwarte Issue Metric Monitora) i `org:admin` (usunięcie integracji). Każdy zestaw sprawdzony lokalnie osobnym tokenem.

```bash
export SENTRY_AUTH_TOKEN=...
# 1. polityka w Sentry (idempotentnie: drugie uruchomienie aktualizuje, nie duplikuje)
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module06.AlertingSetup -Dexec.args=setup
# 2. pięć sytuacji i historia Alertów (około 4 minut, odbiornik webhooków na porcie 8097)
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module06.AlertingDemo
# 3. stan w dowolnej chwili i sprzątanie
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module06.AlertingSetup -Dexec.args=status
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module06.AlertingSetup -Dexec.args=cleanup
# offline: podgląd filtrów i przykład powiadomienia, bez Sentry
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module06.AlertingDemo
# sam odbiornik (dla instancji, która może go osiągnąć)
SENTRY_WEBHOOK_SECRET=... ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module06.WebhookReceiver
```

Scenariusze i wynik sprawdzony online na self-hosted 26.9.0 (Alerty świeżo po `setup`):

1. **Inne środowisko.** Awaria banku w `staging`: żadnej akcji, Monitor jej nie liczy. Środowisko Alertu działa przed filtrami.
2. **Pierwszy błąd komponentu.** Ten sam błąd w `training`: jedna akcja Alertu błędu komponentu dla Issue `bank-timeout`.
3. **Seria.** 12 kolejnych eventów tego Issue: każdy przechodzi filtry, historia Alertu nadal ma jedną akcję (throttling 30 minut na Issue). Monitor przekracza próg i przy najbliższej ocenie okna (lokalnie od kilku sekund do 3 minut po serii) tworzy Metric Issue, a jego Alert wykonuje jedną akcję.
4. **Regresja.** Resolve przez API i kolejny event: akcja Alertu regresji; Alert błędu komponentu milczy (throttling).
5. **Oczekiwany błąd.** Odmowa karty z level warning i `expected=true` oraz ta sama odmowa przez `reportCarelessly` (error bez tagów): żadnej akcji. Druga odpada przez brak tagu `component`, tak samo jak odpadłaby prawdziwa awaria zgłoszona tym pomocnikiem.

Wynik: po jednej nowej akcji każdego Alertu, choć do Issue awarii banku poszło 15 eventów (14 w `training`, jeden w `staging`). Linia `· Alert` nad eventem to lokalny podgląd filtrów (środowisko i filtry eventu), a nie decyzja Sentry.

**Webhook na lokalnej instancji.** Alert wykonuje akcję, ale Sentry nie łączy się z adresem prywatnym: w logu taskworkera (`docker compose logs taskworker`) każda akcja kończy się wpisem `sentry_app.send_webhook.halted` z `outcome_reason='send_and_save_webhook_request.restricted_ip'`. Od 25.9.0 self-hosted ma domyślnie `SENTRY_DISALLOWED_IPS` z zakresami prywatnymi. Blokuje to `host.docker.internal` (192.168.65.254 w Docker Desktop) i adresy sieci Docker (172.x), więc odbiornik uruchomiony jako kontener w sieci Sentry też nie pomoże. Dowodem działania polityki jest historia Alertów w API (`status`, scenariusz 6 demo), a wygląd powiadomienia pokazuje tryb offline na przykładzie zapisanym z tej instancji (`resources/module06/webhook-event-alert.json`, skrócony do pól, których używa odbiornik; pełna treść ma cały event, także wyjątki ze stack trace, `sdk` i `hashes`). Opcja, niewprowadzona na tej instancji i niesprawdzona end-to-end: w `docker/sentry/self-hosted/sentry/sentry.conf.py` ustawić `SENTRY_DISALLOWED_IPS` jako domyślną listę bez zakresu obejmującego adres hosta (np. bez `192.168.0.0/16`) i zrestartować `web` oraz `taskworker`. Osłabia to ochronę przed żądaniami Sentry do usług w sieci lokalnej, dlatego zostaje decyzją prowadzącego. Poza szkoleniem odbiornik ma publiczny adres HTTPS i sprawdza podpis (`SENTRY_WEBHOOK_SECRET`, Client Secret integracji z Settings > Custom Integrations).

**Inne obserwacje z lokalnej instancji.**

- Monitor ocenia okno raz na minutę, w stałej dla subskrypcji sekundzie minuty (Snuba rozkłada subskrypcje przesunięciem od 0 do 59 s), a samo okno kończy się na pełnej minucie. Na instancji bez ruchu ocena rusza dopiero, gdy przyjdzie event późniejszy niż ten moment (harmonogram subskrypcji idzie za zapisem eventów). Dlatego demo czeka do pełnej minuty i jeszcze 65 s, a dopiero potem wysyła regresję. Zaległe minuty przetworzone naraz mogą przyjść w innej kolejności: raz Issue Monitora powstało kilka sekund po serii, a dwie minuty później recovery zamknęło je oceną z wartością 1, choć seria mieściła się jeszcze w oknie 5 minut. Historia Alertu zachowuje akcję także wtedy.
- Filtry częstotliwości („Number of events” w oknie) wymagają oceny opóźnionej, którą lokalny taskworker ma wyłączoną (`delayed_workflow.disabled`), dlatego polityka ich nie używa.
- Filtr tagu z triggerem regresji lokalnie zadziałał, choć dokumentacja i formularz traktują to połączenie jako niewspierane. Polityka nie opiera się na tym zachowaniu.
- Dwa Alerty z tą samą akcją (ta sama integracja) dla jednego eventu: Sentry wykonał jedną akcję i zalogował `workflow_engine.action.dedup.dropped` dla drugiej. Historia pokazuje oba Alerty.
- Aktualizacja Alertu (`setup` drugi raz) nie zeruje throttlingu. Kolejne uruchomienie demo w ciągu 30 minut nie da nowej akcji Alertu błędu komponentu; świeży start daje `cleanup` i `setup`. Monitor nie ma takiej pamięci, ale jego okno 5 minut liczy też eventy poprzedniego uruchomienia: nowy Monitor utworzony zaraz po demo potrafi zadziałać na starej serii, zanim przyjdzie nowa. Między uruchomieniami warto odczekać 5 minut.
- Issue Metric Monitora rozwiązał recovery: kilka minut po serii okno spadło do progu, a Issue ma status `resolved` bez niczyjej akcji. Ręczne Resolve takiego Issue API odrzuca (400 „Cannot manually resolve one or more issues”), a usunięcie Monitora go nie zamyka, dlatego `cleanup` usuwa otwarte Issue Monitora.
- Usunięcie Internal Integration działa w tle: do czasu usunięcia lista zwraca ją ze statusem `deletion_in_progress`. `setup` pomija taką integrację i zakłada nową (nowy slug), a Alerty dostają nowy slug przy aktualizacji.

## Konfiguracja w Sentry UI (poza kodem)

- **Cron Monitor.** Konfiguracją z tego pakietu zarządza kod: ręczną zmianę progu w UI może nadpisać kolejny check-in z `MonitorConfig`. Owner monitora i powiązanie z Alertem ustawia się w UI.
- **Alert dla Issue awarii banku.** Z kodu: `AlertingSetup` (wyżej). W UI to samo: Monitors > Alerts, Create Alert, źródło projekt, środowisko `training` (w produkcji `production`), trigger, filtry eventu na tag `component` równy `payments` i level co najmniej error, throttling. Według dokumentacji filtr tagu działa tylko z triggerem utworzenia Issue albo „An event or issue activity is captured”, więc regresję obsługuje osobny Alert z filtrem priorytetu Issue.
- **Ownership Rules.** Ustawienia projektu, Ownership Rules: `tags.component:payments #payments`. Działa dla eventów z 5B, 2C i 7, a nie dla 5A.
- **Uptime Monitor dla /health.** Monitors, Uptime: metoda GET, URL endpointu, interwał 1 minuta, środowisko. Lokalne self-hosted odrzuca adresy prywatne: próba `http://host.docker.internal:8086/health` kończy się statusem `dns_error: destination is restricted` (sprawdzone na self-hosted 26.9.0), bo uptime-checker ma w `docker/sentry/self-hosted/docker-compose.yml` ustawione `UPTIME_CHECKER_ALLOW_INTERNAL_IPS: "false"`. Zmiana na `"true"` i restart kontenera `uptime-checker` powinny to odblokować, ale ta ścieżka nie była sprawdzona end-to-end i osłabia ochronę przed próbami w sieć wewnętrzną. Bez tej zmiany scenariusz 7 naśladuje próbę lokalnie (te same nagłówki), a prawdziwy monitor wymaga publicznego adresu, np. tunelu do `HealthEndpoint` i projektu w sentry.io.

## Sprzątanie po demo (tryb online)

Monitor ma harmonogram, więc po zakończeniu demo Sentry nadal czeka na check-iny. Monitory importu (interwał 1 minuta) co minutę notują Missed i dopisują zdarzenie do otwartego Cron Issue, uzgodnienie robi to co noc, a wypłaty co 15 minut w dni robocze. W projekcie wspólnym dla wszystkich modułów to ciągły szum, który uruchamia też Alerty z triggerem „An event or issue activity is captured”. Harmonogramy zostają realistyczne, bo krótki interwał jest potrzebny, żeby Missed, Timeout i Cron Issue pojawiły się w kilka minut. Po obejrzeniu wyników monitory usuwa się jawnie.

Dlaczego usunięcie, a nie wyłączenie albo wyciszenie (sprawdzone na self-hosted 26.9.0): SDK nie wyłączy monitora, bo konfiguracja w check-inie nie ma statusu. Wyłączony monitor odrzuca check-iny, więc kolejne uruchomienie demo niczego by nie pokazało. Wyciszony nadal notuje Missed, tylko nie tworzy Issues. Usunięty monitor kolejne demo założy od nowa z `MonitorConfig`, bo konfiguracją zarządza kod. Cron Issues usuniętego monitora zostają otwarte, choć nic ich już nie aktualizuje, więc skrypt je rozwiązuje.

W UI: Insights, Crons, każdy monitor z listy poniżej, Delete; potem Issues, wyszukiwanie `monitor.slug:<slug>`, Resolve. Przez API: token osobisty (Settings, Personal Tokens) z zakresami Alerts: Read & Write i Issue & Event: Read & Write. `sentry` i `sentry-training` to slugi organizacji i projektu lokalnej instancji.

```bash
TOKEN=...
API=http://localhost:9000/api/0
for slug in settlement-reconciliation bank-statement-import bank-statement-import-swallowed \
    bank-statement-import-suppressed bank-statement-import-hanging bank-statement-import-heartbeat \
    payout-batch payout-batch-shared-field; do
  curl -s -o /dev/null -w "monitor $slug: %{http_code}\n" -X DELETE \
    -H "Authorization: Bearer $TOKEN" "$API/organizations/sentry/monitors/$slug/"
  curl -s -o /dev/null -w "issues  $slug: %{http_code}\n" -X PUT \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"status":"resolved"}' \
    "$API/projects/sentry/sentry-training/issues/?query=monitor.slug:$slug"
done
```

Oczekiwany wynik: 202 dla monitora (usunięcie zaplanowane) i 200 albo 204 dla Issues (204, gdy nie było czego rozwiązać). Kolejne uruchomienie demo utworzy monitory ponownie.

**Alerty i Monitor z `AlertingDemo`.** `AlertingSetup cleanup` usuwa trzy Alerty, usuwa otwarte Issue Metric Monitora (jeśli recovery jeszcze go nie zamknęło), usuwa Monitor i Internal Integration (Alerty i Monitor po nazwie z prefiksem `[module06]`, integrację po nazwie `module06 alert webhook`). Bez sprzątania Alert błędu komponentu reaguje na eventy `payments` z każdego kolejnego uruchomienia demo modułu, a Alert regresji na regresje wszystkich modułów w `training`. Bez zakresu `org:admin` integracja zostaje (bez Alertów nic nie wysyła) i można ją usunąć w Settings > Custom Integrations. Issue `bank-timeout` zostaje otwarte, jak po `Module06Demo`.

Uwaga (Maven): wątki zawieszonych jobów ze scenariusza 3 są demonami, a `exec:java` ma w `pom.xml` `cleanupDaemonThreads=false`, więc nie czeka na nie i nie przerywa ich. Kończą się razem z JVM, bez check-inu końcowego, jak job w zabitym procesie.
