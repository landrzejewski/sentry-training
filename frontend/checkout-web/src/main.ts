import { bootstrapApplication } from '@angular/platform-browser';
import { App } from './app/app';
import { appConfig } from './app/app.config';
import { runtimeConfig } from './app/sentry/runtime-config';
import { initSentry } from './app/sentry/sentry-setup';

// Sentry.init przed bootstrapApplication: SDK łapie także błędy startu aplikacji, a instrumentacja
// fetch i nagrywanie Replay działają już podczas startu Angulara.
initSentry(runtimeConfig());

bootstrapApplication(App, appConfig).catch((err) => console.error(err));
