# Moduł 5: Tracing, logi i Replay jako kontekst diagnozy

Domena: sklep internetowy. Nocny job uzgadnia płatności z raportem dostawcy, a checkout przechodzi przez dwie usługi HTTP: orders-api wywołuje payments-api. Obie działają w jednym procesie na serwerze HTTP z JDK, ale łączą się wyłącznie przez HTTP, więc ciągłość trace zależy od nagłówków tak jak między dwoma serwerami. Scenariusz 7 uruchamia aplikację Spring Boot, w której transakcje tworzy starter. Na tych przypadkach scenariusze pokazują, kiedy error event, transakcja, spany i logi da się połączyć w jeden obraz wykonania, a kiedy wspólny identyfikator niczego nie dowodzi. Scenariusze 8-10 dotyczą logów, które aplikacja zapisuje przez SLF4J i Logback: zwrot płatności przechodzi przez granicę, serwis i klienta bramki, a SentryAppender zamienia te same wpisy na error eventy, breadcrumbs i Structured Logs.

Tracing Sentry-native: `Sentry.startTransaction`, `ISpan.startChild`, `continueTrace`. Zależność `sentry-opentelemetry-otlp` jest w projekcie, ale ten moduł jej nie konfiguruje. Wariant, w którym spany tworzy OpenTelemetry, a Sentry tylko odczytuje ich identyfikatory, opisuje moduł 9.

Session Replay nagrywa przeglądarka (albo aplikacja mobilna), nie backend Java. Rola backendu to kontynuacja trace z przeglądarki i zachowanie `baggage`, w którym browser SDK przekazuje m.in. replay ID. Scenariusz 6 symuluje przeglądarkę nagłówkiem `sentry-trace`, więc Replay w tych przykładach nie powstaje.

Najważniejsza teoria modułu jest w komentarzu na początku [`Module05Demo`](Module05Demo.java).

## Klasy

- Kod domenowy bez Sentry: [`SettlementLedger`](SettlementLedger.java), [`SettlementReports`](SettlementReports.java) (job), [`OrderChecks`](OrderChecks.java), [`PaymentInstructions`](PaymentInstructions.java) (orders-api, z celowym błędem nowej ścieżki płatności), [`PaymentProvider`](PaymentProvider.java) (payments-api), [`FeatureFlagProvider`](FeatureFlagProvider.java), [`StorefrontClient`](StorefrontClient.java) (przeglądarka w uproszczeniu), [`Latency`](Latency.java);
- [`ChildSpans`](ChildSpans.java): ręczny child span ze statusem, `setThrowable` i zakończeniem w `finally`, bez root transaction i bez capture;
- [`PaymentReconciliationJob`](PaymentReconciliationJob.java): job bez transakcji i z własną `ITransaction`, logi z atrybutami i podsumowanie jako wide event;
- [`TracedHttpHandler`](TracedHttpHandler.java): granica requestu HTTP (własne scopes, `continueTrace`, transakcja `http.server`, jeden właściciel capture);
- [`TracingHttpClient`](TracingHttpClient.java): client span i nagłówki `sentry-trace` i `baggage` zależne od `tracePropagationTargets`;
- [`CheckoutService`](CheckoutService.java): checkout z trzema wariantami sprawdzeń (sekwencyjnie, równolegle bez kontekstu, równolegle z `SentryWrapper`);
- [`CheckoutFlags`](CheckoutFlags.java): `Sentry.addFeatureFlag`;
- [`CheckoutTracesSampler`](CheckoutTracesSampler.java): sampler respektujący decyzję rodzica, z regułami dla trace rozpoczynanych przez usługę;
- [`OrdersApi`](OrdersApi.java), [`PaymentsApi`](PaymentsApi.java): usługi na `com.sun.net.httpserver.HttpServer`;
- pakiet `spring`: aplikacja order-status-api z `sentry-spring-boot-4-starter`, executor `@Async` z `SentryTaskDecorator` i bez niego, SentryAppender podpięty przez starter z progami `sentry.logging.*`;
- pakiet `logback`: [`RefundGateway`](logback/RefundGateway.java) i [`RefundService`](logback/RefundService.java) (kod domenowy logujący przez SLF4J, bez Sentry), [`RefundEndpoint`](logback/RefundEndpoint.java) (granica: scopes, trace, MDC i jedyny `log.error` z wyjątkiem), [`ErrorReporting`](logback/ErrorReporting.java) (jeden właściciel raportu i dwie pułapki), [`LogbackSentryConfig`](logback/LogbackSentryConfig.java) (Logback z SentryAppender z `logback-sentry.xml` albo programowo);
- [`Module05Demo`](Module05Demo.java): scenariusze uruchamiane po kolei; test `Module05ScenariosTest` sprawdza te same scenariusze na transporcie w pamięci oraz zachowania SDK opisane w komentarzach.

