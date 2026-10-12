#!/usr/bin/env bash
# Backup diario da VPS (cron). Ver docs/deploy-vps.md.
#
#   unimove-<data>.dump      pg_dump em formato custom (ja comprimido)
#   unimove-<data>-docs.tgz  volume dos documentos do motorista (ja cifrados)
#
# Mantem os N mais recentes em $BACKUP_DIR. ATENCAO: copia SO LOCAL — se a VPS
# morrer, o backup morre junto. Leve uma copia para fora assim que possivel.
# Sem a DATA_ENCRYPTION_KEY do .env, CPF/CNH e os documentos sao ilegiveis:
# guarde o .env num gerenciador de senhas, fora da VPS.
#
# Restaurar:
#   pg_restore --clean --if-exists --no-owner --exit-on-error -d <url> <arquivo>.dump
#   docker cp - unimove-api-prod:/data < <(gunzip -c <arquivo>-docs.tgz)
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/opt/unimove/backups}"
KEEP="${BACKUP_KEEP:-7}"
stamp="$(date -u +%Y%m%dT%H%MZ)"

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

# .tmp + mv: um backup interrompido nunca fica com cara de backup valido.
db="$BACKUP_DIR/unimove-$stamp.dump"
docker exec unimove-postgres-prod pg_dump -U unimove -d unimove -Fc > "$db.tmp"
mv "$db.tmp" "$db"

# `docker cp ... -` sai em tar; o diretorio `documents/` vira a raiz do arquivo.
docs="$BACKUP_DIR/unimove-$stamp-docs.tgz"
docker cp unimove-api-prod:/data/documents - | gzip > "$docs.tmp"
mv "$docs.tmp" "$docs"

echo "backup: $(basename "$db") ($(du -h "$db" | cut -f1)), $(basename "$docs") ($(du -h "$docs" | cut -f1))"

# Retencao: os N mais novos de cada tipo (o nome ordena por data).
prune() { find "$BACKUP_DIR" -maxdepth 1 -name "$1" | sort -r | tail -n +"$((KEEP + 1))" | xargs -r rm -f; }
prune 'unimove-*Z.dump'
prune 'unimove-*Z-docs.tgz'
