# Środowisko uruchomieniowe instalatora getsentry/self-hosted.
#
# install.sh wymaga bash >= 4.4 i narzędzi GNU, a macOS ma bash 3.2. Zamiast instalować
# cokolwiek na hoście, uruchamiamy instalator w tym kontenerze z dostępem do Dockera hosta
# (zamontowany /var/run/docker.sock). Kontenery Sentry i tak powstają na hoście.
FROM docker:29-cli

RUN apk add --no-cache bash git curl coreutils findutils grep sed gawk jq \
 && git config --global --add safe.directory '*'

ENTRYPOINT ["bash"]
