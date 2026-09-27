# Laboratorium: integracja Sentry od zera

Cel: samodzielnie podłączyć Sentry do pustej aplikacji Spring Boot i pustej aplikacji Angular, tak żeby błędy miały release, environment i czytelny stack trace, a request z przeglądarki do backendu tworzył jeden trace.

| Katalog | Zawartość |
|---|---|
| `spring/start` | pusta aplikacja Spring Boot 4 (shop-api), stan startowy |
| `spring/rozwiazanie` | shop-api po wszystkich krokach części 1 i z CORS z kroku A4 |
| `angular/start` | pusta aplikacja Angular 21 LTS (`ng new --minimal --zoneless`), stan startowy |
| `angular/rozwiazanie` | shop-web po wszystkich krokach części 2 (DSN do wklejenia) |

Pracujesz w katalogach `start`. Każdy krok kończy się punktem kontrolnym: jeśli wynik jest inny niż opisany, nie idź dalej.

## Przygotowanie

Wymagania: JDK 25, Node.js `^20.19.0`, `^22.12.0` albo `>=24` (wymóg Angular CLI 21), Docker z lokalnym Sentry (`docker/sentry/README.md`).

```bash
./docker/sentry/sentry.sh up
./docker/sentry/sentry.sh dsn     # DSN projektu sentry-training, przyda się w obu częściach
```

Panel: http://localhost:9000, login `admin@sentry-training.local`, hasło `sentry-training`.

## Część 1: Spring Boot

### Krok 0. Stan startowy

```bash
cd labs/spring/start
./mvnw -q spring-boot:run
```

W drugim terminalu:

```bash
curl localhost:8090/api/orders/ORD-1           # {"id":"ORD-1","total":207}
curl -i localhost:8090/api/orders/ORD-9        # HTTP 500 (NullPointerException w logu)
curl -i localhost:8090/api/orders/ORD-1/invoice  # HTTP 503 "Faktura będzie dostępna później"
```

Punkt kontrolny: błąd 500 widać tylko w logu aplikacji, a 503 nie zostawia żadnego śladu.

### Krok 1. Starter Sentry

W `pom.xml`, w `<dependencies>`:

```xml
<dependency>
    <groupId>io.sentry</groupId>
    <artifactId>sentry-spring-boot-4-starter</artifactId>
    <version>8.54.0</version>
</dependency>
```

Zatrzymaj aplikację (Ctrl+C) i uruchom ponownie.

Punkt kontrolny: aplikacja działa jak wcześniej i nic nie wysyła. Autokonfiguracja startera wymaga właściwości `sentry.dsn`, której jeszcze nie ma:

```bash
./mvnw -q spring-boot:run -Dspring-boot.run.arguments=--debug | grep -A2 "SentryAutoConfiguration:"
#   SentryAutoConfiguration:
#      Did not match:
#         - @ConditionalOnProperty (sentry.dsn) did not find property 'sentry.dsn'
# aplikacja nadal działa: Ctrl+C
```

### Krok 2. DSN, environment i release

`src/main/resources/application.properties`:

```properties
sentry.dsn=${SENTRY_DSN:}
sentry.environment=${SENTRY_ENVIRONMENT:local}
sentry.release=@sentry.release@
sentry.traces-sample-rate=1.0
```

W `pom.xml`, w `<properties>` (release z wersji artefaktu, filtrowanie zasobów `spring-boot-starter-parent` podstawia `@sentry.release@`):

```xml
<sentry.release>${project.artifactId}@${project.version}</sentry.release>
```

Punkt kontrolny przy starcie: zastąp `ShopApplication.java` wersją z `spring/rozwiazanie` (metoda `sentryStartupCheck`, pole `log` i importy). Loguje, czy Sentry działa i z jakimi wartościami.

```bash
./mvnw -q spring-boot:run
# WARN ... Sentry wyłączone: brak DSN (ustaw zmienną SENTRY_DSN)

SENTRY_DSN=$(../../../docker/sentry/sentry.sh dsn) ./mvnw -q spring-boot:run
# INFO ... Sentry włączone: environment=local, release=shop-api@1.0.0
```

Skąd te wartości:

- DSN tylko ze zmiennej środowiskowej: adres projektu nie trafia do repozytorium, a lokalnie bez zmiennej aplikacja działa bez wysyłki.
- Release z artefaktu (`shop-api@1.0.0` z `pom.xml`). Pipeline wylicza go raz i nadpisuje zmienną: `SENTRY_RELEASE=shop-api@1.0.1+42 ./mvnw -q spring-boot:run` daje w logu `release=shop-api@1.0.1+42`.
- Environment z miejsca uruchomienia: `local` na laptopie, `SENTRY_ENVIRONMENT=staging` na stagingu.

### Krok 3. Pierwsze błędy

