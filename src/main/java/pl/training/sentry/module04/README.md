# Moduł 4: Releases, source maps i CI/CD

Domena: usługa orders-api stosuje kody kuponów. [`CouponService`](CouponService.java) ma celowy błąd (kod wpisany małymi literami kończy się wyjątkiem), który według historii przykładu pojawił się w `orders-api@5.4.0+184` i przetrwał niepełną poprawkę w `orders-api@5.4.1+185`. Kod jest jeden, a wersję procesu wyznacza artefakt, z którego proces wystartował. Scenariusze pokazują, jak release, dist i bundle ID trafiają z buildu do eventu, co je psuje i jak pipeline może to wykryć przed wdrożeniem. Moduł dotyczy backendu Java: source maps JavaScript, Android i pliki natywne nie mają tu przykładów kodu.

Najważniejsza teoria modułu jest w komentarzu na początku [`Module04Demo`](Module04Demo.java).

## Klasy

- [`ReleaseName`](ReleaseName.java): reguły nazwy release z Sentry i konwencja `komponent@wersja+build`, bez zależności od SDK;
- [`BuildArtifact`](BuildArtifact.java): artefakt jako lista plików, z `build-info.properties`, `sentry-debug-meta.properties` i skrótem SHA-256;
- [`OrdersApiBuilds`](OrdersApiBuilds.java): dwa zamknięte buildy (184 i 185) i build bez source bundle;
- [`CouponService`](CouponService.java): kod domenowy z błędem, bez Sentry;
- [`CouponEndpoint`](CouponEndpoint.java): request i `captureException`;
- [`OrdersApiStartup`](OrdersApiStartup.java): konfiguracja SDK z artefaktu w wersji docelowej i z typowymi błędami (konfiguracja zewnętrzna, walidacja w callbacku, bundle ID w kodzie);
- [`OrdersApiSpringApp`](OrdersApiSpringApp.java): ta sama usługa na Spring Boot ze starterem Sentry i `git.properties` w artefakcie;
- [`ReleasePipeline`](ReleasePipeline.java): kroki pipeline i kontrola kontraktu (kolejność, jedna nazwa release, niezmienny artefakt, zgodny bundle);
- [`Module04Demo`](Module04Demo.java): scenariusze uruchamiane po kolei; test `Module04ScenariosTest` sprawdza te same scenariusze na transporcie w pamięci.

## Scenariusze

Pełne wyjaśnienie każdego scenariusza (o co chodzi, co pokazujemy, problem, dobra praktyka, na co patrzeć) jest w komentarzu nad jego metodą w [`Module04Demo`](Module04Demo.java).

1. **Nazwa release.** `ReleaseName` odrzuca nazwy, których Sentry nie przyjmie jako release (`latest`, `.`, ukośnik, tabulator, 201 znaków, same spacje), i przepuszcza poprawne nazwy bez prefiksu usługi (goły SHA, `5.4.1`), które w organizacji mogą kolidować z inną usługą. Wariant B: dwa buildy z różnych rewizji pod jedną nazwą dają eventy z tym samym release, a różnym dist i bundle ID, więc w Sentry ich historia się miesza.
2. **Release z artefaktu.** A: release z `build-info.properties`. B: `-Dsentry.release` z manifestu przy włączonej konfiguracji zewnętrznej wygrywa z kodem i event ma release 184. C: kontrola przed `Sentry.init` przerywa start, zanim powstanie event. D: wyjątek walidacji w callbacku `Sentry.init` znika, a event ma release szkoleniowy i nie ma `debug_meta`.
3. **Spring Boot i goły SHA.** Bez `sentry.release` starter ustawia release na `git.commit.id` z `git.properties`; z jawnym `sentry.release` event ma nazwę z CI `orders-api@SHA`. Ten sam commit daje dwie różne wartości release.
4. **Regresja po wydaniu.** Ten sam błąd z 184, ze starej repliki 184 w trakcie rolling update, z repliki z `latest` i z 185. Stack trace jest identyczny, zmienia się tylko release eventu, a od niego zależy, czy Sentry uzna event za regresję.
5. **Source context.** A: bundle ID z `sentry-debug-meta.properties` trafia do `debug_meta` eventu. B: bundle ID wpisany w kod sprawia, że SDK pomija plik z buildu. C: build bez source bundle wysyła event bez `debug_meta`.
6. **Kontrakt pipeline.** Poprawny przebieg bez naruszeń, ponowny build w jobie wdrożeniowym (inny skrót i UUID bundle), zła kolejność kroków i goły SHA z akcji CI. Scenariusz nie uruchamia SDK, wypisuje tylko naruszenia.

## Uruchomienie

```bash
# offline: nic nie opuszcza procesu, konsola pokazuje, co SDK by wysłało
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module04.Module04Demo

# wybrane scenariusze, np. tylko 4
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module04.Module04Demo -Dexec.args=4

# online, lokalne Sentry z docker/sentry (instrukcja w docker/sentry/README.md)
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module04.Module04Demo

# jeden scenariusz online (numer w -Dexec.args, np. 1 albo "1 3"); po clean w Sentry są tylko jego dane
./docker/sentry/sentry.sh clean --yes
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module04.Module04Demo -Dexec.args=1

# pełny JSON każdego eventu
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module04.Module04Demo -Dsentry.demo.json=true

# testy scenariuszy
./mvnw -q test -Dtest=Module04ScenariosTest
```

