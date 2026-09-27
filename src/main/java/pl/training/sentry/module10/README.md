# Moduł 10: Dashboardy, metryki i operacyjny monitoring aplikacji

Domena: checkout-api przyjmuje płatność za zamówienie. Płatność może zostać przyjęta, odrzucona przez bramkę (klient dostaje HTTP 200), przekroczyć limit czasu bramki albo trafić na celowy błąd w kodzie (zamówienia w EUR). Scenariusze pokazują, jak z tego ruchu zrobić dane, na których da się zbudować dashboard: Application Metrics o stałym kontrakcie, poprawnie liczony wskaźnik i sesje Release Health.

Najważniejsza teoria modułu jest w komentarzu na początku [`Module10Demo`](Module10Demo.java).

## Klasy

- [`Order`](Order.java), [`CheckoutService`](CheckoutService.java), [`PaymentQueue`](PaymentQueue.java): kod domenowy bez Sentry;
- [`Ratios`](Ratios.java): ratio jako iloraz sum, crash-free sessions i jawny brak wyniku przy zerowym mianowniku; też bez Sentry;
- [`CheckoutMetrics`](CheckoutMetrics.java): kontrakt metryk, czyli stałe nazwy, typy, jednostki i atrybuty; wymiary jako enumy ([`CheckoutOutcome`](CheckoutOutcome.java), `Order.PaymentMethod`);
- [`CheckoutEndpoint`](CheckoutEndpoint.java): request, kolejność emisji metryk, błąd obsłużony i nieobsłużony, sesja na request;
- [`TelemetryPrivacy`](TelemetryPrivacy.java): osobne callbacki `beforeSend` dla eventów i dla metryk;
- [`Module10Demo`](Module10Demo.java): scenariusze uruchamiane po kolei; test `Module10ScenariosTest` sprawdza te same scenariusze na transporcie w pamięci.

Dashboard jako kod (scenariusze 7 do 12):

- `src/main/resources/module10/checkout-dashboard.json`: definicja dashboardu checkoutu w repozytorium; każdy widget ma pytanie i decyzję obok zapytania, dashboard ma właściciela;
- [`DashboardDefinition`](DashboardDefinition.java): model definicji i jej wczytanie (nieznane pole w JSON to błąd, a nie cicho zgubiona wartość);
- [`DashboardRules`](DashboardRules.java): reguły sprawdzane bez sieci: iloraz sum zamiast średniej procentów, jedno środowisko, limit serii, zamknięty słownik grupowania, właściciel, zgodność z kontraktem `CheckoutMetrics`, wizualizacje obsługiwane przez API;
- [`DashboardPayload`](DashboardPayload.java): treść żądania REST API oraz porównanie z dashboardem w Sentry (drift) na polach zarządzanych przez definicję;
- [`DashboardSync`](DashboardSync.java): utwórz albo zaktualizuj po tytule, tryby `PLAN` i `APPLY`;
- [`SentryDashboardsApi`](SentryDashboardsApi.java): REST API dashboardów i test odbiorowy widgetów na `java.net.http`;
- [`DashboardAsCodeDemo`](DashboardAsCodeDemo.java): scenariusze 7 do 12; test `DashboardAsCodeTest` sprawdza je bez sieci, z API dashboardów w pamięci.

## Scenariusze

Pełne wyjaśnienie każdego scenariusza (o co chodzi, co pokazujemy, problem, dobra praktyka, na co patrzeć) jest w komentarzu nad jego metodą w [`Module10Demo`](Module10Demo.java) (scenariusze 1-6) i [`DashboardAsCodeDemo`](DashboardAsCodeDemo.java) (scenariusze 7-12).

