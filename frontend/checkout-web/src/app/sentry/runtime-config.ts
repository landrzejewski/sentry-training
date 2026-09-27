/**
 * Konfiguracja zależna od środowiska wdrożenia: DSN, environment i adres API.
 *
 * Pochodzi z pliku runtime-config.js ładowanego przez index.html przed aplikacją, a nie z bundle.
 * Dzięki temu ten sam zbudowany artefakt (z tymi samymi debug ID i source maps) trafia na staging
 * i produkcję, a różni się tylko ten jeden plik. Release jest odwrotnie: opisuje artefakt, więc
 * wpisuje go build (CHECKOUT_RELEASE w sentry-setup.ts), a nie deploy.
 */
export interface RuntimeConfig {
  /** Pusty DSN oznacza tryb offline: SDK działa, ale envelope trafiają tylko do konsoli. */
  readonly sentryDsn: string;
  readonly environment: string;
  /** Adres checkout-api bez końcowego ukośnika, np. http://localhost:8095. */
  readonly apiUrl: string;
}

declare global {
  interface Window {
    CHECKOUT_CONFIG?: Partial<RuntimeConfig>;
  }
}

export function runtimeConfig(): RuntimeConfig {
  const loaded = window.CHECKOUT_CONFIG ?? {};
  return {
    sentryDsn: loaded.sentryDsn ?? '',
    // PUŁAPKA: bez jawnego environment SDK wysyła "production", więc eventy z laptopa
    // i ze stagingu wyglądałyby w Sentry jak produkcyjne.
    environment: loaded.environment ?? 'training',
    apiUrl: loaded.apiUrl ?? 'http://localhost:8095',
  };
}
