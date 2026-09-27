# Moduł 3: triage issues i analiza błędów

Domena: usługa checkout-api składa zamówienie i autoryzuje płatność w zewnętrznej bramce. Bramka działa lokalnie na `com.sun.net.httpserver.HttpServer`, a klient używa `java.net.http.HttpClient`, więc timeouty, kody HTTP i ramki JDK w stack trace są prawdziwe. Scenariusze nie uczą klikania w Sentry, tylko pokazują, jak kod aplikacji decyduje o danych, na których pracuje triage: łańcuch wyjątków, in-app frames, fingerprint, liczniki wpływu i breadcrumbs.

Najważniejsza teoria modułu jest w komentarzu na początku [`Module03Demo`](Module03Demo.java).

## Klasy

- Domena bez Sentry: [`Order`](Order.java), [`CheckoutService`](CheckoutService.java) (opakowanie błędu z `cause` albo bez), [`CheckoutException`](CheckoutException.java), [`PaymentGatewayClient`](PaymentGatewayClient.java) (jedna próba, klasyfikacja przyczyny), [`PaymentGatewayException`](PaymentGatewayException.java) (przyczyna z zamkniętego enuma), [`FakePaymentGateway`](FakePaymentGateway.java) (bramka na HTTP), [`LegacyLoyaltyClient`](../module03legacy/LegacyLoyaltyClient.java) (biblioteka innego zespołu w pakiecie o podobnej nazwie);
- [`CheckoutEndpoint`](CheckoutEndpoint.java): isolation scope requestu, tag `business.operation`, user, context, breadcrumb i jedno `captureException`;
- [`PaymentRetry`](PaymentRetry.java): ponowienia raportowane jako eventy (pułapka) albo jako breadcrumbs i context (wersja docelowa);
- [`PaymentGrouping`](PaymentGrouping.java): `beforeSend` z tagiem `payment.failure_reason` i czterema strategiami fingerprintu;
- [`HttpCallBreadcrumbs`](HttpCallBreadcrumbs.java) i [`BreadcrumbHygiene`](BreadcrumbHygiene.java): breadcrumbs HTTP jak z integracji i `beforeBreadcrumb`, który je czyści i odsiewa;
- [`CheckoutSentryConfig`](CheckoutSentryConfig.java): konfiguracja docelowa (prefiks in-app z kropką, fingerprint z przyczyną, higiena breadcrumbs) i jej warianty;
- [`EventPreview`](EventPreview.java): narzędzie szkoleniowe, wypisuje ramki in-app i liczbę breadcrumbs, których nie pokazuje wydruk transportu;
- [`Module03Demo`](Module03Demo.java): scenariusze uruchamiane po kolei; test `Module03ScenariosTest` sprawdza te same scenariusze na transporcie w pamięci;
- [`OwnershipRules`](OwnershipRules.java): tekst Ownership Rules projektu i podgląd dopasowania na evencie (linia `· ownership`);
- [`TeamSetup`](TeamSetup.java): zespoły, członkostwo, dostęp do projektu, reguły i auto-assignment przez REST API;
- [`TriageWalkthrough`](TriageWalkthrough.java): suggested owners, przypisanie, komentarz triage i Mark reviewed na issue z demo; test `TeamCollaborationTest` sprawdza reguły na eventach i odpowiedzi API zapisane z lokalnego Sentry.

## Scenariusze

Pełne wyjaśnienie każdego scenariusza (o co chodzi, co pokazujemy, problem, dobra praktyka, na co patrzeć) jest w komentarzu nad jego metodą w [`Module03Demo`](Module03Demo.java).

