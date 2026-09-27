# Moduł 11: AI-assisted debugging z Sentry MCP

Najważniejsza teoria modułu jest w komentarzu na początku [`Module11Demo`](Module11Demo.java).

Domena: agent (np. Codex) diagnozuje `NullPointerException` z modułu 1 (`ShippingLabelService.courierLabel`) na danych z Sentry udostępnionych przez serwer Sentry MCP. Przykłady nie oceniają jakości modelu językowego. Pokazują granice sesji, które muszą działać niezależnie od tego, co model „zrozumie”: katalog narzędzi i allowlistę, uruchomienie serwera, zamrożony zakres, dane niezaufane, jakość dowodów i przejście do naprawy.

Katalogi narzędzi pochodzą z prawdziwego serwera: `@sentry/mcp-server` 0.42.0 w trybie stdio z lokalnym Sentry (self-hosted 26.9.0) odpowiada na `initialize` i `tools/list`. Na self-hosted domyślnie pomija zdolność `seer` (Seer działa tylko w sentry.io). Pozostałe zdolności, także zapisujące, wystawia również przy tokenie z samymi zakresami odczytu; zapis zatrzymuje dopiero API Sentry odpowiedzią 403.

## Klasy

- [`McpStdioClient`](McpStdioClient.java): klient JSON-RPC po stdio (`initialize`, `tools/list` ze stronicowaniem, `tools/call`);
- [`McpServerLaunch`](McpServerLaunch.java): polecenie i środowisko procesu serwera z preflightem (token, `--skills`, ograniczenia sesji, wersja, dziedziczone zmienne);
- [`McpTool`](McpTool.java) i [`ToolEffect`](ToolEffect.java): narzędzie z katalogu z adnotacjami i klasyfikacja skutku;
- [`ToolPolicy`](ToolPolicy.java): allowlista fail closed, `enabled_tools` dla Codex i ocena wywołania bramy `execute_sentry_tool` po operacji docelowej;
- [`DiagnosticScope`](DiagnosticScope.java): zamrożony zakres z cutoffem;
- [`TelemetrySanitizer`](TelemetrySanitizer.java): redakcja sekretów i PII oraz znacznik `UNTRUSTED_DATA`;
- [`EvidenceLog`](EvidenceLog.java): rejestr dowodów z `DataState` i `SamplingStatus`;
- [`SentryIssuesApi`](SentryIssuesApi.java): odczyt issues z REST API jako dowód (stronicowanie z nagłówka `Link`, `X-Hits`, statusy błędów);
- [`RepairReadinessGate`](RepairReadinessGate.java): `READY` albo `NOT_READY_FOR_REPAIR_PLAN`, bez autoryzacji zmian;
- [`Module11Demo`](Module11Demo.java): scenariusze uruchamiane po kolei; test `Module11ScenariosTest` sprawdza te same scenariusze bez sieci.

## Scenariusze

Pełne wyjaśnienie każdego scenariusza (o co chodzi, co pokazujemy, problem, dobra praktyka, na co patrzeć) jest w komentarzu nad jego metodą w [`Module11Demo`](Module11Demo.java).

1. **Katalog `tools/list` i allowlista fail closed.** Serwer stdio bez `--skills` wystawia `update_issue`, profil `inspect` z ograniczeniami sesji już nie. `enabled_tools` powstaje z aktualnego katalogu, a nieprzejrzany odczyt też jest odrzucany. Efekt: przy każdym narzędziu adnotacje i decyzja, na końcu gotowa lista `enabled_tools` dla Codex.
2. **Brama `execute_sentry_tool` otwiera cały katalog.** Bez `--skills` przez bramę dostępne są `create_project`, `update_dsn`, `delete_alert_rule`; decyzja zależy od operacji docelowej, a nawet profil `inspect` ma operację z `readOnlyHint: false` (`onboarding_status_update`). Efekt: `checkCall` dopuszcza tylko przejrzany odczyt przez bramę.
3. **Uruchomienie serwera stdio.** Token w argumencie, brak `--skills`, brak ograniczeń sesji, nieprzypięta wersja, `SENTRY_DSN` i klucz LLM odziedziczone z terminala kontra profil docelowy. Efekt: sześć problemów preflightu kontra zero; token i zdolności to dwie niezależne granice.
4. **Telemetria jako niezaufane dane.** Token w breadcrumbie, e-mail i instrukcja dla agenta w polu klienta: redakcja usuwa sekrety, ale instrukcję zatrzymują dopiero polityka narzędzi i zamrożony zakres. Efekt: `update_issue` odrzucone, odczyt z `production` odrzucony przez `EvidenceLog`.
5. **Rejestr dowodów.** Pierwsza strona issues jako `PARTIAL`, literówka w filtrze jako `NO_DATA`, odwołany token jako `FAILED`, nieistniejące środowisko jako 404, kontrola pozytywna i sampling `UNKNOWN`. Efekt: przy każdym dowodzie linia „ogranicza” i lista powodów, dla których pusty wynik nie dowodzi braku błędów.
6. **Gotowość do planu naprawy.** Częściowy dowód i sam odczyt kodu blokują, test lokalny daje `READY`, które nie obejmuje patcha, PR, merge, deployu ani zmiany issue.

