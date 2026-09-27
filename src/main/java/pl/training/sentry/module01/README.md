# Moduł 1: Sentry w workflow utrzymania aplikacji

Domena: usługa checkout-api generuje etykietę wysyłkową. [`ShippingLabelService`](ShippingLabelService.java) ma celowy błąd: każde zamówienie trafia do generatora etykiety kurierskiej. Poprawne zamówienie do punktu odbioru i niekompletne zamówienie kurierskie kończą się tym samym `NullPointerException`. Na tym jednym błędzie scenariusze pokazują, od czego zależy możliwość diagnozy w Sentry.

Najważniejsza teoria modułu jest w komentarzu na początku [`Module01Demo`](Module01Demo.java).

## Klasy

- [`Order`](Order.java): zamówienie z dwoma trybami dostawy;
- [`ShippingLabelService`](ShippingLabelService.java): kod domenowy z błędem, bez Sentry;
- [`CheckoutEndpoint`](CheckoutEndpoint.java): request, isolation scope i błąd obsłużony;
- [`CheckoutTelemetry`](CheckoutTelemetry.java): tagi, context, user i breadcrumb, w wersji docelowej i z typowymi błędami;
- [`LabelExportJob`](LabelExportJob.java): nocny job w osobnym wątku, błąd nieobsłużony i podwójne raportowanie;
- [`Module01Demo`](Module01Demo.java): scenariusze uruchamiane po kolei; test `Module01ScenariosTest` sprawdza te same scenariusze na transporcie w pamięci.

## Scenariusze

Pełne wyjaśnienie każdego scenariusza (o co chodzi, co pokazujemy, problem, dobra praktyka, na co patrzeć) jest w komentarzu nad jego metodą w [`Module01Demo`](Module01Demo.java).

1. **Dług diagnostyczny.** Dwa różne przypadki wysłane bez kontekstu domenowego. Efekt: dwa eventy różniące się tylko identyfikatorem, z release i environment, ale bez trybu dostawy, stanu zamówienia, użytkownika i breadcrumbs.
2. **Jeden stack trace, dwie przyczyny.** Tag `delivery.mode` i context `shipping` przy tym samym wyjątku. Efekt: grupowanie łączy oba eventy w jedno issue, a tag i context pokazują, że to dwie różne przyczyny.
3. **Każda informacja we właściwym mechanizmie.** `order.id` jako tag, e-mail w user i cały obiekt w breadcrumb obok wersji docelowej. Efekt: pierwszy event niesie e-mail klienta w user i w breadcrumb, drugi tylko identyfikator techniczny i stan zamówienia w contexts.
4. **Błąd obsłużony i nieobsłużony.** Ręczne `captureException` kontra `UncaughtExceptionHandlerIntegration` dla tego samego błędu. Efekt: ręczny capture ma `handled=tak` i `level=error`, wyjątek, który zakończył wątek joba, `handled=nie`, `mechanism=UncaughtExceptionHandler` i `level=fatal`.
5. **Podwójne raportowanie.** Ręczny capture i ponowne rzucenie tego samego obiektu, nowy wyjątek bez `cause`, opakowanie z `cause` bez ręcznego capture. Efekt: deduplikacja SDK zostawia jeden event, drugi wariant daje dwa eventy w różnych issues, trzeci jeden event z pełnym łańcuchem wyjątków.
6. **Wyciek kontekstu między requestami.** Dwa requesty na jednym wątku puli, bez własnego isolation scope i z nim. Efekt: bez izolacji event gościa ma usera i breadcrumb poprzedniego requestu, z izolacją tylko własne dane.

## Uruchomienie

```bash
# offline: nic nie opuszcza procesu, konsola pokazuje, co SDK by wysłało
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module01.Module01Demo

# online, lokalne Sentry z docker/sentry (instrukcja w docker/sentry/README.md)
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module01.Module01Demo

# jeden scenariusz online (numer w -Dexec.args, np. 1 albo "1 3"); po clean w Sentry są tylko jego dane
./docker/sentry/sentry.sh clean --yes
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module01.Module01Demo -Dexec.args=1

# online, dowolny projekt Sentry (np. sentry.io)
SENTRY_DSN=https://...@....ingest.sentry.io/... ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module01.Module01Demo

# pełny JSON każdego eventu pod jego skrótem
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module01.Module01Demo -Dsentry.demo.json=true

# testy scenariuszy
./mvnw -q test -Dtest=Module01ScenariosTest
```

## Na co patrzeć

- W konsoli: pod każdym krokiem jest wydruk eventu (`↳ event`) z polami, na które patrzy się przy diagnozie: `handled`, `level`, wyjątek, `release`, `environment`, `tags`, `contexts`, `user`, `breadcrumbs`. Krok bez wydruku oznacza, że SDK niczego nie wysłało. Każdy scenariusz kończy się liniami „Na co patrzeć”.
- Linia `trace` ma tę samą wartość we wszystkich eventach. Bez tracingu każdy nowy scope kopiuje identyfikator trace ze scope utworzonego przy `Sentry.init`, więc wspólny trace nie oznacza tu wspólnej operacji. Osobny trace dla requestu tworzy integracja frameworka albo transakcja (moduł 5).
- W Sentry UI (tryb online): filtr `training.module:module01`. W issue rozkład tagów i licznik users, w evencie sekcje Exception (mechanism, handled, łańcuch wyjątków), Tags, Contexts, User i Breadcrumbs. Scenariusze 1 i 2 dają po jednym issue z dwoma eventami. Ten sam `NullPointerException` z różnych scenariuszy trafia jednak do osobnych issues, bo metody wywołujące należą do stack trace: jeden defekt w kilku ścieżkach wykonania może utworzyć kilka issues.
