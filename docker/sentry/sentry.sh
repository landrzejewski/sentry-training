#!/usr/bin/env bash
#
# Lokalne, self-hosted Sentry na Docker Compose dla przykładów szkolenia.
#
# Skrypt opakowuje oficjalny projekt getsentry/self-hosted (przypięta wersja), który
# uruchamia pełny stos Sentry: web, relay, kafka, clickhouse, snuba, postgres, redis itd.
#
# Użycie:
#   ./docker/sentry/sentry.sh install     # jednorazowo: pobranie i instalacja (kilkanaście minut)
#   ./docker/sentry/sentry.sh up          # start stosu
#   ./docker/sentry/sentry.sh bootstrap   # konto admina, projekt sentry-training, zapis DSN
#   ./docker/sentry/sentry.sh dsn         # wypisuje DSN projektu szkoleniowego
#   ./docker/sentry/sentry.sh token       # tworzy token API z zakresami odczytu (moduły 7, 8, 11)
#   ./docker/sentry/sentry.sh ci-token    # tworzy token organizacji org:ci (upload source maps, release)
#   ./docker/sentry/sentry.sh status      # stan kontenerów
#   ./docker/sentry/sentry.sh clean       # usunięcie danych z przykładów, konfiguracja zostaje
#   ./docker/sentry/sentry.sh down        # zatrzymanie (dane zostają w wolumenach)
#   ./docker/sentry/sentry.sh destroy     # zatrzymanie i usunięcie wszystkich danych
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SELF_HOSTED_VERSION="${SELF_HOSTED_VERSION:-26.9.0}"
SELF_HOSTED_DIR="${SELF_HOSTED_DIR:-${SCRIPT_DIR}/self-hosted}"
ENV_FILE="${SCRIPT_DIR}/training.env"

ADMIN_EMAIL="${SENTRY_ADMIN_EMAIL:-admin@sentry-training.local}"
ADMIN_PASSWORD="${SENTRY_ADMIN_PASSWORD:-sentry-training}"
PROJECT_SLUG="sentry-training"

compose() {
  (cd "${SELF_HOSTED_DIR}" && docker compose "$@")
}

require_installed() {
  if [[ ! -d "${SELF_HOSTED_DIR}" ]]; then
    echo "Brak instalacji. Uruchom najpierw: $0 install" >&2
    exit 1
  fi
}

cmd_install() {
  if [[ ! -d "${SELF_HOSTED_DIR}" ]]; then
    git clone --branch "${SELF_HOSTED_VERSION}" --depth 1 \
      https://github.com/getsentry/self-hosted.git "${SELF_HOSTED_DIR}"
  fi

  # install.sh wymaga bash >= 4.4 (macOS ma 3.2), więc działa w kontenerze z Dockerem hosta.
  # Katalog montujemy pod tą samą ścieżką, bo compose rozwiązuje bind mounty względem hosta.
  docker build --quiet -t sentry-training-installer "${SCRIPT_DIR}/installer" >/dev/null

  # --skip-user-creation: konto tworzy bootstrap, bez interaktywnego pytania.
  # --no-report-self-hosted-issues: instancja szkoleniowa nie wysyła telemetrii do sentry.io.
  # --apply-automatic-config-updates: instalator sam aktualizuje pliki konfiguracyjne.
  docker run --rm \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "${SELF_HOSTED_DIR}:${SELF_HOSTED_DIR}" \
    -w "${SELF_HOSTED_DIR}" \
    sentry-training-installer ./install.sh \
    --skip-user-creation \
    --no-report-self-hosted-issues \
    --apply-automatic-config-updates
}

cmd_up() {
  require_installed
  compose up --wait
  echo "Sentry działa: http://localhost:9000 (login: ${ADMIN_EMAIL})"
}

cmd_down() {
  require_installed
  compose down
}

cmd_status() {
  require_installed
  compose ps --format 'table {{.Service}}\t{{.State}}\t{{.Status}}'
}

