# checkout-web: Sentry w aplikacji Angular

Przykład frontendowy do modułów 4 i 5: checkout sklepu w Angular 21 LTS (standalone components, bez zone.js) z `@sentry/angular` 11.0.0 i backendem Spring Boot `checkout-api` (pakiet `pl.training.sentry.checkoutapi`). Na jednym checkoutcie scenariusze pokazują błędy przeglądarki, jeden trace przeglądarka → backend, source maps z debug ID i Session Replay z maskowaniem.

## Wymagania

- Node.js `^22.12.0` albo `>=24` (wymóg Angular CLI 21 i `puppeteer-core`). Starsze Node.js zatrzyma `ng` komunikatem o wersji.
- JDK 25 dla backendu (Maven Wrapper z katalogu głównego repozytorium).
- Tryb online: lokalne Sentry z `docker/sentry` (Replay i source maps działają na self-hosted 26.9.0).
- Scenariusze bez okna (`npm run scenarios`): Google Chrome. Ścieżkę wskazuje `CHROME_PATH`.

## Pliki

| Plik | Rola |
|---|---|
| `src/main.ts` | `initSentry` przed `bootstrapApplication` |
| `src/app/sentry/sentry-setup.ts` | `Sentry.init`: DSN, environment, release, `dataCollection`, tracing, Replay, `tracePropagationTargets` |
| `src/app/sentry/runtime-config.ts` | DSN, environment i adres API z `runtime-config.js`, czyli spoza bundle |
| `src/app/sentry/console-transport.ts` | transport szkoleniowy: podgląd envelope w konsoli przeglądarki (odpowiednik `ConsoleEnvelopeTransport` z Javy) |
| `src/app/app.config.ts` | `ErrorHandler` z `createErrorHandler`, `TraceService`, `provideAppInitializer` |
| `src/app/checkout/checkout-page.ts` | scenariusze: kupon, zapis na później, zamówienie, pułapki propagacji |
| `scripts/build.mjs`, `release.mjs` | build produkcyjny z release `checkout-web@1.0.0+<BUILD_ID>` zapisanym w `dist/checkout-web/build-info.json` |
| `scripts/inject-debug-ids.mjs` | `npx @sentry/cli sourcemaps inject` i kontrola, że każdy plik JS rejestruje debug ID |
| `scripts/upload-sourcemaps.mjs` | kontrola kolejności, `npx @sentry/cli sourcemaps upload`, usunięcie map z outputu |
| `scripts/serve-dist.mjs` | „deploy”: zapis `runtime-config.js` i serwer zbudowanej aplikacji na porcie 4300 |
| `scripts/scenarios.mjs` | scenariusze w Chrome bez okna, z wydrukiem konsoli i nagłówków trace |

## Uruchomienie

```bash
# 1. Backend (z katalogu głównego repozytorium), port 8095
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.checkoutapi.CheckoutApiApplication
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.checkoutapi.CheckoutApiApplication

# 2. Frontend w trybie deweloperskim, http://localhost:4200
cd frontend/checkout-web
npm ci
npm start                                               # offline: envelope tylko w konsoli
SENTRY_DSN=$(../../docker/sentry/sentry.sh dsn) npm start  # online

# 3. Build produkcyjny, debug ID, upload map, deploy, http://localhost:4300
npm run build
npm run sourcemaps:inject
SENTRY_AUTH_TOKEN=$(../../docker/sentry/sentry.sh ci-token) npm run sourcemaps:upload
SENTRY_DSN=$(../../docker/sentry/sentry.sh dsn) npm run serve:dist

# 4. Scenariusze bez okna (aplikacja z kroku 3 albo --url http://localhost:4200)
npm run scenarios
npm run scenarios -- --only kupon,zamowienie
```

Tryb offline nie wymaga Sentry: SDK działa normalnie (scope, integracje, sampling, maskowanie Replay), a transport wypisuje w konsoli przeglądarki (DevTools, zakładka Console) to, co wysłałby do Sentry. Każdy envelope to zwinięta grupa `Sentry ▸ typ` z jedną linią opisu i pełnym payloadem po rozwinięciu. W trybie online ten sam wydruk jest podglądem, a envelope idzie dalej do Sentry.

