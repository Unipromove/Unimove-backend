#!/usr/bin/env bash
# Deploy da API na VPS: troca a tag da imagem, sobe e confere o health.
# Se a versao nova nao ficar UP, volta sozinho para a tag anterior.
#
#   /opt/unimove/scripts/deploy.sh <tag>      # tag = SHA do commit (CI) ou "latest"
#   ... <tag> --ghcr-login <usuario>          # token do GHCR pela ENTRADA PADRAO
#
# O CI usa a segunda forma com o GITHUB_TOKEN do proprio job: login, pull e
# logout dentro do deploy, sem nenhuma credencial permanente na VPS.
#
# Rollback manual = rodar de novo com o SHA antigo (sem login, vale para imagens
# ainda no cache local — 7 dias) ou, no GitHub, Re-run do job de deploy antigo.
#
# ATENCAO: migration de banco NAO volta com o rollback. Se a versao quebrada
# aplicou uma V{n}, o schema fica na frente do codigo antigo — corrija com uma
# V{n+1} nova, nunca editando a migration ja aplicada.
#
# Variaveis opcionais: HEALTH_ATTEMPTS (36), HEALTH_INTERVAL (5 s).
set -euo pipefail

TAG="${1:?uso: scripts/deploy.sh <tag> [--ghcr-login <usuario>]}"
cd "$(dirname "$0")/.."

if [ "${2:-}" = "--ghcr-login" ]; then
    # Pelo stdin, nunca por argumento: argumento aparece no `ps` de qualquer usuario.
    if docker login ghcr.io -u "${3:?--ghcr-login exige o usuario}" --password-stdin >/dev/null; then
        trap 'docker logout ghcr.io >/dev/null 2>&1 || true' EXIT
    else
        echo "deploy: AVISO — login no GHCR falhou; so imagens do cache local" >&2
    fi
fi

compose() { docker compose -f docker-compose.prod.yml "$@"; }

current_tag() { sed -n 's/^IMAGE_TAG=//p' .env | tail -n 1; }

# O .env e a fonte da tag em uso: e ele que o compose le em qualquer
# `up` posterior (reboot, restart manual), entao o rollback tem de passar por ele.
set_tag() {
    if grep -q '^IMAGE_TAG=' .env; then
        sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=$1/" .env
    else
        printf 'IMAGE_TAG=%s\n' "$1" >> .env
    fi
}

healthy() {
    for _ in $(seq 1 "${HEALTH_ATTEMPTS:-36}"); do
        if curl -fsS --max-time 5 http://127.0.0.1:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
            return 0
        fi
        sleep "${HEALTH_INTERVAL:-5}"
    done
    return 1
}

# Encadeado com && de proposito: dentro de um `if`, o bash ignora o set -e.
# Sem o encadeamento, um pull que falha (tag inexistente) deixaria o container
# antigo de pe e o health check diria UP para a versao errada.
up() {
    set_tag "$1" &&
        pull_api &&
        compose up -d --remove-orphans &&
        reload_caddy &&
        healthy
}

# Tag ja no cache local sobe mesmo sem acesso ao registry (rollback manual sem
# login). Tag inexistente nos dois lados falha aqui e dispara o rollback.
pull_api() {
    compose pull -q api && return 0
    local image
    image="$(compose config --images | grep unimove-backend)"
    if docker image inspect "$image" >/dev/null 2>&1; then
        echo "deploy: pull falhou; usando $image do cache local" >&2
        return 0
    fi
    return 1
}

# O Caddyfile e bind mount: mudar o conteudo nao recria o container. Falhar
# aqui nao derruba o deploy — a API nova ja esta no ar e o Caddy segue com a
# config anterior.
reload_caddy() {
    compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile >/dev/null ||
        echo "deploy: AVISO — caddy reload falhou; config anterior mantida" >&2
}

PREV="$(current_tag)"
echo "deploy: ${PREV:-<nenhuma>} -> $TAG"

if up "$TAG"; then
    echo "deploy: $TAG UP"
    # Mantem uma semana de imagens antigas para rollback rapido; o resto sai.
    docker image prune -af --filter until=168h >/dev/null || true
    exit 0
fi

echo "deploy: $TAG nao ficou UP" >&2
compose logs --tail 80 api >&2 || true

if [ -z "$PREV" ] || [ "$PREV" = "$TAG" ]; then
    echo "deploy: sem tag anterior para voltar" >&2
    exit 1
fi

echo "deploy: rollback para $PREV" >&2
if up "$PREV"; then
    echo "deploy: rollback OK, $PREV UP" >&2
else
    echo "deploy: rollback tambem falhou — intervencao manual" >&2
fi
exit 1
