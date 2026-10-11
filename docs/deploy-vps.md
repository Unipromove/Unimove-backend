# Deploy — VPS Hetzner (API + Postgres + documentos + Caddy)

Tudo roda numa VPS so, sem Render e sem Supabase. Push na `main` → testes →
imagem no GHCR → o CI entra na VPS por SSH e roda o `scripts/deploy.sh`.
Sem hibernacao: schedulers e SSE rodam 24h.

```
git push origin main            (repo Unipromove/Unimove-backend)
   │
   ├─ [test]    mvn -B verify (unitarios + IT contra Postgres)
   ├─ [build]   push ghcr.io/unipromove/unimove-backend:<sha> e :latest (amd64 + arm64)
   └─ [deploy]  tar (compose + Caddyfile + scripts) | ssh -> /opt/unimove
                ssh -> scripts/deploy.sh <sha>
                     pull, up, health local; falhou => volta para a tag anterior
                health publico em https://<dominio>/actuator/health
```

| Peca | Onde |
|---|---|
| API (Spring Boot) | container `unimove-api-prod`, heap ~768 MB (`mem_limit: 1536m`) |
| Postgres 17 | container `unimove-postgres-prod`, volume `unimove_pgdata_prod`, so em `127.0.0.1` |
| Documentos do motorista | volume `unimove_documents`, **cifrados** com a `DATA_ENCRYPTION_KEY`; o admin abre por URL assinada de 5 min servida pela API (`/files/documents/**`) |
| TLS | Caddy + Let's Encrypt automatico (`Caddyfile`), dominio DuckDNS |
| Backup | `scripts/backup.sh` diario: `pg_dump` + tar dos documentos, 7 copias **so na VPS** |

