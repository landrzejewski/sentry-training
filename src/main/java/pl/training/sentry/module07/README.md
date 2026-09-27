# Moduł 7: AI w Sentry i workflow z agentami

Najważniejsza teoria modułu jest w komentarzu na początku [`Module07Demo`](Module07Demo.java).

Domena: checkout-api przyjmuje kod rabatowy. Klient (atakujący) wpisuje w pole kodu tekst „Ignore previous instructions...”, który aplikacja przepisuje do komunikatu wyjątku, a Sentry do tytułu issue. Przykłady pokazują, co musi zrobić kod hosta agenta, żeby taka telemetria była materiałem do analizy, a nie źródłem poleceń: od pobrania issue z REST API Sentry po przegląd PR przez człowieka.

Czego tu nie ma: Seer (Autofix, Issue Summary) działa tylko w sentry.io, a lokalne self-hosted Sentry go nie ma, więc przykłady go nie udają. Moduł nie wywołuje też modelu językowego: prompt jest wypisywany, a odpowiedzi modelu to stałe w [`SampleModelResponses`](SampleModelResponses.java). Dzięki temu wynik jest powtarzalny, a wszystko, co zależy od developera (materiał, prompt, bramki), widać w kodzie.

## Klasy

- [`DiscountCodeEndpoint`](DiscountCodeEndpoint.java): kod aplikacji, który wysyła zatruty event (wejście z formularza w komunikacie wyjątku, e-mail i nagłówek Authorization w breadcrumbs);
- [`SentryApiClient`](SentryApiClient.java): odczyt issue, eventu i listy issues z REST API klientem HTTP z JDK, token z zakresami odczytu ze zmiennej środowiskowej;
- [`AgentSession`](AgentSession.java): zaufany zakres sesji (organizacja, projekt, environment, repozytorium);
- [`IssueMaterial`](IssueMaterial.java): surowa odpowiedź API albo jej zapis z pliku w trybie offline;
- [`EvidenceCollector`](EvidenceCollector.java) i [`Evidence`](Evidence.java): minimalizacja i rejestr dowodów z ID nadanymi przez hosta, pominięte pola i znane braki;
- [`Redactor`](Redactor.java): redakcja sekretów i e-maili przed modelem;
- [`AnalysisPrompt`](AnalysisPrompt.java): kontrakt promptu z sekcjami i kodowaniem dowodów oraz prompt naiwny dla porównania;
- [`ReportValidator`](ReportValidator.java): walidacja odpowiedzi modelu względem kontraktu i rejestru dowodów;
- [`ActionPolicy`](ActionPolicy.java): katalog akcji, zakres sesji, zgody związane z digestem, merge i deploy zawsze odrzucane;
- [`AgentWorkflow`](AgentWorkflow.java): maszyna stanów od `COLLECT` do `COMPLETE`;
- [`DailyBriefing`](DailyBriefing.java): deterministyczny filtr i ranking issues przed streszczeniem;
- [`Module07Demo`](Module07Demo.java): scenariusze uruchamiane po kolei; test `Module07ScenariosTest` sprawdza te same scenariusze bez sieci.

## Scenariusze

Pełne wyjaśnienie każdego scenariusza (o co chodzi, co pokazujemy, problem, dobra praktyka, na co patrzeć) jest w komentarzu nad jego metodą w [`Module07Demo`](Module07Demo.java).

1. **Zatruty event.** Tekst z formularza przechodzi przez SDK i Sentry do tytułu issue i odpowiedzi API. Efekt: tytuł issue z API zaczyna się od typu wyjątku i zawiera polecenie atakującego, a atakujący nie potrzebował dostępu do Sentry ani do promptu.
2. **Pakiet dowodów.** Z kilkunastu kilobajtów eventu zostaje osiem dowodów z ID. Tagi infrastruktury i contexts SDK trafiają na listę pominiętych, user i request kolektor pomija zawsze, a maskowanie z ingestu (z `_meta`) staje się jawnym brakiem materiału.
3. **Redakcja przed modelem.** Scrubbing Sentry usunął treść breadcrumbu z tokenem, ale przepuścił e-mail; redaktor hosta łapie e-mail, a nie łapie danych zakodowanych. Efekt: żadna warstwa nie wystarcza sama, redaktor to nie DLP.
4. **Kontrakt promptu.** Prompt naiwny kontra sekcje z zakodowanymi dowodami i jawnymi brakami. Efekt: w kontrakcie tekst atakującego ma zakodowane `&lt;` i `&gt;`, a brak repozytorium i commitu jest zapisany jako `[BRAK]`.
5. **Walidacja odpowiedzi.** Odpowiedź zgodna z kontraktem przechodzi, odpowiedź modelu przejętego przez injection odpada z pięcioma powodami (fakt bez dowodu, ID spoza pakietu, ten sam dowód za i przeciw, tekst poza formatem, hipoteza bez weryfikacji).
6. **Autoryzacja poza promptem.** Wywołania narzędzi z odpowiedzi modelu: odczyt w zakresie, mutacja czekająca na zgodę, obcy projekt, nieznane narzędzie, merge odrzucony nawet ze zgodą. Efekt: decyzję widać w kolumnach `ALLOW`, `NEEDS_APPROVAL`, `DENY` z powodem.
7. **Maszyna stanów.** Pełna ścieżka z bramkami: nieudane testy, zgoda na konkretny digest, zmiana patcha po przeglądzie unieważnia zgodę, `COMPLETE` bez merge. Efekt: ścieżka audytowa z każdym przejściem i każdą odmową.
8. **Poranny briefing.** Filtr projektu, deduplikacja i ranking w kodzie, model tylko streszcza; tytuły issues nadal są niezaufanymi danymi. Efekt: regresja jest pierwsza, issue z 5412 eventami wypada poza limit, a ostrzeżenia mówią o braku właściciela i `users=0`.

