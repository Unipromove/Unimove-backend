#!/usr/bin/env bash
# Prepara uma VPS Ubuntu nova para o UniMove. Rodar UMA vez, como root:
#
#   scp scripts/bootstrap-vps.sh root@<ip>:
#   ssh -t root@<ip> bash bootstrap-vps.sh
#
# Faz: atualizacoes automaticas, Docker, firewall (22/80/443), usuario `deploy`,
# chave SSH do CI, endurecimento do SSH, /opt/unimove/.env (pergunta os valores
# do Render) e o cron do backup. No fim imprime o que colar nos secrets do GitHub.
# Pode ser rodado de novo: o que ja existe e mantido.
#
# BOOTSTRAP_DRY_SERVICES=1 pula o que exige systemd/kernel (Docker, ufw, reload
# do sshd) — so para testar o script dentro de um container.
set -euo pipefail

[ "$(id -u)" = 0 ] || { echo "rode como root" >&2; exit 1; }
DRY="${BOOTSTRAP_DRY_SERVICES:-0}"
APP=/opt/unimove
DEPLOY=deploy
CI_TAG=github-actions-unimove

step() { printf '\n==> %s\n' "$*"; }
ask() { local v; read -r -p "$1: " v; printf '%s' "$v"; }
# Conta linhas que nao sao a chave do CI nem vazias. Sem pipe de proposito: com
# pipefail, `grep -v | grep -q` pode dar falso por SIGPIPE quando o -q sai cedo.
personal_keys() { grep -cv -e "$CI_TAG" -e '^$' "$keys" || true; }

step "Pacotes e atualizacoes automaticas de seguranca"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get upgrade -yq
apt-get install -yq curl openssl cron ufw unattended-upgrades openssh-client
[ "$DRY" = 1 ] || dpkg-reconfigure -f noninteractive unattended-upgrades

step "Docker"
if command -v docker >/dev/null; then
    echo "ja instalado"
elif [ "$DRY" = 1 ]; then
    echo "(dry) pulado"
else
    curl -fsSL https://get.docker.com | sh
fi

step "Usuario $DEPLOY"
id "$DEPLOY" >/dev/null 2>&1 || adduser --disabled-password --gecos "" "$DEPLOY"
getent group docker >/dev/null || groupadd docker
usermod -aG docker "$DEPLOY"
keys="/home/$DEPLOY/.ssh/authorized_keys"
install -d -m 700 -o "$DEPLOY" -g "$DEPLOY" "/home/$DEPLOY/.ssh"
touch "$keys"

# Chaves pessoais: as do root (a Hetzner instala a sua chave nele ao criar a VPS).
if [ -s /root/.ssh/authorized_keys ]; then
    while IFS= read -r line; do
        if [ -n "$line" ] && ! grep -qxF "$line" "$keys"; then echo "$line" >> "$keys"; fi
    done < /root/.ssh/authorized_keys
fi
if [ "$(personal_keys)" -eq 0 ]; then
    echo "Nenhuma chave SSH sua encontrada no root."
    personal=$(ask "Cole sua chave PUBLICA (conteudo de ~/.ssh/id_ed25519.pub) ou Enter para pular")
    if [ -n "$personal" ]; then echo "$personal" >> "$keys"; fi
fi

step "Chave SSH do CI (GitHub Actions)"
ci_key=""
if grep -q "$CI_TAG" "$keys"; then
    echo "ja existe — para gerar outra, apague a linha '$CI_TAG' de $keys e rode de novo"
else
    tmp=$(mktemp -d)
    ssh-keygen -q -t ed25519 -N "" -C "$CI_TAG" -f "$tmp/ci"
    cat "$tmp/ci.pub" >> "$keys"
    # A privada so existe ate ser impressa no fim: nao fica no disco da VPS.
    ci_key=$(cat "$tmp/ci")
    rm -rf "$tmp"
fi
chown "$DEPLOY:$DEPLOY" "$keys"
chmod 600 "$keys"

step "SSH: sem senha"
if [ "$(personal_keys)" -gt 0 ]; then
    # Drop-in com prefixo 00: o sshd usa o primeiro valor lido, e o
    # 50-cloud-init.conf da Hetzner liga a senha quando a VPS nasce com ela.
    # Root continua entrando, mas so por chave (o deploy nao tem sudo).
    printf 'PasswordAuthentication no\nKbdInteractiveAuthentication no\nPermitRootLogin prohibit-password\n' \
        > /etc/ssh/sshd_config.d/00-unimove.conf
    [ "$DRY" = 1 ] || systemctl reload ssh
    echo "login por senha desligado (root e $DEPLOY so por chave)"
else
    echo "AVISO: sem chave pessoal para $DEPLOY — login por senha MANTIDO para nao te trancar fora."
fi

step "Firewall (22, 80, 443)"
if [ "$DRY" = 1 ]; then
    echo "(dry) pulado"
else
    # O Docker so fura o ufw nas portas publicadas, e aqui elas sao so 80/443:
    # Postgres e API escutam em 127.0.0.1 (docker-compose.prod.yml).
    ufw allow 22/tcp >/dev/null
    ufw allow 80/tcp >/dev/null
    ufw allow 443/tcp >/dev/null
    ufw --force enable >/dev/null
    echo "ufw ativo"