1. **Wrapper bez cause ukrywa przyczynę.** Timeout i HTTP 503 opakowane w `CheckoutException` z samym komunikatem przyczyny dają eventy z jednym wyjątkiem, tymi samymi ramkami i bez klasyfikacji, więc lądują w jednym issue. Z `cause` event ma pełny łańcuch (przy timeoucie aż do `HttpTimeoutException` z JDK), tag z przyczyną i osobne issue dla każdej przyczyny.
2. **In-app frames.** Ten sam błąd biblioteki lojalnościowej bez `addInAppInclude`, z prefiksem `pl.training.sentry.module03` i z prefiksem zakończonym kropką. Bez prefiksu żadna ramka nie ma `in_app=true`. Prefiks bez kropki obejmuje też `module03legacy`, więc pierwsza ramka in-app wskazuje kod biblioteki. Dopiero prefiks z kropką wskazuje miejsce, w którym aplikacja woła bibliotekę.
3. **Fingerprint.** Cztery awarie (dwa razy 503, 429, timeout) przy czterech strategiach: domyślne grupowanie łączy 503 z 429, surowa ścieżka z numerem zamówienia daje issue na każde zamówienie, jeden stały fingerprint łączy wszystko, a `{{ default }}` z przyczyną rozdziela dokładnie przyczyny.
4. **Retry mnoży eventy.** Dwa zamówienia bez odpowiedzi bramki i jedno odzyskane w drugiej próbie. Raportowanie każdej próby daje 7 eventów i 3 users (w tym klientkę, której zamówienie przeszło), a deduplikacja SDK odrzuca event końcowy, bo jego `cause` był już wysłany. Wersja docelowa: 2 eventy, 2 users, próby w breadcrumbs, historia ponowień w contexcie `payment_retry`.
5. **Limit bufora breadcrumbs.** 120 odpytań o status płatności BLIK wypiera z bufora (100 wpisów) start zamówienia i autoryzację, a każdy wpis niesie token w `http.query`. `beforeBreadcrumb` odrzuca udane odpytania i usuwa query string: w evencie zostają 2 wpisy, start zamówienia i autoryzacja.
6. **Ownership Rules.** Timeout bramki pasuje do reguły ścieżki checkout i reguły tagu `payment.failure_reason`; rozstrzyga ostatnia, a z jej dwóch ownerów auto-assignment bierze pierwszego (`#payments`). Wyjątek z biblioteki `module03legacy` trafia do opiekunki biblioteki. Wynik widać w linii `· ownership` nad wydrukiem eventu.

## Uruchomienie

```bash
# offline: nic nie opuszcza procesu, konsola pokazuje, co SDK by wysłało
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module03.Module03Demo

# online, lokalne Sentry z docker/sentry (instrukcja w docker/sentry/README.md)
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module03.Module03Demo

# jeden scenariusz online (numer w -Dexec.args, np. 1 albo "1 3"); po clean w Sentry są tylko jego dane
./docker/sentry/sentry.sh clean --yes
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module03.Module03Demo -Dexec.args=1

# online, dowolny projekt Sentry (np. sentry.io)
SENTRY_DSN=https://...@....ingest.sentry.io/... ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module03.Module03Demo

# pełny JSON każdego eventu pod jego skrótem (m.in. pole in_app każdej ramki)
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module03.Module03Demo -Dsentry.demo.json=true

# testy scenariuszy
./mvnw -q test -Dtest=Module03ScenariosTest

# współpraca zespołowa (opis poniżej), testy bez sieci
./mvnw -q test -Dtest=TeamCollaborationTest
```

## Współpraca zespołowa (tryb online)

Kolejność kroków na lokalnym Sentry z `docker/sentry` (org `sentry`, projekt `sentry-training`):

```bash
# 1. konto drugiej osoby (opiekunka biblioteki lojalnościowej); --password zamiast --no-password,
#    jeśli ktoś ma się na nie zalogować i zobaczyć swoją kolejkę
cd docker/sentry/self-hosted
docker compose exec web sentry createuser --email anna.checkout@sentry-training.local --no-password --no-superuser --org-id 1 --no-input
cd -
# 2. zespoły, członkostwo, dostęp do projektu, Ownership Rules i auto-assignment
#    token: org:read, member:read, team:write, project:write
SENTRY_AUTH_TOKEN=... ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module03.TeamSetup -Dexec.args=setup
# 3. eventy (scenariusz 6 daje issue timeoutu i issue biblioteki)
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module03.Module03Demo
# 4. triage jednego issue; token: event:read, event:write, project:read, member:read
SENTRY_AUTH_TOKEN=... ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module03.TriageWalkthrough
# sprzątanie: puste reguły i usunięcie zespołów; token: project:write, team:admin
SENTRY_AUTH_TOKEN=... ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module03.TeamSetup -Dexec.args=cleanup
```

Wynik sprawdzony na self-hosted 26.9.0:

- issue timeoutu z scenariusza 6 dostało assignee `#payments`, a wpis Activity `assigned` podaje źródło `projectOwnership` i regułę `tags.payment.failure_reason:* #payments #checkout`; issue biblioteki dostało opiekunkę, a issue wariantu bez `cause` (scenariusz 1A, bez tagu przyczyny) `#checkout`;
- issues sprzed zapisania reguł dostały assignee przy pierwszym nowym evencie: zmiana reguł unieważnia pamięć wcześniejszej oceny, a issue bez assignee jest oceniane ponownie;
- API sugerowanych ownerów (`GET /projects/{org}/{project}/events/{id}/owners/`) zwraca wszystkie pasujące reguły i ownerów w kolejności z pliku (`#checkout`, `#payments`), a auto-assignment bierze pierwszego ownera ostatniej reguły. Ta sama lista to kandydaci, nie przejęcie odpowiedzialności;
- ręczne przypisanie (`PUT .../issues/{id}/` z `assignedTo`) zostaje przy kolejnych eventach, komentarz (`POST .../issues/{id}/notes/`) i Mark reviewed (`inbox=false`, status `ongoing`) są osobnymi wpisami Activity, a przypisanie nie zmienia statusu issue.

