// Wstrzyknięcie debug ID do zbudowanych plików: npx @sentry/cli sourcemaps inject.
//
// Krok modyfikuje pliki JS i mapy, więc musi być po buildzie, a przed uploadem i deployem.
// sentry-cli dopisuje do każdego pliku JS fragment, który rejestruje debug ID w przeglądarce
// (_sentryDebugIds), komentarz //# debugId= oraz ten sam identyfikator w mapie. Na końcu skrypt
// sprawdza, że fragment jest w każdym pliku: bez niego eventy nie mają debug_meta, a stack trace
// zostaje zminifikowany, mimo że upload map się udał.
//
// PUŁAPKA (Angular 22.1+, nie dotyczy Angular 21 z tego przykładu): ng build sam dopisuje
// komentarz //# debugId=, ale bez fragmentu rejestrującego. sentry-cli uznaje wtedy plik za już
// obsłużony i go pomija (raport „already have debug ids”). Ten sam problem w nowym CLI `sentry`
// opisuje zgłoszenie getsentry/cli#1629. Po aktualizacji Angulara ta kontrola zatrzyma pipeline.
import { spawnSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const dir = 'dist/checkout-web/browser';
const RUNTIME_SNIPPET = /_sentryDebugIds\[\w+\]="[0-9a-f-]{36}"/;

export function hasRuntimeSnippet(source) {
  return RUNTIME_SNIPPET.test(source);
}

/** Pliki JS z buildu, czyli te z mapą obok. runtime-config.js należy do deployu, nie do buildu. */
export function builtScripts(directory) {
  return readdirSync(directory).filter((file) => file.endsWith('.js') && existsSync(join(directory, `${file}.map`)));
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const result = spawnSync('npx', ['@sentry/cli', 'sourcemaps', 'inject', '--ignore', 'runtime-config.js', dir], {
    stdio: 'inherit',
    shell: process.platform === 'win32',
  });
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }

  const scripts = builtScripts(dir);
  const missing = scripts.filter((file) => !hasRuntimeSnippet(readFileSync(join(dir, file), 'utf8')));
  if (missing.length > 0) {
    console.error(`Bez fragmentu rejestrującego debug ID: ${missing.join(', ')}`);
    process.exit(1);
  }
  console.log(`\nDebug ID zarejestrowane w runtime we wszystkich plikach JS (${scripts.length}). Następny krok: npm run sourcemaps:upload`);
}
