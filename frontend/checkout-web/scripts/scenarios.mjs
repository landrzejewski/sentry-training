// Scenariusze checkoutu w przeglądarce bez okna (Chrome przez puppeteer-core).
// Wypisuje to, co transport szkoleniowy pokazuje w konsoli przeglądarki, oraz nagłówki trace
// requestów do checkout-api. Służy do powtarzalnej weryfikacji; na szkoleniu te same kroki
// wykonuje się ręcznie w przeglądarce.
//
//   npm run scenarios                                   # aplikacja z serve:dist (port 4300)
//   npm run scenarios -- --url http://localhost:4200    # aplikacja z ng serve
//   npm run scenarios -- --only kupon,zamowienie        # wybrane scenariusze
//
// Zmienna CHROME_PATH wskazuje przeglądarkę (domyślnie Google Chrome w /Applications na macOS).
import puppeteer from 'puppeteer-core';

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const index = args.indexOf(`--${name}`);
  return index >= 0 ? args[index + 1] : fallback;
};
const baseUrl = option('url', 'http://localhost:4300');
const only = option('only', '')?.split(',').filter(Boolean) ?? [];
const chrome = process.env.CHROME_PATH ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

const scenarios = {
  // Scenariusz: 1. Błąd w komponencie: nieznany kod rabatowy.
  //
  // O co chodzi: błędy z szablonów, handlerów zdarzeń i change detection Angular przechwytuje sam
  // i przekazuje do ErrorHandler, więc nie docierają do window.onerror. Bez ErrorHandler
  // z Sentry.createErrorHandler() (app.config.ts) taki błąd ląduje tylko w konsoli przeglądarki.
  //
  // Co pokazujemy: skrypt wpisuje kod ZIMA50, którego nie ma w COUPONS, i klika „Zastosuj kod”.
  // CheckoutPage.applyCoupon czyta coupon.percent z undefined i rzuca TypeError w handlerze kliknięcia.
  //
  // Problem: typ Record<string, Coupon> obiecuje kupon dla każdego klucza, więc kompilator nie
  // wymusza sprawdzenia. Sam błąd bez ErrorHandler z SDK nie trafiłby do Sentry.
  //
  // Dobra praktyka: Sentry.createErrorHandler() jako ErrorHandler aplikacji. Event niesie kontekst
  // ustawiony w komponencie: user z samym pseudonimem c-7f3a9c oraz tagi checkout.variant
  // i delivery.mode. Po tym błędzie SDK wysyła też bufor Session Replay (replaysOnErrorSampleRate: 1.0).
  //
  // Na co patrzeć: w wydruku linia „Sentry ▸ event” z TypeError: Cannot read properties of undefined
  // (reading 'percent'), mechanism=auto.function.angular.error_handler, release, environment, trace,
  // user i tagi oraz replay_event z typ=buffer. W Sentry UI (filtr training.module:frontend) issue
  // z oznaczeniem Unhandled i sekcją Replay; po uploadzie map ramka checkout-page.ts z linią
  // this.discountPercent.set(coupon.percent);.
  //
  // Uruchomienie: npm run scenarios -- --only kupon
  kupon: {
    title: '1. Błąd w komponencie: nieznany kod rabatowy',
    run: async (page) => {
      await page.type('#coupon', 'ZIMA50');
      await page.click('#apply-coupon');
    },
  },
  // Scenariusz: 2. Nieobsłużona obietnica: zapis koszyka na później.
  //
  // O co chodzi: handler, który uruchamia obietnicę i od razu się kończy, nie daje Angularowi
  // błędu do przechwycenia. Odrzucenie bez catch trafia do window jako unhandledrejection, a w
  // aplikacji bez zone.js do ErrorHandler prowadzi je tylko provideBrowserGlobalErrorListeners.
  //
  // Co pokazujemy: skrypt klika „Zapisz na później”. CartDrafts.save po 50 ms rzuca Error
  // o braku miejsca w pamięci przeglądarki, a saveForLater woła tylko then, bez catch.
  //
  // Problem: błąd nie pojawia się w miejscu wywołania i status strony zostaje na „Zapisywanie
  // koszyka...”. Dwa nasłuchujące na unhandledrejection (GlobalHandlers SDK
  // i provideBrowserGlobalErrorListeners) mogłyby sugerować dwa eventy, a Sentry zgłasza jeden.
  //
  // Dobra praktyka: obietnica, która może się nie udać, dostaje catch z obsługą w kodzie. Odrzucenie,
  // które mimo to zostało bez obsługi, i tak dociera do Sentry dzięki nasłuchiwaniu na window.
  //
  // Na co patrzeć: w wydruku jeden „Sentry ▸ event” z Error „Nie udało się zapisać szkicu koszyka”
  // i mechanism=auto.browser.global_handlers.onunhandledrejection oraz „status na stronie:
  // Zapisywanie koszyka...”.
  //
  // Uruchomienie: npm run scenarios -- --only zapis
  zapis: {
    title: '2. Nieobsłużona obietnica: zapis koszyka na później',
    run: async (page) => page.click('#save-for-later'),
  },
  // Scenariusz: 3. Zamówienie: jeden trace przeglądarka -> checkout-api.
  //
  // O co chodzi: browser SDK rozpoczyna trace i dokleja nagłówki sentry-trace i baggage do requestów
  // pasujących do tracePropagationTargets, a starter Spring Boot kontynuuje z nich trace. Domyślnie
  // przeglądarka propaguje tylko same-origin, więc API na porcie 8095 wymaga wpisu w konfiguracji.
  //
  // Co pokazujemy: skrypt wybiera punkt WAW-114 i klika „Zamawiam i płacę”, a potem czeka na
  // stronę potwierdzenia (#order-id). placeOrder otwiera Sentry.startNewTrace i span „Złożenie
  // zamówienia” (ui.action.checkout); pod nim POST localhost (http.client), transakcja backendu
  // POST /api/checkout (http.server) i jej spany reserve stock oraz POST payments /authorize.
  //
  // Problem: bez startNewTrace request trafiłby do trace pageloadu, który w przeglądarce trwa do
  // następnej nawigacji, razem z każdym innym kliknięciem na stronie. Bez wpisu w
  // tracePropagationTargets backend zacząłby własny trace (scenariusz 5).
  //
  // Dobra praktyka: tracePropagationTargets jako wzorzec zakotwiczony na początku adresu własnego API,
  // backend dopuszcza sentry-trace i baggage w CORS, a jedno zamówienie ma własny korzeń trace.
  //
  // Na co patrzeć: w wydruku linia request POST http://localhost:8095/api/checkout z wartością
  // sentry-trace, response 201 i „Sentry ▸ span” z [segment] Złożenie zamówienia. W konsoli backendu
  // transakcja POST /api/checkout ma parent równy span= spanu http.client przeglądarki. W Sentry UI
  // trace view: korzeń Złożenie zamówienia, pod nim POST localhost, pod nim transakcja backendu
  // z dwoma spanami.
  //
  // Uruchomienie: npm run scenarios -- --only zamowienie
  zamowienie: {
    title: '3. Zamówienie: jeden trace przeglądarka -> checkout-api',
    run: async (page) => {
      await page.select('#pickup-point', 'WAW-114');
      await Promise.all([page.waitForSelector('#order-id', { timeout: 10_000 }), page.click('#place-order')]);
    },
  },
  // Scenariusz: 4. Błąd backendu w tym samym trace: punkt KRK-031.
  //
  // O co chodzi: przy ciągłym trace błąd backendu jest widoczny w trace kliknięcia, które go
  // wywołało. Każdy błąd powinien mieć jednego właściciela raportowania, żeby jeden problem nie
  // dawał dwóch eventów z dwóch stron.
  //
  // Co pokazujemy: skrypt wybiera punkt KRK-031, który jest w katalogu frontendu, ale nie w magazynie,
  // i klika „Zamawiam i płacę”. CheckoutController rzuca IllegalStateException, starter wysyła event
  // z handled=false w trace kliknięcia, a przeglądarka dostaje 500.
  //
  // Problem: kusi, żeby frontend zgłosił każdą nieudaną odpowiedź. Event z frontendu dla 5xx byłby
  // duplikatem błędu, który backend już zgłosił; request z kodem odpowiedzi jest i tak
  // w breadcrumbs i w Replay.
  //
  // Dobra praktyka: 5xx raportuje backend, a frontend pokazuje tylko komunikat. Status 0 (sieć, CORS)
  // zgłasza frontend, bo backend o nim nie wie (scenariusz 6).
  //
  // Na co patrzeć: w wydruku response 500 i „status na stronie: Nie udało się złożyć zamówienia.
  // Spróbuj ponownie później.”, bez eventu przeglądarki. W konsoli backendu event IllegalStateException
  // w tym samym trace. W Sentry UI błąd backendu wisi w trace view przy spanie reserve stock. Sam
  // błąd backendu nie uruchamia wysyłki Replay.
  //
  // Uruchomienie: npm run scenarios -- --only blad-backendu
  'blad-backendu': {
    title: '4. Błąd backendu w tym samym trace: punkt KRK-031',
    run: async (page) => {
      await page.select('#pickup-point', 'KRK-031');
      await page.click('#place-order');
    },
  },
  // Scenariusz: 5. Pułapka: adres poza tracePropagationTargets.
  //
  // O co chodzi: browser SDK dokleja sentry-trace i baggage tylko do adresów z tracePropagationTargets.
  // Wzorzec w sentry-setup.ts jest zakotwiczony na początku adresu API (domyślnie
  // http://localhost:8095/), więc inny zapis tego samego hosta już do niego nie pasuje.
  //
  // Co pokazujemy: skrypt klika przycisk zamówienia przez 127.0.0.1. CheckoutApi wysyła request pod
  // http://127.0.0.1:8095/api/checkout, czyli do tego samego backendu i kontrolera.
  //
  // Problem: request dochodzi do backendu, ale bez nagłówków trace. Backend zaczyna nowy
  // trace, więc w Sentry są dwa niezależne trace, przeglądarki i backendu, bez żadnego błędu.
  //
  // Dobra praktyka: tracePropagationTargets obejmuje każdy adres, pod którym frontend woła własne
  // API, i tylko zaufane usługi. Nagłówki sprawdza się w requestach, a nie tylko w konfiguracji.
  //
  // Na co patrzeć: w wydruku request POST http://127.0.0.1:8095/api/checkout sentry-trace=BRAK.
  // W konsoli backendu transakcja POST /api/checkout bez parent, we własnym trace.
  //
  // Uruchomienie: npm run scenarios -- --only poza-targets
  'poza-targets': {
    title: '5. Pułapka: adres poza tracePropagationTargets',
    run: async (page) => page.click('#order-foreign-host'),
  },
  // Scenariusz: 6. Pułapka: CORS bez nagłówków trace.
  //
  // O co chodzi: POST z JSON i nagłówkami trace do innego originu wymaga preflightu OPTIONS. Backend
  // musi dopuścić w CORS sentry-trace i baggage; /api/** to robi, /legacy-api/** (polityka sprzed
  // wdrożenia Sentry w przeglądarce) dopuszcza tylko content-type.
  //
  // Co pokazujemy: skrypt klika przycisk zamówienia przez /legacy-api. Adres pasuje do
  // tracePropagationTargets, więc przeglądarka dokleja nagłówki trace, a preflight ich nie dopuszcza.
  //
  // Problem: skutek jest gorszy niż rozerwany trace. Spring Framework 7 odpowiada na preflight 200
  // z niepełną listą nagłówków, więc logi backendu wyglądają poprawnie, a przeglądarka blokuje POST:
  // zamówienie w ogóle nie dochodzi. Frontend dostaje HttpErrorResponse ze statusem 0, który nie jest
  // obiektem Error i przekazany wprost daje w Sentry mylący tytuł issue.
  //
  // Dobra praktyka: CORS dla wywołań cross-origin dopuszcza sentry-trace i baggage dla własnego
  // frontendu. Status 0 zgłasza frontend, bo backend o nim nie wie, i to oryginalny błąd fetch
  // (error.error) z tagiem checkout.route, a nie sam HttpErrorResponse.
  //
  // Na co patrzeć: w wydruku „Sentry ▸ event” z TypeError: Failed to fetch (localhost:8095) i tagiem
  // checkout.route=legacy-cors oraz „status na stronie: Brak połączenia z serwerem. Spróbuj
  // ponownie.”. W konsoli backendu tylko transakcja OPTIONS w osobnym trace, bez POST.
  //
  // Uruchomienie: npm run scenarios -- --only legacy-cors
  'legacy-cors': {
    title: '6. Pułapka: CORS bez nagłówków trace',
    run: async (page) => page.click('#order-legacy-cors'),
  },
};

