# Deploy — VPS Hetzner (API + Postgres + Caddy)

Push na `main` → testes → imagem no GHCR → o CI entra na VPS por SSH e roda o
`scripts/deploy.sh`. Sem hibernacao: schedulers e SSE rodam 24h.

```
git push origin main
   │
   ├─ [test]    mvn -B verify (unitarios + IT contra Postgres)
   ├─ [build]   push ghcr.io/cristianneves/unimove:<sha> e :latest
   └─ [deploy]  tar (compose + Caddyfile + scripts) | ssh -> /opt/unimove
                ssh -> scripts/deploy.sh <sha>
                     pull, up, health local; falhou => volta para a tag anterior
                health publico em https://<dominio>/actuator/health
```

| Peca | Onde |
|---|---|
| API (Spring Boot) | container `unimove-api-prod`, heap ~768 MB (`mem_limit: 1536m`) |
| Postgres 17 | container `unimove-postgres-prod`, volume `unimove_pgdata_prod`, so em `127.0.0.1` |
| TLS | Caddy + Let's Encrypt automatico (`Caddyfile`), dominio DuckDNS |
| Documentos do motorista | continuam no **Supabase Storage** (bucket privado `driver-documents`) |
| Backup | `scripts/backup.sh` diario: local (7 copias) + bucket privado `backups` no Supabase (14 dias) |

