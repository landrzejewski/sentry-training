import {
  ApplicationConfig,
  ErrorHandler,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import * as Sentry from '@sentry/angular';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    // Nieobsłużone błędy i odrzucone obietnice z window trafiają do ErrorHandler.
    // Aplikacja bez zone.js nie ma innej drogi do ErrorHandler dla kodu asynchronicznego.
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withFetch()),

    // Błędy, które Angular przechwytuje sam (szablony, handlery zdarzeń, change detection),
    // nie docierają do window.onerror. Bez tego providera Angular tylko wypisuje je w konsoli
    // i Sentry ich nie zobaczy.
    { provide: ErrorHandler, useValue: Sentry.createErrorHandler() },

    // TraceService nazywa spany nawigacji szablonem trasy (/zamowienie/:orderId/), a nie adresem
    // z identyfikatorem, więc wszystkie potwierdzenia zamówień lądują w jednej grupie.
    { provide: Sentry.TraceService, deps: [Router] },
    provideAppInitializer(() => {
      inject(Sentry.TraceService);
    }),
  ],
};