Cleanup czyści reguły i usuwa zespoły. Sprawdzone lokalnie: przypisania do usuniętych zespołów znikają razem z nimi (issue wraca do stanu bez assignee), a przypisania do osób, np. opiekunki biblioteki, i wpisy Activity zostają. Sentry dopisuje autora zespołu do jego członków, więc po `setup` właściciel tokenu jest w obu zespołach, nie tylko w `#payments`. Konto utworzone przez `createuser` usuwa się w Settings > Members (Remove from organization) albo zostawia na kolejne uruchomienia.

**GitHub i Jira (tylko opis, wymagają kont zewnętrznych).** Integracja GitHub (Settings > Integrations) daje trzy rzeczy, których lokalna instancja nie pokaże: CODEOWNERS zsynchronizowane z repozytorium (Ownership Rules oceniane po CODEOWNERS, zespoły GitHub mapowane na zespoły Sentry w Code Owners, bez mapowania wpis jest pomijany), suspect commits z git blame (wymaga code mappings: Stack Trace Root `pl/training/sentry/` i Source Code Root `src/main/java/pl/training/sentry/`) oraz tryb „Auto-assign to suspect commits”. Integracja Jira dodaje w issue powiązanie ze zgłoszeniem (nowym albo istniejącym, filtr `is:linked`), akcję Alertu tworzącą zgłoszenie oraz, zależnie od konfiguracji, synchronizację statusu, komentarzy i przypisania. Obie wymagają konta i aplikacji po stronie dostawcy, dlatego moduł ich nie symuluje.

## Na co patrzeć

- W konsoli: pod każdym krokiem wydruk eventu (`↳ event`), pod nim odpowiedź endpointu (`odpowiedź ORDER_NOT_PLACED` albo decyzja bramki). Brak wydruku nad odpowiedzią oznacza, że SDK niczego nie wysłało. Linia `fingerprint` pojawia się tylko wtedy, gdy kod ustawił fingerprint.
- Linie `· in-app` i `· breadcrumbs` wypisuje `EventPreview` przed wydrukiem eventu: pierwsza ramka `in_app=true` każdego wyjątku w łańcuchu i liczba breadcrumbs w evencie. Wydruk transportu pokazuje tylko 10 ostatnich breadcrumbs. Linię `· ownership` w scenariuszu 6 wypisuje w ten sam sposób `OwnershipRules`.
- Linia `Sentry [module03]` pojawia się wiele razy: każdy wariant startuje SDK z własną konfiguracją, opisaną krokiem nad tą linią.
- `level=error (domyślny...)` i `handled=tak (mechanism=chained...)`: ręczny `captureException` nie ustawia poziomu ani pola `handled`; tak wygląda każdy błąd obsłużony w endpoincie. Breadcrumbs `[http]` nie mają message, tylko dane. Port bramki w adresach jest losowy. Pauzy przy wariantach z timeoutem to czekanie klienta (250 ms na próbę).
- W Sentry UI (tryb online): filtr `training.module:module03`. Sprawdzone na self-hosted Sentry szkolenia (konfiguracja grupowania `newstyle:2026-01-20`): scenariusz 1 daje jedno issue dla wariantu bez `cause` i dwa dla wariantu z `cause`; scenariusz 3 kolejno 2, 4, 1 i 3 issues; scenariusz 4 issue z 7 events i 3 users oraz issue z 2 events i 2 users. Liczniki rosną z każdym uruchomieniem, bo eventy z tym samym wynikiem grupowania trafiają do tych samych issues.
- Wyniki z serwera, które mogą zaskoczyć. W scenariuszu 2 wszystkie trzy eventy trafiają do jednego issue: Event Grouping Information pokazuje, że wariant z ramkami in-app różni się między konfiguracjami (bez prefiksu nie bierze udziału, a serwer oznacza wtedy wszystkie ramki jako systemowe), ale wariant ze wszystkimi ramkami ma ten sam hash. W scenariuszu 5 wartość `http.query` w UI to `[Filtered]`: token opuścił aplikację, a zamaskowało go dopiero domyślne czyszczenie danych po stronie serwera. Tytuł issue pokazuje komunikat jednego eventu, więc w scenariuszu 1 (wariant bez `cause`) sugeruje jedną przyczynę, choć issue zawiera obie.
- Ten sam błąd z różnych scenariuszy trafia do osobnych issues, bo metody demo należą do stack trace (i do ramek in-app). Jeden defekt wywołany z kilku ścieżek wykonania może więc utworzyć kilka issues.