`ci-token` tworzy token organizacji z zakresem `org:ci`: wystarcza do release i uploadu map, a nie pozwala czytać issues ani eventów (odczyt `/issues/` zwraca 403). Token zawiera adres Sentry i organizację, więc `sentry-cli` nie potrzebuje `SENTRY_URL` ani `--org`. Token trzyma się w magazynie sekretów CI, nigdy w repozytorium; pliki `*.token`, `.env*` i `.sentryclirc` są w `.gitignore`.

## Scenariusze

Pełne wyjaśnienie każdego scenariusza (o co chodzi, co pokazujemy, problem, dobra praktyka, na co patrzeć) jest w komentarzu nad jego wpisem w [`scripts/scenarios.mjs`](scripts/scenarios.mjs).

1. **Błąd w komponencie** (`kupon`). Nieznany kod rabatowy daje `TypeError` w handlerze kliknięcia. Angular przechwytuje go sam i przekazuje do `ErrorHandler`, więc bez `Sentry.createErrorHandler()` błąd ląduje tylko w konsoli.
2. **Nieobsłużona obietnica** (`zapis`). `then` bez `catch`: odrzucenie trafia do `window` jako `unhandledrejection`. Sentry zgłasza jeden event mimo dwóch nasłuchujących (`GlobalHandlers` SDK i `provideBrowserGlobalErrorListeners`).
3. **Zamówienie: jeden trace** (`zamowienie`). `Złożenie zamówienia` (`ui.action.checkout`) → `POST localhost` (`http.client`; SDK 11 nazywa span metodą i hostem, pełny adres jest w atrybucie `url.full`) → `POST /api/checkout` (`http.server`, starter Spring Boot) → `reserve stock` i `POST payments /authorize`.
4. **Błąd backendu w tym samym trace** (`blad-backendu`). Punkt odbioru KRK-031 jest w katalogu frontendu, ale nie w magazynie: backend rzuca `IllegalStateException`, starter wysyła event w trace kliknięcia. Frontend nie wysyła drugiego eventu dla 5xx (jeden właściciel raportowania), a status 0 (sieć, CORS) zgłasza sam, bo backend o nim nie wie.
5. **Pułapka: adres poza `tracePropagationTargets`** (`poza-targets`). Ten sam backend pod `127.0.0.1` dostaje request bez `sentry-trace` i `baggage`: w Sentry dwa niezależne trace, przeglądarki i backendu.
6. **Pułapka: CORS bez nagłówków trace** (`legacy-cors`). Adres pasuje do `tracePropagationTargets`, ale preflight `/legacy-api` dopuszcza tylko `content-type`. Spring Framework 7 odpowiada 200 z niepełną listą nagłówków, a przeglądarka blokuje `POST`: trace nie jest rozerwany, tylko zamówienie w ogóle nie dochodzi. Frontend dostaje status 0 i wysyła oryginalny błąd fetch, `TypeError: Failed to fetch (localhost:8095)`, a nie sam `HttpErrorResponse` (ten nie jest obiektem `Error` i daje w Sentry mylący tytuł issue); backend ma tylko transakcję `OPTIONS` w osobnym trace.
7. **Source maps z debug ID.** Build → inject → upload → deploy, szczegóły niżej.
8. **Session Replay z maskowaniem.** `replaysSessionSampleRate: 0` i `replaysOnErrorSampleRate: 1.0`: przeglądarka trzyma bufor ostatniej minuty i wysyła go razem z dalszą częścią sesji dopiero po pierwszym błędzie przechwyconym przez browser SDK (scenariusze 1, 2, 6). Sam błąd backendu z scenariusza 4 nie uruchamia wysyłki Replay.

## Source maps: kolejność i pułapki

