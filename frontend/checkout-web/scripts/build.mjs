// Build produkcyjny z release wpisanym do bundle i zapisanym obok artefaktu.
//
// build-info.json pozwala krokowi uploadu użyć dokładnie tego release, z którym zbudowano pliki,
// zamiast wyliczać go ponownie (i może inaczej) w innym jobie.
import { spawnSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { release } from './release.mjs';

const name = release();
const result = spawnSync('npx', ['ng', 'build', '--define', `CHECKOUT_RELEASE='${name}'`], {
  stdio: 'inherit',
  shell: process.platform === 'win32',
});
if (result.status !== 0) {
  process.exit(result.status ?? 1);
}
writeFileSync('dist/checkout-web/build-info.json', JSON.stringify({ release: name }, null, 2) + '\n');
console.log(`\nRelease ${name} zapisany w dist/checkout-web/build-info.json`);
console.log('Source maps (hidden) są w dist/checkout-web/browser/*.js.map. Następny krok: npm run sourcemaps:inject');
