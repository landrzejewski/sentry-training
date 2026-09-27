import { bootstrapApplication } from '@angular/platform-browser';
import * as Sentry from '@sentry/angular';
import { appConfig } from './app/app.config';
import { App } from './app/app';

// Krok A2: wklej tu wynik ./docker/sentry/sentry.sh dsn. Z pustym DSN SDK nic nie wysyła.
// DSN przeglądarki nie jest sekretem (i tak trafia do bundle), ale token do uploadu source maps
// jest: ten nigdy nie trafia do kodu aplikacji.
const SENTRY_DSN = '';

Sentry.init({
  dsn: SENTRY_DSN,
  // Bez environment SDK wysyła "production", także z laptopa.
  environment: 'local',
  // Ta sama wartość musi trafić do kroku uploadu source maps (--release w package.json).
  release: 'shop-web@1.0.0',
  // SDK 11: sendDefaultPii nie istnieje, domyślnie zbierane są m.in. dane użytkownika (adres IP).
  // Świadoma decyzja: bez automatycznych danych użytkownika.
  dataCollection: { userInfo: false },

  // Krok A4: tracing przeglądarki i propagacja do własnego API na innym porcie.
  integrations: [Sentry.browserTracingIntegration()],
  tracesSampleRate: 1.0,
  tracePropagationTargets: [/^http:\/\/localhost:8090\/api\//],
});

bootstrapApplication(App, appConfig).catch((err) => console.error(err));