## Scenariusze

Pełne wyjaśnienie każdego scenariusza (o co chodzi, co pokazujemy, problem, dobra praktyka, na co patrzeć) jest w komentarzu nad jego metodą w [`Module05Demo`](Module05Demo.java).

1. **Nocny job bez transakcji i z transakcją.** Bez transakcji event i logi obu uruchomień mają jeden trace, skopiowany z propagation context utworzonego przy `Sentry.init`, i nie ma żadnego czasu kroków. Z własną `ITransaction` każde uruchomienie ma osobny trace ze spanami `db.query` i `http.client`, a event wskazuje span raportu, który zawiódł, choć capture nastąpił później, na granicy joba. Podsumowanie uruchomienia to jeden log z atrybutami (wide event); log ERROR nie tworzy issue.
2. **Propagacja między usługami.** Z nagłówkami `sentry-trace` i `baggage` transakcja payments-api ma trace orders-api, a jej parent to client span orders-api. Adres spoza `tracePropagationTargets` nie dostaje nagłówków i trace rozpada się na dwa niezależne.
3. **Kontekst na puli wątków.** Bez `SentryWrapper` znikają spany sprawdzeń, a log z wątku puli ma trace z `Sentry.init` i nie ma `user.id`. Z `SentryWrapper` i jawnie przekazanym rodzicem oba spany są w transakcji, a log ma trace checkoutu.
4. **Waterfall.** Te same sprawdzenia sekwencyjnie i równolegle: przesunięcia startu, suma duration większa od czasu transakcji, przerwa bez spanu (self time) i transakcja krótsza o czas krótszej gałęzi. Koniec sprawdzeń wyznacza dłuższa gałąź, więc to ona leży na critical path.
5. **Feature flag.** Nowa ścieżka płatności za flagą psuje kwotę. Wynik flagi jest w contexts `flags` eventów i w danych transakcji obu wariantów. Pozwala porównać warianty, ale nie dowodzi przyczyny: flaga może korelować z innym wymiarem, np. z segmentem klientów objętych rolloutem.
6. **Sampling w origin.** Decyzja przeglądarki z `sentry-trace` obowiązuje obie usługi. Po odrzuceniu trace zostają event i logi z trace ID przeglądarki, ale bez transakcji. Health check bez nagłówków odrzuca reguła samplera orders-api.
7. **Spring Boot.** Starter tworzy transakcję `http.server` nazwaną szablonem trasy, kontroler dodaje child span, `@Async` z `SentryTaskDecorator` zachowuje kontekst, a executor bez dekoratora go gubi: faktura nie ma spanu, a jej log ma inny trace. Log SLF4J kontrolera trafia do Structured Logs przez appender podpięty przez starter. Nieobsłużony wyjątek raportuje `SentryExceptionResolver` (w wydruku `mechanism=Spring7ExceptionResolver`).
8. **Logback: trzy kanały i progi.** Przy domyślnych progach `log.error` z wyjątkiem tworzy jeden event z wiadomością, loggerem i łańcuchem wyjątków, wpisy INFO i WARN są jego breadcrumbs, a wszystkie wpisy od INFO trafiają do Structured Logs. DEBUG zostaje w konsoli. Próg eventu WARN zamienia ponowienie udanego zwrotu w osobny event.
9. **MDC.** Klucze z `contextTags` są tagami eventu i atrybutami `mdc.*` logów, pozostałe klucze MDC trafiają tylko do contexts `MDC` eventu. `order_id` w `contextTags` to tag o nowej wartości dla każdego zamówienia, a e-mail w MDC wychodzi z eventem mimo `sendDefaultPii=false`.
10. **Podwójne raportowanie.** `log.error(e)` i `captureException(e)` z tym samym wyjątkiem dają jeden event (deduplikacja SDK). Logowanie błędu na każdej warstwie daje trzy eventy, bo każda warstwa raportuje inny obiekt i SDK nie ma czego porównać. Po poprawce (niższe warstwy opakowują wyjątek z `cause`, ERROR loguje tylko granica) jeden event z pełnym łańcuchem.

