// Zapisuje runtime-config.js (DSN, environment, adres API) do wskazanego katalogu.
// To odpowiednik kroku deployu: ten sam zbudowany artefakt dostaje konfigurację środowiska
// bez przebudowy i bez zmiany plików JS objętych source maps.
//
//   node scripts/runtime-config.mjs public                   # dla ng serve
//   node scripts/runtime-config.mjs dist/checkout-web/browser  # dla zbudowanej aplikacji
//
// Zmienne: SENTRY_DSN (brak = tryb offline), SENTRY_ENVIRONMENT (domyślnie training),
// API_URL (domyślnie http://localhost:8095).
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

export function writeRuntimeConfig(directory) {
  const config = {
    sentryDsn: process.env.SENTRY_DSN ?? '',
    environment: process.env.SENTRY_ENVIRONMENT ?? 'training',
    apiUrl: process.env.API_URL ?? 'http://localhost:8095',
  };
  mkdirSync(directory, { recursive: true });
  // DSN nie jest sekretem (i tak trafia do przeglądarki), ale nie należy do repozytorium:
  // plik jest generowany i ignorowany przez git.
  writeFileSync(
    join(directory, 'runtime-config.js'),
    `// Wygenerowane przez scripts/runtime-config.mjs\nwindow.CHECKOUT_CONFIG = ${JSON.stringify(config, null, 2)};\n`,
  );
  console.log(`runtime-config.js -> ${directory}: ${config.sentryDsn ? 'online' : 'offline'}, environment=${config.environment}, api=${config.apiUrl}`);
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  writeRuntimeConfig(process.argv[2] ?? 'public');
}
