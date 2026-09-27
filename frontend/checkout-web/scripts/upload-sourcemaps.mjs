// Upload source maps do Sentry i usunięcie ich z publicznego outputu.
//
//   SENTRY_AUTH_TOKEN=$(../../docker/sentry/sentry.sh ci-token) npm run sourcemaps:upload
//
// Token organizacji (org:ci) zawiera adres Sentry i slug organizacji, więc wystarczy projekt.
// Dla tokenu osobistego trzeba dodatkowo ustawić SENTRY_URL i SENTRY_ORG.
import { spawnSync } from 'node:child_process';
import { readdirSync, readFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { builtScripts, hasRuntimeSnippet } from './inject-debug-ids.mjs';

const dir = 'dist/checkout-web/browser';
const project = process.env.SENTRY_PROJECT ?? 'sentry-training';

if (!process.env.SENTRY_AUTH_TOKEN) {
  console.error('Brak SENTRY_AUTH_TOKEN. Token CI: ./docker/sentry/sentry.sh ci-token (z katalogu głównego repozytorium).');
  process.exit(1);
}

// PUŁAPKA: upload przed inject wysyła mapy bez debug ID albo z innym debug ID niż bundle,
// który trafi do użytkownika. Dlatego krok sprawdza, że każdy plik JS ma już fragment
// rejestrujący debug ID w runtime (dodaje go sentry-cli sourcemaps inject).
const scripts = builtScripts(dir);
const notInjected = scripts.filter((file) => !hasRuntimeSnippet(readFileSync(join(dir, file), 'utf8')));
if (notInjected.length > 0) {
  console.error(`Pliki bez wstrzykniętego debug ID: ${notInjected.join(', ')}. Najpierw: npm run sourcemaps:inject`);
  process.exit(1);
}

// Release tylko jako słabe powiązanie (nawigacja w UI). Dopasowanie mapy do eventu idzie
// przez debug ID. Wartość z build-info.json, czyli dokładnie ta, z którą zbudowano pliki.
const { release } = JSON.parse(readFileSync('dist/checkout-web/build-info.json', 'utf8'));
const result = spawnSync('npx', ['@sentry/cli', 'sourcemaps', 'upload', '--project', project, '--release', release, '--ignore', 'runtime-config.js', dir], {
  stdio: 'inherit',
  shell: process.platform === 'win32',
});
if (result.status !== 0) {
  // PRODUKCJA: nieudany upload zatrzymuje pipeline przed deployem, zamiast przejść dalej po cichu.
  process.exit(result.status ?? 1);
}

// Mapy są już w Sentry, a przeglądarka ich nie potrzebuje (hidden source maps, bez komentarza
// sourceMappingURL). Usunięcie dopiero po udanym uploadzie.
const maps = readdirSync(dir).filter((file) => file.endsWith('.map'));
for (const map of maps) {
  rmSync(join(dir, map));
}
console.log(`\nUsunięte z outputu pliki .map: ${maps.length}. Artefakt gotowy do wdrożenia (npm run serve:dist).`);
