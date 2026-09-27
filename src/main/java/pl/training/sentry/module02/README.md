# Moduł 2: Projekty, środowiska i konfiguracja SDK

Domena: usługa checkout-api przyjmuje płatności przez zewnętrzną bramkę. Przykłady pokazują, jak konfiguracja SDK decyduje o tym, czy błąd w ogóle dotrze do Sentry, do którego środowiska trafi i jakie dane opuszczą proces. Scenariusze 1 do 6 działają w czystej Javie, 7 i 8 w aplikacji Spring Boot ze starterem `sentry-spring-boot-4-starter` (pakiet `module02.spring`).

Najważniejsza teoria modułu jest w komentarzu na początku [`Module02Demo`](Module02Demo.java).

## Klasy

- [`DeploymentStage`](DeploymentStage.java): zamknięty słownik etapów wdrożenia i ich wartości `environment`;
- [`SentrySettings`](SentrySettings.java): kontrakt konfiguracji odczytany ze zmiennych wdrożenia i zwalidowany przed startem SDK;
- [`SentryOptionsConfigurer`](SentryOptionsConfigurer.java): jedno miejsce, w którym kontrakt trafia do `SentryOptions`, bez `Sentry.init`;
- [`CheckoutService`](CheckoutService.java), [`PaymentGateway`](PaymentGateway.java), [`ResponseChannel`](ResponseChannel.java), [`ClientAbortedException`](ClientAbortedException.java): kod domenowy bez Sentry;
- [`IncomingRequest`](IncomingRequest.java), [`TrafficClassifier`](TrafficClassifier.java): request HTTP i zaufana klasyfikacja ruchu (klient, pracownik, sonda syntetyczna);
- [`CheckoutEndpoint`](CheckoutEndpoint.java): granica requestu, tagi i context w scope, raportowanie błędu; wersja docelowa i wersja z błędem z przeglądu kodu;
- [`FilteringBeforeSend`](FilteringBeforeSend.java), [`EventDataScrubber`](EventDataScrubber.java): `beforeSend` klasyfikujący i czyszczący; [`HttpOnlyBeforeSend`](HttpOnlyBeforeSend.java) to jego pierwsza, wadliwa wersja;
- [`PaymentRetryJob`](PaymentRetryJob.java): zadanie w tle, event bez requestu;
- [`DeploymentSmokeCheck`](DeploymentSmokeCheck.java): kontrolny event po wdrożeniu;
- `spring.CheckoutApplication`, `spring.CheckoutController`: checkout-api w Spring Boot; `spring.SentryTrainingConfiguration` podpina tryb szkoleniowy przez bean `Sentry.OptionsConfiguration`, a `spring.SentryPrivacyConfiguration` rejestruje `FilteringBeforeSend` jako bean `BeforeSendCallback`; `spring.CheckoutWebClient` udaje frontend. Właściwości `sentry.*` są w `src/main/resources/module02` (`application.properties` i profile `local`, `pii-debug`);
- [`RequestPreviewTransport`](RequestPreviewTransport.java): narzędzie demo, dopisuje do wydruku sekcję request;
- [`Module02Demo`](Module02Demo.java): scenariusze uruchamiane po kolei; test `Module02ScenariosTest` sprawdza te same scenariusze na opcjach SDK i na transporcie w pamięci.

## Scenariusze

Pełne wyjaśnienie każdego scenariusza (o co chodzi, co pokazujemy, problem, dobra praktyka, na co patrzeć) jest w komentarzu nad jego metodą w [`Module02Demo`](Module02Demo.java).