Arquivos: [`docker-compose.prod.yml`](../docker-compose.prod.yml), [`Caddyfile`](../Caddyfile),
[`scripts/deploy.sh`](../scripts/deploy.sh), [`scripts/backup.sh`](../scripts/backup.sh),
[`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml).

> **Latencia:** a VPS esta na Europa; cada request do Brasil paga ~200 ms de ida e
> volta. Aceito para o piloto. Se incomodar, recriar a VPS em Ashburn (EUA) e
> repetir este runbook — so muda o IP no DuckDNS.

> ⚠️ **Backup so local.** Se a VPS for perdida (falha de disco, conta bloqueada,
> `rm` errado), banco e documentos se perdem junto com os backups. Antes de
> usuarios reais, leve uma copia para fora: Hetzner Storage Box (~€4/mes, rsync)
> ou ative *Backups* no console da Hetzner (+20% no preco da VPS).

---

## 1. Preparar a VPS (uma vez)

Como root, num Ubuntu 24.04:

```bash
# Usuario de deploy (o CI entra com ele) — sem senha, so chave
adduser --disabled-password --gecos "" deploy
mkdir -p /home/deploy/.ssh && chmod 700 /home/deploy/.ssh
# cole a SUA chave publica e a chave publica do CI (passo 4) aqui:
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

# Gere cada um com o comando ao lado e GUARDE O .env NUM GERENCIADOR DE SENHAS.
# Sem DATA_ENCRYPTION_KEY, CPF/CNH e os arquivos de documento ficam ilegiveis —
# inclusive nos backups.
DATABASE_PASSWORD=          # openssl rand -base64 32 | tr -d '/+='
JWT_SECRET=                 # openssl rand -base64 48
DATA_ENCRYPTION_KEY=        # openssl rand -base64 32

# Mesmo valor do dashboard do Render, se ja estavam configurados la
PHONE_VERIFICATION_CHANNEL=LOG
WHATSAPP_BUSINESS_NUMBER=
WHATSAPP_APP_SECRET=
WHATSAPP_WEBHOOK_VERIFY_TOKEN=
GOOGLE_LOGIN_ENABLED=false
GOOGLE_CLIENT_IDS=
PRIVACY_TERMS_VERSION=
PRIVACY_POLICY_URL=
```

Qualquer outra variavel do `application.yml` (rate limit, lockout, `DB_POOL_SIZE`...)
pode ser posta aqui: o compose repassa o `.env` inteiro para a API. O diretorio
e a URL dos documentos ja vem do compose. `IMAGE_TAG` e escrito pelo `deploy.sh`
— nao edite na mao.

## 4. Auto-deploy no repositorio novo (conta Unipromove)

O GHCR so aceita do `GITHUB_TOKEN` pacotes no namespace do **dono do repo**, por
isso a imagem e `ghcr.io/unipromove/unimove-backend` (nao mais
`ghcr.io/cristianneves/unimove`). Logado na conta **Unipromove**:

**4.1 Actions liberado.** Repo → Settings → Actions → General → *Allow all actions*.
Nao precisa mexer em *Workflow permissions*: o job de build pede `packages: write`
sozinho.

**4.2 Chave SSH so do CI** (no seu computador):
```bash
ssh-keygen -t ed25519 -N "" -C "github-actions-unimove" -f ci_unimove
ssh-keyscan -t ed25519 <ip>          # saida = VPS_KNOWN_HOSTS
```
Cole `ci_unimove.pub` no `authorized_keys` do `deploy` (passo 1).

**4.3 Secrets e variavel** (Settings → Secrets and variables → Actions):

| Tipo | Nome | Valor |
|---|---|---|
| secret | `VPS_HOST` | IP da VPS |
| secret | `VPS_USER` | `deploy` |
| secret | `VPS_SSH_KEY` | conteudo de `ci_unimove` (privada) |
| secret | `VPS_KNOWN_HOSTS` | saida do `ssh-keyscan` |
| variable | `VPS_PUBLIC_URL` | `https://unimove.duckdns.org` |

O environment `production` (usado pelo job) e criado sozinho na primeira execucao.
Depois de cadastrar, apague `ci_unimove` do disco. Secrets antigos do Render
(`RENDER_DEPLOY_HOOK`, `RENDER_SERVICE_URL`), se tiverem sido copiados, podem sair.

**4.4 Primeira imagem.** Faca o merge na `main` (ou Actions → CI/CD → *Run workflow*).
O job de build cria o pacote `unimove-backend`, **privado** e ligado ao repo.
Sem `VPS_HOST` o deploy e pulado com aviso; com ele, vai direto para o passo 5.

**4.5 Login da VPS no GHCR.** Na conta Unipromove: Settings → Developer settings →
Personal access tokens → **Tokens (classic)** → escopo **so** `read:packages`
(o GHCR nao aceita token *fine-grained*). Na VPS, como `deploy`:
```bash
echo <PAT> | docker login ghcr.io -u unipromove --password-stdin
```

Opcional: apague o pacote antigo `ghcr.io/cristianneves/unimove` na sua conta.

## 5. Primeiro deploy

Do seu computador, na raiz do repo (depois o CI faz isso sozinho):
```bash
tar czf - docker-compose.prod.yml Caddyfile scripts | ssh deploy@<ip> 'tar xzf - -C /opt/unimove'
ssh deploy@<ip> 'bash /opt/unimove/scripts/deploy.sh latest'
```

O Flyway cria o schema do zero, inclusive o admin do seed. Nada a migrar: o
Supabase so tinha esse admin (conferido em 2026-10-11).

1. **Senha do admin:** `admin@unimove.local` / `ChangeMe123!`. A API **obriga** a
   troca antes de qualquer outra acao (`PUT /users/me/password`) — faca ja.
2. **Smoke:** `scripts/smoke.sh https://unimove.duckdns.org`
3. **Meta (WhatsApp):** app da Meta → WhatsApp → Configuration → Webhook → URL
   `https://unimove.duckdns.org/webhooks/whatsapp` (mesmo verify token).
   Login Google **nao muda** (o fluxo de ID Token nao tem URL de callback).
4. **App Flutter (outro repo):** troque a base URL e publique build nova.
5. **Desligar o antigo:** apague o servico no Render e o projeto no Supabase.

## 6. Backup

```bash
crontab -e     # como deploy
```
```cron
30 3 * * * /opt/unimove/scripts/backup.sh >> /opt/unimove/backups/backup.log 2>&1
```

Rode uma vez na mao (`bash scripts/backup.sh`). **Backup so vale depois de
restaurado** — teste em container descartavel:
```bash
docker run -d --rm --name rt -e POSTGRES_PASSWORD=x postgres:17-alpine && sleep 5
docker cp backups/<data>.dump rt:/tmp/b.dump
docker exec rt pg_restore --no-owner --exit-on-error -U postgres -d postgres /tmp/b.dump
docker exec rt psql -U postgres -Atc "select count(*) from users"; docker rm -f rt
```

Restaurar os documentos (o tar ja traz o dono 65532 e os arquivos cifrados):
```bash
docker cp - unimove-api-prod:/data < backups/<data>-docs.tgz
```

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
| Espaco dos documentos | `docker system df -v \| grep unimove_documents` |

> Migration **nao** volta com o rollback. Se a versao quebrada aplicou uma `V{n}`,
> corrija com uma `V{n+1}` nova, nunca editando a migration aplicada.

> **Nunca troque a `DATA_ENCRYPTION_KEY`** de um ambiente com dados: CPF/CNH e
> documentos ja gravados ficam ilegiveis. Rotacao exige recifrar (fora do MVP).

## Limites conhecidos

| Limite | Quando resolver |
|---|---|
| Backup so na VPS | antes de usuarios reais: Storage Box ou Backups da Hetzner |
| ~200 ms de latencia (VPS na Europa) | se incomodar no piloto: VPS em Ashburn |
| Instancia unica (hubs de SSE em memoria, documentos em disco local) | ao escalar: pub/sub via Redis e object storage |
| OSRM/Photon nas demos publicas, sem SLA | self-host |
| Sem staging | antes de abrir para usuarios reais |
