#!/usr/bin/env bash
# Deploy da API na VPS: troca a tag da imagem, sobe e confere o health.
# Se a versao nova nao ficar UP, volta sozinho para a tag anterior.
#
#   /opt/unimove/scripts/deploy.sh <tag>      # tag = SHA do commit (CI) ou "latest"
#
# Rollback manual = rodar de novo com o SHA antigo.
#
# ATENCAO: migration de banco NAO volta com o rollback. Se a versao quebrada
# aplicou uma V{n}, o schema fica na frente do codigo antigo — corrija com uma
# V{n+1} nova, nunca editando a migration ja aplicada.
#
# Variaveis opcionais: HEALTH_ATTEMPTS (36), HEALTH_INTERVAL (5 s).
set -euo pipefail

TAG="${1:?uso: scripts/deploy.sh <tag>}"
cd "$(dirname "$0")/.."

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
        compose pull -q api &&
        compose up -d --remove-orphans &&
        reload_caddy &&
        healthy
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