## Uruchomienie

```bash
# offline: nic nie opuszcza procesu, konsola pokazuje, co SDK by wysłało
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module05.Module05Demo

# online, lokalne Sentry z docker/sentry (instrukcja w docker/sentry/README.md)
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module05.Module05Demo

# jeden scenariusz online (numer w -Dexec.args, np. 1 albo "1 3"); po clean w Sentry są tylko jego dane
./docker/sentry/sentry.sh clean --yes
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module05.Module05Demo -Dexec.args=1

# pełny JSON każdego itemu (dane spanów, atrybuty logów, baggage w trace context)
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module05.Module05Demo -Dsentry.demo.json=true

# testy scenariuszy
./mvnw -q test -Dtest=Module05ScenariosTest
```

## Na co patrzeć

- Linia `↳ transakcja`: nazwa, `op`, status, trace ID, span ID transakcji, `parent` (tylko gdy trace przyszedł z innej usługi albo z przeglądarki), wyniki flag i czas trwania. Linie `span` są w kolejności startu: `+N ms` to start względem początku transakcji, dalej czas trwania, status i span ID. To tekstowy waterfall.
- Linia `↳ log`: poziom, treść, trace i span ID oraz atrybuty. `user.id` pochodzi z usera w scope, `server.address` z nazwy hosta. Logi SDK wysyła paczkami, dlatego demo robi flush na końcu kroku, a część logów może pojawić się kilka linii dalej.
- Linia `trace` eventu ma też span ID. Bez aktywnego spanu to span ID z propagation context: nie odpowiada żadnemu spanowi w żadnej transakcji.
- Brak linii `↳ transakcja` przy requeście oznacza, że SDK jej nie wysłało (sampling albo tracing wyłączony). Transakcje odrzucone przez sampling SDK tylko zlicza w client report z powodem `sample_rate`, który transport HTTP dołącza do kolejnego envelope. Tryb offline go nie pokazuje; w trybie online te liczby trafiają do statystyk użycia (Stats), a nie do Traces.
- W scenariuszu 6A rodzic transakcji orders-api to span przeglądarki, której tu nie ma w Sentry. Korzeń trace wskazuje więc rodzica, którego Sentry nie zna. Tak samo wygląda trace, gdy frontend jest w projekcie, do którego analizujący nie ma dostępu.
- W scenariuszu 7 event z `SentryExceptionResolver` (`mechanism=Spring7ExceptionResolver`) ma `handled=nie` i `level=fatal`: to wyjątek, który opuścił kontroler, a nie ręczny capture.
- W Sentry UI (tryb online): filtr `training.module:module05` w Issues, tag `service` odróżnia orders-api od payments-api. Transakcje i waterfall: Explore > Traces (albo przycisk trace w evencie), logi: Explore > Logs z filtrem trace. W issue `PaymentFailedException` każdy event ma w sekcji Feature Flags wartość `true`, a w Explore > Traces atrybut `flag.evaluation.checkout.new-payment-flow` rozdziela checkouty obu wariantów. Scenariusz 1A daje jeden trace z eventem i logami, ale bez spanów; do tego samego trace trafia log z wątku puli ze scenariusza 3A, choć dotyczy innego wykonania.
- W scenariuszach 8-10 linie `log>` to konsola Logback, czyli pełny strumień logów aplikacji, a linie `↳` pod nimi to, co z tych wpisów dostało Sentry. Event z logu ma linie `wiadomość` i `logger`, a gdy log niesie wyjątek, także `mechanism=LogbackSentryAppender`. Event z logu bez wyjątku nie ma mechanizmu (`handled=n/d`). Structured Log z Logback nie ma nazwy loggera ani stack trace. SDK trzyma logi w kolejce do 1000 wpisów i wysyła je paczkami po 100, pierwszą 5 s po dodaniu wpisu do pustej kolejki. Stawki samplingu dla logów nie ma: nadmiar ponad kolejkę SDK odrzuca z powodem `queue_overflow`. W Sentry UI: Issues z filtrem `training.module:module05`, Explore > Logs z filtrem `order.id` albo `mdc.channel`.
