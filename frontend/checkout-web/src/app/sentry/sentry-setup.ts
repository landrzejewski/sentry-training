import * as Sentry from '@sentry/angular';
import { makeConsoleTransport, trainingConsole } from './console-transport';
import { RuntimeConfig } from './runtime-config';

/**
 * Release wpisany przez build (opcja define w angular.json, nadpisywana przez scripts/build.mjs).
 * Ta sama konwencja komponent@wersja+build co w backendzie (checkout-api@1.0.0+local).
 */
declare const CHECKOUT_RELEASE: string;

/**
 * DSN zastępczy trybu offline, ten sam co w Javie (TrainingSentry). SDK bez DSN wyłącza się
 * całkowicie, a domena .invalid nigdy nie zostanie rozwiązana. Transport i tak nic nie wysyła.
 */
const OFFLINE_DSN = 'https://public@training.invalid/1';

/**
 * Inicjalizacja SDK, wywoływana w main.ts przed bootstrapApplication, żeby SDK widziało także
 * błędy i requesty ze startu Angulara.
 */
export function initSentry(config: RuntimeConfig): void {
  const online = config.sentryDsn !== '';

  Sentry.init({
    dsn: online ? config.sentryDsn : OFFLINE_DSN,
    environment: config.environment,
    release: CHECKOUT_RELEASE,

    // Podgląd każdego envelope w konsoli przeglądarki. Online przekazuje go dalej do transportu
    // fetch, offline na tym kończy.
    // PRODUKCJA: nie podmienia się transportu i nie wypisuje eventów, bo mogą zawierać dane osobowe.
    transport: makeConsoleTransport(online ? Sentry.makeFetchTransport : undefined),

    // SDK 11 usunęło sendDefaultPii. Zastępuje je dataCollection, którego wartości domyślne są
    // szersze niż dawne sendDefaultPii: false (m.in. userInfo, czyli adres IP w user).
    // PUŁAPKA: brak tej sekcji po aktualizacji z SDK 10 oznacza więcej danych osobowych w Sentry.
    // Świadoma decyzja dla checkoutu: bez automatycznych danych użytkownika, cookies i body,
    // a identyfikator klienta ustawia jawnie kod (setUser w checkout-page.ts).
    dataCollection: {
      userInfo: false,
      cookies: false,
      httpBodies: [],
    },

    integrations: [
      // Pageload, nawigacje routera (nazwy z TraceService) oraz spany fetch/XHR.
      Sentry.browserTracingIntegration(),
      // Wartości domyślne, zapisane jawnie: cały tekst i wszystkie pola formularzy są
      // maskowane, a media (obrazy, SVG, wideo, audio) blokowane jeszcze w przeglądarce, przed wysłaniem.
      Sentry.replayIntegration({
        maskAllText: true,
        maskAllInputs: true,
        blockAllMedia: true,
      }),
    ],

    // 1.0 tylko na potrzeby szkolenia: każdy trace trafia do Sentry.
    // PRODUKCJA: stawka albo tracesSampler dobrane do ruchu i budżetu.
    tracesSampleRate: 1.0,

    // Nagłówki sentry-trace i baggage tylko dla własnego API. Wzorzec zakotwiczony na początku
    // adresu, bo zwykły string pasuje do dowolnego fragmentu URL.
    // PUŁAPKA: domyślnie browser SDK propaguje tylko do tego samego originu. API na innym porcie
    // albo domenie bez wpisu tutaj dostaje request bez nagłówków i zaczyna własny trace.
    tracePropagationTargets: [new RegExp('^' + escapeRegExp(config.apiUrl) + '/')],

    // Bez pełnych sesji, ale każda sesja z błędem wysyła bufor z ostatniej minuty i dalszy ciąg.
    replaysSessionSampleRate: 0,
    replaysOnErrorSampleRate: 1.0,

    // Ten sam tag co w backendzie: filtr training.module:frontend pokazuje obie strony.
    initialScope: { tags: { 'training.module': 'frontend' } },
  });

  trainingConsole.info(
    `Sentry [frontend] tryb=${online ? 'online (envelope trafiają do Sentry)' : 'offline (tylko konsola)'}` +
      ` | environment=${config.environment} | release=${CHECKOUT_RELEASE}`,
  );
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