const browser = await puppeteer.launch({ executablePath: chrome, headless: true });
try {
  const page = await browser.newPage();
  page.on('console', (message) => {
    const text = message.text();
    if (text.includes('Sentry')) {
      console.log('  ' + text.replaceAll('%c', '').replace(/ color:#6c5fc7;font-weight:bold\s*$/, ''));
    }
  });
  page.on('request', (request) => {
    if (/\/(api|legacy-api)\/checkout$/.test(request.url()) && request.method() === 'POST') {
      const headers = request.headers();
      console.log(`  request ${request.method()} ${request.url()} sentry-trace=${headers['sentry-trace'] ?? 'BRAK'}`);
    }
  });
  page.on('requestfailed', (request) => {
    if (!request.url().includes('/api/') && !request.url().includes('/envelope/')) {
      return;
    }
    console.log(`  request NIEUDANY ${request.method()} ${request.url()}: ${request.failure()?.errorText}`);
  });
  page.on('response', (response) => {
    if (/\/(api|legacy-api)\/checkout$/.test(response.url()) && response.request().method() === 'POST') {
      console.log(`  response ${response.status()} ${response.url()}`);
    }
  });

  for (const [name, scenario] of Object.entries(scenarios)) {
    if (only.length > 0 && !only.includes(name)) {
      continue;
    }
    console.log(`\n${scenario.title}`);
    await page.goto(`${baseUrl}/koszyk`, { waitUntil: 'networkidle0' });
    await scenario.run(page);
    // Czas na odpowiedź API, zakończenie spanów i wysłanie envelope (także segmentu Replay).
    await new Promise((resolve) => setTimeout(resolve, Number(process.env.SCENARIO_WAIT_MS ?? 8_000)));
    const status = await page.$eval('#status', (element) => element.textContent).catch(() => '');
    if (status) {
      console.log(`  status na stronie: ${status}`);
    }
  }
  // Opuszczenie strony (visibilitychange: hidden) każe SDK wysłać zaległe dane.
  await page.goto('about:blank');
  await new Promise((resolve) => setTimeout(resolve, 2_000));
} finally {
  await browser.close();
}