cmd_bootstrap() {
  require_installed
  echo "Tworzenie konta administratora ${ADMIN_EMAIL} ..."
  compose exec -T web sentry createuser \
    --email "${ADMIN_EMAIL}" --password "${ADMIN_PASSWORD}" \
    --superuser --no-input --force-update >/dev/null

  # Bez url-prefix Sentry nie zna swojego publicznego adresu i nie potrafi zbudować DSN.
  compose exec -T web sentry config set system.url-prefix "http://localhost:9000" >/dev/null 2>&1

  echo "Tworzenie projektu ${PROJECT_SLUG} i klucza DSN ..."
  local dsn
  dsn="$(compose exec -T web sentry exec -c "
from sentry.models.organization import Organization
from sentry.models.organizationmember import OrganizationMember
from sentry.models.project import Project
from sentry.models.projectkey import ProjectKey
from sentry.models.team import Team
from sentry.users.models.user import User

org = Organization.get_default()
user = User.objects.get(email='${ADMIN_EMAIL}')
member, _ = OrganizationMember.objects.get_or_create(organization=org, user_id=user.id, defaults={'role': 'owner'})
team, _ = Team.objects.get_or_create(organization=org, slug='training', defaults={'name': 'Training'})
project, _ = Project.objects.get_or_create(
    organization=org, slug='${PROJECT_SLUG}', defaults={'name': '${PROJECT_SLUG}', 'platform': 'java'})
project.add_team(team)
key = ProjectKey.objects.filter(project=project).first() or ProjectKey.objects.create(project=project)
print('DSN=' + key.dsn_public)
" | sed -n 's/^DSN=//p' | tail -1)"

  if [[ -z "${dsn}" ]]; then
    echo "Nie udało się odczytać DSN." >&2
    exit 1
  fi
  printf 'SENTRY_DSN=%s\n' "${dsn}" > "${ENV_FILE}"
  echo "DSN zapisany w ${ENV_FILE}"
  echo "  ${dsn}"
  echo "Panel: http://localhost:9000  login: ${ADMIN_EMAIL}  hasło: ${ADMIN_PASSWORD}"
}

cmd_dsn() {
  if [[ ! -f "${ENV_FILE}" ]]; then
    echo "Brak ${ENV_FILE}. Uruchom: $0 bootstrap" >&2
    exit 1
  fi
  sed -n 's/^SENTRY_DSN=//p' "${ENV_FILE}"
}

cmd_token() {
  require_installed
  # Token osobisty admina z zakresami odczytu: org:read, project:read i event:read. Przykłady
  # modułów 7, 8 i 11 czytają nim issues i eventy przez REST API albo Sentry MCP.
  # PUŁAPKA: to nie jest token ściśle tylko do odczytu. W Sentry org:read pozwala też tworzyć,
  # zmieniać i usuwać dashboardy organizacji (sprawdzone na tej instancji 26.9.0); zmiany
  # projektu, issues czy alertów token nie wykona. Token wypisujemy tylko raz; Sentry
  # przechowuje jego skrót, więc zgubionego nie da się odczytać, trzeba utworzyć nowy.
  compose exec -T web sentry exec -c "
from sentry.models.apitoken import ApiToken
from sentry.users.models.user import User
user = User.objects.get(email='${ADMIN_EMAIL}')
token = ApiToken.objects.create(user=user, name='sentry-training-read', scope_list=['org:read', 'project:read', 'event:read'])
print('TOKEN=' + token.plaintext_token)
" 2>/dev/null | sed -n 's/^TOKEN=//p' | tail -1
}

cmd_ci_token() {
  require_installed
  # Token organizacji (Settings > Developer Settings > Organization Tokens) ze stałym zakresem
  # org:ci: tworzenie release, upload source maps i code mappings, bez odczytu issues i eventów.
  # Tak wygląda token CI zalecany w module 4. Token zawiera adres Sentry i slug organizacji,
  # więc sentry-cli nie potrzebuje SENTRY_URL ani --org. Wypisujemy go tylko raz, jak token
  # w UI; nazwę można zmienić zmienną CI_TOKEN_NAME (np. osobny token dla każdego pipeline).
  local name="${CI_TOKEN_NAME:-sentry-training-ci}"
  compose exec -T web sentry exec -c "
from sentry.api.utils import generate_locality_url
from sentry.models.organization import Organization
from sentry.models.organizationmapping import OrganizationMapping
from sentry.models.orgauthtoken import OrgAuthToken
from sentry.types.cell import get_locality_name_for_cell
from sentry.users.models.user import User
from sentry.utils.security.orgauthtoken_token import generate_token, hash_token

org = Organization.get_default()
user = User.objects.get(email='${ADMIN_EMAIL}')
mapping = OrganizationMapping.objects.get(organization_id=org.id)
token = generate_token(org.slug, generate_locality_url(get_locality_name_for_cell(mapping.cell_name)))
OrgAuthToken.objects.create(
    organization_id=org.id, name='${name}', scope_list=['org:ci'], created_by_id=user.id,
    token_last_characters=token[-4:], token_hashed=hash_token(token))
print('TOKEN=' + token)
" 2>/dev/null | sed -n 's/^TOKEN=//p' | tail -1
}