fi

step "Diretorio $APP"
install -d -o "$DEPLOY" -g "$DEPLOY" "$APP" "$APP/backups"

step "Variaveis ($APP/.env)"
env_file="$APP/.env"
if [ -s "$env_file" ]; then
    echo "ja existe — mantido. Para mudar algo: nano $env_file"
else
    echo "Copie os valores do Render (Dashboard -> servico -> Environment). Enter = pular/gerar."
    domain=""
    while [ -z "$domain" ]; do domain=$(ask "Subdominio DuckDNS completo (ex: unimove.duckdns.org)"); done

    jwt=$(ask "JWT_SECRET (Enter = gerar novo)")
    [ -n "$jwt" ] || jwt=$(openssl rand -base64 48 | tr -d '\n')

    while :; do
        key=$(ask "DATA_ENCRYPTION_KEY (Enter = gerar nova)")
        [ -n "$key" ] || key=$(openssl rand -base64 32)
        if [ "$(printf '%s' "$key" | base64 -d 2>/dev/null | wc -c)" = 32 ]; then break; fi
        echo "  invalida: precisa ser base64 de 32 bytes (openssl rand -base64 32)"
    done

    channel=$(ask "PHONE_VERIFICATION_CHANNEL [LOG/WHATSAPP] (Enter = LOG)")
    google=$(ask "GOOGLE_LOGIN_ENABLED [true/false] (Enter = false)")

    umask 077
    {
        echo "DOMAIN=$domain"
        echo "DATABASE_PASSWORD=$(openssl rand -base64 32 | tr -d '/+=\n')"
        echo "JWT_SECRET=$jwt"
        echo "DATA_ENCRYPTION_KEY=$key"
        echo "PHONE_VERIFICATION_CHANNEL=${channel:-LOG}"
        if [ "${channel:-LOG}" = WHATSAPP ]; then
            echo "WHATSAPP_BUSINESS_NUMBER=$(ask "WHATSAPP_BUSINESS_NUMBER")"
            echo "WHATSAPP_APP_SECRET=$(ask "WHATSAPP_APP_SECRET")"
            echo "WHATSAPP_WEBHOOK_VERIFY_TOKEN=$(ask "WHATSAPP_WEBHOOK_VERIFY_TOKEN")"
        fi
        echo "GOOGLE_LOGIN_ENABLED=${google:-false}"
        if [ "${google:-false}" = true ]; then
            echo "GOOGLE_CLIENT_IDS=$(ask "GOOGLE_CLIENT_IDS")"
        fi
        terms=$(ask "PRIVACY_TERMS_VERSION (Enter = padrao do app)")
        if [ -n "$terms" ]; then echo "PRIVACY_TERMS_VERSION=$terms"; fi
        policy=$(ask "PRIVACY_POLICY_URL (Enter = padrao do app)")
        if [ -n "$policy" ]; then echo "PRIVACY_POLICY_URL=$policy"; fi
    } > "$env_file"
    chown "$DEPLOY:$DEPLOY" "$env_file"
    chmod 600 "$env_file"
    echo "gravado. GUARDE UMA COPIA deste arquivo num gerenciador de senhas:"
    echo "sem a DATA_ENCRYPTION_KEY os dados e os backups ficam ilegiveis."
fi
domain=$(sed -n 's/^DOMAIN=//p' "$env_file")

step "Backup diario (03:30 UTC)"
cron_line="30 3 * * * bash $APP/scripts/backup.sh >> $APP/backups/backup.log 2>&1"
{ crontab -u "$DEPLOY" -l 2>/dev/null | grep -vF "$APP/scripts/backup.sh" || true; echo "$cron_line"; } | crontab -u "$DEPLOY" -
echo "agendado"

ip=$(hostname -I | awk '{print $1}')
host_key=$(cut -d' ' -f1,2 /etc/ssh/ssh_host_ed25519_key.pub)

cat <<EOF

==================================================================================
 PRONTO. Agora no GitHub (repo -> Settings -> Secrets and variables -> Actions):
==================================================================================

 Aba "Secrets" -> New repository secret:

   VPS_HOST          $ip
   VPS_USER          $DEPLOY
   VPS_KNOWN_HOSTS   $ip $host_key
EOF
if [ -n "$ci_key" ]; then
    cat <<EOF
   VPS_SSH_KEY       (copie TUDO entre as linhas tracejadas, inclusive BEGIN/END)
-----------------------------------------------------------------------------------
$ci_key
-----------------------------------------------------------------------------------
   (esta chave nao fica salva na VPS: se perder, gere outra rodando o script de novo)
EOF
else
    echo "   VPS_SSH_KEY       (ja gerada antes; nao e exibida de novo)"
fi
cat <<EOF

 Aba "Variables" -> New repository variable:

   VPS_PUBLIC_URL    https://$domain

 Depois: confira que $domain aponta para $ip (DuckDNS) e faca o merge na main.
==================================================================================
EOF