1. **Próba przed pracą, wynik osobno.** Metryki dopisane na końcu happy path gubią timeout z licznika i z mianownika: 3 próby, 1 porażka, failure rate 1/3. Wersja docelowa emituje `checkout.attempted` przed wywołaniem bramki: 4 próby, 2 porażki, 2/4. Odrzucona płatność to HTTP 200 i jednocześnie `checkout.failed` z `checkout.outcome=declined`.
2. **Stan kolejki: gauge, a nie counter.** Konsument stoi od trzeciej minuty. `payments.queue.enqueued` ma co minutę te same 2 zdarzenia, a gauge `payments.queue.depth` rośnie 0, 0, 2, 4. Gauge nie ma agregacji `latest`, więc widget stanu wybiera `max` albo `avg` w buckecie.
3. **Kardynalność atrybutów.** `order.id` i komunikat bramki jako atrybuty dają tyle wartości, ile zamówień; wersja docelowa ma tylko `payment.method` i `checkout.outcome` o zamkniętym słowniku. Identyfikator służy do znalezienia próbki (event, log), a nie do stałego grupowania.
4. **Dane osobowe ze scope w metrykach.** User z e-mailem ustawiony poza modułem płatności trafia do każdej metryki jako `user.id`, `user.name` i `user.email`, choć `CheckoutMetrics` przyjmuje tylko enumy. `options.setBeforeSend` czyści tylko event; metryki czyści dopiero `options.getMetrics().setBeforeSend`.
5. **Failure rate doby: iloraz sum.** Średnia z ratio godzin daje 18,0%, iloraz sum 2,1%, bo godzina z 6 próbami waży tyle co godzina szczytu. Okno bez prób nie ma wyniku (brak danych, a nie 0%).
6. **Release Health: sesje trzeba utworzyć samemu.** Serwerowy Java SDK nie tworzy sesji, więc bez `startSession` release nie ma sesji ani crash-free. Z sesją na request konsola pokazuje sesję zakończoną bez błędów, sesję z `errors=1` (błąd obsłużony) i sesję `crashed` (błąd z `handled=false`): crash-free sessions 66,7%.

Scenariusze `DashboardAsCodeDemo`:

7. **Definicja i reguły.** Osiem widgetów z pytaniem, decyzją i datasetem, wykresy także z agregacją (lista issues jej nie ma); reguły nie zgłaszają naruszeń.
8. **Pułapki wychwycone przed wysłaniem.** Siedem wariantów: średnia procentów, pusty filtr środowiska, 12 serii na wykresie, grupowanie po `order.id`, brak właściciela, nazwa metryki niezgodna z kodem, pusty filtr release. Każdy ma co najmniej jedno naruszenie reguł. Z tokenem demo pyta też serwer (`validateOnly`), który wszystkie przyjmuje.
9. **Treść żądania.** Pytanie i decyzja w opisie widgetu, właściciel w Edit Access, progi decyzji w `thresholds`.
10. **Ruch referencyjny** (tylko z `SENTRY_DSN`). 12 prób z sesją na request: 9 opłaconych, po jednej porażce declined, gateway_timeout i internal_error, 1 sesja crashed, 4 pomiary kolejki. Ruch trafia do release przypiętego w definicji, a nie do `SENTRY_RELEASE`.
11. **Synchronizacja** (tylko z `SENTRY_AUTH_TOKEN`). Utwórz albo zaktualizuj po tytule; ponowne uruchomienie bez zmian daje UNCHANGED, a ręczna zmiana środowiska, release i widgetów wraca jako drift.
12. **Test odbiorowy** (tylko z `SENTRY_AUTH_TOKEN`). Zapytanie każdego widgetu z filtrami dashboardu: dane albo ich brak. Brak danych to brak pomiaru, a nie zero.

## Dane pod dashboard

Kontrakt z [`CheckoutMetrics`](CheckoutMetrics.java) przekłada się na widgety datasetu Application Metrics (Explore, Metrics) i jeden widget datasetu Releases:

- failure rate checkoutu: `sum` z `checkout.failed` przez `sum` z `checkout.attempted`, ten sam zakres, okno i filtry; przekrój po `payment.method`, przyczyny po `checkout.outcome`;
- czas checkoutu: `p50` i `p95` z `checkout.duration` (millisecond) razem z `count`, bo percentyl z kilku obserwacji niewiele mówi;
- zaległość kolejki: `max` z `payments.queue.depth` w buckecie;
- crash-free sessions: dataset Releases, tylko gdy aplikacja tworzy sesje.

Te widgety, razem z tabelą nierozwiązanych issues checkoutu (dataset Issues, filtr `training.module:module10`), zapisuje plik `checkout-dashboard.json`. Agregacje Application Metrics mają w API postać `funkcja(value,nazwa,typ,jednostka)`, np. `p95(value,checkout.duration,distribution,millisecond)`; metryka bez jednostki ma jednostkę `none`. Failure rate to jedno równanie `equation|sum(value,checkout.failed,counter,none) / sum(value,checkout.attempted,counter,none)`, więc Sentry liczy iloraz sum w każdym buckecie i w całym zakresie.

## Uruchomienie

