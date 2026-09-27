# Lokalne Sentry (self-hosted) dla przykładów

Przykłady działają w dwóch trybach. Bez `SENTRY_DSN` SDK wypisuje na konsolę to, co wysłałoby do Sentry (tryb offline). Z `SENTRY_DSN` eventy trafiają do prawdziwego Sentry i widać je w UI. Ten katalog stawia pełne Sentry lokalnie na Docker Compose, więc tryb online nie wymaga konta w sentry.io.

Skrypt `sentry.sh` opakowuje oficjalny projekt [getsentry/self-hosted](https://github.com/getsentry/self-hosted) w przypiętej wersji `26.9.0`. To ten sam produkt co sentry.io, uruchomiony jako kilkadziesiąt kontenerów (web, relay, kafka, clickhouse, snuba, postgres, redis i inne).

## Wymagania

- Docker z Compose 2.32.2 lub nowszym. Na macOS i Windows Docker Desktop z przydzielonymi co najmniej 4 CPU i 16 GB RAM (zalecane 32 GB).
- Około 20 GB wolnego miejsca na obrazy i dane.
- Wolny port `9000`.

Bash na hoście nie musi być nowy: `install.sh` z self-hosted wymaga bash 4.4 lub nowszego (macOS ma 3.2), więc `sentry.sh` uruchamia go w małym kontenerze z `installer/Dockerfile`, który korzysta z Dockera hosta.

## Pierwsze uruchomienie

```bash
./docker/sentry/sentry.sh install     # pobranie i instalacja, kilkanaście minut przy pierwszym razie
./docker/sentry/sentry.sh up          # start wszystkich kontenerów
./docker/sentry/sentry.sh bootstrap   # konto admina, projekt sentry-training, DSN w training.env
```

Panel: <http://localhost:9000>, login `admin@sentry-training.local`, hasło `sentry-training`. Konto można zmienić zmiennymi `SENTRY_ADMIN_EMAIL` i `SENTRY_ADMIN_PASSWORD` przed `bootstrap`.

## Uruchomienie przykładu w trybie online

```bash
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) \
  ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module01.Module01Demo
```

W Sentry: projekt `sentry-training`, filtr `training.module:module01`.

Jeden przykład na raz: `clean` usuwa dane poprzednich uruchomień, a numer w `-Dexec.args` wybiera scenariusz. W Sentry zostają wtedy tylko dane tego scenariusza.

```bash
./docker/sentry/sentry.sh clean --yes
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) \
  ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module01.Module01Demo -Dexec.args=1
```

## Codzienna praca

| Polecenie | Działanie |
|---|---|
| `./docker/sentry/sentry.sh up` | start po restarcie komputera |
| `./docker/sentry/sentry.sh status` | stan kontenerów |
| `./docker/sentry/sentry.sh dsn` | DSN projektu szkoleniowego |
| `./docker/sentry/sentry.sh token` | nowy token API z zakresami odczytu (`org:read`, `project:read`, `event:read`), np. `export SENTRY_AUTH_TOKEN=$(./docker/sentry/sentry.sh token)` dla modułów 7, 8 i 11. Uwaga: `org:read` pozwala w Sentry także tworzyć, zmieniać i usuwać dashboardy organizacji, więc token nie jest ściśle tylko do odczytu. |
| `./docker/sentry/sentry.sh ci-token` | nowy token organizacji z zakresem `org:ci` (release, upload source maps, bez odczytu issues i eventów) dla `frontend/checkout-web` i `labs`; nazwę zmienia `CI_TOKEN_NAME` |
| `./docker/sentry/sentry.sh clean` | czyszczenie danych między przykładami: issues, eventy, trace, logi, metryki, sesje, replaye i Stats. Zostaje konfiguracja: konto, projekt i DSN, tokeny, zespoły, Ownership Rules, Alerty, Monitory, dashboardy i release. Pyta o potwierdzenie, `clean --yes` pomija pytanie. Czyści całą instancję, nie jeden projekt. |
| `./docker/sentry/sentry.sh down` | zatrzymanie, dane zostają w wolumenach |
| `./docker/sentry/sentry.sh destroy` | usunięcie kontenerów i wszystkich danych (pyta o potwierdzenie) |

## Ograniczenia instancji self-hosted

- Seer (Autofix, Issue Summary) i inne funkcje AI działają tylko w sentry.io. Moduły 7 i 11 pokazują je koncepcyjnie albo wymagają projektu w sentry.io.
- Instancja nie ma skonfigurowanej poczty, więc powiadomienia e-mail z Alertów nie wychodzą. Do ćwiczeń z Alertami służy podgląd akcji w UI albo integracja webhook.
- To środowisko szkoleniowe: hasło admina jest jawne, instancja nie ma TLS i nie nadaje się do wystawienia poza własny komputer.