Aplikacja działa z `SENTRY_DSN`. Błąd nieobsłużony nie wymaga zmian w kodzie:

```bash
curl -i localhost:8090/api/orders/ORD-9          # HTTP 500, event w Sentry
curl -i localhost:8090/api/orders/ORD-1/invoice  # HTTP 503, w Sentry nic
```

Wyjątek z `invoice` nie opuszcza kontrolera, więc starter go nie widzi. W bloku `catch` w `OrderController.invoice` dodaj (import `io.sentry.Sentry`):

```java
Sentry.captureException(exception);
```

Po restarcie ten sam `curl` na `/invoice` daje event `InvoiceUnavailableException`.

### Krok 4. Weryfikacja w Sentry

Issues, filtr `release:shop-api@1.0.0`. Punkty kontrolne:

- dwa issues: `NullPointerException` z oznaczeniem Unhandled (mechanism `Spring7ExceptionResolver`) i `InvoiceUnavailableException` bez niego (błąd obsłużony, zgłoszony ręcznie);
- w evencie Release `shop-api@1.0.0` i Environment `local`, a nie `production`;
- w stack trace ramki `pl.training.shop` oznaczone jako in-app i rozwinięte (starter bierze pakiet z `@SpringBootApplication`), ramki Springa i JDK zwinięte;
- przy `NullPointerException` transaction `GET /api/orders/{id}`, czyli szablon trasy, a nie adres z numerem.

### Typowe błędy

| Objaw | Przyczyna |
|---|---|
| Brak eventów i brak jakiegokolwiek błędu w logu | Brak DSN. Bez właściwości `sentry.dsn` starter się nie uruchamia (krok 1), a z pustą wartością uruchamia się z wyłączonym SDK. Stąd punkt kontrolny przy starcie. |
| W Sentry `environment=production` z laptopa | Brak `sentry.environment`: SDK przyjmuje `production` (po usunięciu linii log pokazuje `environment=production`). Eventy z laptopa mieszają się wtedy z produkcyjnymi. |
| Sekret w repozytorium | DSN wpisany na stałe w `application.properties` pozwala każdemu z dostępem do repozytorium wysyłać dane do projektu. Token API (`SENTRY_AUTH_TOKEN`, upload source maps, release) to prawdziwy sekret: tylko w magazynie sekretów CI, nigdy w konfiguracji aplikacji. |
| `Port 8090 was already in use` | Poprzednia instancja nadal działa albo port zajmuje inna aplikacja: `server.port` w `application.properties`. |

## Część 2: Angular

Backend z części 1 działa z `SENTRY_DSN` na porcie 8090 (albo uruchom `spring/rozwiazanie`).

### Krok A0. Stan startowy

```bash
cd labs/angular/start
npm install
npm start          # http://localhost:4200, nagłówek "Hello, shop-web"
```

### Krok A1. SDK

```bash
npm install --save-exact @sentry/angular@11.0.0
npm install --save-dev --save-exact @sentry/cli@3.8.0
```

### Krok A2. `Sentry.init` w `src/main.ts`

Przed `bootstrapApplication` (pełna wersja w `angular/rozwiazanie/src/main.ts`):

```ts
import * as Sentry from '@sentry/angular';

Sentry.init({
  dsn: 'WKLEJ_DSN',                       // wynik ./docker/sentry/sentry.sh dsn
  environment: 'local',
  release: 'shop-web@1.0.0',
  dataCollection: { userInfo: false },    // SDK 11: zastępuje sendDefaultPii
});
```

DSN przeglądarki nie jest sekretem, bo i tak trafia do bundle. SDK 11 nie ma już `sendDefaultPii`, a bez `dataCollection` zbiera domyślnie więcej danych o użytkowniku niż dawniej, między innymi adres IP.

### Krok A3. `ErrorHandler` i pierwszy błąd

W `src/app/app.config.ts` dodaj providera `{ provide: ErrorHandler, useValue: Sentry.createErrorHandler() }`, a w `App` przycisk `Zepsuj coś`, którego handler rzuca `new Error('Testowy błąd z laboratorium')` (wzór w `angular/rozwiazanie`).

Punkt kontrolny: bez providera kliknięcie daje tylko `ERROR Error: Testowy błąd z laboratorium` w konsoli przeglądarki i nic w Sentry, bo Angular sam przechwytuje błędy z szablonów. Z providerem w Sentry pojawia się issue z Release `shop-web@1.0.0`, Environment `local` i mechanism `auto.function.angular.error_handler`. Stack trace wskazuje `main.js`: w trybie deweloperskim mapy nie są w Sentry, to naprawia krok A5.

### Krok A4. Tracing i jeden trace z backendem