```bash
# offline: nic nie opuszcza procesu, konsola pokazuje, co SDK by wysłało
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module10.Module10Demo

# online, lokalne Sentry z docker/sentry (instrukcja w docker/sentry/README.md)
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module10.Module10Demo

# jeden scenariusz online (numer w -Dexec.args, np. 1 albo "1 3"); po clean w Sentry są tylko jego dane
./docker/sentry/sentry.sh clean --yes
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module10.Module10Demo -Dexec.args=1

# online z osobnym release: crash-free tylko z sesji tego uruchomienia
SENTRY_RELEASE=sentry-training@1.0.0-m10 SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module10.Module10Demo

# pełny JSON każdego itemu, także wszystkie atrybuty metryk
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module10.Module10Demo -Dsentry.demo.json=true

# testy scenariuszy
./mvnw -q test -Dtest=Module10ScenariosTest

# dashboard jako kod, offline: definicja, reguły i treść żądania, bez sieci
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module10.DashboardAsCodeDemo

# wybrane scenariusze dashboardu (7-12), np. tylko 8; scenariusz 12 zawsze poprzedza synchronizacja z 11
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module10.DashboardAsCodeDemo -Dexec.args=8

# dashboard jako kod, online: ruch referencyjny do release z definicji, synchronizacja i test odbiorowy
export SENTRY_AUTH_TOKEN=$(./docker/sentry/sentry.sh token)   # raz na terminal
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module10.DashboardAsCodeDemo

# ponowne uruchomienie z symulacją ręcznej zmiany w UI (drift), bez nowego ruchu
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module10.DashboardAsCodeDemo -Dexec.args=--simulate-drift

# tylko raport różnic, bez zmian w Sentry (np. krok pipeline dla pull requestu)
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module10.DashboardAsCodeDemo -Dexec.args=--plan

# testy dashboardu jako kodu (bez sieci)
./mvnw -q test -Dtest=DashboardAsCodeTest
```

Zmienne dla dashboardu: `SENTRY_AUTH_TOKEN` (zakresy `org:read` i `event:read`; token z `sentry.sh token` je ma), opcjonalnie `SENTRY_URL` (domyślnie `http://localhost:9000`) i `SENTRY_ORG` (domyślnie `sentry`).

Przypięty release: dashboard pokazuje tylko release z pola `release` definicji (`sentry-training@1.0.0-m10ref`), a ruch referencyjny scenariusza 10 trafia właśnie tam, niezależnie od `SENTRY_RELEASE`. Nowe wydanie to zmiana tej wartości w `checkout-dashboard.json`, uruchomienie z `--plan` (drift: release w repozytorium nowy, w Sentry stary) i uruchomienie bez `--plan`, które zapisuje nowy filtr. Porównanie dwóch wydań to dwie wartości na liście; tabela crash-free sessions pokaże wtedy dwa wiersze, a pozostałe widgety zsumują oba releases.

Usunięcie dashboardu szkoleniowego: w UI Dashboards, dashboard „Checkout: kontrakt operacyjny (moduł 10)”, menu z trzema kropkami, Delete. Albo przez API, z identyfikatorem wypisanym w scenariuszu 11:

```bash
curl -X DELETE -H "Authorization: Bearer $SENTRY_AUTH_TOKEN" http://localhost:9000/api/0/organizations/sentry/dashboards/ID/
```

Kolejne uruchomienie demo utworzy go od nowa. Token po szkoleniu usuwa się w `/settings/account/api/auth-tokens/`.

## Na co patrzeć

- W konsoli: linie `↳ metryka` mają typ, nazwę, wartość, jednostkę i atrybuty ustawione przez kod albo skopiowane ze scope (`user.*`). Atrybuty `sentry.*` (release, environment, SDK) i `server.address` są w każdej metryce, więc skrót je pomija; pokazuje je tryb JSON. Linie `↳ sesja` to kolejne stany sesji: start (`init`), aktualizacja po błędzie i koniec. Metryki pojawiają się dopiero po `Sentry.flush`, bo SDK je buforuje i wysyła paczką 5 s po pierwszej metryce w buforze (najwyżej 1000 metryk w paczce).
- W Sentry UI (tryb online): eventy mają tag `training.module:module10`, ale metryki nie. Tag ustawiony przez `options.setTag` trafia tylko do eventów, więc metryki modułu znajdziesz po nazwach (`checkout.*`, `payments.queue.*`) i po `environment:training`. Sesje: Releases, release `sentry-training@1.0.0`. Liczby sesji release sumują wszystkie uruchomienia (także demo innych modułów z tym samym release), więc do porównania z konsolą uruchom demo z osobnym `SENTRY_RELEASE`.
- Dashboard (scenariusze 11 i 12): Dashboards, „Checkout: kontrakt operacyjny (moduł 10)”. Filtry projektu `sentry-training`, środowiska `training` i release `sentry-training@1.0.0-m10ref` są zapisane w dashboardzie, więc widgety pokazują ruch scenariusza 10 bez ręcznego ustawiania filtrów: failure rate 0,25 (powyżej progu decyzji 0,05), trzy przyczyny porażek po jednej, max kolejki 2, crash-free sessions 11 z 12. Kolejne uruchomienia zwiększają liczby, proporcje zostają. Zmiana filtra bez „Save” nie jest driftem; zapisana zmiana jest i wykryje ją kolejne uruchomienie.

