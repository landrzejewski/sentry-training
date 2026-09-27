#!/usr/bin/env bash
#
# Sentry MCP (stdio) dla lokalnego Sentry z docker/sentry, w profilu tylko do odczytu.
#
# Ten sam profil buduje w Javie McpServerLaunch.inspectOnly (moduł 11). Skrypt jest poleceniem
# serwera w przykładowej konfiguracji Codex (config.toml obok) i nadaje się do ręcznego startu,
# np. z MCP Inspector. Serwer mówi JSON-RPC na stdin/stdout, więc uruchomiony w terminalu czeka
# na wiadomości klienta; Ctrl+C kończy pracę.
#
# Użycie:
#   export SENTRY_ACCESS_TOKEN=$(./docker/sentry/sentry.sh token)   # zakresy org:read, project:read, event:read
#   ./docker/sentry/mcp/sentry-mcp.sh
#
# Zmienne opcjonalne: SENTRY_HOST (domyślnie localhost:9000), SENTRY_ORG (sentry),
# SENTRY_PROJECT (sentry-training).
#
set -euo pipefail

if [[ -z "${SENTRY_ACCESS_TOKEN:-}" ]]; then
  echo "Brak SENTRY_ACCESS_TOKEN. Utwórz token: export SENTRY_ACCESS_TOKEN=\$(./docker/sentry/sentry.sh token)" >&2
  echo "albo w Sentry: http://localhost:9000/settings/account/api/auth-tokens/ (Create New Personal Token," >&2
  echo "uprawnienia Read dla Project, Issue & Event i Organization)." >&2
  exit 1
fi

# Wersja przypięta: npx -y pobiera i uruchamia pakiet bez pytania.
PACKAGE="@sentry/mcp-server@0.42.0"

# env -i: proces serwera dostaje tylko wymienione zmienne. Bez tego odziedziczyłby np. SENTRY_DSN
# z trybu online przykładów (serwer wysłałby tam własną telemetrię) albo klucz OPENAI_API_KEY
# (zapytania wyszukiwania trafiłyby do zewnętrznego dostawcy LLM).
#
# --skills=inspect: bez tej flagi serwer stdio przyznaje wszystkie aktywne zdolności, także
#   triage (update_issue) i project-management (create_project, update_dsn).
# --organization-slug i --project-slug: ograniczenia sesji, agent nie wybiera innego projektu.
# --insecure-http: lokalne Sentry działa bez TLS. Dla instancji z HTTPS usuń tę flagę.
exec env -i \
  PATH="${PATH}" \
  HOME="${HOME}" \
  SENTRY_ACCESS_TOKEN="${SENTRY_ACCESS_TOKEN}" \
  npx -y "${PACKAGE}" \
  --host="${SENTRY_HOST:-localhost:9000}" \
  --insecure-http \
  --skills=inspect \
  --organization-slug="${SENTRY_ORG:-sentry}" \
  --project-slug="${SENTRY_PROJECT:-sentry-training}"