- w `Sentry.init`: `integrations: [Sentry.browserTracingIntegration()]`, `tracesSampleRate: 1.0`, `tracePropagationTargets: [/^http:\/\/localhost:8090\/api\//]`;
- w `app.config.ts`: `provideHttpClient(withFetch())`, `{ provide: Sentry.TraceService, deps: [Router] }` i `provideAppInitializer(() => { inject(Sentry.TraceService); })`;
- w `App` przycisk `Pokaż zamówienie ORD-1`, który wywołuje `http://localhost:8090/api/orders/ORD-1`;
- w backendzie klasa `CorsConfig` z `spring/rozwiazanie`: origin `http://localhost:4200` i nagłówki `sentry-trace`, `baggage`.

Punkt kontrolny: w DevTools (Network) request `GET /api/orders/ORD-1` ma nagłówek `sentry-trace`, a strona pokazuje `ORD-1: 207 zł`. W Sentry (Explore > Traces albo link Trace w evencie) jeden trace: pageload strony, span `GET localhost` (`http.client`, przeglądarka; SDK 11 nazywa span metodą i hostem, pełny adres jest w atrybucie `url.full`), a pod nim `GET /api/orders/{id}` (`http.server`, backend). Request idzie do trace pageloadu, bo przeglądarka trzyma jeden trace aż do następnej nawigacji (osobny trace na akcję pokazuje `frontend/checkout-web`). Bez `sentry-trace` i `baggage` w `allowedHeaders` przeglądarka zablokuje request już na preflight.

### Krok A5. Source maps z debug ID

W `angular.json`, w `configurations.production`:

```json
"sourceMap": { "scripts": true, "styles": false, "hidden": true }
```

`hidden` generuje mapy bez komentarza `sourceMappingURL`, więc przeglądarka ich nie szuka. Skopiuj trzy skrypty z `package.json` rozwiązania (`sourcemaps:inject`, `sourcemaps:upload`, `serve:dist`), potem:

```bash
npm run build
npm run sourcemaps:inject
SENTRY_AUTH_TOKEN=$(../../../docker/sentry/sentry.sh ci-token) npm run sourcemaps:upload
rm dist/shop-web/browser/*.map         # mapy są w Sentry, serwer ich nie potrzebuje
npm run serve:dist                     # http://localhost:4300
```

Punkty kontrolne:

- inject: raport `Modified: ... main-XXXX.js` z debug ID;
- upload: `Upload type: artifact bundle`, `Release: shop-web@1.0.0` i ten sam debug ID przy `~/main-XXXX.js`;
- po kliknięciu `Zepsuj coś` na porcie 4300 nowy event ma ramkę `src/app/app.ts` z linią `throw new Error('Testowy błąd z laboratorium');`.

Kolejność jest istotna: build, inject, upload, deploy. Inject zmienia pliki JS i mapy, więc upload przed nim wysyła mapy, które nie pasują do wdrożonych plików. Event wysłany przed uploadem zostaje zminifikowany na zawsze.

Po aktualizacji do Angular 22.1+ sprawdź raport inject: ta wersja sama dopisuje do JS komentarz `//# debugId=`, ale bez fragmentu, który rejestruje identyfikator w przeglądarce. `sentry-cli` 3.8.0 pomija wtedy plik (raport „already have debug ids” zamiast „Modified”), a eventy nie mają `debug_meta` i zostają zminifikowane mimo udanego uploadu. Ten sam problem w nowym CLI `sentry` opisuje zgłoszenie getsentry/cli#1629.

`ci-token` tworzy token organizacji z zakresem `org:ci`: wystarcza do uploadu, a nie pozwala czytać issues. W CI ten token jest sekretem pipeline.

### `npx @sentry/wizard` jako alternatywa

`npx @sentry/wizard@latest -i angular` (Angular 17+) dopisuje `Sentry.init` do `main.ts`, rejestruje `ErrorHandler` i `TraceService` w `app.config.ts`, tworzy `.sentryclirc` z tokenem do uploadu map (i dodaje go do `.gitignore`), prowadzi przez upload map przy buildzie produkcyjnym i dodaje przykładowy komponent. Wizard nie podejmuje decyzji, które należą do zespołu:

- skąd biorą się release i environment na każdym środowisku;
- `tracePropagationTargets` dla API na innym originie i CORS po stronie backendu;
- `dataCollection`, czyli jakie dane użytkownika wolno wysyłać;
- stawki samplingu tracingu i Replay dla produkcji oraz przegląd maskowania Replay;
- przeniesienie tokenu z `.sentryclirc` do magazynu sekretów CI;
- sprawdzenie, że pierwszy event z buildu produkcyjnego ma `debug_meta` i odtworzony stack trace (upload map idzie przez `sentry-cli`, więc przy Angular 22.1+ dotyczy go pułapka opisana w kroku A5).

## Dalej

Pełniejszy przykład z Session Replay, trybem offline, pułapkami propagacji i CORS: `frontend/checkout-web/README.md`.