1. **Kontrakt konfiguracji.** `APP_STAGE=prod`, pusty DSN, release `4.12.0` i `SENTRY_SAMPLE_RATE=25%` kończą start listą naruszeń, zanim SDK cokolwiek wyśle. Poprawny staging wysyła kontrolny event z `environment=staging`, release z pipeline i tagiem `service.name`.
2. **Laptop developera.** `APP_STAGE=local` wyłącza SDK mimo DSN z `.env`: brak wydruku eventu. SDK z quickstartu, bez environment, wysyła event z laptopa jako `production`, bez release i z query oraz tokenem w nagłówku.
3. **Error sampling.** `sampleRate=0.25` w trakcie awarii bramki: licznik pokazuje, że `beforeSend` działa dla wszystkich 13 eventów, a losowanie odrzuca potem średnio 3 na 4, także rzadki błąd krytyczny.
4. **Warstwy filtrowania.** `ignoredExceptionsForType` z dedykowanym typem odrzuca tylko zerwane połączenie z klientem. `ignoredErrors` jest za szeroki (gubi awarię bramki) albo za wąski (nic nie filtruje, bo wzorzec musi pasować do całego tekstu wyjątku).
5. **beforeSend klasyfikujący.** Ruch pracownika i potwierdzona sonda odpadają, ta sama ścieżka bez sekretu i podobna ścieżka zostają, wysyłany event traci query i niedozwolone nagłówki.
6. **Ciche gubienie błędów.** Tag `traffic.origin=internal` bez isolation scope przecieka na następny request klienta i jego błąd znika; wyjątek w `beforeSend` odrzuca event joba bez logu w aplikacji (SDK loguje to tylko przy `debug=true`, i to mylącym tekstem „It will be added as breadcrumb and continue”).
7. **Spring Boot i profil lokalny.** Bez `sentry.dsn` starter się nie uruchamia; `SENTRY_DSN` odziedziczony z shella go uruchamia, ale `sentry.enabled=false` w profilu `local` blokuje wysyłkę. W obu wariantach błąd 500 nie daje eventu.
8. **Spring Boot i dane requestu.** Profil `pii-debug` wysyła body z numerem karty, nagłówki, cookies i IP z `X-Forwarded-For`; `send-default-pii=false` nie usuwa query ani nieznanych nagłówków; bean `BeforeSendCallback` zostawia tylko dane z listy dozwolonych.

## Uruchomienie

```bash
# offline: nic nie opuszcza procesu, konsola pokazuje, co SDK by wysłało
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module02.Module02Demo

# online, lokalne Sentry z docker/sentry (instrukcja w docker/sentry/README.md)
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module02.Module02Demo

# jeden scenariusz online (numer w -Dexec.args, np. 1 albo "1 3"); po clean w Sentry są tylko jego dane
./docker/sentry/sentry.sh clean --yes
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module02.Module02Demo -Dexec.args=1

# online, dowolny projekt Sentry (np. sentry.io)
SENTRY_DSN=https://...@....ingest.sentry.io/... ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module02.Module02Demo

# pełny JSON każdego eventu pod jego skrótem
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module02.Module02Demo -Dsentry.demo.json=true

# testy scenariuszy
./mvnw -q test -Dtest=Module02ScenariosTest
```

## Na co patrzeć

- W konsoli: linia `Sentry [module02] ...` pojawia się przy każdej inicjalizacji SDK przez `TrainingSentry.init` (scenariusze 1 do 6 bez wariantu 2A) i pokazuje environment i release, z którymi SDK wystartowało. Pod krokiem jest wydruk eventu (`↳ event`) z dopisanymi liniami `request`, `query`, `headers`, `cookies` i `body`, czyli tym, co z requestu opuściłoby proces. Krok bez wydruku i odpowiedź z `event niewysłany (pusty identyfikator)` oznaczają, że SDK niczego nie wysłało: `captureException` zwraca wtedy pusty `SentryId`.
- Wydruk może mylić w kilku miejscach. `environment=production` w scenariuszach 3 do 6 to wartość z kontraktu (`APP_STAGE=production`), a nie wartość domyślna SDK. W scenariuszach Spring environment i release pochodzą z ustawień szkoleniowych (`training`, `sentry-training@1.0.0`), bo bean `SentryTrainingConfiguration` nadpisuje właściwości. `user (pusty)` to pusty obiekt usera dodany przez SDK, a `(brak)` to brak usera po scrubbingu. Liczba eventów w scenariuszu 3 zmienia się między uruchomieniami, bo sampling jest losowy. `level=fatal` i `handled=nie` w scenariuszu 8 nadaje integracja Spring błędom, które opuściły kontroler.
- W Sentry UI (tryb online): filtr `training.module:module02` przy wszystkich środowiskach, bo eventy trafiają do `staging`, `production` i `training`. W issue rozkład tagów (`traffic.origin`, `service.name`), w evencie sekcje Request, User i stack trace z ramkami in-app. Eventy odrzucone przez SDK nie tworzą issues, widać je tylko w Stats organizacji jako Client Discard (SDK podaje powód `sample_rate`, `event_processor` albo `before_send`). SDK nie wysyła tego raportu osobno, tylko dołącza go do następnego envelope. Odrzucenia, po których ta sama inicjalizacja SDK nic już nie wysłała (wariant 4C, job w 6C), nie docierają więc nigdzie. Domyślny scrubbing po stronie serwera zamienia część wartości na `[Filtered]`, więc surowe dane porównuj z wydrukiem w konsoli.