cmd_clean() {
  require_installed
  # Czyści dane z przykładów, a zostawia konfigurację: konto, projekt i DSN, tokeny, zespoły,
  # Ownership Rules, Alerty, Monitory, dashboardy i release. Działa na całą instancję, nie na
  # jeden projekt: tabel ClickHouse nie da się szybko wyczyścić dla pojedynczego projektu.
  if [[ "${1:-}" != "--yes" ]]; then
    read -r -p "Usunąć issues, eventy, trace, logi, metryki, replaye i Stats z lokalnego Sentry? [tak/N] " answer
    if [[ "${answer}" != "tak" ]]; then
      echo "Przerwano."
      exit 0
    fi
  fi

  # Issues usuwa mechanizm deletions Sentry (ten sam co Delete w UI), ale synchronicznie:
  # razem z hashami grupowania, treścią eventów w nodestore i powiązaniami (assignee, komentarze).
  # Nowy event z tym samym fingerprintem utworzy potem nowe issue.
  echo "Usuwanie issues ..."
  compose exec -T web sentry exec -c "
from uuid import uuid4
from sentry import deletions
from sentry.models.group import Group
from sentry.models.organization import Organization

org = Organization.get_default()
total = 0
for project_id in Group.objects.filter(project__organization=org).values_list('project_id', flat=True).distinct():
    ids = list(Group.objects.filter(project_id=project_id).values_list('id', flat=True))
    for start in range(0, len(ids), 100):
        task = deletions.get(model=Group, query={'id__in': ids[start:start + 100]}, transaction_id=uuid4().hex)
        while task.chunk():
            pass
    total += len(ids)
print('ISSUES=%d' % total)
" 2>/dev/null | sed -n 's/^ISSUES=/  usunięte issues: /p'

  # Eventy, transakcje, spany i logi (EAP), metryki i sesje, replaye oraz outcomes (Stats)
  # leżą w tabelach *_local ClickHouse. TRUNCATE jest natychmiastowy; migrations_local to stan
  # schematu Snuby i zostaje.
  echo "Czyszczenie telemetrii w ClickHouse ..."
  local tables
  tables="$(compose exec -T clickhouse clickhouse-client --query "
    SELECT name FROM system.tables
    WHERE database = 'default' AND engine LIKE '%MergeTree' AND name LIKE '%\\_local'
      AND name != 'migrations_local' AND total_rows > 0")"
  local table
  for table in ${tables}; do
    compose exec -T clickhouse clickhouse-client --query "TRUNCATE TABLE default.${table}"
    echo "  ${table}"
  done
  echo "Gotowe. Konfiguracja i DSN bez zmian; Monitory Cron z modułu 6 nadal zgłaszają Missed,"
  echo "a Alerty i zespoły usuwa cleanup z README modułów 3 i 6."
}

cmd_destroy() {
  require_installed
  read -r -p "Usunąć kontenery i WSZYSTKIE dane lokalnego Sentry? [tak/N] " answer
  if [[ "${answer}" != "tak" ]]; then
    echo "Przerwano."
    exit 0
  fi
  compose down --volumes --remove-orphans
  # Instalator tworzy wolumeny zewnętrzne, których compose down --volumes nie usuwa.
  docker volume ls --format '{{.Name}}' | grep '^sentry-' | xargs -r docker volume rm
  rm -f "${ENV_FILE}"
}

case "${1:-}" in
  install) cmd_install ;;
  up) cmd_up ;;
  down) cmd_down ;;
  status) cmd_status ;;
  bootstrap) cmd_bootstrap ;;
  dsn) cmd_dsn ;;
  token) cmd_token ;;
  ci-token) cmd_ci_token ;;
  clean) cmd_clean "${2:-}" ;;
  destroy) cmd_destroy ;;
  *)
    sed -n '3,20p' "$0"
    exit 1
    ;;
esac