## Sprawdzone na lokalnym self-hosted Sentry 26.9.0

- Application Metrics są przyjmowane: organizacja ma włączone `tracemetrics-ingestion` i `tracemetrics-enabled`, a API zapytań (`/api/0/organizations/sentry/events/` z `dataset=tracemetrics`) zwraca metryki z demo z typem, jednostką `millisecond` dla `checkout.duration` i atrybutami, także `user.email` z wariantu A scenariusza 4.
- Sesje są przyjmowane: dla release z jednego uruchomienia API sesji (`/api/0/organizations/sentry/sessions/`) i lista releases pokazują 3 sesje: healthy, errored i crashed, crash-free sessions 66,7%. Dane sesji pojawiają się z opóźnieniem około minuty.
- Wywołanie `endSession` po crashu wysyła stan crashed drugi raz i Sentry liczy ten sam crash podwójnie. Ten sam ruch z `endSession` po crashu dał 3 sesje, 2 crashed, 0 errored i crash-free 33,3%. Dlatego [`CheckoutEndpoint`](CheckoutEndpoint.java) nie kończy sesji po crashu.
- Dashboard z kodu: REST API przyjmuje datasety `tracemetrics` (Application Metrics), `metrics` (Releases, sesje), `issue`, `error-events`, `spans` i `logs`. Odrzuca `discover` i `transaction-like` (komunikat o wycofaniu Transactions na rzecz Spans), tabelę dla `tracemetrics` (wymaga flagi `tracemetrics-dashboard-table`, lokalnie wyłączonej), wykres z grupowaniem bez `limit`, równanie obok innej agregacji na wykresie Application Metrics i wysokość mniejszą niż 2 dla wykresu.
- Te same żądania bez błędu przyjmują `avg` z countera, 6 grup przy dwóch agregacjach (12 serii), grupowanie po `order.id`, pusty filtr środowiska i nieistniejącą metrykę. Dlatego [`DashboardRules`](DashboardRules.java) sprawdza je w teście.
- Zakres `org:read` wystarcza do utworzenia, zmiany i usunięcia dashboardu, więc token z `sentry.sh token`, choć ma same zakresy odczytu, może zmieniać dashboardy organizacji. `POST` z polem `projects` zwrócił 403, bo użytkownik tokenu (właściciel organizacji) nie należy do zespołu projektu; ten sam payload z parametrem `?project=2` przeszedł.
- `POST` z zajętym tytułem nie zwraca błędu, tylko tworzy dashboard z tytułem zakończonym „copy”. Stąd wyszukanie po tytule przed utworzeniem. Ponowne uruchomienie demo dało UNCHANGED i nie utworzyło drugiego dashboardu, a po symulowanej zmianie w UI (`--simulate-drift`) tryb PLAN wypisał trzy różnice: environment, release i dodany widget. Serwer zwraca filtr release w `filters.release`, tak jak go przyjmuje.
- Serwer zwraca progi z kluczem `preferredPolarity`, choć przyjmuje `preferred_polarity`; porównanie surowego JSON zgłaszałoby drift przy każdym uruchomieniu.
- Test odbiorowy bez filtra release (wszystkie uruchomienia demo w zakresie 24h) dał failure rate około 0,91, a ten sam widget z release ruchu referencyjnego 0,25. Różnicę robią warianty z błędami z `Module10Demo`: scenariusz 3 wysyła `checkout.failed` bez próby i bez `checkout.outcome` (widget porażek pokazuje dla nich pustą przyczynę), a wariant A scenariusza 1 gubi próby. Jedno uruchomienie `Module10Demo` samo daje failure rate 1,0. Stąd reguła przypiętego release w [`DashboardRules`](DashboardRules.java).
- Zapytanie z równaniem failure rate dla release bez ruchu zwraca 0, a nie brak wyniku: operandy `sum` są `null`, iloraz 0. Widget z progami może więc pokazać 0 w zielonym przedziale zamiast braku danych, dlatego test odbiorowy sprawdza operandy równania, a decyzja widgetu każe patrzeć na liczbę prób.