## Uruchomienie

```bash
# offline: zapisane snapshoty katalogu i odpowiedzi REST, bez sieci i bez Node.js
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module11.Module11Demo

# wybrane scenariusze, np. tylko 2
./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module11.Module11Demo -Dexec.args=2

# online: prawdziwy serwer MCP (npx, Node.js 22.13+) i REST API lokalnego Sentry
SENTRY_ACCESS_TOKEN=... ./mvnw -q compile exec:java -Dexec.mainClass=pl.training.sentry.module11.Module11Demo

# testy scenariuszy
./mvnw -q test -Dtest=Module11ScenariosTest
```

Tryb online czyta dane, które wysłały przykłady innych modułów (np. moduł 1 w trybie online). Opcjonalne zmienne: `SENTRY_URL` (domyślnie `http://localhost:9000`), `SENTRY_ORG` (`sentry`), `SENTRY_PROJECT` (`sentry-training`), `SENTRY_ENVIRONMENT` (`training`). Moduł nie wysyła eventów, więc `SENTRY_DSN` nie jest potrzebny, a jeśli jest ustawiony, nie trafia do serwera MCP.

## Token dla trybu online i dla Codex

Najszybciej: `export SENTRY_ACCESS_TOKEN=$(./docker/sentry/sentry.sh token)` tworzy token admina lokalnego Sentry z zakresami `org:read`, `project:read` i `event:read`, a wartość trafia tylko do zmiennej, nie do historii poleceń. Usuwa się go w UI jak niżej (nazwa `sentry-training-read`). Równoważny token można też utworzyć ręcznie:

1. Zaloguj się do lokalnego Sentry i otwórz `http://localhost:9000/settings/account/api/auth-tokens/` (Personal Tokens).
2. „Create New Personal Token”: uprawnienie Read dla Project, Issue & Event i Organization (zakresy `project:read`, `event:read`, `org:read`), reszta No Access.
3. Wklej token do zmiennej bieżącej powłoki tak, żeby nie trafił do historii poleceń: `read -rs SENTRY_ACCESS_TOKEN && export SENTRY_ACCESS_TOKEN`. Nie zapisuj go w repozytorium, TOML ani prompcie.
4. Po szkoleniu usuń token na tej samej stronie. Usunięcie definicji serwera w kliencie (`codex mcp remove`) tokenu nie unieważnia.

## Sentry MCP w Codex z lokalnym Sentry

`docker/sentry/mcp/sentry-mcp.sh` uruchamia serwer w profilu z [`McpServerLaunch#inspectOnly`](McpServerLaunch.java): przypięta wersja, `--skills=inspect`, organizacja i projekt ustalone flagami, czyste środowisko procesu. `docker/sentry/mcp/config.toml` zawiera blok `[mcp_servers.sentry_local]` do skopiowania do własnej konfiguracji Codex (token przez `env_vars`, `enabled_tools` z oceny [`ToolPolicy`](ToolPolicy.java), tryb zatwierdzania `prompt`). Konfiguracji nie ma w `.codex/` repozytorium celowo: projektowy plik konfiguracyjny jest częścią powierzchni zaufania repozytorium.

## Na co patrzeć

- W konsoli: każde narzędzie z adnotacjami i decyzją (`DOPUSZCZONE` albo `ODRZUCONE` z powodem), każdy dowód ze stanem, samplingiem i linią „ogranicza”. Każdy scenariusz kończy się linią „Na co patrzeć”.
- W trybie online: linia „na żywo przez stdio” przy katalogach oznacza odpowiedź prawdziwego serwera. Gdy start się nie uda (brak Node.js, zły token), demo wypisuje `FAILED` z końcówką stderr serwera i wraca do snapshotu, zamiast udawać sukces.
- W Sentry UI (tryb online): issues z X-Hits w scenariuszu 5 to te same, które widać w projekcie `sentry-training` z filtrem `is:unresolved`, środowiskiem `training` i oknem ostatnich 24 godzin.