Arquivos: [`docker-compose.prod.yml`](../docker-compose.prod.yml), [`Caddyfile`](../Caddyfile),
[`scripts/deploy.sh`](../scripts/deploy.sh), [`scripts/backup.sh`](../scripts/backup.sh),
[`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml).

> **Latencia:** a VPS esta na Europa; cada request do Brasil paga ~200 ms de ida e
> volta. Aceito para o piloto. Se incomodar, recriar a VPS em Ashburn (EUA) e
> repetir este runbook — so muda o IP no DuckDNS.

---

## 1. Preparar a VPS (uma vez)

Como root, num Ubuntu 24.04:

```bash
# Usuario de deploy (o CI entra com ele) — sem senha, so chave
adduser --disabled-password --gecos "" deploy
mkdir -p /home/deploy/.ssh && chmod 700 /home/deploy/.ssh
# cole a SUA chave publica e a chave publica do CI (passo 5) aqui:
nano /home/deploy/.ssh/authorized_keys
chmod 600 /home/deploy/.ssh/authorized_keys && chown -R deploy:deploy /home/deploy/.ssh

# SSH so por chave, sem root
sed -i 's/^#\?PasswordAuthentication .*/PasswordAuthentication no/; s/^#\?PermitRootLogin .*/PermitRootLogin no/' /etc/ssh/sshd_config
systemctl reload ssh

# Atualizacoes de seguranca automaticas
apt update && apt install -y unattended-upgrades && dpkg-reconfigure -f noninteractive unattended-upgrades

# Docker + compose plugin (repositorio oficial)
curl -fsSL https://get.docker.com | sh
usermod -aG docker deploy

mkdir -p /opt/unimove && chown deploy:deploy /opt/unimove
```

> ⚠️ Teste `ssh deploy@<ip>` numa **segunda janela** antes de fechar a sessao root.

**Firewall:** use o **Hetzner Cloud Firewall** (console → Firewalls), entrada so
TCP 22, 80, 443. Nao confie no `ufw`: o Docker escreve regras de iptables por
cima dele e publicaria qualquer porta mapeada. (O compose ja prende Postgres e
API em `127.0.0.1`; o firewall e a segunda camada.)

**GHCR (pacote privado):** como `deploy`, com um PAT do GitHub de escopo **so**
`read:packages`:
```bash
echo <PAT> | docker login ghcr.io -u cristianneves --password-stdin
```

## 2. DuckDNS

Em https://www.duckdns.org crie o subdominio (ex `unimove`) e aponte para o IP
da VPS. Confira: `nslookup unimove.duckdns.org` devolve o IP. O Caddy so
consegue o certificado depois disso.

## 3. `/opt/unimove/.env`

```bash
cd /opt/unimove && touch .env && chmod 600 .env && nano .env
```

```dotenv
DOMAIN=unimove.duckdns.org

# NOVA, so desta VPS: openssl rand -base64 32 | tr -d '/+='
DATABASE_PASSWORD=

# >>> COPIE DO RENDER EXATAMENTE COMO ESTAO <<<
# DATA_ENCRYPTION_KEY diferente = CPF/CNH ja gravados ficam ILEGIVEIS.
# JWT_SECRET diferente = todo mundo deslogado.
JWT_SECRET=
DATA_ENCRYPTION_KEY=

# Copie do dashboard do Render (Environment)
PHONE_VERIFICATION_CHANNEL=
WHATSAPP_BUSINESS_NUMBER=
WHATSAPP_APP_SECRET=
WHATSAPP_WEBHOOK_VERIFY_TOKEN=
GOOGLE_LOGIN_ENABLED=
GOOGLE_CLIENT_IDS=
SUPABASE_URL=
SUPABASE_SERVICE_KEY=
PRIVACY_TERMS_VERSION=
PRIVACY_POLICY_URL=

# Keepalive do Supabase (usado so pelo backup.sh): o plano free pausa o
# projeto sem atividade NO BANCO, e pausado o Storage dos documentos cai.
# Connection string "Session pooler" do Supabase (Connect -> Session pooler).
SUPABASE_DB_URL=postgresql://postgres.<ref>:<senha>@aws-0-us-east-1.pooler.supabase.com:5432/postgres
```

Qualquer outra variavel do `application.yml` (rate limit, lockout, `DB_POOL_SIZE`...)
pode ser posta aqui: o compose repassa o `.env` inteiro para a API.
`IMAGE_TAG` e escrito pelo `deploy.sh` — nao edite na mao.

## 4. Primeiro envio dos arquivos

Do seu computador, na raiz do repo:
```bash
tar czf - docker-compose.prod.yml Caddyfile scripts | ssh deploy@<ip> 'tar xzf - -C /opt/unimove'
```

## 5. Secrets do GitHub (Settings → Secrets and variables → Actions)

Gere uma chave **so para o CI** (no seu computador):
```bash
ssh-keygen -t ed25519 -N "" -C "github-actions-unimove" -f ci_unimove
ssh-keyscan -t ed25519 <ip>          # saida = VPS_KNOWN_HOSTS
```
Adicione `ci_unimove.pub` ao `authorized_keys` do `deploy` (passo 1).

| Tipo | Nome | Valor |
|---|---|---|
| secret | `VPS_USER` | `deploy` |
| secret | `VPS_SSH_KEY` | conteudo de `ci_unimove` (privada) |
| secret | `VPS_KNOWN_HOSTS` | saida do `ssh-keyscan` |
| variable | `VPS_PUBLIC_URL` | `https://unimove.duckdns.org` |
| secret | `VPS_HOST` | IP da VPS — **so no passo 6.5** |

> `VPS_HOST` fica por ultimo de proposito: sem ele o job de deploy e pulado.
> Se a API subisse antes do restore, o Flyway criaria o schema vazio e o
> restore colidiria com ele.

Depois de cadastrar, apague `ci_unimove` do disco. Os secrets `RENDER_DEPLOY_HOOK`
e `RENDER_SERVICE_URL` podem ser removidos apos o passo 8.

## 6. Virada (cutover) — alguns minutos fora do ar

1. **Congelar o Render:** dashboard → servico → *Suspend*. Nada mais escreve no Supabase.
2. **Dump do Supabase** (na VPS, mesma major 17; so o schema `public`, que inclui
   o `flyway_schema_history`):
   ```bash
   cd /opt/unimove
   docker run --rm -e PGURL="$(sed -n 's/^SUPABASE_DB_URL=//p' .env)" postgres:17-alpine \
     sh -c 'pg_dump "$PGURL" --schema=public --no-owner --no-privileges -Fc' > supabase.dump
   ```
3. **Restore no Postgres da VPS:**
   ```bash
   docker compose -f docker-compose.prod.yml up -d postgres
   # O dump traz o CREATE SCHEMA public; com o schema vazio de fabrica no lugar o
   # restore "funciona" mas sai com erro, e o --exit-on-error nao teria como
   # distinguir de um erro real. O restore recria o schema, dono = unimove.
   docker exec unimove-postgres-prod psql -U unimove -d unimove -c 'drop schema public'
   docker exec -i unimove-postgres-prod pg_restore --no-owner --exit-on-error -U unimove -d unimove < supabase.dump
   docker exec unimove-postgres-prod psql -U unimove -Atc "select count(*) from users; select max(installed_rank) from flyway_schema_history;"
   ```
   Os numeros devem bater com o Supabase.
4. **Subir a API:**
   ```bash
   bash scripts/deploy.sh latest
   ```
   No log, o Flyway deve dizer `Schema "public" is up to date. No migration necessary.`
   Se aparecer `Migrating schema ... to version "1"`, o restore nao entrou — pare e confira o passo 3.
5. **Ligar o CI:** cadastre o secret `VPS_HOST`. Rode o workflow uma vez
   (Actions → CI/CD → *Run workflow*) para provar o caminho completo.
6. **Smoke:**
   ```bash
   scripts/smoke.sh https://unimove.duckdns.org
   ```
7. **Meta (WhatsApp):** no app da Meta → WhatsApp → Configuration → Webhook, troque
   a URL para `https://unimove.duckdns.org/webhooks/whatsapp` (mesmo verify token).
   Login Google **nao muda** (o fluxo de ID Token nao tem URL de callback).
8. **App Flutter (outro repo):** troque a base URL e publique build nova. Builds
   antigas continuam apontando para o Render suspenso.

## 7. Backup

```bash
crontab -e     # como deploy
```
```cron
30 3 * * * /opt/unimove/scripts/backup.sh >> /opt/unimove/backups/backup.log 2>&1
```
Rode uma vez na mao (`bash scripts/backup.sh`) e confira o bucket `backups` no
Supabase (criado privado pelo proprio script). **Backup so vale depois de
restaurado:** teste em container descartavel —
```bash
docker run -d --rm --name rt -e POSTGRES_PASSWORD=x postgres:17-alpine && sleep 5
docker cp backups/<arquivo>.dump rt:/tmp/b.dump
docker exec rt pg_restore --no-owner --exit-on-error -U postgres -d postgres /tmp/b.dump
docker exec rt psql -U postgres -Atc "select count(*) from users"; docker rm -f rt
```

Opcional (~20% do preco da VPS): ative **Backups** no console da Hetzner — snapshot
diario do disco inteiro, util para recriar a maquina rapido.

## 8. Depois de ~1 semana estavel

- Apague o servico no Render e o `render.yaml` do repo.
- **LGPD:** os dados pessoais continuam duplicados no Postgres do Supabase. Apague
  as tabelas do schema `public` la (o Storage e o keepalive continuam funcionando):
  `drop schema public cascade; create schema public;` — so depois de um backup da
  VPS restaurado com sucesso.

---

## Operacao

| Tarefa | Comando (em `/opt/unimove`) |
|---|---|
| Versao no ar | `grep IMAGE_TAG .env` |
| Rollback | `bash scripts/deploy.sh <sha-antigo>` (imagens de ate 7 dias ficam em cache) |
| Logs | `docker compose -f docker-compose.prod.yml logs -f api` |
| psql | `docker exec -it unimove-postgres-prod psql -U unimove` |
| Banco do seu PC | `ssh -L 5432:127.0.0.1:5432 deploy@<ip>` |
| Trocar um segredo | edite `.env` e `bash scripts/deploy.sh $(sed -n 's/^IMAGE_TAG=//p' .env)` |

> Migration **nao** volta com o rollback. Se a versao quebrada aplicou uma `V{n}`,
> corrija com uma `V{n+1}` nova, nunca editando a migration aplicada.

## Limites conhecidos

| Limite | Quando resolver |
|---|---|
| ~200 ms de latencia (VPS na Europa) | se incomodar no piloto: VPS em Ashburn |
| Instancia unica (hubs de SSE em memoria) | ao escalar: pub/sub via Redis |
| Supabase free pausa sem atividade no banco | keepalive diario no `backup.sh`; ou migrar o Storage |
| OSRM/Photon nas demos publicas, sem SLA | self-host |
| Sem staging | antes de abrir para usuarios reais |