Release każdego procesu pochodzi z artefaktu, nie z `SENTRY_RELEASE`. Zmienna `SENTRY_RELEASE` ustawiona w powłoce z inną wartością zatrzyma start w wariantach docelowych: to kontrola ze scenariusza 2.

## Source bundle w lokalnym Sentry

Artefakt 185 wskazuje bundle `1740e7df-8b9e-4ebd-a6f6-19f2f02ad9ad`. Żeby Sentry pokazało fragmenty kodu przy ramkach, bundle o tym UUID musi trafić do Debug Files projektu, zanim przyjdą eventy (Sentry nie przetwarza starszych eventów ponownie). Token: Settings > Developer Settings > Organization Tokens (zakres `org:ci`). `sentry-cli` działa w kontenerze, więc nie trzeba go instalować; `--network host` jest potrzebne, bo Sentry zwraca adresy uploadu z `localhost:9000`.

```bash
export SENTRY_AUTH_TOKEN=sntrys_...
docker run --rm --network host -v "$PWD":/work -w /work \
  -e SENTRY_URL=http://localhost:9000 -e SENTRY_ORG=sentry -e SENTRY_PROJECT=sentry-training -e SENTRY_AUTH_TOKEN \
  getsentry/sentry-cli debug-files bundle-jvm --output target/sentry-source-bundle \
  --debug-id 1740e7df-8b9e-4ebd-a6f6-19f2f02ad9ad src/main/java
docker run --rm --network host -v "$PWD":/work -w /work \
  -e SENTRY_URL=http://localhost:9000 -e SENTRY_ORG=sentry -e SENTRY_PROJECT=sentry-training -e SENTRY_AUTH_TOKEN \
  getsentry/sentry-cli debug-files upload --type jvm target/sentry-source-bundle
```

PUŁAPKA: `bundle-jvm` dostaje korzeń źródeł (`src/main/java`). Ścieżki w bundle są liczone względem podanego katalogu, więc katalog pakietu dałby `CouponService.jvm` zamiast `pl/training/sentry/module04/CouponService.jvm` i Sentry nie dopasuje pliku do ramki.

PRODUKCJA: te same kroki wykonuje `sentry-maven-plugin` z celem `uploadSourceBundle` (generuje UUID, zbiera source roots, wysyła bundle i zapisuje `sentry-debug-meta.properties` w JAR). Wtedy SDK wczytuje plik samo przy `Sentry.init`, a job budujący potrzebuje tokenu. Pipeline wylicza release raz, zapisuje go w artefakcie (`build-info.properties`, np. przez filtrowanie zasobów), rejestruje tę samą nazwę (`sentry-cli releases new`, `set-commits`, `finalize`), wdraża zamknięty artefakt i dopiero po potwierdzonym wdrożeniu zapisuje deploy (`sentry-cli deploys new`).

## Na co patrzeć

- W konsoli: linia „Sentry [module04]” pokazuje release, z którym wystartował proces, a pod krokiem jest event (`↳ event`) z polem `release`. Demo dopisuje pod nim `dist` i `debug_meta`, których wspólny wydruk nie pokazuje. W scenariuszu 3 SDK startuje przez starter Spring Boot, więc zamiast linii „Sentry [module04]” jest linia „Spring Boot wystartował, SDK ma release=...”, a pod eventem nie ma `dist` i `debug_meta`. Scenariusz 6 nie uruchamia SDK: wypisuje naruszenia kontraktu pipeline.
- W Sentry UI (tryb online): filtr `training.module:module04`. Eventy różnych scenariuszy trafiają do osobnych issues, bo metody scenariuszy należą do stack trace (wariant 2B ma dodatkowe ramki lambdy i własne issue). Strona Releases pokazuje nazwy z eventów: `orders-api@5.4.1`, `orders-api@5.4.0+184`, `orders-api@5.4.1+185`, goły SHA, `orders-api@SHA` i release szkoleniowy z wariantu 2D. Event z `latest` nie ma release i ma błąd przetwarzania `invalid_data` dla pola release.
- Eksperyment z regresją: w issue scenariusza 4 wybierz Resolve > Another existing release > `orders-api@5.4.1+185` i uruchom ponownie `-Dexec.args=4`. Eventy z 184 i event bez release zostawiają issue rozwiązane (184 jest starszy od granicy, a event bez release Sentry traktuje jak starszy), event z 185 oznacza je jako Regressed, a Activity pokazuje regresję w `orders-api@5.4.1+185`. Opcja The next release wyznacza granicę sama; we wspólnym projekcie szkoleniowym może wskazać release innego modułu, dlatego eksperyment używa jawnie wybranego release.
- Source context (po wysłaniu bundle): w issue scenariusza 5 event A ma fragment kodu przy ramkach aplikacji (np. `CouponService`), B i C tylko nazwę klasy i numer linii. UUID, którego szukało Sentry, jest w `debug_meta` surowego JSON eventu.