```text
npm run build              ng build, hidden source maps (bez komentarza sourceMappingURL w JS)
npm run sourcemaps:inject  debug ID w plikach JS i mapach, fragment rejestrujący ID w przeglądarce
npm run sourcemaps:upload  kontrola inject, upload artifact bundle, usunięcie *.map z outputu
npm run serve:dist         deploy dokładnie tych plików
```

Na co patrzeć: w konsoli przeglądarki linia `debug_meta=` eventu pokazuje debug ID z uruchomionych plików, a `ramka=` pozycję w zminifikowanym `main-XXXX.js`. W Sentry ten sam event ma ramkę `src/app/checkout/checkout-page.ts` z linią `this.discountPercent.set(coupon.percent);`, ramkę szablonu `checkout-page.html` i ramki Angulara oznaczone jako nie in-app.

- **Upload po deployu.** Event wysłany przed uploadem zostaje zminifikowany (`js_no_source` w błędach przetwarzania) i upload go nie naprawi. Na lokalnym Sentry także kolejne eventy z tym samym debug ID pozostawały zminifikowane przez ponad 40 minut po uploadzie (tyle trwała obserwacja). Naprawę potwierdza nowy event z nowego buildu, a nie ponowienie starego.
- **Zła kolejność inject i upload.** Inject zmienia pliki: dopisuje na początku JS dwie linie i przesuwa o nie mapowanie w mapie. Mapa wysłana przed inject nie ma debug ID, więc Sentry dopasowuje ją starym sposobem, po release i nazwie pliku, i liczy pozycje o dwie linie obok. Na lokalnym Sentry taki event pokazał ramki w `src/main.ts:11` zamiast `checkout-page.ts:58`, bez żadnego błędu przetwarzania w evencie: wynik czytelny, ale fałszywy. `upload-sourcemaps.mjs` odmawia uploadu plików bez fragmentu z inject.
- **Mapy publicznie na serwerze.** `serve-dist.mjs` oddaje każdy plik z katalogu. Przed uploadem `curl -I http://localhost:4300/main-XXXX.js.map` zwraca 200, czyli mapa z całym kodem źródłowym (`sourcesContent`) jest publiczna, a po uploadzie 404, bo skrypt usuwa mapy z outputu dopiero po udanym uploadzie.
- **Debug ID wynika z treści pliku.** `sentry-cli` wylicza go z zawartości, więc plik, który się nie zmienił (np. chunk z kodem Angulara), w kolejnym buildzie dostaje ten sam debug ID, a zmieniony `main` nowy. Release identyfikuje wersję produktu, a debug ID konkretny plik.
- **Aktualizacja do Angular 22.1+.** Od tej wersji `ng build` sam dopisuje `//# debugId=` (standard ECMA-426), ale bez fragmentu rejestrującego ID w przeglądarce. `sentry-cli` 3.8.0 uznaje taki plik za już obsłużony i go pomija („already have debug ids”): upload się udaje, a eventy nie mają `debug_meta` i zostają zminifikowane. Ten sam problem w nowym CLI `sentry` opisuje zgłoszenie getsentry/cli#1629. Angular 21 z tego przykładu nie dopisuje komentarza, a kontrola w `inject-debug-ids.mjs` zatrzyma build, jeśli problem się pojawi.

## Na co patrzeć

W konsoli przeglądarki:

- `event`: `handled` i `mechanism` (`auto.function.angular.error_handler` dla błędu z komponentu, `auto.browser.global_handlers.onunhandledrejection` dla obietnicy), `release`, `environment`, `trace`, `user` z samym pseudonimem `c-7f3a9c`, tagi `checkout.variant` i `delivery.mode`, tag `replayId`.
- `span`: SDK 11 wysyła spany strumieniowo, paczkami (`traceLifecycle: 'stream'`), a nie jako jedną transakcję. `[segment]` to korzeń fragmentu trace z tej przeglądarki: `pageload`, `navigation` (nazwa trasy `/zamowienie/:orderId/`, a nie adres z numerem zamówienia) albo `ui.action.checkout`.
- Linie `request` w `npm run scenarios`: `sentry-trace` przy `localhost:8095`, `BRAK` przy `127.0.0.1`. W konsoli backendu transakcja `POST /api/checkout` ma `parent` równy `span=` spanu `http.client` przeglądarki.
- `replay_event` i `replay_recording`: `typ=buffer`, identyfikator błędu, który uruchomił wysyłkę, oraz linia „teksty w nagraniu” z rozpakowanego nagrania: każdy tekst i każda wartość pola to gwiazdki, także „Anna Kowalska”, e-mail i telefon.

