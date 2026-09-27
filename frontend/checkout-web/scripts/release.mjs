// Nazwa release frontendu: komponent@wersja+build, ta sama konwencja co checkout-api.
// PRODUKCJA: pipeline wylicza release raz (np. z numeru buildu albo SHA commita) i przekazuje
// go przez SENTRY_RELEASE do builda i do kroku uploadu, zamiast liczyć go w każdym kroku osobno.
import { readFileSync } from 'node:fs';

export function release() {
  if (process.env.SENTRY_RELEASE) {
    return process.env.SENTRY_RELEASE;
  }
  const { name, version } = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'));
  return `${name}@${version}+${process.env.BUILD_ID ?? 'local'}`;
}
