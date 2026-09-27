# checkout-api: backend przykładu frontendowego

Backend checkout-api dla przykładu frontendowego `frontend/checkout-web` (Angular).

Domena: przeglądarka składa zamówienie przez `POST /api/checkout`. Browser SDK rozpoczyna trace i dokleja do requestu nagłówki `sentry-trace` i `baggage`, a starter Spring Boot odczytuje je i tworzy transakcję backendu w tym samym trace. Pakiet pokazuje backendową połowę tego trace oraz dwa miejsca, w których ciągłość się urywa: adres spoza `tracePropagationTargets` i polityka CORS bez nagłówków trace. Scenariusze po stronie przeglądarki, polecenia frontendu i widok w Sentry UI opisuje `frontend/checkout-web/README.md`.

## Klasy

- [`CheckoutApiApplication`](CheckoutApiApplication.java): aplikacja na porcie 8095, starter inicjalizuje SDK, release `checkout-api@1.0.0+local`, tag `training.module=frontend`;
- [`CheckoutController`](CheckoutController.java): `POST /api/checkout` z dwoma child spanami; punkt odbioru `KRK-031` kończy się nieobsłużonym wyjątkiem;
- [`CheckoutCors`](CheckoutCors.java): CORS z nagłówkami `sentry-trace` i `baggage` dla `/api/**` oraz pułapka `/legacy-api/**` bez nich;
- test `CheckoutApiScenariosTest`: preflight obu ścieżek, kontynuacja trace przeglądarki i nowy trace bez nagłówków.

## Scenariusze

Numeracja jak w `frontend/checkout-web/README.md`.

3. **Zamówienie: jeden trace.** `POST /api/checkout` z nagłówkami trace. Efekt: transakcja `http.server` z child spanami `reserve stock` i `POST payments /authorize`, w trace przeglądarki.
4. **Błąd backendu w tym samym trace.** Punkt odbioru `KRK-031`. Efekt: odpowiedź 500 i event `IllegalStateException` z `handled=false` w tym samym trace co kliknięcie.
5. **Adres spoza `tracePropagationTargets`.** Ten sam backend pod `127.0.0.1`. Efekt: request bez nagłówków trace, transakcja backendu bez `parent` i we własnym trace.
6. **CORS bez nagłówków trace.** Ścieżka `/legacy-api`. Efekt: preflight odpowiada 200 bez `sentry-trace` i `baggage` na liście, przeglądarka nie wysyła `POST`, backend ma tylko transakcję `OPTIONS`.

## Uruchomienie

```bash
# offline: konsola pokazuje transakcje i eventy, nic nie trafia do Sentry
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.checkoutapi.CheckoutApiApplication

# online, lokalne Sentry z docker/sentry
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.checkoutapi.CheckoutApiApplication

# testy
./mvnw -q test -Dtest=CheckoutApiScenariosTest
```

## Na co patrzeć

- W konsoli backendu: transakcja `POST /api/checkout` ma `parent` równy span ID requestu `http.client` z konsoli przeglądarki i ten sam trace. Przy wywołaniu przez `127.0.0.1` (adres spoza `tracePropagationTargets`) transakcja nie ma `parent` i ma własny trace.
- `POST` z JSON i nagłówkami trace z innego originu wymaga preflightu, więc w konsoli widać też transakcje `OPTIONS /api/checkout` w osobnych trace (preflight nie niesie nagłówków trace). Przeglądarka może przez pewien czas używać zapamiętanego wyniku preflightu (Spring domyślnie wysyła `Access-Control-Max-Age: 1800`), więc nie każdy `POST` ma swoją transakcję `OPTIONS`. Przy ścieżce `/legacy-api` to jedyny ślad: `POST` nigdy nie dociera do backendu.
