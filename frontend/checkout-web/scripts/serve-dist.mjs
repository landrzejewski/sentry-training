// Minimalny serwer statyczny dla zbudowanej aplikacji: http://localhost:4300.
// Odpowiednik deployu: najpierw zapisuje runtime-config.js środowiska, potem serwuje
// dokładnie te pliki, które powstały w buildzie (i przeszły inject oraz upload).
//
//   npm run serve:dist
//   SENTRY_DSN=$(../../docker/sentry/sentry.sh dsn) npm run serve:dist
import { createReadStream, existsSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { extname, join, normalize } from 'node:path';
import { writeRuntimeConfig } from './runtime-config.mjs';

const root = 'dist/checkout-web/browser';
const port = Number(process.env.PORT ?? 4300);
const types = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.ico': 'image/x-icon',
};

if (!existsSync(root)) {
  console.error('Brak dist/checkout-web/browser. Uruchom najpierw: npm run build');
  process.exit(1);
}
writeRuntimeConfig(root);

createServer((request, response) => {
  const path = normalize(decodeURIComponent(new URL(request.url, 'http://x').pathname)).replace(/^(\.\.[/\\])+/, '');
  let file = join(root, path);
  if (!existsSync(file) || statSync(file).isDirectory()) {
    if (extname(path) !== '') {
      response.writeHead(404).end();
      return;
    }
    // Trasy aplikacji (/koszyk, /zamowienie/ORD-1) obsługuje router Angulara.
    file = join(root, 'index.html');
  }
  // PUŁAPKA: serwer oddaje każdy plik z katalogu, także *.js.map, jeśli nikt ich nie usunął.
  // Wtedy kod źródłowy (sourcesContent) jest publiczny. upload-sourcemaps.mjs usuwa mapy
  // z outputu po udanym uploadzie; sprawdzenie: curl -I http://localhost:4300/main-XXXX.js.map
  response.writeHead(200, { 'Content-Type': types[extname(file)] ?? 'application/octet-stream' });
  createReadStream(file).pipe(response);
}).listen(port, () => console.log(`checkout-web (dist): http://localhost:${port}`));
