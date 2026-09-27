# Moduł 8: standard wdrożenia i audyt gotowości Sentry

Domena: usługa checkout-api (scenariusze 1 i 2) z konfiguracją w stylu Spring Boot, czyli plik bazowy i nakładki profili, oraz aplikacja stanowiska pakowania, która drukuje etykiety (scenariusz 4). Scenariusze pokazują, że gotowość potwierdza dowód, a nie obecność ustawienia. Audyt porównuje realne `SentryOptions` po `Sentry.init` z profilem wdrożenia, a nie plik konfiguracji z samym sobą. Syntetyczny event odbiorowy potwierdza dostarczenie odczytem z Sentry. Sesje Release Health pokazują, że nawet poprawnie wysłane dane dają fałszywy obraz, gdy kod kończy sesję w złym miejscu.

Najważniejsza teoria modułu jest w komentarzu na początku [`Module08Demo`](Module08Demo.java).

## Klasy

- [`DeploymentProfile`](DeploymentProfile.java): profil stosowalności z inwentaryzacji (środowisko, zatwierdzony DSN, release z pipeline, możliwości techniczne);
- [`CheckoutDeployment`](CheckoutDeployment.java): profile checkout-api i pliki konfiguracji w wersji zastanej i poprawionej;
- [`SentryConfiguration`](SentryConfiguration.java): jedno miejsce, które zamienia właściwości na opcje SDK;
- [`ReadinessAuditor`](ReadinessAuditor.java) i [`ReadinessReport`](ReadinessReport.java): kontrole PASS, FAIL, NOT_VERIFIED, NOT_APPLICABLE i decyzja bez uśredniania;
- [`AcceptanceProbe`](AcceptanceProbe.java) i [`SentryEventApi`](SentryEventApi.java): event odbiorowy i jego odczyt z API Sentry;
- [`LabelPrinter`](LabelPrinter.java): kod domenowy bez Sentry;
- [`PrintStation`](PrintStation.java): sesje Release Health, `distinctId` i miejsce `endSession`;
- [`Module08Demo`](Module08Demo.java): scenariusze uruchamiane po kolei; test `Module08ScenariosTest` sprawdza te same scenariusze i zachowania SDK, na których opierają się komentarze.

## Scenariusze

Pełne wyjaśnienie każdego scenariusza (o co chodzi, co pokazujemy, problem, dobra praktyka, na co patrzeć) jest w komentarzu nad jego metodą w [`Module08Demo`](Module08Demo.java).

1. **Profil lokalny wysyła do projektu produkcyjnego.** Profil local dziedziczy DSN z pliku bazowego, więc laptop wysyła do projektu checkout-api z `environment=production` i `tracesSampleRate=1.0`: kontrola DSN ma FAIL. Po poprawce (pusty DSN) SDK jest wyłączone, DSN ma PASS, a pozostałe kontrole NOT_APPLICABLE, bo profil nie ma zatwierdzonej telemetrii.
2. **Audyt konfiguracji stagingu.** Brak environment (SDK przyjmuje `production`), release bez numeru buildu, `sendDefaultPii=true` „na chwilę”, `sample-rate` zamiast `traces-sample-rate`, domyślne `tracePropagationTargets=.*`: każdy problem to osobny FAIL. Po poprawkach wszystkie kontrole konfiguracji mają PASS, a decyzja to INSUFFICIENT_EVIDENCE, bo DELIVERY ma NOT_VERIFIED.
3. **Event odbiorowy po wdrożeniu.** Syntetyczny event z tagiem `synthetic`, stałym fingerprintem i poziomem info. Kontrola DELIVERY: NOT_VERIFIED bez odczytu z Sentry, PASS po odczycie z API z właściwym release, environment i ramkami in-app. FAIL, gdy SDK odrzuciło event lokalnie (pusty ID), event nie dotarł albo dotarł z innym release, environment lub bez ramek in-app.
4. **Release Health.** Sesje zmiany operatora: błąd obsłużony (exited, errors=1), crash (crashed) i ten sam crash z `endSession` w `finally` (exited, crash niewidoczny w crash-free rate).

## Uruchomienie

```bash
# offline: nic nie opuszcza procesu, konsola pokazuje, co SDK by wysłało
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module08.Module08Demo

# online, lokalne Sentry z docker/sentry: scenariusze 3 i 4 wysyłają do projektu sentry-training
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module08.Module08Demo

# jeden scenariusz online (numer w -Dexec.args, np. 1 albo "1 3"); po clean w Sentry są tylko jego dane
./docker/sentry/sentry.sh clean --yes
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module08.Module08Demo -Dexec.args=1

# online z odczytem eventu odbiorowego (scenariusz 3 daje PASS albo FAIL zamiast NOT_VERIFIED).
# Token z zakresami odczytu: ./docker/sentry/sentry.sh token (albo ręcznie w
# http://localhost:9000/settings/account/api/auth-tokens/ z uprawnieniem event:read).
# Dla sentry.io trzeba dodać SENTRY_URL, SENTRY_ORG i SENTRY_PROJECT.
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) SENTRY_AUTH_TOKEN=... ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module08.Module08Demo

# testy scenariuszy
./mvnw -q test -Dtest=Module08ScenariosTest
```

## Na co patrzeć

- W konsoli: raport audytu pod krokiem (status, kontrola, stan zastany i jego skutek, na końcu decyzja), pod scenariuszami 3 i 4 wydruk eventów (`↳ event`) i sesji (`↳ sesja`) z polami `status`, `errors` i `distinctId` (w sesji pole `did`).
- Scenariusze 1 i 2 nie wysyłają niczego także w trybie online: konfiguracja checkout-api ma fikcyjny DSN, a transport konsolowy nie ma delegata HTTP.
- W Sentry UI (tryb online): filtr `training.module:module08`. Issue `ProbeException` z tagiem `synthetic` i priorytetem low zbiera eventy odbiorowe ze wszystkich uruchomień. W Releases: każde uruchomienie scenariusza 4 dodaje 3 sesje, z których tylko B jest crashed, więc crash-free sessions wynosi 66,7% (crash z wariantu C nie obniża wyniku, choć jego event jest w Issues), a users 1, bo sesje mają ten sam `did`. Domyślny release `sentry-training@1.0.0` dzielą wszystkie moduły, więc do oglądania sesji tego demo najlepiej uruchomić je z własnym `SENTRY_RELEASE`, np. `sentry-training@1.0.0+m08`.