W Sentry UI (filtr `training.module:frontend`):

- Issues: `TypeError: Cannot read properties of undefined (reading 'percent')` z oznaczeniem Unhandled i odtworzonym stack trace; w evencie sekcja Replay i rozkład tagów.
- Trace view zamówienia: korzeń `Złożenie zamówienia`, pod nim `POST localhost` (`http.client`) przeglądarki, pod nim transakcja backendu z dwoma spanami. W scenariuszu 4 błąd backendu wisi przy spanie `reserve stock`.
- Replays: jedna sesja z kilkoma trace, lista błędów zawiera błędy przeglądarki i błąd backendu. Po pierwszym błędzie przeglądarki w sesji SDK dopisuje replay ID do `baggage`, a backend przenosi je do swojego eventu; błąd backendu przed pierwszym błędem przeglądarki nie trafi do nagrania. Tekst i pola formularza są zamaskowane. Użytkownik ma tylko `id`, bez adresu IP.
- Settings > Projects > sentry-training > Source Maps: artifact bundle z release `checkout-web@1.0.0+local` i debug ID z konsoli.

## Decyzje konfiguracyjne

- **`dataCollection` zamiast `sendDefaultPii` (świadoma decyzja, pułapka prywatności).** SDK 11 usunęło `sendDefaultPii`. Nowe `dataCollection` ma domyślnie wszystko włączone, czyli zbiera więcej niż dawne `sendDefaultPii: false`: `userInfo: true` każe Sentry zapisać adres IP użytkownika (`infer_ip: auto`), a pozostałe pola domyślnie dopuszczają cookies, nagłówki, parametry URL i body HTTP. Aktualizacja z SDK 10 bez tej sekcji po cichu zwiększa ilość danych osobowych w Sentry. Checkout ustawia jawnie `userInfo: false`, `cookies: false` i `httpBodies: []`, a identyfikator klienta podaje sam przez `setUser` (w Sentry `ip_address` jest pusty). Java SDK 8.54.0 w backendzie nadal używa `sendDefaultPii` (domyślnie `false`).
- **Tagi a spany.** W SDK 11 tagi scope trafiają do błędów, ale nie do spanów. Do filtrowania spanów służą atrybuty (`Sentry.setAttributes`).
- **Release w buildzie, DSN i environment w deployu.** Release opisuje artefakt, więc wpisuje go build (`--define CHECKOUT_RELEASE`). DSN, environment i adres API są w `runtime-config.js`, który zapisuje deploy: ten sam artefakt z tymi samymi debug ID trafia na każde środowisko.
- **Jedno zamówienie, jeden trace.** Browser SDK trzyma jeden trace od pageloadu do następnej nawigacji. `Sentry.startNewTrace` i `Sentry.startSpan` dają zamówieniu własny korzeń zamiast doklejać je do pageloadu razem z każdym innym kliknięciem.

## React i Node.js

Te same wzorce przenoszą się bez zmian koncepcji: `@sentry/react` ma `Sentry.init` z `browserTracingIntegration` (albo integracją routera), `replayIntegration`, `tracePropagationTargets` i `dataCollection`, a błędy renderowania łapie `Sentry.ErrorBoundary` zamiast `ErrorHandler`. `@sentry/node` inicjalizuje się przed importem aplikacji i kontynuuje trace z nagłówków `sentry-trace` i `baggage` jak starter Spring Boot. Source maps z debug ID i kolejność build → inject → upload → deploy są takie same; w projektach z Vite albo webpack wstrzyknięcie i upload zwykle wykonuje plugin bundlera. Przykład nie zawiera implementacji dla tych platform.
