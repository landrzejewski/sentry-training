# Moduł 9: Distributed tracing i OpenTelemetry

Domena: checkout-api przyjmuje zamówienie, liczy cenę na puli wątków i rezerwuje towar w inventory-service przez HTTP. Obie usługi działają w jednym procesie, ale każda ma własne OpenTelemetry SDK, a rozmawiają przez prawdziwe HTTP na porcie lokalnym (`com.sun.net.httpserver.HttpServer` i `java.net.http.HttpClient`). Relację między nimi niosą więc wyłącznie nagłówki, jak między dwoma procesami. Topologia to lekki wariant OTLP: spany tworzy i eksportuje OTel, a Sentry SDK raportuje błędy powiązane z bieżącym spanem.

Najważniejsza teoria modułu jest w komentarzu na początku [`Module09Demo`](Module09Demo.java).

## Klasy

- [`ServiceTelemetry`](ServiceTelemetry.java): właściciel SDK jednej usługi (`Resource`, sampler, procesory, propagatory, shutdown);
- [`SentryOtlp`](SentryOtlp.java): procesor eventów i propagator `sentry` z `sentry-opentelemetry-otlp` oraz exporter OTLP do projektu Sentry;
- [`StockReservation`](StockReservation.java): cykl życia spana w kodzie domenowym, w wersji docelowej i z typowymi błędami;
- [`TracedHttpClient`](TracedHttpClient.java): instrumentacja klienta HTTP, czyli span `CLIENT` i inject;
- [`InventoryService`](InventoryService.java): serwer, extract, span `SERVER`, Baggage z allowlistą i raportowanie błędów do Sentry;
- [`CheckoutService`](CheckoutService.java): wywołanie HTTP i zadanie na puli w wersji docelowej i w wariantach, w których kontekst się gubi;
- [`StockImportJob`](StockImportJob.java): krótki proces z `BatchSpanProcessor`;
- [`TraceConsole`](TraceConsole.java): exporter wypisujący spany jako drzewa trace;
- [`Module09Demo`](Module09Demo.java): scenariusze uruchamiane po kolei; test `Module09ScenariosTest` sprawdza te same scenariusze na exporterze w pamięci i transporcie Sentry w pamięci.

## Scenariusze

Pełne wyjaśnienie każdego scenariusza (o co chodzi, co pokazujemy, problem, dobra praktyka, na co patrzeć) jest w komentarzu nad jego metodą w [`Module09Demo`](Module09Demo.java).

1. **Cykl życia spana.** `startSpan`, `makeCurrent`, `recordException`, `setStatus` i `end` jako osobne kroki. Span bez `makeCurrent` gubi dzieci (każde zaczyna własny trace), a sam `recordException` daje zdarzenie wyjątku przy statusie UNSET.
2. **Propagacja W3C między usługami.** Propagatory wysyłają `traceparent`, `baggage` i `sentry-trace`, a span SERVER inventory-service jest dzieckiem spana CLIENT. Span CLIENT bez inject daje dwa trace. Ręcznie sklejony `traceparent` pomija span klienta, gubi Baggage i wymusza próbkowanie w usłudze podrzędnej.
3. **Kontekst przez executor.** Zadanie bez kontekstu zaczyna nowy trace, `Context.current().wrap` zachowuje rodzica, a niezamknięty `Scope` dokleja pracę następnego requestu do cudzego trace.
4. **Head sampling w dwóch usługach.** Niezależny `TraceIdRatioBased` w usłudze podrzędnej rozcina trace: część zapisanych trace nie ma spanów inventory-service. `ParentBased` zachowuje decyzję rodzica, więc każdy zapisany trace jest pełny.
5. **Błąd Sentry w trace OTel.** `OpenTelemetryOtlpEventProcessor` wiąże event ze spanem bieżącym w chwili capture, więc event ma trace ID requestu. Raport z globalnej obsługi błędów, po zamknięciu `Scope`, dostaje trace ID ze scope Sentry i zostaje poza trace.
6. **Dwa spany jednej granicy HTTP.** Ręczny span CLIENT wokół instrumentowanego klienta daje dwa zagnieżdżone spany CLIENT, rozpoznawalne po instrumentation scope.
7. **Krótki proces: flush i shutdown.** Bez shutdown spany zostają w kolejce `BatchSpanProcessor` i exporter nie dostaje nic. `forceFlush` po każdej pozycji rozbija eksport na drobne paczki i nie wysyła trwającego spana joba.

## Uruchomienie

```bash
# offline: spany i eventy tylko w konsoli
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module09.Module09Demo

# online, lokalne Sentry z docker/sentry: eventy przez transport Sentry, spany przez OTLP
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module09.Module09Demo

# jeden scenariusz online (numer w -Dexec.args, np. 1 albo "1 3"); po clean w Sentry są tylko jego dane
./docker/sentry/sentry.sh clean --yes
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module09.Module09Demo -Dexec.args=1

# testy scenariuszy
./mvnw -q test -Dtest=Module09ScenariosTest
```

W trybie online endpoint OTLP jest wyliczany z DSN ([`SentryOtlp#tracesEndpoint`](SentryOtlp.java)): dla lokalnego Sentry to `http://localhost:9000/api/2/integration/otlp/v1/traces` z nagłówkiem `x-sentry-auth: sentry sentry_key=<klucz publiczny DSN>`. To skrót szkoleniowy: w produkcji endpoint i nagłówek kopiuje się z ustawień projektu (Client Keys (DSN)). Lokalne self-hosted Sentry 26.9.0 przyjmuje ten endpoint (OTLP/HTTP z protobuf); spany są widoczne po kilkunastu sekundach. Ingestia OTLP ma w Sentry status open beta, więc obserwacje z Sentry UI opisane niżej pochodzą z tej lokalnej instancji i nie są gwarancją zachowania innych wersji ani sentry.io.

## Na co patrzeć

- W konsoli: pod krokami drzewa trace (`↳ trace`) z usługą, `SpanKind`, nazwą, czasem, statusem, wybranymi atrybutami i instrumentation scope w nawiasie. Span, którego rodzica nie wyeksportowano, ma adnotację „rodzic ... nie został wyeksportowany”. Eventy Sentry (`↳ event`) mają linię `trace`: te same 8 znaków co w drzewie oznaczają event powiązany z trace.
- W Sentry UI (tryb online, obserwacje z lokalnego self-hosted 26.9.0): Explore, Traces z filtrem `resource.training.module:module09`; atrybuty `Resource` są zapisane z prefiksem `resource.`, a atrybuty spana bez prefiksu (np. `checkout.order_id:ORD-3003`). Instrumentation scope widać jako `instrumentation.name`, a `deployment.environment.name` jako environment. `service.version` nie staje się release spana. Status UNSET jest pokazywany jako `ok`. Zdarzeń spanów (także z `recordException`) nie ma: według dokumentacji ingestia OTLP je odrzuca.
- Issues: filtr `training.module:module09`. Event ze scenariusza 5A ma w widoku trace miejsce przy spanie `http.server POST /inventory/reservations`; event z 5B wskazuje trace bez spanów.
