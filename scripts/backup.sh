#!/usr/bin/env bash
# Backup diario do Postgres da VPS (cron). Ver docs/deploy-vps.md.
#
#   1. pg_dump (formato custom, ja comprimido) em $BACKUP_DIR, mantendo 7 copias;
#   2. copia fora da VPS no bucket PRIVADO `backups` do Supabase Storage,
#      apagando as remotas com mais de 14 dias;
#   3. `select 1` no Postgres do Supabase (SUPABASE_DB_URL, opcional): o plano
#      free pausa o projeto por falta de atividade NO BANCO, e pausado o Storage
#      dos documentos do motorista cai junto.
#
# Restaurar: pg_restore --clean --if-exists --no-owner -d <url> <arquivo>.dump
set -euo pipefail

cd "$(dirname "$0")/.."
BACKUP_DIR="${BACKUP_DIR:-/opt/unimove/backups}"
LOCAL_KEEP="${BACKUP_LOCAL_KEEP:-7}"
REMOTE_DAYS="${BACKUP_REMOTE_DAYS:-14}"
BUCKET="${BACKUP_BUCKET:-backups}"

# Le uma chave do .env sem dar `source` (valor com $ ou espaco quebraria o shell).
env_get() { sed -n "s/^$1=//p" .env | tail -n 1 | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"; }

SUPABASE_URL="$(env_get SUPABASE_URL)"
SUPABASE_URL="${SUPABASE_URL%/}"
SUPABASE_KEY="$(env_get SUPABASE_SERVICE_KEY)"

name="unimove-$(date -u +%Y%m%dT%H%MZ).dump"
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

# .tmp + mv: um dump interrompido nunca fica com cara de backup valido.
docker exec unimove-postgres-prod pg_dump -U unimove -d unimove -Fc > "$BACKUP_DIR/$name.tmp"
mv "$BACKUP_DIR/$name.tmp" "$BACKUP_DIR/$name"
echo "backup: $name ($(du -h "$BACKUP_DIR/$name" | cut -f1))"

# Retencao local: os N mais novos (o nome ordena por data).
find "$BACKUP_DIR" -maxdepth 1 -name 'unimove-*.dump' | sort -r | tail -n +"$((LOCAL_KEEP + 1))" | xargs -r rm -f

if [ -z "$SUPABASE_URL" ] || [ -z "$SUPABASE_KEY" ]; then
    echo "backup: AVISO — sem SUPABASE_URL/SUPABASE_SERVICE_KEY, copia so local" >&2
else
    storage() {
        curl -fsS --max-time 120 -H "Authorization: Bearer $SUPABASE_KEY" -H "apikey: $SUPABASE_KEY" "$@"
    }

    # Cria o bucket privado na primeira execucao; nas seguintes ele ja existe e o
    # erro e ignorado (o upload logo abaixo falha alto se o problema for outro).
    storage -o /dev/null -X POST "$SUPABASE_URL/storage/v1/bucket" \
        -H 'Content-Type: application/json' \
        -d "{\"id\":\"$BUCKET\",\"name\":\"$BUCKET\",\"public\":false}" 2>/dev/null || true

    storage -o /dev/null -X POST "$SUPABASE_URL/storage/v1/object/$BUCKET/$name" \
        -H 'Content-Type: application/octet-stream' --data-binary "@$BACKUP_DIR/$name"
    echo "backup: enviado para o bucket $BUCKET"

    # Retencao remota por nome (a data esta nele), sem depender de jq.
    cutoff="unimove-$(date -u -d "$REMOTE_DAYS days ago" +%Y%m%dT%H%MZ).dump"
    old=$(storage -X POST "$SUPABASE_URL/storage/v1/object/list/$BUCKET" \
            -H 'Content-Type: application/json' -d '{"prefix":"","limit":1000}' |
          grep -o '"name":"unimove-[0-9TZ]*\.dump"' | cut -d'"' -f4 |
          while read -r n; do [[ "$n" < "$cutoff" ]] && printf '"%s",' "$n"; done || true)
    if [ -n "$old" ]; then
        storage -o /dev/null -X DELETE "$SUPABASE_URL/storage/v1/object/$BUCKET" \
            -H 'Content-Type: application/json' -d "{\"prefixes\":[${old%,}]}"
        echo "backup: removidas copias remotas anteriores a ${cutoff#unimove-}"
    fi
fi

SUPABASE_DB_URL="$(env_get SUPABASE_DB_URL)"
if [ -n "$SUPABASE_DB_URL" ]; then
    # -e sem valor: a URL (com senha) vai pelo ambiente, nao pela linha de comando.
    if SUPABASE_DB_URL="$SUPABASE_DB_URL" docker exec -e SUPABASE_DB_URL unimove-postgres-prod \
         sh -c 'psql "$SUPABASE_DB_URL" -Atqc "select 1"' >/dev/null; then
        echo "backup: keepalive do Supabase OK"
    else
        echo "backup: AVISO — keepalive do Supabase falhou (projeto pausado?)" >&2
    fi
fi