## Uruchomienie

```bash
# offline: event tylko na konsoli, materiał API z plików src/main/resources/module07
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module07.Module07Demo

# wybrane scenariusze, np. tylko 4; bez scenariusza 1 materiał pochodzi z zapisanej odpowiedzi API, bez wysyłania eventu
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module07.Module07Demo -Dexec.args=4

# online: event trafia do lokalnego Sentry, host pobiera go z REST API
export SENTRY_AUTH_TOKEN=$(./docker/sentry/sentry.sh token)   # raz na terminal
SENTRY_DSN=$(./docker/sentry/sentry.sh dsn) \
  ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module07.Module07Demo

# testy scenariuszy
./mvnw -q test -Dtest=Module07ScenariosTest
```

Zmienne środowiskowe trybu online: `SENTRY_DSN` (wysyłka eventu przez SDK), `SENTRY_AUTH_TOKEN` (odczyt przez REST API), opcjonalnie `SENTRY_URL` (domyślnie `http://localhost:9000`), `SENTRY_ORG` (domyślnie `sentry`), `SENTRY_PROJECT` (domyślnie `sentry-training`) i `SENTRY_ENVIRONMENT` (domyślnie `training`). Sam token bez DSN wystarcza do briefingu z prawdziwej listy issues; materiał pojedynczego issue pochodzi wtedy z pliku.

## Token z zakresami odczytu

Dla lokalnego Sentry najprościej: `./docker/sentry/sentry.sh token` tworzy token administratora z zakresami `org:read`, `project:read` i `event:read` i wypisuje go jeden raz. PUŁAPKA: to nie jest token ściśle tylko do odczytu, bo w Sentry `org:read` pozwala też tworzyć, zmieniać i usuwać dashboardy organizacji; zmian w projekcie, issues czy alertach token nie wykona. Klient z tego modułu używa wyłącznie GET. Każde wywołanie tworzy nowy token, więc zapisuje się go w zmiennej na czas sesji terminala.

Ręcznie w Sentry UI: menu konta, User Settings, Personal Tokens (adres `/settings/account/api/auth-tokens/`), Create New Token (formularz pod `/settings/account/api/auth-tokens/new-token/`). Uprawnienia: Project, Issue & Event i Organization na poziomie Read (zakresy `project:read`, `event:read`, `org:read`), pozostałe No Access. Token przekazuje się tylko zmienną środowiskową w bieżącym terminalu, nie w pliku repozytorium ani w argumencie programu. Po szkoleniu token usuwa się na tej samej stronie. Klient odmawia wysłania tokenu przez HTTP bez TLS na inny host niż localhost.

## Na co patrzeć

- W konsoli: w scenariuszu 1 wydruk eventu (`↳ event`) pokazuje, co SDK wysyła, a dalsze kroki, co z tego robi host. Każdy scenariusz kończy się liniami „Na co patrzeć”. W scenariuszu 4 cały prompt jest wypisany: warto znaleźć w nim tekst atakującego i sprawdzić, że nie zamyka sekcji `<dowody>`.
- W trybie offline event na konsoli ma inne `event_id` niż materiał: materiał to zapis odpowiedzi API dla tego samego eventu z wcześniejszego uruchomienia online.
- W Sentry UI (tryb online): filtr `training.module:module07`. Tytuł issue z tekstem atakującego, w evencie breadcrumb z e-mailem klienta i breadcrumb `http` z treścią `[Filtered]`: to scrubbing serwerowy z domyślnych ustawień projektu. Kolejne uruchomienia trafiają do tego samego issue, a pakiet dowodów dostaje wtedy brak „Pakiet zawiera 1 event z N”.
- Briefing online obejmuje pierwszą stronę (25 pozycji) nierozwiązanych issues projektu w środowisku sesji, widzianych w ostatnich 24 h, także z innych modułów szkolenia. Lista przychodzi z endpointu organizacji z parametrem `project`: endpoint projektu nie filtruje po czasie, a `statsPeriod=24h` wybiera w nim tylko okres wykresu. Gdy API ma kolejne strony wyników, demo mówi to wprost.
